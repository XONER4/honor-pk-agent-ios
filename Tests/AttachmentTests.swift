import ImageIO
import AVFoundation
import UIKit
import XCTest
@testable import HonorPKAgent

final class AttachmentTests: XCTestCase {
    func testTextImportPreservesUnicodeAndSurvivesSourceRemoval() async throws {
        let folder = try temporaryFolder()
        let source = folder.appendingPathComponent("Заметка.md")
        let text = "# Honor\nПредпочитаю короткие ответы.\nUnicode: 🌍 café."
        let original = Data(text.utf8)
        try original.write(to: source)

        let attachment = try await AttachmentService.importFile(url: source)
        let saved = try retainedURL(attachment)
        XCTAssertEqual(attachment.kind, .text)
        XCTAssertEqual(attachment.name, "Заметка.md")
        XCTAssertEqual(attachment.extractedText, text)
        XCTAssertNotEqual(saved.path, source.path)
        try FileManager.default.removeItem(at: source)
        XCTAssertEqual(try Data(contentsOf: saved), original)
        XCTAssertEqual(attachment.resolvedURL, saved)
    }

    func testUTF16TextFileDecodesWithoutCorruptingCyrillic() async throws {
        let folder = try temporaryFolder()
        let source = folder.appendingPathComponent("unicode.txt")
        let text = "Honor помнит выбранные факты.\nSecond line."
        try XCTUnwrap(text.data(using: .utf16)).write(to: source)

        let attachment = try await AttachmentService.importFile(url: source)
        _ = try retainedURL(attachment)
        XCTAssertEqual(attachment.extractedText, text)
        XCTAssertEqual(attachment.kind, .text)
    }

    @MainActor
    func testImageImportNormalizesToJPEGAndLimitsPixelDimensions() async throws {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 3000, height: 1200), format: format)
        let image = renderer.image { context in
            UIColor.white.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 3000, height: 1200))
            UIColor.blue.setFill()
            context.fill(CGRect(x: 500, y: 200, width: 1600, height: 700))
        }
        let original = try XCTUnwrap(image.pngData())
        let attachment = try await AttachmentService.importImage(data: original, name: "source.PNG")
        let saved = try retainedURL(attachment)
        XCTAssertEqual(attachment.kind, .image)
        XCTAssertEqual(attachment.name, "source.jpg")
        XCTAssertEqual(saved.pathExtension, "jpg")
        let jpeg = try Data(contentsOf: saved)
        XCTAssertEqual(Array(jpeg.prefix(2)), [0xff, 0xd8])
        let source = try XCTUnwrap(CGImageSourceCreateWithData(jpeg as CFData, nil))
        let decoded = try XCTUnwrap(CGImageSourceCreateImageAtIndex(source, 0, nil))
        XCTAssertLessThanOrEqual(max(decoded.width, decoded.height), 2048)
        XCTAssertEqual(Double(decoded.width) / Double(decoded.height), 2.5, accuracy: 0.01)
        XCTAssertLessThan(jpeg.count, 2_500_000)
    }

    @MainActor
    func testPDFImportExtractsTextFromEveryPageAndKeepsOriginalPDF() async throws {
        let folder = try temporaryFolder()
        let source = folder.appendingPathComponent("two-pages.pdf")
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792))
        let data = renderer.pdfData { context in
            for text in ["Honor first page", "Honor second page"] {
                context.beginPage()
                (text as NSString).draw(at: CGPoint(x: 48, y: 64), withAttributes: [
                    .font: UIFont.systemFont(ofSize: 24), .foregroundColor: UIColor.black
                ])
            }
        }
        try data.write(to: source)

        let attachment = try await AttachmentService.importFile(url: source)
        let saved = try retainedURL(attachment)
        XCTAssertEqual(attachment.kind, .document)
        XCTAssertTrue(attachment.extractedText.contains("Honor first page"))
        XCTAssertTrue(attachment.extractedText.contains("Honor second page"))
        XCTAssertTrue(attachment.extractedText.contains("[Page 1]"))
        XCTAssertTrue(attachment.extractedText.contains("[Page 2]"))
        XCTAssertEqual(try Data(contentsOf: saved), data)
    }

    func testUnsupportedFileDoesNotBecomeTextAttachment() async throws {
        let folder = try temporaryFolder()
        let source = folder.appendingPathComponent("program.exe")
        try Data("This looks like text but is not an allowed document.".utf8).write(to: source)
        do {
            _ = try await AttachmentService.importFile(url: source)
            XCTFail("An unsupported file was accepted.")
        } catch AttachmentService.AttachmentError.unsupported {
            // Correctly refuses a misleading extension before creating an attachment.
        }
    }

    func testOversizedFileFailsBeforeReadingSparsePayload() async throws {
        let folder = try temporaryFolder()
        let source = folder.appendingPathComponent("oversized.txt")
        XCTAssertTrue(FileManager.default.createFile(atPath: source.path, contents: Data()))
        let handle = try FileHandle(forWritingTo: source)
        try handle.truncate(atOffset: 20 * 1024 * 1024 + 1)
        try handle.close()
        do {
            _ = try await AttachmentService.importFile(url: source)
            XCTFail("A document exceeding the file limit was accepted.")
        } catch AttachmentService.AttachmentError.tooLarge {
            // No large in-memory fixture or slow decode is needed for this guard.
        }
    }

    func testInvalidImageAndEmptyTextReturnUsefulFailures() async throws {
        do {
            _ = try await AttachmentService.importImage(data: Data([0, 1, 2, 3]), name: "broken.jpg")
            XCTFail("Invalid image bytes were accepted.")
        } catch AttachmentService.AttachmentError.invalidImage {}

        let source = try temporaryFolder().appendingPathComponent("empty.txt")
        try Data(" \n\t ".utf8).write(to: source)
        do {
            _ = try await AttachmentService.importFile(url: source)
            XCTFail("Whitespace-only text was accepted.")
        } catch AttachmentService.AttachmentError.emptyDocument {}
    }

    func testLongTextIsRejectedWithoutSilentlyTruncatingIt() async throws {
        let source = try temporaryFolder().appendingPathComponent("long.txt")
        try Data(String(repeating: "a", count: 160_001).utf8).write(to: source)
        do {
            _ = try await AttachmentService.importFile(url: source)
            XCTFail("Text above the model attachment limit was silently accepted.")
        } catch AttachmentService.AttachmentError.tooMuchText {}
    }

    func testAlreadyCancelledImportDoesNotReturnAnAttachment() async throws {
        let source = try temporaryFolder().appendingPathComponent("cancelled.txt")
        try Data("Cancelled before processing.".utf8).write(to: source)
        let task = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await AttachmentService.importFile(url: source)
        }
        do {
            _ = try await task.value
            XCTFail("A cancelled import returned an attachment.")
        } catch is CancellationError {}
    }

    func testVideoImportPreservesPlayableOriginalAndSamplesRealFrames() async throws {
        let folder = try temporaryFolder()
        let source = folder.appendingPathComponent("short.mp4")
        try await writeVideo(to: source, duration: 2, frameCount: 30)
        let original = try Data(contentsOf: source)
        let attachment = try await AttachmentService.importFile(url: source)
        let saved = try retainedURL(attachment)
        XCTAssertEqual(attachment.kind, .video)
        XCTAssertEqual(try Data(contentsOf: saved), original)
        let frames = try XCTUnwrap(attachment.videoFramePaths)
        XCTAssertGreaterThanOrEqual(frames.count, 1)
        XCTAssertLessThanOrEqual(frames.count, 6)
        XCTAssertEqual(Set(frames).count, frames.count)
        for path in frames {
            let data = try Data(contentsOf: URL(fileURLWithPath: path))
            let source = try XCTUnwrap(CGImageSourceCreateWithData(data as CFData, nil))
            let frame = try XCTUnwrap(CGImageSourceCreateImageAtIndex(source, 0, nil))
            XCTAssertGreaterThan(frame.width, 0)
            XCTAssertLessThanOrEqual(max(frame.width, frame.height), 1280)
        }
        XCTAssertTrue(attachment.extractedText.contains("frame timestamps"))
        XCTAssertTrue(attachment.extractedText.contains("audio track has not been transcribed"))
        let retainedAsset = AVURLAsset(url: saved)
        let duration = try await retainedAsset.load(.duration)
        XCTAssertGreaterThan(duration.seconds, 0)
        let tracks = try await retainedAsset.loadTracks(withMediaType: .video)
        XCTAssertFalse(tracks.isEmpty)
    }

    func testVideoSizeLimitRejectsSparseFileBeforeDecoding() async throws {
        let source = try temporaryFolder().appendingPathComponent("too-large.mov")
        XCTAssertTrue(FileManager.default.createFile(atPath: source.path, contents: Data()))
        let handle = try FileHandle(forWritingTo: source)
        try handle.truncate(atOffset: UInt64(AttachmentService.maximumVideoBytes + 1))
        try handle.close()
        do {
            _ = try await AttachmentService.importVideo(url: source)
            XCTFail("Oversized video was accepted.")
        } catch AttachmentService.AttachmentError.videoTooLarge {}
    }

    func testVideoDurationLimitRejectsLongPlayableAsset() async throws {
        let source = try temporaryFolder().appendingPathComponent("long.mp4")
        try await writeVideo(to: source, duration: 122, frameCount: 2)
        do {
            _ = try await AttachmentService.importVideo(url: source)
            XCTFail("A video longer than two minutes was accepted.")
        } catch AttachmentService.AttachmentError.videoTooLong {}
    }

    func testCancelledVideoImportDoesNotProduceAttachment() async throws {
        let source = try temporaryFolder().appendingPathComponent("cancelled.mp4")
        try await writeVideo(to: source, duration: 1, frameCount: 15)
        let task = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await AttachmentService.importVideo(url: source)
        }
        do {
            _ = try await task.value
            XCTFail("A cancelled video import returned an attachment.")
        } catch is CancellationError {}
    }

    private func writeVideo(to url: URL, duration: Double, frameCount: Int) async throws {
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: 96, AVVideoHeightKey: 64
        ])
        input.expectsMediaDataInRealTime = false
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32ARGB,
            kCVPixelBufferWidthKey as String: 96, kCVPixelBufferHeightKey as String: 64
        ])
        guard writer.canAdd(input) else { throw fixtureError("Cannot add video input") }
        writer.add(input)
        guard writer.startWriting() else { throw writer.error ?? fixtureError("Cannot start video writer") }
        writer.startSession(atSourceTime: .zero)
        let deadline = Date().addingTimeInterval(15)
        for index in 0..<frameCount {
            while !input.isReadyForMoreMediaData {
                guard Date() < deadline, writer.status == .writing else {
                    throw writer.error ?? fixtureError("Video encoder timed out")
                }
                try await Task.sleep(nanoseconds: 2_000_000)
            }
            var buffer: CVPixelBuffer?
            let status = CVPixelBufferCreate(kCFAllocatorDefault, 96, 64, kCVPixelFormatType_32ARGB, nil, &buffer)
            guard status == kCVReturnSuccess, let buffer else { throw fixtureError("Cannot create video frame") }
            CVPixelBufferLockBaseAddress(buffer, [])
            if let base = CVPixelBufferGetBaseAddress(buffer) {
                memset(base, Int32((index * 9) % 255), CVPixelBufferGetDataSize(buffer))
            }
            CVPixelBufferUnlockBaseAddress(buffer, [])
            let time = CMTime(seconds: Double(index) * duration / Double(frameCount), preferredTimescale: 600)
            guard adaptor.append(buffer, withPresentationTime: time) else {
                throw writer.error ?? fixtureError("Cannot encode video frame")
            }
        }
        writer.endSession(atSourceTime: CMTime(seconds: duration, preferredTimescale: 600))
        input.markAsFinished()
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            writer.finishWriting { continuation.resume() }
        }
        guard writer.status == .completed else { throw writer.error ?? fixtureError("Cannot finish video") }
    }

    private func fixtureError(_ message: String) -> NSError {
        NSError(domain: "AttachmentTests.VideoFixture", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
    }

    private func temporaryFolder() throws -> URL {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("HonorAttachmentTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: folder) }
        return folder
    }

    private func retainedURL(_ attachment: MessageAttachment) throws -> URL {
        let url = URL(fileURLWithPath: try XCTUnwrap(attachment.localPath))
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        for path in attachment.videoFramePaths ?? [] {
            addTeardownBlock { try? FileManager.default.removeItem(at: URL(fileURLWithPath: path)) }
        }
        return url
    }
}
