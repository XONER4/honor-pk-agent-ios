import AVFoundation
import CoreTransferable
import ImageIO
import PDFKit
import Photos
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers
import Vision

enum AttachmentService {
    private static let maximumFileBytes = 20 * 1024 * 1024
    private static let maximumTextLength = 160_000
    static let maximumVideoBytes = 300 * 1024 * 1024
    static let maximumVideoDuration: Double = 20 * 60
    static let maximumAudioBytes = 150 * 1024 * 1024
    /// Сколько секунд звука расшифровывается сразу при прикреплении.
    /// Остальное модель дослушает инструментом transcribe_media.
    static let importTranscriptionSeconds: Double = 300
    static let audioExtensions: Set<String> = ["m4a", "mp3", "wav", "aac", "caf", "aif", "aiff", "flac", "mp4a", "amr", "3gp"]

    static func importImage(data: Data, name: String = "Фото.jpg") async throws -> MessageAttachment {
        let worker = Task.detached(priority: .userInitiated) {
            try Task.checkCancellation()
            guard data.count <= maximumFileBytes else { throw AttachmentError.tooLarge }
            guard let source = CGImageSourceCreateWithData(data as CFData, nil),
                  let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                    kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceCreateThumbnailWithTransform: true,
                    kCGImageSourceThumbnailMaxPixelSize: 2048,
                    kCGImageSourceShouldCacheImmediately: true
                  ] as CFDictionary) else { throw AttachmentError.invalidImage }
            let image = UIImage(cgImage: cgImage)
            var quality: CGFloat = 0.86
            guard var jpeg = image.jpegData(compressionQuality: quality) else { throw AttachmentError.invalidImage }
            while jpeg.count > 2_500_000 && quality > 0.25 {
                quality -= 0.12
                if let reduced = image.jpegData(compressionQuality: quality) { jpeg = reduced }
            }
            let id = UUID()
            let savedURL = try destination(id: id, extension: "jpg")
            let extracted = (try? recognizedText(cgImage: cgImage)) ?? ""
            try Task.checkCancellation()
            try jpeg.write(to: savedURL, options: .atomic)
            let displayName = (name as NSString).deletingPathExtension + ".jpg"
            return MessageAttachment(id: id, name: displayName, kind: .image,
                                     extractedText: extracted, localPath: savedURL.path)
        }
        return try await withTaskCancellationHandler {
            try await worker.value
        } onCancel: {
            worker.cancel()
        }
    }

    static func importFile(url: URL) async throws -> MessageAttachment {
        let lowerExtension = url.pathExtension.lowercased()
        if audioExtensions.contains(lowerExtension)
            || (UTType(filenameExtension: lowerExtension)?.conforms(to: .audio) == true && lowerExtension != "mp4") {
            return try await importAudio(url: url)
        }
        if UTType(filenameExtension: url.pathExtension)?.conforms(to: .movie) == true {
            return try await importVideo(url: url)
        }
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        let values = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
        guard values.isRegularFile != false else { throw AttachmentError.unsupported }
        guard (values.fileSize ?? 0) <= maximumFileBytes else { throw AttachmentError.tooLarge }
        let data = try await Task.detached(priority: .userInitiated) { try Data(contentsOf: url) }.value
        try Task.checkCancellation()
        guard data.count <= maximumFileBytes else { throw AttachmentError.tooLarge }
        if UTType(filenameExtension: url.pathExtension)?.conforms(to: .image) == true {
            return try await importImage(data: data, name: url.lastPathComponent)
        }
        let worker = Task.detached(priority: .userInitiated) {
            try Task.checkCancellation()
            let fileExtension = url.pathExtension.lowercased()
            let text: String
            let kind: AttachmentKind
            var summary: String?
            if fileExtension == "pdf" {
                text = try extractPDF(data: data)
                kind = .document
                summary = "PDF"
            } else if DocumentReader.isSupported(fileExtension: fileExtension) {
                // Word, Excel, PowerPoint, OpenDocument, EPUB, RTF, HTML, CSV, код и текст.
                let document: DocumentText
                do {
                    document = try DocumentReader.extractText(from: data, fileExtension: fileExtension)
                } catch DocumentReaderError.emptyDocument {
                    throw AttachmentError.emptyDocument
                }
                text = document.text
                summary = document.summary
                let plain: Set<String> = ["txt", "md", "markdown", "text", "log"]
                kind = plain.contains(fileExtension) || DocumentReader.kind(forExtension: fileExtension).hasPrefix("Код")
                    ? .text : .document
            } else {
                throw AttachmentError.unsupported
            }
            guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw AttachmentError.emptyDocument }
            guard text.count <= maximumTextLength else { throw AttachmentError.tooMuchText }
            let id = UUID()
            let savedURL = try destination(id: id, extension: fileExtension)
            try Task.checkCancellation()
            try data.write(to: savedURL, options: .atomic)
            return MessageAttachment(id: id, name: url.lastPathComponent, kind: kind,
                                     extractedText: text, localPath: savedURL.path, summary: summary)
        }
        return try await withTaskCancellationHandler {
            try await worker.value
        } onCancel: {
            worker.cancel()
        }
    }

    /// Preserve the playable original and extract bounded, time-labelled visual samples for the model.
    static func importVideo(url: URL, name: String? = nil) async throws -> MessageAttachment {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        let worker = Task.detached(priority: .userInitiated) {
            try Task.checkCancellation()
            let values = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
            guard values.isRegularFile != false else { throw AttachmentError.invalidVideo }
            guard (values.fileSize ?? 0) <= maximumVideoBytes else { throw AttachmentError.videoTooLarge }
            let asset = AVURLAsset(url: url)
            let duration = try await asset.load(.duration).seconds
            guard duration.isFinite, duration > 0, !(try await asset.loadTracks(withMediaType: .video)).isEmpty else {
                throw AttachmentError.invalidVideo
            }
            guard duration <= maximumVideoDuration else { throw AttachmentError.videoTooLong }
            let generator = AVAssetImageGenerator(asset: asset)
            generator.appliesPreferredTrackTransform = true
            generator.maximumSize = CGSize(width: 1280, height: 1280)
            generator.requestedTimeToleranceBefore = CMTime(seconds: 0.1, preferredTimescale: 600)
            generator.requestedTimeToleranceAfter = CMTime(seconds: 0.1, preferredTimescale: 600)
            var createdFiles: [URL] = []
            var completed = false
            defer {
                generator.cancelAllCGImageGeneration()
                if !completed { for file in createdFiles { try? FileManager.default.removeItem(at: file) } }
            }
            let count = min(10, max(1, Int(ceil(duration * 2))))
            let end = max(0, duration - min(0.05, duration / 4))
            var frames: [String] = []
            var labels: [String] = []
            for index in 0..<count {
                try Task.checkCancellation()
                let second = count == 1 ? duration / 2 : end * Double(index) / Double(count - 1)
                let requested = CMTime(seconds: second, preferredTimescale: 600)
                let result = try await generator.image(at: requested)
                try Task.checkCancellation()
                guard let data = UIImage(cgImage: result.image).jpegData(compressionQuality: 0.8) else {
                    throw AttachmentError.invalidVideo
                }
                let frame = try destination(id: UUID(), extension: "jpg")
                createdFiles.append(frame)
                try data.write(to: frame, options: .atomic)
                frames.append(frame.path)
                labels.append(String(format: "%.2f", result.actualTime.seconds))
            }
            try Task.checkCancellation()
            let id = UUID()
            let fileExtension = url.pathExtension.isEmpty ? "mov" : url.pathExtension.lowercased()
            let saved = try destination(id: id, extension: fileExtension)
            createdFiles.append(saved)
            try FileManager.default.copyItem(at: url, to: saved)
            try Task.checkCancellation()
            let copiedSize = (try saved.resourceValues(forKeys: [.fileSizeKey])).fileSize ?? 0
            guard copiedSize <= maximumVideoBytes else { throw AttachmentError.videoTooLarge }
            // Звук видео расшифровывается на устройстве: модель «слышит», что говорят.
            let hasAudio = !(try await asset.loadTracks(withMediaType: .audio)).isEmpty
            var speech = ""
            if hasAudio {
                speech = (try? await MediaTranscriber.transcribe(saved, locale: Self.speechLocale,
                                                                 maximumSeconds: importTranscriptionSeconds)) ?? ""
            }
            try Task.checkCancellation()
            var description = "Video duration: \(String(format: "%.1f", duration)) seconds. Sampled frame timestamps in seconds: \(labels.joined(separator: ", ")). The frames below are a sample; do not claim to have seen unsampled moments."
            if !speech.isEmpty {
                description += "\nSpeech in the audio track (on-device transcription\(duration > importTranscriptionSeconds ? ", first \(Int(importTranscriptionSeconds / 60)) minutes; call transcribe_media with this file name for the rest" : "")):\n" + speech
            } else if hasAudio {
                description += "\nThe audio track has no recognizable speech (music, noise or silence)."
            } else {
                description += "\nThe video has no audio track."
            }
            let minutes = Int(duration) / 60, seconds = Int(duration) % 60
            let attachment = MessageAttachment(id: id, name: name ?? url.lastPathComponent, kind: .video,
                extractedText: description, localPath: saved.path, videoFramePaths: frames,
                summary: String(format: "Видео %d:%02d", minutes, seconds) + (speech.isEmpty ? "" : " · речь расшифрована"))
            completed = true
            return attachment
        }
        return try await withTaskCancellationHandler {
            try await worker.value
        } onCancel: { worker.cancel() }
    }

    /// Язык расшифровки речи — по языку приложения.
    static var speechLocale: String {
        UserDefaults.standard.string(forKey: "honor.language") == "en" ? "en-US" : "ru-RU"
    }

    /// Голосовое сообщение или аудиофайл: копия в приложении и расшифровка речи.
    static func importAudio(url: URL) async throws -> MessageAttachment {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        let worker = Task.detached(priority: .userInitiated) { () throws -> MessageAttachment in
            let values = try url.resourceValues(forKeys: [.fileSizeKey])
            guard (values.fileSize ?? 0) <= maximumAudioBytes else { throw AttachmentError.tooLarge }
            let id = UUID()
            let fileExtension = url.pathExtension.isEmpty ? "m4a" : url.pathExtension.lowercased()
            let saved = try destination(id: id, extension: fileExtension)
            try FileManager.default.copyItem(at: url, to: saved)
            let asset = AVURLAsset(url: saved)
            let duration = (try? await asset.load(.duration).seconds) ?? 0
            guard duration.isFinite, duration > 0 else {
                try? FileManager.default.removeItem(at: saved)
                throw AttachmentError.unsupported
            }
            let speech = (try? await MediaTranscriber.transcribe(saved, locale: speechLocale,
                                                                 maximumSeconds: importTranscriptionSeconds)) ?? ""
            try Task.checkCancellation()
            let minutes = Int(duration) / 60, seconds = Int(duration) % 60
            var text = "Audio recording, duration \(minutes):\(String(format: "%02d", seconds))."
            if speech.isEmpty {
                text += " No recognizable speech (music, noise or silence) — say so if asked about its content."
            } else {
                text += "\nTranscription (on-device speech recognition"
                if duration > importTranscriptionSeconds { text += ", first \(Int(importTranscriptionSeconds / 60)) minutes; call transcribe_media with this file name for the rest" }
                text += "):\n" + speech
            }
            return MessageAttachment(id: id, name: url.lastPathComponent, kind: .audio, extractedText: text,
                                     localPath: saved.path,
                                     summary: String(format: "Аудио %d:%02d", minutes, seconds) + (speech.isEmpty ? "" : " · расшифровано"))
        }
        return try await withTaskCancellationHandler {
            try await worker.value
        } onCancel: { worker.cancel() }
    }

    private static func destination(id: UUID, extension fileExtension: String) throws -> URL {
        let directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                    appropriateFor: nil, create: true)
            .appendingPathComponent("HonorPKAgent/Attachments", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent(id.uuidString).appendingPathExtension(fileExtension)
    }

    private static func recognizedText(cgImage: CGImage) throws -> String {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        request.automaticallyDetectsLanguage = true
        let supported = try request.supportedRecognitionLanguages()
        let preferred = ["ru-RU", "en-US"].filter { supported.contains($0) }
        if !preferred.isEmpty { request.recognitionLanguages = preferred }
        try VNImageRequestHandler(cgImage: cgImage).perform([request])
        return (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }.joined(separator: "\n")
    }

    private static func extractPDF(data: Data) throws -> String {
        guard let document = PDFDocument(data: data), !document.isLocked else { throw AttachmentError.lockedPDF }
        guard document.pageCount <= 80 else { throw AttachmentError.tooManyPages }
        var pages: [String] = []
        var totalLength = 0
        for index in 0..<document.pageCount {
            try Task.checkCancellation()
            guard let page = document.page(at: index) else { continue }
            var text = page.string?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if text.isEmpty {
                let image = page.thumbnail(of: CGSize(width: 1800, height: 2400), for: .mediaBox)
                if let cgImage = image.cgImage { text = (try? recognizedText(cgImage: cgImage)) ?? "" }
            }
            if !text.isEmpty {
                let content = "[Page \(index + 1)]\n\(text)"
                totalLength += content.count
                guard totalLength <= maximumTextLength else { throw AttachmentError.tooMuchText }
                pages.append(content)
            }
        }
        return pages.joined(separator: "\n\n")
    }

    enum AttachmentError: LocalizedError {
        case tooLarge, tooMuchText, tooManyPages, invalidImage, unsupported, unreadableText, emptyDocument, lockedPDF
        case videoTooLarge, videoTooLong, invalidVideo
        var errorDescription: String? {
            switch self {
            case .tooLarge: return "Размер файла — до 20 МБ / Maximum file size is 20 MB."
            case .tooMuchText: return "В документе больше 160 000 символов. Прикрепите меньший фрагмент. / Document exceeds 160,000 characters."
            case .tooManyPages: return "PDF должен содержать не больше 80 страниц / PDF must contain no more than 80 pages."
            case .invalidImage: return "Не удалось прочитать изображение / Could not read this image."
            case .unsupported: return "Этот тип файла не поддерживается. Можно: фото, видео, аудио, PDF, Word, Excel, PowerPoint, CSV, код и текст / Unsupported file type."
            case .unreadableText: return "Не удалось прочитать текст файла / Could not decode this text file."
            case .emptyDocument: return "В документе не удалось распознать текст / No readable text found in this document."
            case .lockedPDF: return "PDF повреждён или защищён паролем / PDF is invalid or password protected."
            case .videoTooLarge: return "Видео должно быть не больше 300 МБ / Maximum video size is 300 MB."
            case .videoTooLong: return "Видео должно быть не длиннее 20 минут / Maximum video duration is 20 minutes."
            case .invalidVideo: return "Не удалось прочитать видео. Выберите MP4, MOV или M4V. / Could not read the video. Choose MP4, MOV or M4V."
            }
        }
    }
}

@MainActor
struct AttachmentTray: View {
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var store: ChatStore
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let onAttachment: (MessageAttachment) -> Void
    let onError: (String) -> Void
    @State private var selectedPhoto: PhotosPickerItem?
    @State private var showsCamera = false
    @State private var showsFiles = false
    @State private var isLoading = false
    @State private var processingTask: Task<Void, Never>?
    @State private var permissionTask: Task<Void, Never>?
    @State private var trayVisible = false
    @State private var showsCameraPermissionAlert = false
    @State private var permissionIsPhotos = false
    @State private var libraryStatus = PHPhotoLibrary.authorizationStatus(for: .readWrite)
    @State private var recentAssets: [PHAsset] = []
    @State private var thumbnailImages: [String: UIImage] = [:]
    @State private var thumbnailRequests: [PHImageRequestID] = []
    @State private var importedAssetIDs: [String: UUID] = [:]
    @State private var thumbnailSession = UUID()

    var body: some View {
        let photoLabel = tile("photo", settings.text("Альбом", "Photos"))
        VStack(spacing: 14) {
            recentPhotos
            if isLoading {
                HStack(spacing: 10) {
                    ProgressView()
                    Text(settings.text("Обрабатываю вложение…", "Processing attachment…"))
                        .font(.system(size: 14))
                    Spacer()
                }
                .foregroundStyle(.secondary)
            }
            HStack(spacing: 9) {
                Button { openCamera() } label: { tile("camera", settings.text("Камера", "Camera")) }
                    .accessibilityIdentifier("attachment.camera")
                PhotosPicker(selection: $selectedPhoto, matching: .any(of: [.images, .videos]), photoLibrary: .shared()) {
                    photoLabel
                }
                .accessibilityIdentifier("attachment.album")
                Button { showsFiles = true } label: { tile("paperclip", settings.text("Файл", "File")) }
                    .accessibilityIdentifier("attachment.file")
            }
            .buttonStyle(.plain)
            .disabled(isLoading)
            Text(settings.text("Фото · видео до 2 минут и 40 МБ · файлы", "Photos · video up to 2 min / 40 MB · files"))
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: isLoading)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: recentAssets.count)
        .task { trayVisible = true; refreshRecents() }
        .onChange(of: scenePhase) { if $0 == .active && trayVisible { refreshRecents() } }
        .onChange(of: selectedPhoto) { photo in
            guard let photo else { return }
            processingTask = Task { @MainActor in
                isLoading = true
                defer { isLoading = false; selectedPhoto = nil }
                do {
                    let attachment: MessageAttachment
                    if photo.supportedContentTypes.contains(where: { $0.conforms(to: .movie) }) {
                        guard let media = try await photo.loadTransferable(type: ImportedMovie.self) else {
                            throw AttachmentService.AttachmentError.invalidVideo
                        }
                        defer { try? FileManager.default.removeItem(at: media.url) }
                        attachment = try await AttachmentService.importVideo(url: media.url,
                            name: settings.text("Видео.", "Video.") + media.url.pathExtension)
                    } else {
                        guard let data = try await photo.loadTransferable(type: Data.self) else {
                            throw AttachmentService.AttachmentError.invalidImage
                        }
                        attachment = try await AttachmentService.importImage(data: data,
                            name: settings.text("Фото.jpg", "Photo.jpg"))
                    }
                    try deliverNewAttachment(attachment)
                } catch {
                    if !(error is CancellationError) { onError(error.localizedDescription) }
                }
            }
        }
        .sheet(isPresented: $showsCamera) {
            CameraPicker { image in
                showsCamera = false
                guard let data = image.jpegData(compressionQuality: 0.9) else {
                    onError(AttachmentService.AttachmentError.invalidImage.localizedDescription)
                    return
                }
                processingTask = Task { @MainActor in
                    isLoading = true
                    defer { isLoading = false }
                    do {
                        let attachment = try await AttachmentService.importImage(data: data,
                            name: settings.text("Камера.jpg", "Camera.jpg"))
                        try deliverNewAttachment(attachment)
                    } catch {
                        if !(error is CancellationError) { onError(error.localizedDescription) }
                    }
                }
            } onCancel: { showsCamera = false }
            .ignoresSafeArea()
        }
        .fileImporter(isPresented: $showsFiles,
                      allowedContentTypes: [.item],
                      allowsMultipleSelection: false) { result in
            switch result {
            case .success(let urls):
                guard let url = urls.first else { return }
                processingTask = Task { @MainActor in
                    isLoading = true
                    defer { isLoading = false }
                    do {
                        let attachment = try await AttachmentService.importFile(url: url)
                        try deliverNewAttachment(attachment)
                    } catch {
                        if !(error is CancellationError) { onError(error.localizedDescription) }
                    }
                }
            case .failure(let error):
                if (error as NSError).code != NSUserCancelledError { onError(error.localizedDescription) }
            }
        }
        .alert(settings.text(permissionIsPhotos ? "Доступ к фото" : "Доступ к камере", permissionIsPhotos ? "Photos access" : "Camera access"), isPresented: $showsCameraPermissionAlert) {
            Button(settings.text("Отмена", "Cancel"), role: .cancel) {}
                .accessibilityIdentifier("attachment.permission.cancel")
            Button(settings.text("Настройки iPhone", "iPhone Settings")) {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
            .accessibilityIdentifier("attachment.permission.settings")
        } message: {
            Text(settings.text(permissionIsPhotos ? "Разрешите Honer AI показывать фото и видео в настройках iPhone. Выбор через «Альбом» доступен и без этого разрешения." : "Разрешите доступ к камере для Honer AI в настройках iPhone.",
                               permissionIsPhotos ? "Allow Honer AI to show photos and videos in iPhone Settings. You can still choose individual items using Photos." : "Allow camera access for Honer AI in iPhone Settings."))
        }
        .onDisappear {
            trayVisible = false
            thumbnailSession = UUID()
            processingTask?.cancel()
            permissionTask?.cancel()
            for request in thumbnailRequests { PHImageManager.default().cancelImageRequest(request) }
            thumbnailRequests.removeAll()
            thumbnailImages.removeAll()
        }
    }

    private func tile(_ symbol: String, _ title: String) -> some View {
        VStack(spacing: 10) {
            Image(systemName: symbol).font(.system(size: 26, weight: .medium))
            Text(title).font(.system(size: 15, weight: .semibold))
        }
        .foregroundStyle(.primary)
        .frame(maxWidth: .infinity)
        .frame(height: 92)
        .background(colorScheme == .dark ? Color(white: 0.18) : Color(white: 0.91),
                    in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private func openCamera() {
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
            onError(settings.text("Камера на этом устройстве недоступна.", "Camera is unavailable on this device."))
            return
        }
        processingTask = Task { @MainActor in
            let allowed = await AVCaptureDevice.requestAccess(for: .video)
            guard !Task.isCancelled else { return }
            if allowed { showsCamera = true }
            else { permissionIsPhotos = false; showsCameraPermissionAlert = true }
        }
    }

    @ViewBuilder
    private var recentPhotos: some View {
        if libraryStatus == .authorized || libraryStatus == .limited {
            if recentAssets.isEmpty {
                Text(settings.text("В доступной медиатеке пока нет фото и видео", "No photos or videos in the available library"))
                    .font(.system(size: 13)).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, minHeight: 72)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(recentAssets, id: \.localIdentifier) { asset in
                            Button { selectRecent(asset) } label: {
                                ZStack(alignment: .topTrailing) {
                                    Group {
                                        if let image = thumbnailImages[asset.localIdentifier] {
                                            Image(uiImage: image).resizable().scaledToFill()
                                        } else {
                                            Color.primary.opacity(0.08).overlay(ProgressView())
                                        }
                                    }
                                    .frame(width: 76, height: 76).clipped()
                                    .clipShape(RoundedRectangle(cornerRadius: 17, style: .continuous))
                                    let selected = importedAssetIDs[asset.localIdentifier].map { id in store.attachments.contains { $0.id == id } } ?? false
                                    Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                                        .font(.system(size: 20, weight: .semibold))
                                        .foregroundStyle(selected ? Color.blue : Color.white)
                                        .background(Circle().fill(Color.black.opacity(0.28)))
                                        .padding(5)
                                    if asset.mediaType == .video {
                                        Text("\(Int(asset.duration) / 60):\(String(format: "%02d", Int(asset.duration) % 60))")
                                            .font(.system(size: 10, weight: .semibold)).foregroundStyle(.white)
                                            .padding(.horizontal, 5).padding(.vertical, 2)
                                            .background(.black.opacity(0.6), in: Capsule())
                                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                                            .padding(5)
                                    }
                                }
                            }
                            .buttonStyle(.plain).disabled(isLoading)
                            .accessibilityLabel(settings.text(asset.mediaType == .video ? "Прикрепить видео" : "Прикрепить фото", asset.mediaType == .video ? "Attach video" : "Attach photo"))
                            .accessibilityIdentifier("attachment.recent.\(asset.localIdentifier)")
                        }
                    }
                }
                .frame(height: 78)
            }
        } else {
            Button { requestRecentPhotos() } label: {
                HStack(spacing: 12) {
                    Image(systemName: "photo.stack").font(.system(size: 25))
                    VStack(alignment: .leading, spacing: 3) {
                        Text(settings.text("Недавние фото и видео", "Recent photos and videos")).font(.system(size: 14, weight: .semibold))
                        Text(settings.text("Разрешить доступ", "Allow access")).font(.system(size: 12)).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold))
                }
                .foregroundStyle(.primary).padding(13)
                .frame(maxWidth: .infinity, minHeight: 74)
                .background(Color.primary.opacity(0.05), in: RoundedRectangle(cornerRadius: 18))
            }
            .buttonStyle(.plain).accessibilityIdentifier("attachment.recentsPermission")
        }
    }

    private func requestRecentPhotos() {
        permissionTask = Task { @MainActor in
            let status = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
            guard !Task.isCancelled, trayVisible else { return }
            libraryStatus = status
            if status == .authorized || status == .limited { refreshRecents() }
            else { permissionIsPhotos = true; showsCameraPermissionAlert = true }
        }
    }

    private func refreshRecents() {
        guard trayVisible else { return }
        let session = UUID()
        thumbnailSession = session
        libraryStatus = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        guard libraryStatus == .authorized || libraryStatus == .limited else { recentAssets = []; return }
        for request in thumbnailRequests { PHImageManager.default().cancelImageRequest(request) }
        thumbnailRequests.removeAll()
        let options = PHFetchOptions()
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        options.predicate = NSPredicate(format: "mediaType == %d OR mediaType == %d", PHAssetMediaType.image.rawValue, PHAssetMediaType.video.rawValue)
        options.fetchLimit = 14
        let fetched = PHAsset.fetchAssets(with: options)
        var assets: [PHAsset] = []
        fetched.enumerateObjects { asset, _, _ in assets.append(asset) }
        recentAssets = assets
        let imageOptions = PHImageRequestOptions()
        imageOptions.isNetworkAccessAllowed = true
        imageOptions.deliveryMode = .opportunistic
        for asset in assets {
            let key = asset.localIdentifier
            let request = PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: 180, height: 180), contentMode: .aspectFill, options: imageOptions) { image, _ in
                if let image {
                    Task { @MainActor in
                        guard trayVisible, thumbnailSession == session else { return }
                        thumbnailImages[key] = image
                    }
                }
            }
            thumbnailRequests.append(request)
        }
    }

    private func selectRecent(_ asset: PHAsset) {
        if let id = importedAssetIDs[asset.localIdentifier], store.attachments.contains(where: { $0.id == id }) {
            store.attachments.removeAll { $0.id == id }
            importedAssetIDs.removeValue(forKey: asset.localIdentifier)
            return
        }
        processingTask = Task { @MainActor in
            isLoading = true
            defer { isLoading = false }
            do {
                if asset.mediaType == .video && asset.duration > AttachmentService.maximumVideoDuration { throw AttachmentService.AttachmentError.videoTooLong }
                let resources = PHAssetResource.assetResources(for: asset)
                let preferred: [PHAssetResourceType] = asset.mediaType == .video ? [.fullSizeVideo, .video] : [.fullSizePhoto, .photo]
                guard let resource = preferred.compactMap({ type in resources.first { $0.type == type } }).first else { throw AttachmentService.AttachmentError.unsupported }
                let temporary = FileManager.default.temporaryDirectory.appendingPathComponent("HonerMedia-\(UUID().uuidString)").appendingPathExtension((resource.originalFilename as NSString).pathExtension)
                defer { try? FileManager.default.removeItem(at: temporary) }
                let options = PHAssetResourceRequestOptions()
                options.isNetworkAccessAllowed = true
                try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                    PHAssetResourceManager.default().writeData(for: resource, toFile: temporary, options: options) { error in
                        if let error { continuation.resume(throwing: error) }
                        else { continuation.resume(returning: ()) }
                    }
                }
                try Task.checkCancellation()
                var attachment = try await AttachmentService.importFile(url: temporary)
                attachment.name = resource.originalFilename
                try deliverNewAttachment(attachment)
                importedAssetIDs[asset.localIdentifier] = attachment.id
            } catch { if !(error is CancellationError) { onError(error.localizedDescription) } }
        }
    }

    /// Called only for fresh imports, before they become part of a draft or conversation.
    private func deliverNewAttachment(_ attachment: MessageAttachment) throws {
        guard !Task.isCancelled, trayVisible else {
            let referenced = store.attachments + store.conversations.flatMap { $0.messages.flatMap(\.attachments) }
            let protected = Set(referenced.flatMap(\.allLocalURLs).map { $0.resolvingSymlinksInPath().standardizedFileURL.path })
            if let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first {
                let root = support.appendingPathComponent("HonorPKAgent/Attachments", isDirectory: true)
                    .resolvingSymlinksInPath().standardizedFileURL
                for file in attachment.allLocalURLs {
                    let resolved = file.resolvingSymlinksInPath().standardizedFileURL
                    // Flat, app-owned import files only. Never remove a shared or external file.
                    if resolved.deletingLastPathComponent().path == root.path, !protected.contains(resolved.path) {
                        try? FileManager.default.removeItem(at: file)
                    }
                }
            }
            throw CancellationError()
        }
        onAttachment(attachment)
    }
}

private struct ImportedMovie: Transferable {
    let url: URL
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .movie) { received in
            let size = (try received.file.resourceValues(forKeys: [.fileSizeKey])).fileSize ?? 0
            guard size <= AttachmentService.maximumVideoBytes else { throw AttachmentService.AttachmentError.videoTooLarge }
            let fileExtension = received.file.pathExtension.isEmpty ? "mov" : received.file.pathExtension
            let copied = FileManager.default.temporaryDirectory.appendingPathComponent("HonerPicker-\(UUID().uuidString)").appendingPathExtension(fileExtension)
            try FileManager.default.copyItem(at: received.file, to: copied)
            return ImportedMovie(url: copied)
        }
    }
}

struct CameraPicker: UIViewControllerRepresentable {
    let onImage: (UIImage) -> Void
    let onCancel: () -> Void
    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }
    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.cameraCaptureMode = .photo
        picker.delegate = context.coordinator
        return picker
    }
    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}
    final class Coordinator: NSObject, UINavigationControllerDelegate, UIImagePickerControllerDelegate {
        let parent: CameraPicker
        init(parent: CameraPicker) { self.parent = parent }
        func imagePickerController(_ picker: UIImagePickerController,
                                   didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage { parent.onImage(image) }
            else { parent.onCancel() }
        }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.onCancel() }
    }
}

struct ActivitySheet: UIViewControllerRepresentable {
    var items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
