import SwiftUI
import UIKit
import AVKit
import AVFoundation
import PhotosUI
import PencilKit
import UniformTypeIdentifiers

// MARK: - Общие детали

private enum EditorPalette {
    static let colors: [UIColor] = [
        .white, .black,
        UIColor(red: 0.93, green: 0.22, blue: 0.21, alpha: 1),
        UIColor(red: 1, green: 0.58, blue: 0.1, alpha: 1),
        UIColor(red: 1, green: 0.84, blue: 0.1, alpha: 1),
        UIColor(red: 0.2, green: 0.75, blue: 0.35, alpha: 1),
        UIColor(red: 0.3, green: 0.8, blue: 0.95, alpha: 1),
        UIColor(red: 0.18, green: 0.42, blue: 0.95, alpha: 1),
        UIColor(red: 0.58, green: 0.32, blue: 0.9, alpha: 1),
        UIColor(red: 1, green: 0.45, blue: 0.7, alpha: 1)
    ]

    static let gradients: [[UIColor]] = [
        [UIColor(red: 1, green: 0.45, blue: 0.6, alpha: 1), UIColor(red: 1, green: 0.72, blue: 0.3, alpha: 1)],
        [UIColor(red: 0.3, green: 0.45, blue: 1, alpha: 1), UIColor(red: 0.72, green: 0.35, blue: 0.95, alpha: 1)],
        [UIColor(red: 0.1, green: 0.75, blue: 0.7, alpha: 1), UIColor(red: 0.55, green: 0.9, blue: 0.4, alpha: 1)],
        [UIColor(red: 0.12, green: 0.12, blue: 0.2, alpha: 1), UIColor(red: 0.35, green: 0.3, blue: 0.55, alpha: 1)]
    ]

    static let emojis: [String] = [
        "😀", "😂", "🥰", "😍", "😎", "🤩", "🥳", "😜", "🤔", "😴", "😭", "😡",
        "👍", "👏", "🙌", "💪", "🙏", "✌️", "👀", "💯", "🔥", "✨", "⭐️", "🌟",
        "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "💔", "🎉", "🎁", "🎈", "🎂",
        "🌸", "🌈", "☀️", "🌙", "⚡️", "❄️", "🐱", "🐶", "🦄", "🍕", "☕️", "🚀"
    ]

    static let gradientColors: [[Color]] = gradients.map { (pair: [UIColor]) -> [Color] in
        pair.map { (color: UIColor) -> Color in Color(uiColor: color) }
    }
}

private enum EditorGeometry {
    static func fitted(_ size: CGSize, in container: CGSize) -> CGSize {
        guard size.width > 0, size.height > 0, container.width > 0, container.height > 0 else { return .zero }
        let scale: CGFloat = min(container.width / size.width, container.height / size.height)
        return CGSize(width: floor(size.width * scale), height: floor(size.height * scale))
    }

    static func clampUnit(_ value: CGFloat) -> CGFloat {
        min(max(value, 0), 1)
    }

    static func timeString(_ seconds: Double) -> String {
        let value = seconds.isFinite ? max(0, seconds) : 0
        let minutes = Int(value) / 60
        let rest = value - Double(minutes * 60)
        return String(format: "%ld:%04.1f", minutes, rest)
    }
}

private struct EditorCheckerboard: View {
    var body: some View {
        Canvas { (context: inout GraphicsContext, size: CGSize) in
            let tile: CGFloat = 10
            let columns = Int(ceil(size.width / tile))
            let rows = Int(ceil(size.height / tile))
            for row in 0..<max(rows, 0) {
                for column in 0..<max(columns, 0) where (row + column) % 2 == 0 {
                    let rect = CGRect(x: CGFloat(column) * tile, y: CGFloat(row) * tile, width: tile, height: tile)
                    context.fill(Path(rect), with: .color(Color(white: 0.82)))
                }
            }
        }
        .background(Color.white)
    }
}

private struct EditorSliderRow: View {
    let title: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    var onBegin: () -> Void = {}

    var body: some View {
        HStack(spacing: 10) {
            Text(title)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(HonorTheme.secondary)
                .frame(width: 96, alignment: .leading)
                .lineLimit(1)
            Slider(value: $value, in: range, onEditingChanged: { (editing: Bool) in
                if editing { onBegin() }
            })
            .tint(HonorTheme.accent)
            Text(valueLabel)
                .font(.system(size: 12, weight: .semibold).monospacedDigit())
                .foregroundStyle(HonorTheme.foreground)
                .frame(width: 40, alignment: .trailing)
        }
        .padding(.horizontal, 16)
    }

    private var valueLabel: String {
        String(Int((value * 100).rounded()))
    }
}

private struct EditorColorRow: View {
    @Binding var selection: Int
    @EnvironmentObject private var settings: AppSettings

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(0..<EditorPalette.colors.count, id: \.self) { index in
                    swatch(index)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 3)
        }
    }

    private func swatch(_ index: Int) -> some View {
        let selected = selection == index
        return Button {
            selection = index
        } label: {
            Circle()
                .fill(Color(uiColor: EditorPalette.colors[index]))
                .frame(width: 28, height: 28)
                .overlay(Circle().stroke(HonorTheme.divider, lineWidth: 1))
                .overlay(Circle().stroke(HonorTheme.accent, lineWidth: selected ? 3 : 0).padding(-4))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(settings.text("Цвет", "Color") + " \(index + 1)")
    }
}

private struct EditorChip: View {
    let title: String
    let symbol: String?
    let selected: Bool

    var body: some View {
        HStack(spacing: 6) {
            if let symbol = symbol {
                Image(systemName: symbol).font(.system(size: 13, weight: .semibold))
            }
            Text(title).font(.system(size: 13, weight: .semibold)).lineLimit(1)
        }
        .foregroundStyle(selected ? Color.white : HonorTheme.foreground)
        .padding(.horizontal, 12)
        .frame(height: 34)
        .background(Capsule().fill(selected ? HonorTheme.accent : HonorTheme.raised))
    }
}

private struct EditorBusyOverlay: View {
    let message: String
    var progress: Double? = nil

    var body: some View {
        ZStack {
            Color.black.opacity(0.45).ignoresSafeArea()
            VStack(spacing: 14) {
                indicator
                Text(message)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(HonorTheme.foreground)
                    .multilineTextAlignment(.center)
            }
            .padding(24)
            .frame(minWidth: 180)
            .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(HonorTheme.surface))
        }
        .transition(.opacity)
    }

    @ViewBuilder private var indicator: some View {
        if let progress = progress {
            ProgressView(value: min(max(progress, 0), 1))
                .tint(HonorTheme.accent)
                .frame(width: 160)
            Text("\(Int((progress * 100).rounded()))%")
                .font(.system(size: 13, weight: .medium).monospacedDigit())
                .foregroundStyle(HonorTheme.secondary)
        } else {
            ProgressView().controlSize(.large).tint(HonorTheme.accent)
        }
    }
}

// MARK: - Модель фоторедактора

private struct PhotoLook: Equatable {
    var filter: EditFilter = .original
    var intensity: Double = 1
    var brightness: Double = 0
    var contrast: Double = 0
    var saturation: Double = 0
    var warmth: Double = 0

    var hasAdjustments: Bool {
        abs(brightness) > 0.001 || abs(contrast) > 0.001 || abs(saturation) > 0.001 || abs(warmth) > 0.001
    }

    var isIdentity: Bool {
        filter == .original && !hasAdjustments
    }

    func apply(to image: UIImage) -> UIImage {
        var result = image
        if filter != .original {
            result = ImageEditing.applyFilter(result, filter, intensity: intensity)
        }
        if hasAdjustments {
            result = ImageEditing.adjust(result, brightness: brightness, contrast: contrast,
                                         saturation: saturation, warmth: warmth)
        }
        return result
    }
}

private struct PhotoEditorSnapshot {
    let image: UIImage
    let look: PhotoLook
    let cutout: UIImage?
    let cutoutSource: UIImage?
}

private struct EditorSticker: Identifiable, Equatable {
    let id: UUID
    var emoji: String
    var position: CGPoint
    var scale: CGFloat
}

private enum PhotoEditorTool: String, CaseIterable, Identifiable {
    case filters, adjust, crop, background, text, stickers, draw

    var id: String { rawValue }

    var symbol: String {
        switch self {
        case .filters: return "camera.filters"
        case .adjust: return "slider.horizontal.3"
        case .crop: return "crop.rotate"
        case .background: return "wand.and.stars"
        case .text: return "textformat"
        case .stickers: return "face.smiling"
        case .draw: return "pencil.tip"
        }
    }

    var russian: String {
        switch self {
        case .filters: return "Фильтры"
        case .adjust: return "Настройки"
        case .crop: return "Обрезка"
        case .background: return "Фон"
        case .text: return "Текст"
        case .stickers: return "Стикеры"
        case .draw: return "Рисование"
        }
    }

    var english: String {
        switch self {
        case .filters: return "Filters"
        case .adjust: return "Adjust"
        case .crop: return "Crop"
        case .background: return "Background"
        case .text: return "Text"
        case .stickers: return "Stickers"
        case .draw: return "Draw"
        }
    }
}

@MainActor
private final class PhotoEditorModel: ObservableObject {
    @Published private(set) var base: UIImage?
    @Published private(set) var original: UIImage?
    @Published private(set) var preview: UIImage?
    @Published private(set) var previewHasTransparency = false
    @Published private(set) var thumbnails: [EditFilter: UIImage] = [:]
    @Published private(set) var busyMessage: String?
    @Published private(set) var isWorking = false
    @Published private(set) var canUndo = false
    @Published private(set) var hasCutout = false
    @Published private(set) var loadFailed = false
    @Published var errorMessage: String?
    @Published var look = PhotoLook() {
        didSet {
            if look != oldValue && !batchingLook { renderPreview() }
        }
    }

    @Published var textDraft = ""
    @Published var textColorIndex = 0
    @Published var textStyle: TextStyle = .outline
    @Published var textSize: Double = 0.08
    @Published var textPosition = CGPoint(x: 0.5, y: 0.5)

    @Published private(set) var stickers: [EditorSticker] = []

    @Published var drawColorIndex = 2
    @Published var drawWidth: Double = 8
    @Published var erasing = false

    let canvas = PKCanvasView()
    var english = false

    private var displayBase: UIImage?
    private var cutout: UIImage?
    private var cutoutSource: UIImage?
    private var undoStack: [PhotoEditorSnapshot] = []
    private var isRendering = false
    private var needsRender = false
    private var batchingLook = false
    private var thumbnailTask: Task<Void, Never>?

    private func t(_ russian: String, _ englishText: String) -> String {
        english ? englishText : russian
    }

    var textColor: UIColor { EditorPalette.colors[min(max(textColorIndex, 0), EditorPalette.colors.count - 1)] }
    var drawColor: UIColor { EditorPalette.colors[min(max(drawColorIndex, 0), EditorPalette.colors.count - 1)] }

    // MARK: Загрузка и предпросмотр

    func load(url: URL) async {
        guard base == nil, !loadFailed else { return }
        let loaded: UIImage? = await Task.detached(priority: .userInitiated) { () -> UIImage? in
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            return ImageEditing.load(url, maxPixel: 2048)
        }.value
        guard let image = loaded else {
            loadFailed = true
            return
        }
        original = image
        await setBase(image)
    }

    private func setBase(_ image: UIImage) async {
        base = image
        if look.isIdentity {
            preview = image
        }
        let proxy: UIImage = await Task.detached(priority: .userInitiated) { () -> UIImage in
            ImageEditing.resize(image, maxSide: 1600)
        }.value
        guard base === image else { return }
        displayBase = proxy
        renderPreview()
        rebuildThumbnails(from: proxy)
    }

    func renderPreview() {
        guard let source = displayBase else { return }
        if isRendering {
            needsRender = true
            return
        }
        isRendering = true
        let currentLook = look
        Task {
            let result: (UIImage, Bool) = await Task.detached(priority: .userInitiated) { () -> (UIImage, Bool) in
                let image = currentLook.apply(to: source)
                return (image, ImageEditing.hasTransparency(image))
            }.value
            self.preview = result.0
            self.previewHasTransparency = result.1
            self.isRendering = false
            if self.needsRender {
                self.needsRender = false
                self.renderPreview()
            }
        }
    }

    private func rebuildThumbnails(from proxy: UIImage) {
        thumbnailTask?.cancel()
        thumbnailTask = Task {
            let map: [EditFilter: UIImage] = await Task.detached(priority: .utility) { () -> [EditFilter: UIImage] in
                let small = ImageEditing.crop(ImageEditing.resize(proxy, maxSide: 200), aspect: .square)
                var result: [EditFilter: UIImage] = [:]
                for filter in EditFilter.allCases {
                    result[filter] = ImageEditing.applyFilter(small, filter)
                }
                return result
            }.value
            if Task.isCancelled { return }
            self.thumbnails = map
        }
    }

    // MARK: Отмена

    func pushUndo() {
        guard let image = base else { return }
        undoStack.append(PhotoEditorSnapshot(image: image, look: look, cutout: cutout, cutoutSource: cutoutSource))
        if undoStack.count > 15 {
            undoStack.removeFirst(undoStack.count - 15)
        }
        canUndo = true
    }

    func undo() async {
        guard !isWorking, let last = undoStack.popLast() else { return }
        canUndo = !undoStack.isEmpty
        cutout = last.cutout
        cutoutSource = last.cutoutSource
        hasCutout = last.cutout != nil
        batchingLook = true
        look = last.look
        batchingLook = false
        await setBase(last.image)
    }

    // MARK: Правки

    /// Выполнить тяжёлую правку вне главного потока. resetLook — «запечь» фильтр в фото.
    @discardableResult
    private func perform(busy: String?, resetLook: Bool = false,
                         _ work: @escaping (UIImage) async throws -> UIImage) async -> Bool {
        guard let current = base, !isWorking else { return false }
        isWorking = true
        busyMessage = busy
        defer {
            isWorking = false
            busyMessage = nil
        }
        do {
            let result: UIImage = try await Task.detached(priority: .userInitiated) { () -> UIImage in
                try await work(current)
            }.value
            pushUndo()
            if resetLook {
                batchingLook = true
                look = PhotoLook()
                batchingLook = false
            }
            await setBase(result)
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    private func clearCutout() {
        cutout = nil
        cutoutSource = nil
        hasCutout = false
    }

    func selectFilter(_ filter: EditFilter) {
        guard look.filter != filter else { return }
        pushUndo()
        var next = look
        next.filter = filter
        next.intensity = 1
        look = next
    }

    func resetAdjustments() {
        guard look.hasAdjustments else { return }
        pushUndo()
        var next = look
        next.brightness = 0
        next.contrast = 0
        next.saturation = 0
        next.warmth = 0
        look = next
    }

    func rotate(clockwise: Bool) async {
        let done = await perform(busy: nil) { (image: UIImage) -> UIImage in
            ImageEditing.rotate(image, clockwise: clockwise)
        }
        if done { clearCutout() }
    }

    func flip(vertical: Bool) async {
        let done = await perform(busy: nil) { (image: UIImage) -> UIImage in
            vertical ? ImageEditing.flipVertically(image) : ImageEditing.flipHorizontally(image)
        }
        if done { clearCutout() }
    }

    func crop(_ aspect: CropAspect) async {
        let done = await perform(busy: nil) { (image: UIImage) -> UIImage in
            ImageEditing.crop(image, aspect: aspect)
        }
        if done { clearCutout() }
    }

    func removeBackground() async {
        guard let current = base, !isWorking else { return }
        if let existing = cutout, existing === current { return }
        isWorking = true
        busyMessage = t("Убираю фон…", "Removing background…")
        defer {
            isWorking = false
            busyMessage = nil
        }
        do {
            let subject = try await ImageEditing.removeBackground(current)
            pushUndo()
            cutoutSource = current
            cutout = subject
            hasCutout = true
            await setBase(subject)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// nil — прозрачный фон.
    func applyBackground(_ background: EditBackground?) async {
        if cutout == nil {
            await removeBackground()
        }
        guard let subject = cutout, let source = cutoutSource, !isWorking else { return }
        guard let background = background else {
            if base !== subject {
                pushUndo()
                await setBase(subject)
            }
            return
        }
        isWorking = true
        busyMessage = t("Меняю фон…", "Changing background…")
        defer {
            isWorking = false
            busyMessage = nil
        }
        let result: UIImage = await Task.detached(priority: .userInitiated) { () -> UIImage in
            var chosen = background
            if case .image(let picture) = background {
                chosen = .image(ImageEditing.resize(picture, maxSide: 2048))
            }
            return ImageEditing.composite(subject: subject, over: chosen, original: source)
        }.value
        pushUndo()
        await setBase(result)
    }

    // MARK: Текст, стикеры, рисунок

    var pendingText: String {
        textDraft.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    func commitText() async {
        let text = pendingText
        guard !text.isEmpty else { return }
        let currentLook = look
        let color = textColor
        let position = textPosition
        let size = CGFloat(textSize)
        let style = textStyle
        let done = await perform(busy: nil, resetLook: !currentLook.isIdentity) { (image: UIImage) -> UIImage in
            let looked = currentLook.apply(to: image)
            return ImageEditing.addText(looked, text: text, position: position, color: color, fontSize: size, style: style)
        }
        if done {
            textDraft = ""
            textPosition = CGPoint(x: 0.5, y: 0.5)
            clearCutout()
        }
    }

    func addSticker(_ emoji: String) {
        let offset = CGFloat(stickers.count % 5) * 0.05
        let sticker = EditorSticker(id: UUID(), emoji: emoji,
                                    position: CGPoint(x: 0.5 + offset, y: 0.5 + offset), scale: 1)
        stickers.append(sticker)
    }

    func moveSticker(_ id: UUID, to position: CGPoint) {
        guard let index = stickers.firstIndex(where: { (item: EditorSticker) -> Bool in item.id == id }) else { return }
        stickers[index].position = CGPoint(x: EditorGeometry.clampUnit(position.x), y: EditorGeometry.clampUnit(position.y))
    }

    func scaleSticker(_ id: UUID, to scale: CGFloat) {
        guard let index = stickers.firstIndex(where: { (item: EditorSticker) -> Bool in item.id == id }) else { return }
        stickers[index].scale = min(max(scale, 0.3), 5)
    }

    func removeSticker(_ id: UUID) {
        stickers.removeAll { (item: EditorSticker) -> Bool in item.id == id }
    }

    func clearStickers() {
        stickers.removeAll()
    }

    func commitStickers() async {
        guard !stickers.isEmpty else { return }
        let items = stickers
        let currentLook = look
        let done = await perform(busy: nil, resetLook: !currentLook.isIdentity) { (image: UIImage) -> UIImage in
            var result = currentLook.apply(to: image)
            for item in items {
                result = ImageEditing.addSticker(result, emoji: item.emoji, position: item.position, scale: item.scale)
            }
            return result
        }
        if done {
            stickers.removeAll()
            clearCutout()
        }
    }

    var hasDrawing: Bool {
        !canvas.drawing.bounds.isEmpty
    }

    func clearDrawing() {
        canvas.drawing = PKDrawing()
    }

    func commitDrawing() async {
        guard hasDrawing, let image = base, canvas.bounds.width > 1 else { return }
        let bounds = canvas.bounds
        let pixelWidth = image.size.width * image.scale
        let scale: CGFloat = max(1, pixelWidth / bounds.width)
        let drawing = canvas.drawing
        var layer = UIImage()
        UITraitCollection(userInterfaceStyle: .light).performAsCurrent {
            layer = drawing.image(from: bounds, scale: scale)
        }
        let drawnLayer = layer
        let currentLook = look
        let done = await perform(busy: nil, resetLook: !currentLook.isIdentity) { (source: UIImage) -> UIImage in
            ImageEditing.overlay(currentLook.apply(to: source), with: drawnLayer)
        }
        if done {
            canvas.drawing = PKDrawing()
            clearCutout()
        }
    }

    func hasPending(for tool: PhotoEditorTool) -> Bool {
        switch tool {
        case .text: return !pendingText.isEmpty
        case .stickers: return !stickers.isEmpty
        case .draw: return hasDrawing
        default: return false
        }
    }

    /// Закрепить незавершённые слои инструмента, который покидаем.
    func flush(leaving tool: PhotoEditorTool) async {
        switch tool {
        case .text: await commitText()
        case .stickers: await commitStickers()
        case .draw: await commitDrawing()
        default: break
        }
    }

    // MARK: Сохранение

    func exportFinal() async -> URL? {
        await commitDrawing()
        await commitText()
        await commitStickers()
        guard let image = base, !isWorking else { return nil }
        isWorking = true
        busyMessage = t("Сохраняю…", "Saving…")
        defer {
            isWorking = false
            busyMessage = nil
        }
        let currentLook = look
        do {
            let url: URL = try await Task.detached(priority: .userInitiated) { () -> URL in
                let finished = currentLook.apply(to: image)
                return try ImageEditing.save(finished, preferPNG: ImageEditing.hasTransparency(finished))
            }.value
            return url
        } catch {
            errorMessage = error.localizedDescription
            return nil
        }
    }
}

// MARK: - Фоторедактор

struct PhotoEditorView: View {
    private let imageURL: URL
    private let onSave: (URL) -> Void

    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    @StateObject private var model = PhotoEditorModel()
    @State private var tool: PhotoEditorTool = .filters
    @State private var comparing = false

    init(imageURL: URL, onSave: @escaping (URL) -> Void) {
        self.imageURL = imageURL
        self.onSave = onSave
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            canvasArea
            toolPanel
            tabBar
        }
        .background(HonorTheme.background.ignoresSafeArea())
        .overlay { busyOverlay }
        .alert(settings.text("Ошибка", "Error"), isPresented: errorBinding, actions: {
            Button("OK", role: .cancel) {}
        }, message: {
            Text(model.errorMessage ?? "")
        })
        .task {
            model.english = settings.language == .english
            await model.load(url: imageURL)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("editor.photo")
    }

    private var errorBinding: Binding<Bool> {
        Binding(get: { model.errorMessage != nil },
                set: { (shown: Bool) in if !shown { model.errorMessage = nil } })
    }

    @ViewBuilder private var busyOverlay: some View {
        if let message = model.busyMessage {
            EditorBusyOverlay(message: message)
        }
    }

    // MARK: Верхняя панель

    private var topBar: some View {
        HStack(spacing: 12) {
            Button(settings.text("Отмена", "Cancel")) { dismiss() }
                .foregroundStyle(HonorTheme.foreground)
                .accessibilityIdentifier("editor.cancel")
            Spacer()
            Text(settings.text("Редактор", "Editor"))
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(HonorTheme.foreground)
            Spacer()
            doneButton
        }
        .padding(.horizontal, 16)
        .frame(height: 52)
    }

    private var doneButton: some View {
        Button(action: save) {
            Text(settings.text("Готово", "Done"))
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Color.white)
                .padding(.horizontal, 16)
                .frame(height: 34)
                .background(Capsule().fill(HonorTheme.accent))
        }
        .buttonStyle(.plain)
        .disabled(model.base == nil || model.isWorking)
        .opacity(model.base == nil ? 0.5 : 1)
        .accessibilityIdentifier("editor.save")
    }

    // MARK: Холст

    private var canvasArea: some View {
        GeometryReader { (proxy: GeometryProxy) in
            PhotoEditorCanvas(model: model, tool: tool, comparing: comparing, containerSize: proxy.size)
        }
        .padding(.horizontal, 12)
        .padding(.top, 4)
        .padding(.bottom, 52)
        .overlay(alignment: .bottom) { canvasControls }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var canvasControls: some View {
        HStack(spacing: 12) {
            undoButton
            Spacer()
            compareButton
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    private var undoButton: some View {
        Button {
            Task { await model.undo() }
        } label: {
            Image(systemName: "arrow.uturn.backward")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(HonorTheme.foreground)
                .frame(width: 38, height: 38)
                .background(Circle().fill(HonorTheme.raised))
        }
        .buttonStyle(.plain)
        .disabled(!model.canUndo || model.isWorking)
        .opacity(model.canUndo ? 1 : 0.4)
        .accessibilityLabel(settings.text("Отменить", "Undo"))
        .accessibilityIdentifier("editor.undo")
    }

    private var compareButton: some View {
        Text(settings.text("Сравнить", "Compare"))
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(comparing ? Color.white : HonorTheme.foreground)
            .padding(.horizontal, 14)
            .frame(height: 38)
            .background(Capsule().fill(comparing ? HonorTheme.accent : HonorTheme.raised))
            .contentShape(Capsule())
            .gesture(compareGesture)
            .accessibilityAddTraits(.isButton)
            .accessibilityIdentifier("editor.compare")
    }

    private var compareGesture: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { (_: DragGesture.Value) in
                if !comparing { comparing = true }
            }
            .onEnded { (_: DragGesture.Value) in
                comparing = false
            }
    }

    // MARK: Панель инструмента

    private var toolPanel: some View {
        ZStack {
            panelContent
                .id(tool)
                .transition(.asymmetric(insertion: .move(edge: .bottom).combined(with: .opacity),
                                        removal: .opacity))
        }
        .frame(height: 178)
        .frame(maxWidth: .infinity)
        .background(HonorTheme.surface)
        .clipped()
    }

    @ViewBuilder private var panelContent: some View {
        switch tool {
        case .filters: PhotoFiltersPanel(model: model)
        case .adjust: PhotoAdjustPanel(model: model)
        case .crop: PhotoCropPanel(model: model)
        case .background: PhotoBackgroundPanel(model: model)
        case .text: PhotoTextPanel(model: model)
        case .stickers: PhotoStickersPanel(model: model)
        case .draw: PhotoDrawPanel(model: model)
        }
    }

    private var tabBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 4) {
                ForEach(PhotoEditorTool.allCases) { item in
                    tabButton(item)
                }
            }
            .padding(.horizontal, 8)
        }
        .frame(height: 60)
        .background(HonorTheme.background)
        .overlay(alignment: .top) { Rectangle().fill(HonorTheme.divider).frame(height: 0.5) }
    }

    private func tabButton(_ item: PhotoEditorTool) -> some View {
        let selected = tool == item
        return Button {
            select(item)
        } label: {
            VStack(spacing: 4) {
                Image(systemName: item.symbol).font(.system(size: 18, weight: .medium))
                Text(settings.text(item.russian, item.english)).font(.system(size: 10, weight: .semibold)).lineLimit(1)
            }
            .foregroundStyle(selected ? HonorTheme.accent : HonorTheme.secondary)
            .frame(minWidth: 58, minHeight: 50)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("editor.tab." + item.rawValue)
    }

    private func select(_ item: PhotoEditorTool) {
        guard item != tool else { return }
        let leaving = tool
        if model.hasPending(for: leaving) {
            Task {
                await model.flush(leaving: leaving)
                withAnimation(.spring(response: 0.35, dampingFraction: 0.86)) { tool = item }
            }
        } else {
            withAnimation(.spring(response: 0.35, dampingFraction: 0.86)) { tool = item }
        }
    }

    private func save() {
        Task {
            if let url = await model.exportFinal() {
                onSave(url)
                dismiss()
            }
        }
    }
}

// MARK: - Холст с наложениями

private struct PhotoEditorCanvas: View {
    @ObservedObject var model: PhotoEditorModel
    @EnvironmentObject private var settings: AppSettings
    let tool: PhotoEditorTool
    let comparing: Bool
    let containerSize: CGSize

    private var displayed: UIImage? {
        comparing ? (model.original ?? model.preview) : model.preview
    }

    var body: some View {
        ZStack {
            if let image = displayed {
                stage(image)
            } else if model.loadFailed {
                failure
            } else {
                ProgressView().tint(HonorTheme.accent)
            }
        }
        .frame(width: containerSize.width, height: containerSize.height)
    }

    private var failure: some View {
        VStack(spacing: 8) {
            Image(systemName: "photo").font(.system(size: 34))
            Text(settings.text("Не удалось открыть фото", "Could not open the photo")).font(.system(size: 14))
        }
        .foregroundStyle(HonorTheme.secondary)
    }

    private func stage(_ image: UIImage) -> some View {
        let size = EditorGeometry.fitted(image.size, in: containerSize)
        return ZStack {
            if model.previewHasTransparency && !comparing {
                EditorCheckerboard()
            }
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
            if !comparing {
                overlays(size)
            }
        }
        .frame(width: size.width, height: size.height)
        .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
        .shadow(color: Color.black.opacity(0.25), radius: 10, y: 4)
        .animation(.spring(response: 0.35, dampingFraction: 0.86), value: size)
    }

    @ViewBuilder private func overlays(_ size: CGSize) -> some View {
        EditorStickerLayer(model: model, size: size)
        if tool == .text {
            EditorTextOverlay(model: model, size: size)
        }
        if tool == .draw {
            EditorDrawingCanvas(canvas: model.canvas, color: model.drawColor,
                                width: CGFloat(model.drawWidth), erasing: model.erasing)
                .frame(width: size.width, height: size.height)
        }
    }
}

private struct EditorStyledCaption: View {
    let text: String
    let color: UIColor
    let style: TextStyle
    let pointSize: CGFloat

    var body: some View {
        styled.multilineTextAlignment(.center)
    }

    private var baseText: Text {
        Text(text).font(.system(size: pointSize, weight: .heavy))
    }

    @ViewBuilder private var styled: some View {
        switch style {
        case .plain:
            baseText
                .foregroundColor(Color(uiColor: color))
                .shadow(color: Color.black.opacity(0.4), radius: pointSize * 0.06, x: 0, y: pointSize * 0.04)
        case .outline:
            outlined
        case .banner:
            baseText
                .foregroundColor(Color(uiColor: ImageEditing.contrastingColor(for: color)))
                .padding(.horizontal, pointSize * 0.45)
                .padding(.vertical, pointSize * 0.2)
                .background(RoundedRectangle(cornerRadius: pointSize * 0.3, style: .continuous).fill(Color(uiColor: color)))
        case .neon:
            baseText
                .foregroundColor(Color(uiColor: ImageEditing.neonCore(for: color)))
                .shadow(color: Color(uiColor: color), radius: pointSize * 0.2)
                .shadow(color: Color(uiColor: color), radius: pointSize * 0.4)
        }
    }

    private var outlined: some View {
        let stroke = Color(uiColor: ImageEditing.contrastingColor(for: color))
        let offset: CGFloat = max(1, pointSize * 0.03)
        return baseText
            .foregroundColor(Color(uiColor: color))
            .shadow(color: stroke, radius: 0, x: offset, y: 0)
            .shadow(color: stroke, radius: 0, x: -offset, y: 0)
            .shadow(color: stroke, radius: 0, x: 0, y: offset)
            .shadow(color: stroke, radius: 0, x: 0, y: -offset)
    }
}

private struct EditorTextOverlay: View {
    @ObservedObject var model: PhotoEditorModel
    let size: CGSize
    @State private var dragStart: CGPoint?

    var body: some View {
        ZStack {
            if !model.pendingText.isEmpty {
                caption
            }
        }
        .frame(width: size.width, height: size.height)
    }

    private var caption: some View {
        EditorStyledCaption(text: model.pendingText, color: model.textColor, style: model.textStyle,
                            pointSize: max(6, CGFloat(model.textSize) * size.width))
            .frame(maxWidth: size.width * 0.92)
            .fixedSize(horizontal: false, vertical: true)
            .gesture(dragGesture)
            .position(x: model.textPosition.x * size.width, y: model.textPosition.y * size.height)
    }

    private var dragGesture: some Gesture {
        DragGesture()
            .onChanged { (value: DragGesture.Value) in
                let start = dragStart ?? model.textPosition
                if dragStart == nil { dragStart = start }
                let x = start.x + value.translation.width / max(size.width, 1)
                let y = start.y + value.translation.height / max(size.height, 1)
                model.textPosition = CGPoint(x: EditorGeometry.clampUnit(x), y: EditorGeometry.clampUnit(y))
            }
            .onEnded { (_: DragGesture.Value) in
                dragStart = nil
            }
    }
}

private struct EditorStickerLayer: View {
    @ObservedObject var model: PhotoEditorModel
    let size: CGSize

    var body: some View {
        ZStack {
            ForEach(model.stickers) { sticker in
                EditorStickerView(model: model, sticker: sticker, size: size)
            }
        }
        .frame(width: size.width, height: size.height)
    }
}

private struct EditorStickerView: View {
    @ObservedObject var model: PhotoEditorModel
    let sticker: EditorSticker
    let size: CGSize
    @State private var dragStart: CGPoint?
    @State private var scaleStart: CGFloat?

    var body: some View {
        Text(sticker.emoji)
            .font(.system(size: fontSize))
            .gesture(dragGesture.simultaneously(with: pinchGesture))
            .onTapGesture(count: 2) { model.removeSticker(sticker.id) }
            .position(x: sticker.position.x * size.width, y: sticker.position.y * size.height)
            .accessibilityLabel(sticker.emoji)
    }

    private var fontSize: CGFloat {
        max(8, size.width * 0.18 * sticker.scale)
    }

    private var dragGesture: some Gesture {
        DragGesture()
            .onChanged { (value: DragGesture.Value) in
                let start = dragStart ?? sticker.position
                if dragStart == nil { dragStart = start }
                let x = start.x + value.translation.width / max(size.width, 1)
                let y = start.y + value.translation.height / max(size.height, 1)
                model.moveSticker(sticker.id, to: CGPoint(x: x, y: y))
            }
            .onEnded { (_: DragGesture.Value) in
                dragStart = nil
            }
    }

    private var pinchGesture: some Gesture {
        MagnificationGesture()
            .onChanged { (value: CGFloat) in
                let start = scaleStart ?? sticker.scale
                if scaleStart == nil { scaleStart = start }
                model.scaleSticker(sticker.id, to: start * value)
            }
            .onEnded { (_: CGFloat) in
                scaleStart = nil
            }
    }
}

private struct EditorDrawingCanvas: UIViewRepresentable {
    let canvas: PKCanvasView
    let color: UIColor
    let width: CGFloat
    let erasing: Bool

    func makeUIView(context: Context) -> PKCanvasView {
        canvas.backgroundColor = .clear
        canvas.isOpaque = false
        canvas.drawingPolicy = .anyInput
        canvas.overrideUserInterfaceStyle = .light
        canvas.isScrollEnabled = false
        canvas.showsVerticalScrollIndicator = false
        canvas.showsHorizontalScrollIndicator = false
        configure(canvas)
        return canvas
    }

    func updateUIView(_ uiView: PKCanvasView, context: Context) {
        configure(uiView)
    }

    private func configure(_ view: PKCanvasView) {
        if erasing {
            view.tool = PKEraserTool(.vector)
        } else {
            view.tool = PKInkingTool(.pen, color: color, width: width)
        }
    }
}

// MARK: - Панели инструментов

private struct PhotoFiltersPanel: View {
    @ObservedObject var model: PhotoEditorModel
    @EnvironmentObject private var settings: AppSettings

    var body: some View {
        VStack(spacing: 12) {
            strip
            intensityRow
            Spacer(minLength: 0)
        }
        .padding(.top, 14)
    }

    private var strip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(EditFilter.allCases) { filter in
                    filterButton(filter)
                }
            }
            .padding(.horizontal, 14)
        }
    }

    private func filterButton(_ filter: EditFilter) -> some View {
        let selected = model.look.filter == filter
        return Button {
            withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) { model.selectFilter(filter) }
        } label: {
            VStack(spacing: 6) {
                thumbnail(filter, selected: selected)
                Text(settings.text(filter.title, filter.englishTitle))
                    .font(.system(size: 11, weight: selected ? .bold : .medium))
                    .foregroundStyle(selected ? HonorTheme.accent : HonorTheme.secondary)
                    .lineLimit(1)
            }
            .frame(width: 70)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("editor.filter." + filter.rawValue)
    }

    private func thumbnail(_ filter: EditFilter, selected: Bool) -> some View {
        ZStack {
            RoundedRectangle(cornerRadius: 12, style: .continuous).fill(HonorTheme.raised)
            if let image = model.thumbnails[filter] {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                ProgressView().controlSize(.small)
            }
        }
        .frame(width: 64, height: 64)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous)
            .stroke(HonorTheme.accent, lineWidth: selected ? 2.5 : 0))
        .scaleEffect(selected ? 1.04 : 1)
    }

    @ViewBuilder private var intensityRow: some View {
        if model.look.filter != .original {
            EditorSliderRow(title: settings.text("Сила", "Strength"), value: $model.look.intensity,
                            range: 0...1, onBegin: { model.pushUndo() })
                .transition(.opacity)
        }
    }
}

private struct PhotoAdjustPanel: View {
    @ObservedObject var model: PhotoEditorModel
    @EnvironmentObject private var settings: AppSettings

    var body: some View {
        VStack(spacing: 6) {
            header
            EditorSliderRow(title: settings.text("Яркость", "Brightness"), value: $model.look.brightness,
                            range: -1...1, onBegin: { model.pushUndo() })
            EditorSliderRow(title: settings.text("Контраст", "Contrast"), value: $model.look.contrast,
                            range: -1...1, onBegin: { model.pushUndo() })
            EditorSliderRow(title: settings.text("Насыщенность", "Saturation"), value: $model.look.saturation,
                            range: -1...1, onBegin: { model.pushUndo() })
            EditorSliderRow(title: settings.text("Теплота", "Warmth"), value: $model.look.warmth,
                            range: -1...1, onBegin: { model.pushUndo() })
        }
        .padding(.top, 8)
    }

    private var header: some View {
        HStack {
            Spacer()
            Button(settings.text("Сбросить", "Reset")) { model.resetAdjustments() }
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(model.look.hasAdjustments ? HonorTheme.accent : HonorTheme.secondary)
                .disabled(!model.look.hasAdjustments)
                .accessibilityIdentifier("editor.adjust.reset")
        }
        .padding(.horizontal, 16)
        .frame(height: 22)
    }
}

private struct PhotoCropPanel: View {
    @ObservedObject var model: PhotoEditorModel
    @EnvironmentObject private var settings: AppSettings

    private let aspects: [CropAspect] = [.square, .portrait4x5, .story9x16, .landscape16x9]

    var body: some View {
        VStack(spacing: 18) {
            aspectRow
            transformRow
        }
        .padding(.top, 16)
        .frame(maxHeight: .infinity, alignment: .top)
    }

    private var aspectRow: some View {
        HStack(spacing: 10) {
            ForEach(aspects) { aspect in
                aspectButton(aspect)
            }
        }
        .padding(.horizontal, 16)
    }

    private func aspectButton(_ aspect: CropAspect) -> some View {
        let ratio: CGFloat = aspect.ratio ?? 1
        let boxWidth: CGFloat = ratio >= 1 ? 30 : 30 * ratio
        let boxHeight: CGFloat = ratio >= 1 ? 30 / ratio : 30
        return Button {
            Task { await model.crop(aspect) }
        } label: {
            VStack(spacing: 6) {
                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .stroke(HonorTheme.foreground, lineWidth: 2)
                    .frame(width: boxWidth, height: boxHeight)
                    .frame(width: 34, height: 34)
                Text(aspect.label).font(.system(size: 12, weight: .semibold))
                Text(settings.text(aspect.title, aspect.englishTitle))
                    .font(.system(size: 10))
                    .foregroundStyle(HonorTheme.secondary)
            }
            .foregroundStyle(HonorTheme.foreground)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(HonorTheme.raised))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("editor.crop." + aspect.rawValue)
    }

    private var transformRow: some View {
        HStack(spacing: 10) {
            transformButton("rotate.left", settings.text("Влево", "Left"), id: "rotateLeft") {
                await model.rotate(clockwise: false)
            }
            transformButton("rotate.right", settings.text("Вправо", "Right"), id: "rotateRight") {
                await model.rotate(clockwise: true)
            }
            transformButton("arrow.left.and.right.righttriangle.left.righttriangle.right",
                            settings.text("Зеркало", "Mirror"), id: "flipHorizontal") {
                await model.flip(vertical: false)
            }
            transformButton("arrow.up.and.down.righttriangle.up.righttriangle.down",
                            settings.text("Вверх-вниз", "Flip"), id: "flipVertical") {
                await model.flip(vertical: true)
            }
        }
        .padding(.horizontal, 16)
    }

    private func transformButton(_ symbol: String, _ title: String, id: String,
                                 action: @escaping () async -> Void) -> some View {
        Button {
            Task { await action() }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: symbol).font(.system(size: 14, weight: .semibold))
                Text(title).font(.system(size: 12, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.8)
            }
            .foregroundStyle(HonorTheme.foreground)
            .frame(maxWidth: .infinity)
            .frame(height: 38)
            .background(Capsule().fill(HonorTheme.raised))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("editor.crop." + id)
    }
}

private struct PhotoBackgroundPanel: View {
    @ObservedObject var model: PhotoEditorModel
    @EnvironmentObject private var settings: AppSettings
    @State private var pickerItem: PhotosPickerItem?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            removeButton
            Text(settings.text("Новый фон", "New background"))
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(HonorTheme.secondary)
                .padding(.horizontal, 16)
            options
        }
        .padding(.top, 14)
        .frame(maxHeight: .infinity, alignment: .top)
        .onChange(of: pickerItem) { (item: PhotosPickerItem?) in
            loadPicked(item)
        }
    }

    private var removeButton: some View {
        Button {
            Task { await model.removeBackground() }
        } label: {
            HStack(spacing: 8) {
                Image(systemName: model.hasCutout ? "checkmark.circle.fill" : "wand.and.stars")
                Text(model.hasCutout ? settings.text("Фон убран", "Background removed")
                                     : settings.text("Убрать фон", "Remove background"))
            }
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(Color.white)
            .frame(maxWidth: .infinity)
            .frame(height: 44)
            .background(Capsule().fill(HonorTheme.accent))
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 16)
        .disabled(model.isWorking)
        .accessibilityIdentifier("editor.removeBackground")
    }

    private var options: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 12) {
                transparentOption
                ForEach(0..<EditorPalette.colors.count, id: \.self) { index in
                    colorOption(index)
                }
                blurOption
                ForEach(0..<EditorPalette.gradients.count, id: \.self) { index in
                    gradientOption(index)
                }
                photoOption
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 2)
        }
    }

    private func circle<Content: View>(_ content: Content) -> some View {
        content
            .frame(width: 44, height: 44)
            .clipShape(Circle())
            .overlay(Circle().stroke(HonorTheme.divider, lineWidth: 1))
    }

    private var transparentOption: some View {
        Button {
            Task { await model.applyBackground(nil) }
        } label: {
            circle(EditorCheckerboard())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(settings.text("Прозрачный", "Transparent"))
        .accessibilityIdentifier("editor.background.transparent")
    }

    private func colorOption(_ index: Int) -> some View {
        let color = EditorPalette.colors[index]
        return Button {
            Task { await model.applyBackground(.color(color)) }
        } label: {
            circle(Circle().fill(Color(uiColor: color)))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(settings.text("Цвет", "Color") + " \(index + 1)")
        .accessibilityIdentifier("editor.background.color.\(index)")
    }

    private var blurOption: some View {
        Button {
            Task { await model.applyBackground(.blur(radius: 30)) }
        } label: {
            circle(ZStack {
                Circle().fill(LinearGradient(colors: [HonorTheme.raised, HonorTheme.secondary],
                                             startPoint: .top, endPoint: .bottom))
                Image(systemName: "drop.fill").foregroundStyle(Color.white)
            })
        }
        .buttonStyle(.plain)
        .accessibilityLabel(settings.text("Размытие", "Blur"))
        .accessibilityIdentifier("editor.background.blur")
    }

    private func gradientOption(_ index: Int) -> some View {
        let colors = EditorPalette.gradients[index]
        return Button {
            Task { await model.applyBackground(.gradient(colors)) }
        } label: {
            circle(Circle().fill(LinearGradient(colors: EditorPalette.gradientColors[index],
                                                startPoint: .topLeading, endPoint: .bottomTrailing)))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(settings.text("Градиент", "Gradient") + " \(index + 1)")
        .accessibilityIdentifier("editor.background.gradient.\(index)")
    }

    private var photoOption: some View {
        PhotosPicker(selection: $pickerItem, matching: .images, photoLibrary: .shared()) {
            circle(ZStack {
                Circle().fill(HonorTheme.raised)
                Image(systemName: "photo.on.rectangle").foregroundStyle(HonorTheme.foreground)
            })
        }
        .accessibilityLabel(settings.text("Своё фото", "Your photo"))
        .accessibilityIdentifier("editor.background.photo")
    }

    private func loadPicked(_ item: PhotosPickerItem?) {
        guard let item = item else { return }
        Task {
            let data = try? await item.loadTransferable(type: Data.self)
            pickerItem = nil
            guard let data = data, let picture = UIImage(data: data) else { return }
            await model.applyBackground(.image(picture))
        }
    }
}

private struct PhotoTextPanel: View {
    @ObservedObject var model: PhotoEditorModel
    @EnvironmentObject private var settings: AppSettings

    var body: some View {
        VStack(spacing: 10) {
            inputRow
            EditorColorRow(selection: $model.textColorIndex)
            stylePicker
            EditorSliderRow(title: settings.text("Размер", "Size"), value: $model.textSize, range: 0.03...0.2)
        }
        .padding(.top, 10)
        .frame(maxHeight: .infinity, alignment: .top)
    }

    private var inputRow: some View {
        HStack(spacing: 8) {
            TextField(settings.text("Введите текст", "Type text"), text: $model.textDraft)
                .font(.system(size: 15))
                .foregroundStyle(HonorTheme.foreground)
                .padding(.horizontal, 12)
                .frame(height: 38)
                .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(HonorTheme.raised))
                .accessibilityIdentifier("editor.text.field")
            Button {
                Task { await model.commitText() }
            } label: {
                Image(systemName: "checkmark")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(Color.white)
                    .frame(width: 38, height: 38)
                    .background(Circle().fill(HonorTheme.accent))
            }
            .buttonStyle(.plain)
            .disabled(model.pendingText.isEmpty)
            .opacity(model.pendingText.isEmpty ? 0.4 : 1)
            .accessibilityLabel(settings.text("Добавить на фото", "Add to photo"))
            .accessibilityIdentifier("editor.text.apply")
        }
        .padding(.horizontal, 16)
    }

    private var stylePicker: some View {
        Picker(settings.text("Стиль", "Style"), selection: $model.textStyle) {
            ForEach(TextStyle.allCases) { style in
                Text(settings.text(style.title, style.englishTitle)).tag(style)
            }
        }
        .pickerStyle(.segmented)
        .padding(.horizontal, 16)
        .accessibilityIdentifier("editor.text.style")
    }
}

private struct PhotoStickersPanel: View {
    @ObservedObject var model: PhotoEditorModel
    @EnvironmentObject private var settings: AppSettings

    private let columns: [GridItem] = Array(repeating: GridItem(.flexible(), spacing: 4), count: 8)

    var body: some View {
        VStack(spacing: 6) {
            header
            grid
        }
        .padding(.top, 8)
    }

    private var header: some View {
        HStack {
            Text(settings.text("Двигайте и масштабируйте пальцами, двойное касание — удалить",
                               "Drag and pinch to adjust, double-tap to remove"))
                .font(.system(size: 11))
                .foregroundStyle(HonorTheme.secondary)
                .lineLimit(2)
            Spacer()
            if !model.stickers.isEmpty {
                Button(settings.text("Очистить", "Clear")) { model.clearStickers() }
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(HonorTheme.accent)
            }
        }
        .padding(.horizontal, 16)
    }

    private var grid: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 4) {
                ForEach(EditorPalette.emojis, id: \.self) { emoji in
                    stickerButton(emoji)
                }
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 8)
        }
    }

    private func stickerButton(_ emoji: String) -> some View {
        Button {
            withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) { model.addSticker(emoji) }
        } label: {
            Text(emoji)
                .font(.system(size: 28))
                .frame(maxWidth: .infinity, minHeight: 40)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct PhotoDrawPanel: View {
    @ObservedObject var model: PhotoEditorModel
    @EnvironmentObject private var settings: AppSettings

    var body: some View {
        VStack(spacing: 12) {
            toolRow
            EditorColorRow(selection: $model.drawColorIndex)
            EditorSliderRow(title: settings.text("Толщина", "Width"), value: $model.drawWidth, range: 2...40)
        }
        .padding(.top, 12)
        .frame(maxHeight: .infinity, alignment: .top)
    }

    private var toolRow: some View {
        HStack(spacing: 10) {
            Button {
                model.erasing = false
            } label: {
                EditorChip(title: settings.text("Кисть", "Pen"), symbol: "pencil.tip", selected: !model.erasing)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("editor.draw.pen")
            Button {
                model.erasing = true
            } label: {
                EditorChip(title: settings.text("Ластик", "Eraser"), symbol: "eraser", selected: model.erasing)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("editor.draw.eraser")
            Spacer()
            Button {
                model.clearDrawing()
            } label: {
                EditorChip(title: settings.text("Очистить", "Clear"), symbol: "trash", selected: false)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("editor.draw.clear")
        }
        .padding(.horizontal, 16)
    }
}

// MARK: - Модель видеоредактора

@MainActor
private final class VideoEditorModel: ObservableObject {
    let player = AVPlayer()

    @Published private(set) var duration: Double = 0
    @Published private(set) var trimStart: Double = 0
    @Published private(set) var trimEnd: Double = 0
    @Published private(set) var currentTime: Double = 0
    @Published private(set) var thumbnails: [UIImage] = []
    @Published private(set) var isPlaying = false
    @Published var muted = false { didSet { applyVolumes() } }
    @Published var speed: Double = 1 { didSet { if isPlaying { player.rate = Float(speed) } } }
    @Published var filter: EditFilter = .original { didSet { if filter != oldValue { applyFilterPreview() } } }
    @Published var originalVolume: Double = 1 { didSet { applyVolumes() } }
    @Published var extraVolume: Double = 0.8 { didSet { applyVolumes() } }
    @Published private(set) var extraAudioURL: URL?
    @Published private(set) var extraAudioName = ""
    @Published private(set) var isRecording = false
    @Published private(set) var exporting = false
    @Published private(set) var progress: Double = 0
    @Published var errorMessage: String?

    var english = false
    static let minimumLength: Double = 0.5

    private var sourceURL: URL?
    private var timeObserver: Any?
    private var musicPlayer: AVPlayer?
    private var recorder: AVAudioRecorder?
    private var recordingURL: URL?
    private var loaded = false

    private func t(_ russian: String, _ englishText: String) -> String {
        english ? englishText : russian
    }

    func load(url: URL) async {
        guard !loaded else { return }
        loaded = true
        sourceURL = url
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        player.replaceCurrentItem(with: AVPlayerItem(url: url))
        player.actionAtItemEnd = .pause
        addTimeObserver()
        let length = await VideoEditing.duration(of: url)
        duration = length
        trimStart = 0
        trimEnd = length
        if length <= 0 {
            errorMessage = t("Не удалось открыть видео", "Could not open the video")
            return
        }
        thumbnails = await VideoEditing.thumbnails(for: url, count: 10)
    }

    func teardown() {
        if isRecording { stopRecording() }
        player.pause()
        musicPlayer?.pause()
        if let observer = timeObserver {
            player.removeTimeObserver(observer)
            timeObserver = nil
        }
    }

    // MARK: Воспроизведение

    private func addTimeObserver() {
        guard timeObserver == nil else { return }
        let interval = CMTime(seconds: 0.05, preferredTimescale: 600)
        timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] (time: CMTime) in
            let seconds = time.seconds
            Task { @MainActor in self?.tick(seconds) }
        }
    }

    private func tick(_ seconds: Double) {
        guard seconds.isFinite else { return }
        currentTime = seconds
        let playing = player.rate != 0
        if playing != isPlaying {
            isPlaying = playing
            if !playing { musicPlayer?.pause() }
        }
        if playing && abs(Double(player.rate) - speed) > 0.01 {
            player.rate = Float(speed)
        }
        if isRecording && seconds >= trimEnd - 0.03 {
            stopRecording()
            return
        }
        if playing && seconds >= trimEnd - 0.03 {
            seek(to: trimStart)
            restartMusic()
        }
    }

    func togglePlay() {
        if isPlaying { pause() } else { play() }
    }

    func play() {
        if currentTime < trimStart || currentTime >= trimEnd - 0.05 {
            seek(to: trimStart)
        }
        player.playImmediately(atRate: Float(speed))
        isPlaying = true
        restartMusic()
    }

    func pause() {
        player.pause()
        musicPlayer?.pause()
        isPlaying = false
    }

    func seek(to seconds: Double) {
        let clamped = min(max(seconds, 0), max(duration, 0))
        player.seek(to: CMTime(seconds: clamped, preferredTimescale: 600),
                    toleranceBefore: .zero, toleranceAfter: .zero)
        currentTime = clamped
    }

    private func restartMusic() {
        guard let music = musicPlayer, isPlaying else { return }
        let offset = max(0, (currentTime - trimStart) / max(speed, 0.25))
        music.seek(to: CMTime(seconds: offset, preferredTimescale: 600))
        music.volume = Float(extraVolume)
        music.play()
    }

    private func applyVolumes() {
        player.isMuted = muted || isRecording
        player.volume = Float(originalVolume)
        musicPlayer?.volume = Float(extraVolume)
    }

    private func applyFilterPreview() {
        guard let item = player.currentItem else { return }
        if filter == .original {
            item.videoComposition = nil
        } else {
            item.videoComposition = VideoEditing.filterComposition(for: item.asset, filter: filter)
        }
    }

    // MARK: Обрезка

    func setTrimStart(_ value: Double) {
        let upper = max(0, trimEnd - Self.minimumLength)
        trimStart = min(max(value, 0), upper)
        if isPlaying { pause() }
        seek(to: trimStart)
    }

    func setTrimEnd(_ value: Double) {
        let lower = min(duration, trimStart + Self.minimumLength)
        trimEnd = max(min(value, duration), lower)
        if isPlaying { pause() }
        seek(to: trimEnd)
    }

    var selectionLength: Double {
        max(0, trimEnd - trimStart) / max(speed, 0.25)
    }

    // MARK: Музыка и голос

    private func setExtraAudio(_ url: URL, name: String) {
        musicPlayer?.pause()
        extraAudioURL = url
        extraAudioName = name
        let music = AVPlayer(url: url)
        music.volume = Float(extraVolume)
        musicPlayer = music
    }

    func removeExtraAudio() {
        musicPlayer?.pause()
        musicPlayer = nil
        extraAudioURL = nil
        extraAudioName = ""
    }

    func importAudio(from url: URL) async {
        let copied: URL? = await Task.detached(priority: .userInitiated) { () -> URL? in
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            let ext = url.pathExtension.isEmpty ? "m4a" : url.pathExtension
            let target = FileManager.default.temporaryDirectory
                .appendingPathComponent("audio-" + UUID().uuidString)
                .appendingPathExtension(ext)
            do {
                try FileManager.default.copyItem(at: url, to: target)
                return target
            } catch {
                return nil
            }
        }.value
        guard let local = copied else {
            errorMessage = t("Не удалось открыть аудиофайл", "Could not open the audio file")
            return
        }
        setExtraAudio(local, name: url.lastPathComponent)
    }

    func toggleRecording() async {
        if isRecording {
            stopRecording()
            return
        }
        let granted: Bool = await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
            AVAudioSession.sharedInstance().requestRecordPermission { (allowed: Bool) in
                continuation.resume(returning: allowed)
            }
        }
        guard granted else {
            errorMessage = t("Разрешите доступ к микрофону в Настройках", "Allow microphone access in Settings")
            return
        }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
            try session.setActive(true)
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent("voice-" + UUID().uuidString)
                .appendingPathExtension("m4a")
            let recorderSettings: [String: Any] = [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: 44_100,
                AVNumberOfChannelsKey: 1,
                AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
            ]
            let newRecorder = try AVAudioRecorder(url: url, settings: recorderSettings)
            guard newRecorder.record() else {
                errorMessage = t("Не удалось начать запись", "Could not start recording")
                return
            }
            recorder = newRecorder
            recordingURL = url
            isRecording = true
            musicPlayer?.pause()
            applyVolumes()
            seek(to: trimStart)
            player.playImmediately(atRate: Float(speed))
            isPlaying = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func stopRecording() {
        guard isRecording else { return }
        recorder?.stop()
        recorder = nil
        isRecording = false
        pause()
        applyVolumes()
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        if let url = recordingURL {
            setExtraAudio(url, name: t("Голос", "Voice-over"))
        }
        recordingURL = nil
    }

    // MARK: Экспорт

    func export() async -> URL? {
        guard let source = sourceURL, !exporting, duration > 0 else { return nil }
        if isRecording { stopRecording() }
        pause()
        exporting = true
        progress = 0
        defer { exporting = false }
        let lower = min(trimStart, trimEnd)
        let upper = max(trimStart, trimEnd)
        let trimmed = lower > 0.01 || upper < duration - 0.01
        let trim: ClosedRange<Double>? = trimmed ? lower...upper : nil
        let chosenFilter: EditFilter? = filter == .original ? nil : filter
        let model = self
        do {
            let url = try await VideoEditing.export(source: source, trim: trim, muteOriginal: muted,
                                                    extraAudio: extraAudioURL,
                                                    extraAudioVolume: Float(extraVolume),
                                                    originalVolume: Float(originalVolume),
                                                    speed: speed, filter: chosenFilter,
                                                    progress: { (value: Double) in
                                                        Task { @MainActor in model.progress = value }
                                                    })
            return url
        } catch {
            errorMessage = error.localizedDescription
            return nil
        }
    }
}

// MARK: - Видеоредактор

struct VideoEditorView: View {
    private let videoURL: URL
    private let onSave: (URL) -> Void

    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    @StateObject private var model = VideoEditorModel()
    @State private var importingAudio = false

    private static let speeds: [Double] = [0.5, 1, 1.5, 2]
    private static let filters: [EditFilter] = [.original, .vivid, .warm, .cool, .mono, .noir,
                                                .sepia, .fade, .chrome, .instant, .dramatic]

    init(videoURL: URL, onSave: @escaping (URL) -> Void) {
        self.videoURL = videoURL
        self.onSave = onSave
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            playerArea
            controls
        }
        .background(HonorTheme.background.ignoresSafeArea())
        .overlay { exportOverlay }
        .alert(settings.text("Ошибка", "Error"), isPresented: errorBinding, actions: {
            Button("OK", role: .cancel) {}
        }, message: {
            Text(model.errorMessage ?? "")
        })
        .fileImporter(isPresented: $importingAudio, allowedContentTypes: [.audio]) { (result: Result<URL, Error>) in
            handleImport(result)
        }
        .task {
            model.english = settings.language == .english
            await model.load(url: videoURL)
        }
        .onDisappear { model.teardown() }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("editor.video")
    }

    private var errorBinding: Binding<Bool> {
        Binding(get: { model.errorMessage != nil },
                set: { (shown: Bool) in if !shown { model.errorMessage = nil } })
    }

    @ViewBuilder private var exportOverlay: some View {
        if model.exporting {
            EditorBusyOverlay(message: settings.text("Сохраняю видео…", "Exporting video…"), progress: model.progress)
        }
    }

    private var topBar: some View {
        HStack(spacing: 12) {
            Button(settings.text("Отмена", "Cancel")) { dismiss() }
                .foregroundStyle(HonorTheme.foreground)
                .accessibilityIdentifier("editor.cancel")
            Spacer()
            Text(settings.text("Видео", "Video"))
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(HonorTheme.foreground)
            Spacer()
            saveButton
        }
        .padding(.horizontal, 16)
        .frame(height: 52)
    }

    private var saveButton: some View {
        Button(action: save) {
            Text(settings.text("Готово", "Done"))
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Color.white)
                .padding(.horizontal, 16)
                .frame(height: 34)
                .background(Capsule().fill(HonorTheme.accent))
        }
        .buttonStyle(.plain)
        .disabled(model.duration <= 0 || model.exporting)
        .opacity(model.duration <= 0 ? 0.5 : 1)
        .accessibilityIdentifier("editor.save")
    }

    private var playerArea: some View {
        VideoPlayer(player: model.player)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .padding(.horizontal, 12)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var controls: some View {
        VStack(spacing: 12) {
            playbackRow
            VideoTrimTimeline(model: model)
                .padding(.horizontal, 16)
            soundRow
            filterRow
            audioSection
        }
        .padding(.vertical, 12)
        .background(HonorTheme.surface)
    }

    private var playbackRow: some View {
        HStack(spacing: 12) {
            Button {
                model.togglePlay()
            } label: {
                Image(systemName: model.isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(HonorTheme.foreground)
                    .frame(width: 36, height: 36)
                    .background(Circle().fill(HonorTheme.raised))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(model.isPlaying ? settings.text("Пауза", "Pause") : settings.text("Играть", "Play"))
            .accessibilityIdentifier("editor.play")
            Text(EditorGeometry.timeString(model.currentTime) + " / " + EditorGeometry.timeString(model.duration))
                .font(.system(size: 13, weight: .medium).monospacedDigit())
                .foregroundStyle(HonorTheme.foreground)
            Spacer()
            Text(settings.text("Итог: ", "Result: ") + EditorGeometry.timeString(model.selectionLength))
                .font(.system(size: 12, weight: .medium).monospacedDigit())
                .foregroundStyle(HonorTheme.secondary)
        }
        .padding(.horizontal, 16)
    }

    private var soundRow: some View {
        HStack(spacing: 10) {
            Button {
                model.muted.toggle()
            } label: {
                EditorChip(title: settings.text("Без звука", "Mute"),
                           symbol: model.muted ? "speaker.slash.fill" : "speaker.wave.2.fill",
                           selected: model.muted)
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(model.muted ? .isSelected : [])
            .accessibilityIdentifier("editor.mute")
            speedPicker
        }
        .padding(.horizontal, 16)
    }

    private var speedPicker: some View {
        Picker(settings.text("Скорость", "Speed"), selection: $model.speed) {
            ForEach(Self.speeds, id: \.self) { value in
                Text(speedLabel(value)).tag(value)
            }
        }
        .pickerStyle(.segmented)
        .accessibilityIdentifier("editor.speed")
    }

    private func speedLabel(_ value: Double) -> String {
        if value == 1 { return "1×" }
        return String(format: "%g×", value)
    }

    private var filterRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Self.filters) { item in
                    filterChip(item)
                }
            }
            .padding(.horizontal, 16)
        }
    }

    private func filterChip(_ item: EditFilter) -> some View {
        Button {
            model.filter = item
        } label: {
            EditorChip(title: settings.text(item.title, item.englishTitle), symbol: nil, selected: model.filter == item)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("editor.filter." + item.rawValue)
    }

    private var audioSection: some View {
        VStack(spacing: 8) {
            audioButtons
            extraAudioRow
            EditorSliderRow(title: settings.text("Звук видео", "Video sound"), value: $model.originalVolume, range: 0...1)
                .disabled(model.muted)
                .opacity(model.muted ? 0.4 : 1)
            extraVolumeRow
        }
    }

    private var audioButtons: some View {
        HStack(spacing: 10) {
            Button {
                importingAudio = true
            } label: {
                EditorChip(title: settings.text("Добавить музыку/голос", "Add music/voice"),
                           symbol: "music.note", selected: false)
            }
            .buttonStyle(.plain)
            .disabled(model.isRecording)
            .accessibilityIdentifier("editor.addAudio")
            Button {
                Task { await model.toggleRecording() }
            } label: {
                EditorChip(title: model.isRecording ? settings.text("Стоп", "Stop") : settings.text("Записать голос", "Record voice"),
                           symbol: model.isRecording ? "stop.circle.fill" : "mic.fill",
                           selected: model.isRecording)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("editor.recordVoice")
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
    }

    @ViewBuilder private var extraAudioRow: some View {
        if model.extraAudioURL != nil {
            HStack(spacing: 8) {
                Image(systemName: "waveform").foregroundStyle(HonorTheme.accent)
                Text(model.extraAudioName)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(HonorTheme.foreground)
                    .lineLimit(1)
                Spacer()
                Button {
                    model.removeExtraAudio()
                } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(HonorTheme.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(settings.text("Убрать звук", "Remove audio"))
                .accessibilityIdentifier("editor.removeAudio")
            }
            .padding(.horizontal, 16)
        }
    }

    @ViewBuilder private var extraVolumeRow: some View {
        if model.extraAudioURL != nil {
            EditorSliderRow(title: settings.text("Музыка/голос", "Music/voice"), value: $model.extraVolume, range: 0...1)
        }
    }

    private func handleImport(_ result: Result<URL, Error>) {
        switch result {
        case .success(let url):
            Task { await model.importAudio(from: url) }
        case .failure(let error):
            model.errorMessage = error.localizedDescription
        }
    }

    private func save() {
        Task {
            if let url = await model.export() {
                onSave(url)
                dismiss()
            }
        }
    }
}

// MARK: - Лента обрезки

private struct VideoTrimTimeline: View {
    @ObservedObject var model: VideoEditorModel
    @EnvironmentObject private var settings: AppSettings

    private let handleWidth: CGFloat = 16
    private let height: CGFloat = 56

    var body: some View {
        GeometryReader { (proxy: GeometryProxy) in
            timeline(width: proxy.size.width)
        }
        .frame(height: height)
    }

    private func track(_ width: CGFloat) -> CGFloat {
        max(1, width - handleWidth * 2)
    }

    private func xPosition(_ time: Double, width: CGFloat) -> CGFloat {
        guard model.duration > 0 else { return handleWidth }
        let fraction = CGFloat(min(max(time / model.duration, 0), 1))
        return handleWidth + fraction * track(width)
    }

    private func time(at x: CGFloat, width: CGFloat) -> Double {
        let fraction = Double(min(max((x - handleWidth) / track(width), 0), 1))
        return fraction * model.duration
    }

    private func timeline(width: CGFloat) -> some View {
        ZStack(alignment: .topLeading) {
            strip(width: width)
            dimming(width: width)
            selectionFrame(width: width)
            playhead(width: width)
            startHandle(width: width)
            endHandle(width: width)
        }
        .frame(width: width, height: height, alignment: .topLeading)
        .coordinateSpace(name: "trimTimeline")
    }

    private func strip(width: CGFloat) -> some View {
        let trackWidth = track(width)
        let count = max(1, model.thumbnails.count)
        let cell: CGFloat = trackWidth / CGFloat(count)
        return HStack(spacing: 0) {
            ForEach(Array(model.thumbnails.enumerated()), id: \.offset) { pair in
                Image(uiImage: pair.element)
                    .resizable()
                    .scaledToFill()
                    .frame(width: cell, height: height - 8)
                    .clipped()
            }
        }
        .frame(width: trackWidth, height: height - 8, alignment: .leading)
        .background(HonorTheme.raised)
        .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
        .offset(x: handleWidth, y: 4)
        .gesture(scrubGesture(width: width))
    }

    private func scrubGesture(width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .named("trimTimeline"))
            .onChanged { (value: DragGesture.Value) in
                let target = time(at: value.location.x, width: width)
                if model.isPlaying { model.pause() }
                model.seek(to: min(max(target, model.trimStart), model.trimEnd))
            }
    }

    private func dimming(width: CGFloat) -> some View {
        let startX = xPosition(model.trimStart, width: width)
        let endX = xPosition(model.trimEnd, width: width)
        let rightWidth: CGFloat = max(0, handleWidth + track(width) - endX)
        return ZStack(alignment: .topLeading) {
            Rectangle()
                .fill(Color.black.opacity(0.55))
                .frame(width: max(0, startX - handleWidth), height: height - 8)
                .offset(x: handleWidth, y: 4)
            Rectangle()
                .fill(Color.black.opacity(0.55))
                .frame(width: rightWidth, height: height - 8)
                .offset(x: endX, y: 4)
        }
        .allowsHitTesting(false)
    }

    private func selectionFrame(width: CGFloat) -> some View {
        let startX = xPosition(model.trimStart, width: width)
        let endX = xPosition(model.trimEnd, width: width)
        return RoundedRectangle(cornerRadius: 6, style: .continuous)
            .stroke(Color.yellow, lineWidth: 3)
            .frame(width: max(0, endX - startX) + handleWidth * 2, height: height)
            .offset(x: startX - handleWidth)
            .allowsHitTesting(false)
    }

    private func playhead(width: CGFloat) -> some View {
        RoundedRectangle(cornerRadius: 1)
            .fill(Color.white)
            .frame(width: 2, height: height - 4)
            .shadow(color: Color.black.opacity(0.4), radius: 2)
            .offset(x: xPosition(model.currentTime, width: width) - 1, y: 2)
            .allowsHitTesting(false)
    }

    private func handleShape(symbol: String) -> some View {
        RoundedRectangle(cornerRadius: 5, style: .continuous)
            .fill(Color.yellow)
            .frame(width: handleWidth, height: height)
            .overlay(Image(systemName: symbol).font(.system(size: 10, weight: .heavy)).foregroundStyle(Color.black))
            .contentShape(Rectangle().inset(by: -10))
    }

    private func startHandle(width: CGFloat) -> some View {
        handleShape(symbol: "chevron.compact.left")
            .offset(x: xPosition(model.trimStart, width: width) - handleWidth)
            .gesture(DragGesture(minimumDistance: 0, coordinateSpace: .named("trimTimeline"))
                .onChanged { (value: DragGesture.Value) in
                    model.setTrimStart(time(at: value.location.x + handleWidth / 2, width: width))
                })
            .accessibilityElement()
            .accessibilityLabel(settings.text("Начало", "Start"))
            .accessibilityValue(EditorGeometry.timeString(model.trimStart))
            .accessibilityAdjustableAction { (direction: AccessibilityAdjustmentDirection) in
                let step: Double = direction == .increment ? 0.1 : -0.1
                model.setTrimStart(model.trimStart + step)
            }
            .accessibilityIdentifier("editor.trim.start")
    }

    private func endHandle(width: CGFloat) -> some View {
        handleShape(symbol: "chevron.compact.right")
            .offset(x: xPosition(model.trimEnd, width: width))
            .gesture(DragGesture(minimumDistance: 0, coordinateSpace: .named("trimTimeline"))
                .onChanged { (value: DragGesture.Value) in
                    model.setTrimEnd(time(at: value.location.x - handleWidth / 2, width: width))
                })
            .accessibilityElement()
            .accessibilityLabel(settings.text("Конец", "End"))
            .accessibilityValue(EditorGeometry.timeString(model.trimEnd))
            .accessibilityAdjustableAction { (direction: AccessibilityAdjustmentDirection) in
                let step: Double = direction == .increment ? 0.1 : -0.1
                model.setTrimEnd(model.trimEnd + step)
            }
            .accessibilityIdentifier("editor.trim.end")
    }
}
