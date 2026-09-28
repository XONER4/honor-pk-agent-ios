import Foundation
import AVFoundation
import CoreImage
import UIKit

/// Монтаж видео: обрезка, скорость, звук, музыка/голос, фильтр.
enum VideoEditing {
    static let unreadable = MediaEditingError(russian: "Не удалось открыть видео",
                                              english: "Could not open the video")
    static let noVideoTrack = MediaEditingError(russian: "В файле нет видеодорожки",
                                                english: "The file has no video track")
    static let tooShort = MediaEditingError(russian: "Фрагмент слишком короткий",
                                            english: "The selected clip is too short")
    static let noAudioTrack = MediaEditingError(russian: "В аудиофайле нет звуковой дорожки",
                                                english: "The audio file has no audio track")
    static let compositionFailed = MediaEditingError(russian: "Не удалось собрать видео",
                                                     english: "Could not build the video")
    static let exportUnavailable = MediaEditingError(russian: "Экспорт видео недоступен",
                                                     english: "Video export is unavailable")
    static let cancelled = MediaEditingError(russian: "Сохранение видео отменено",
                                             english: "Video export was cancelled")

    /// Собрать и сохранить отредактированное видео во вложения (.mp4).
    /// - trim: секунды исходника; speed: 0.25...4; громкости 0...1.
    static func export(source: URL, trim: ClosedRange<Double>?, muteOriginal: Bool, extraAudio: URL?,
                       extraAudioVolume: Float, originalVolume: Float, speed: Double, filter: EditFilter?,
                       progress: ((Double) -> Void)? = nil) async throws -> URL {
        let asset = AVURLAsset(url: source)
        let assetDuration: CMTime
        do {
            assetDuration = try await asset.load(.duration)
        } catch {
            throw unreadable
        }
        let total = assetDuration.seconds
        guard total.isFinite, total > 0 else { throw unreadable }
        var start: Double = 0
        var end: Double = total
        if let trim = trim {
            start = min(max(0, trim.lowerBound), total)
            end = min(max(start, trim.upperBound), total)
        }
        guard end - start >= 0.1 else { throw tooShort }
        let timescale: CMTimeScale = 600
        let timeRange = CMTimeRange(start: CMTime(seconds: start, preferredTimescale: timescale),
                                    end: CMTime(seconds: end, preferredTimescale: timescale))

        let videoTracks = try await asset.loadTracks(withMediaType: .video)
        guard let sourceVideo = videoTracks.first else { throw noVideoTrack }
        let composition = AVMutableComposition()
        guard let videoTrack = composition.addMutableTrack(withMediaType: .video,
                                                           preferredTrackID: kCMPersistentTrackID_Invalid) else {
            throw compositionFailed
        }
        try videoTrack.insertTimeRange(timeRange, of: sourceVideo, at: .zero)
        videoTrack.preferredTransform = try await sourceVideo.load(.preferredTransform)

        var mixParameters: [AVMutableAudioMixInputParameters] = []
        if !muteOriginal {
            let audioTracks = try await asset.loadTracks(withMediaType: .audio)
            if let sourceAudio = audioTracks.first,
               let audioTrack = composition.addMutableTrack(withMediaType: .audio,
                                                            preferredTrackID: kCMPersistentTrackID_Invalid) {
                try audioTrack.insertTimeRange(timeRange, of: sourceAudio, at: .zero)
                let parameters = AVMutableAudioMixInputParameters(track: audioTrack)
                parameters.setVolume(min(max(originalVolume, 0), 1), at: .zero)
                parameters.audioTimePitchAlgorithm = .spectral
                mixParameters.append(parameters)
            }
        }

        let clampedSpeed = min(max(speed, 0.25), 4)
        if abs(clampedSpeed - 1) > 0.001 {
            let clip = CMTimeRange(start: .zero, duration: timeRange.duration)
            let scaled = CMTimeMultiplyByFloat64(timeRange.duration, multiplier: 1.0 / clampedSpeed)
            composition.scaleTimeRange(clip, toDuration: scaled)
        }
        let finalDuration = composition.duration

        if let extraAudio = extraAudio {
            let parameters = try await addExtraAudio(extraAudio, to: composition, length: finalDuration,
                                                     volume: extraAudioVolume)
            mixParameters.append(parameters)
        }

        var videoComposition: AVMutableVideoComposition? = nil
        if let filter = filter, filter != .original {
            videoComposition = filterComposition(for: composition, filter: filter)
        }

        guard let session = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetHighestQuality) else {
            throw exportUnavailable
        }
        let useMP4 = session.supportedFileTypes.contains(.mp4)
        let outputURL = try ImageEditing.attachmentsDirectory()
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension(useMP4 ? "mp4" : "mov")
        session.outputURL = outputURL
        session.outputFileType = useMP4 ? .mp4 : .mov
        session.shouldOptimizeForNetworkUse = true
        if !mixParameters.isEmpty {
            let mix = AVMutableAudioMix()
            mix.inputParameters = mixParameters
            session.audioMix = mix
        }
        if let videoComposition = videoComposition {
            session.videoComposition = videoComposition
        }

        var poller: Task<Void, Never>? = nil
        if let progress = progress {
            poller = Task.detached(priority: .utility) {
                while !Task.isCancelled {
                    progress(Double(session.progress))
                    try? await Task.sleep(nanoseconds: 150_000_000)
                }
            }
        }
        await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                session.exportAsynchronously {
                    continuation.resume()
                }
            }
        } onCancel: {
            session.cancelExport()
        }
        poller?.cancel()

        switch session.status {
        case .completed:
            progress?(1)
            return outputURL
        case .cancelled:
            throw cancelled
        default:
            try? FileManager.default.removeItem(at: outputURL)
            let reason = session.error?.localizedDescription ?? "unknown"
            throw MediaEditingError(russian: "Не удалось сохранить видео: \(reason)",
                                    english: "Could not export the video: \(reason)")
        }
    }

    /// Музыка или голос поверх видео: зацикливается или обрезается по длине ролика.
    private static func addExtraAudio(_ url: URL, to composition: AVMutableComposition, length: CMTime,
                                      volume: Float) async throws -> AVMutableAudioMixInputParameters {
        let asset = AVURLAsset(url: url)
        let tracks = try await asset.loadTracks(withMediaType: .audio)
        guard let source = tracks.first else { throw noAudioTrack }
        let sourceRange = try await source.load(.timeRange)
        let pieceLength = sourceRange.duration
        guard pieceLength.seconds > 0.05,
              let track = composition.addMutableTrack(withMediaType: .audio,
                                                      preferredTrackID: kCMPersistentTrackID_Invalid) else {
            throw noAudioTrack
        }
        var cursor = CMTime.zero
        var pieces = 0
        while CMTimeCompare(cursor, length) < 0 && pieces < 500 {
            let remaining = CMTimeSubtract(length, cursor)
            let piece = CMTimeMinimum(pieceLength, remaining)
            try track.insertTimeRange(CMTimeRange(start: sourceRange.start, duration: piece), of: source, at: cursor)
            cursor = CMTimeAdd(cursor, piece)
            pieces += 1
        }
        let level = min(max(volume, 0), 1)
        let parameters = AVMutableAudioMixInputParameters(track: track)
        parameters.setVolume(level, at: .zero)
        let totalSeconds = length.seconds
        if totalSeconds > 2 {
            let fadeStart = CMTime(seconds: totalSeconds - 0.6, preferredTimescale: 600)
            let fade = CMTimeRange(start: fadeStart, duration: CMTime(seconds: 0.6, preferredTimescale: 600))
            parameters.setVolumeRamp(fromStartVolume: level, toEndVolume: 0, timeRange: fade)
        }
        return parameters
    }

    /// Видеокомпозиция с фильтром Core Image (для экспорта и живого предпросмотра).
    static func filterComposition(for asset: AVAsset, filter: EditFilter) -> AVMutableVideoComposition {
        let chosen = filter
        return AVMutableVideoComposition(asset: asset, applyingCIFiltersWithHandler: { (request: AVAsynchronousCIImageFilteringRequest) in
            let output = ImageEditing.filteredCIImage(request.sourceImage, filter: chosen, intensity: 1)
            request.finish(with: output, context: nil)
        })
    }

    /// Кадры для ленты обрезки.
    static func thumbnails(for url: URL, count: Int) async -> [UIImage] {
        let seconds = await duration(of: url)
        guard count > 0, seconds > 0 else { return [] }
        let asset = AVURLAsset(url: url)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 240, height: 240)
        let tolerance = CMTime(seconds: max(0.05, seconds / Double(count) / 2), preferredTimescale: 600)
        generator.requestedTimeToleranceBefore = tolerance
        generator.requestedTimeToleranceAfter = tolerance
        var images: [UIImage] = []
        for index in 0..<count {
            let time = CMTime(seconds: seconds * (Double(index) + 0.5) / Double(count), preferredTimescale: 600)
            if let frame = try? await generator.image(at: time) {
                images.append(UIImage(cgImage: frame.image))
            } else if let last = images.last {
                images.append(last)
            }
        }
        return images
    }

    /// Длительность в секундах (0, если прочитать не удалось).
    static func duration(of url: URL) async -> Double {
        let asset = AVURLAsset(url: url)
        guard let value = try? await asset.load(.duration) else { return 0 }
        let seconds = value.seconds
        return seconds.isFinite && seconds > 0 ? seconds : 0
    }
}
