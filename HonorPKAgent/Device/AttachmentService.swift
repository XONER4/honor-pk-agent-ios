import AVFoundation
import ImageIO
import PDFKit
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers
import Vision

enum AttachmentService {
    private static let maximumFileBytes = 20 * 1024 * 1024
    private static let maximumTextLength = 160_000

    static func importImage(data: Data, name: String = "Фото.jpg") async throws -> MessageAttachment {
        try await Task.detached(priority: .userInitiated) {
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
            try jpeg.write(to: savedURL, options: .atomic)
            let extracted = (try? recognizedText(cgImage: cgImage)) ?? ""
            let displayName = (name as NSString).deletingPathExtension + ".jpg"
            return MessageAttachment(id: id, name: displayName, kind: .image,
                                     extractedText: extracted, localPath: savedURL.path)
        }.value
    }

    static func importFile(url: URL) async throws -> MessageAttachment {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        let values = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
        guard values.isRegularFile != false else { throw AttachmentError.unsupported }
        guard (values.fileSize ?? 0) <= maximumFileBytes else { throw AttachmentError.tooLarge }
        let data = try await Task.detached(priority: .userInitiated) { try Data(contentsOf: url) }.value
        guard data.count <= maximumFileBytes else { throw AttachmentError.tooLarge }
        if UTType(filenameExtension: url.pathExtension)?.conforms(to: .image) == true {
            return try await importImage(data: data, name: url.lastPathComponent)
        }
        return try await Task.detached(priority: .userInitiated) {
            let fileExtension = url.pathExtension.lowercased()
            let text: String
            let kind: AttachmentKind
            if fileExtension == "pdf" {
                text = try extractPDF(data: data)
                kind = .document
            } else {
                guard ["txt", "md", "markdown", "csv", "json", "log", "tsv", "text"].contains(fileExtension) else {
                    throw AttachmentError.unsupported
                }
                guard let decoded = String(data: data, encoding: .utf8)
                    ?? String(data: data, encoding: .utf16)
                    ?? String(data: data, encoding: .windowsCP1251) else { throw AttachmentError.unreadableText }
                text = decoded
                kind = .text
            }
            guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw AttachmentError.emptyDocument }
            guard text.count <= maximumTextLength else { throw AttachmentError.tooMuchText }
            let id = UUID()
            let savedURL = try destination(id: id, extension: fileExtension)
            try data.write(to: savedURL, options: .atomic)
            return MessageAttachment(id: id, name: url.lastPathComponent, kind: kind,
                                     extractedText: text, localPath: savedURL.path)
        }.value
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
        var errorDescription: String? {
            switch self {
            case .tooLarge: return "Размер файла — до 20 МБ / Maximum file size is 20 MB."
            case .tooMuchText: return "В документе больше 160 000 символов. Прикрепите меньший фрагмент. / Document exceeds 160,000 characters."
            case .tooManyPages: return "PDF должен содержать не больше 80 страниц / PDF must contain no more than 80 pages."
            case .invalidImage: return "Не удалось прочитать изображение / Could not read this image."
            case .unsupported: return "Поддерживаются изображения, PDF, TXT, Markdown, CSV, TSV и JSON / Unsupported file type."
            case .unreadableText: return "Не удалось прочитать текст файла / Could not decode this text file."
            case .emptyDocument: return "В документе не удалось распознать текст / No readable text found in this document."
            case .lockedPDF: return "PDF повреждён или защищён паролем / PDF is invalid or password protected."
            }
        }
    }
}

struct AttachmentTray: View {
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.colorScheme) private var colorScheme
    let onAttachment: (MessageAttachment) -> Void
    let onError: (String) -> Void
    @State private var selectedPhoto: PhotosPickerItem?
    @State private var showsCamera = false
    @State private var showsFiles = false
    @State private var isLoading = false

    var body: some View {
        VStack(spacing: 14) {
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
                PhotosPicker(selection: $selectedPhoto, matching: .images, photoLibrary: .shared()) {
                    tile("photo", settings.text("Альбом", "Photos"))
                }
                Button { showsFiles = true } label: { tile("paperclip", settings.text("Файл", "File")) }
            }
            .buttonStyle(.plain)
            .disabled(isLoading)
            Text(settings.text("Изображения · PDF · текстовые файлы", "Images · PDF · text files"))
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .onChange(of: selectedPhoto) { photo in
            guard let photo else { return }
            Task { @MainActor in
                isLoading = true
                defer { isLoading = false; selectedPhoto = nil }
                do {
                    guard let data = try await photo.loadTransferable(type: Data.self) else {
                        throw AttachmentService.AttachmentError.invalidImage
                    }
                    onAttachment(try await AttachmentService.importImage(data: data,
                        name: settings.text("Фото.jpg", "Photo.jpg")))
                } catch { onError(error.localizedDescription) }
            }
        }
        .sheet(isPresented: $showsCamera) {
            CameraPicker { image in
                showsCamera = false
                guard let data = image.jpegData(compressionQuality: 0.9) else {
                    onError(AttachmentService.AttachmentError.invalidImage.localizedDescription)
                    return
                }
                Task { @MainActor in
                    isLoading = true
                    defer { isLoading = false }
                    do { onAttachment(try await AttachmentService.importImage(data: data,
                        name: settings.text("Камера.jpg", "Camera.jpg"))) }
                    catch { onError(error.localizedDescription) }
                }
            } onCancel: { showsCamera = false }
            .ignoresSafeArea()
        }
        .fileImporter(isPresented: $showsFiles,
                      allowedContentTypes: [.image, .pdf, .text, .json, .commaSeparatedText,
                        UTType(filenameExtension: "md") ?? .plainText,
                        UTType(filenameExtension: "log") ?? .plainText],
                      allowsMultipleSelection: false) { result in
            switch result {
            case .success(let urls):
                guard let url = urls.first else { return }
                Task { @MainActor in
                    isLoading = true
                    defer { isLoading = false }
                    do { onAttachment(try await AttachmentService.importFile(url: url)) }
                    catch { onError(error.localizedDescription) }
                }
            case .failure(let error): onError(error.localizedDescription)
            }
        }
    }

    private func tile(_ symbol: String, _ title: String) -> some View {
        VStack(spacing: 10) {
            Image(systemName: symbol).font(.system(size: 26, weight: .medium))
            Text(title).font(.system(size: 15, weight: .semibold))
        }
        .foregroundStyle(.primary)
        .frame(maxWidth: .infinity)
        .frame(height: 84)
        .background(colorScheme == .dark ? Color(white: 0.18) : Color(white: 0.91),
                    in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private func openCamera() {
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
            onError(settings.text("Камера на этом устройстве недоступна.", "Camera is unavailable on this device."))
            return
        }
        Task { @MainActor in
            let allowed = await AVCaptureDevice.requestAccess(for: .video)
            if allowed { showsCamera = true }
            else { onError(settings.text("Разрешите доступ к камере в настройках iPhone → Honor PK Agent.",
                                          "Allow camera access in iPhone Settings → Honor PK Agent.")) }
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
