import Foundation
import UIKit
import CoreImage
import ImageIO
import Vision

/// Понятная пользователю ошибка редактора фото и видео (русский + английский).
struct MediaEditingError: LocalizedError, Equatable {
    let russian: String
    let english: String

    var errorDescription: String? { russian + " / " + english }

    static let noSubject = MediaEditingError(russian: "Не удалось найти объект на фото",
                                             english: "No subject found")
    static let invalidImage = MediaEditingError(russian: "Не удалось прочитать изображение",
                                                english: "Could not read the image")
    static let saveFailed = MediaEditingError(russian: "Не удалось сохранить изображение",
                                              english: "Could not save the image")
}

// MARK: - Параметры правок

/// Готовые фильтры редактора.
enum EditFilter: String, CaseIterable, Identifiable, Codable {
    case original, vivid, warm, cool, mono, noir, sepia, fade, chrome, instant, dramatic, vignette, sharpen, blur

    var id: String { rawValue }

    var title: String {
        switch self {
        case .original: return "Оригинал"
        case .vivid: return "Яркий"
        case .warm: return "Тёплый"
        case .cool: return "Холодный"
        case .mono: return "Моно"
        case .noir: return "Нуар"
        case .sepia: return "Сепия"
        case .fade: return "Выцветший"
        case .chrome: return "Хром"
        case .instant: return "Полароид"
        case .dramatic: return "Драма"
        case .vignette: return "Виньетка"
        case .sharpen: return "Резкость"
        case .blur: return "Размытие"
        }
    }

    var englishTitle: String {
        switch self {
        case .original: return "Original"
        case .vivid: return "Vivid"
        case .warm: return "Warm"
        case .cool: return "Cool"
        case .mono: return "Mono"
        case .noir: return "Noir"
        case .sepia: return "Sepia"
        case .fade: return "Fade"
        case .chrome: return "Chrome"
        case .instant: return "Instant"
        case .dramatic: return "Dramatic"
        case .vignette: return "Vignette"
        case .sharpen: return "Sharpen"
        case .blur: return "Blur"
        }
    }

    /// Мягкий разбор названия фильтра (английский, русский, синонимы).
    static func named(_ raw: String?) -> EditFilter? {
        guard let raw = raw else { return nil }
        let value = ImageEditing.normalizedWord(raw)
        if value.isEmpty { return nil }
        if let exact = EditFilter(rawValue: value) { return exact }
        for filter in EditFilter.allCases {
            if ImageEditing.normalizedWord(filter.title) == value || filter.englishTitle.lowercased() == value {
                return filter
            }
        }
        let aliases: [(String, EditFilter)] = [
            ("none", .original), ("normal", .original), ("без", .original), ("ориг", .original),
            ("bw", .mono), ("b&w", .mono), ("black", .mono), ("gray", .mono), ("grey", .mono),
            ("чб", .mono), ("черно", .mono), ("моно", .mono), ("сер", .mono),
            ("нуар", .noir), ("vivid", .vivid), ("ярк", .vivid), ("насыщ", .vivid), ("сочн", .vivid),
            ("тепл", .warm), ("warm", .warm), ("холод", .cool), ("cold", .cool), ("cool", .cool),
            ("сепи", .sepia), ("vintage", .instant), ("винтаж", .instant), ("полароид", .instant),
            ("моментал", .instant), ("ретро", .instant), ("fade", .fade), ("выцв", .fade), ("блекл", .fade),
            ("хром", .chrome), ("драм", .dramatic), ("drama", .dramatic), ("виньет", .vignette),
            ("sharp", .sharpen), ("резк", .sharpen), ("чётк", .sharpen), ("четк", .sharpen),
            ("blur", .blur), ("размыт", .blur), ("размыв", .blur)
        ]
        for (prefix, filter) in aliases where value.hasPrefix(prefix) {
            return filter
        }
        return nil
    }
}

/// Пропорции обрезки (обрезка по центру).
enum CropAspect: String, CaseIterable, Identifiable {
    case original, square, portrait4x5, story9x16, landscape16x9

    var id: String { rawValue }

    /// Ширина / высота; nil — без изменений.
    var ratio: CGFloat? {
        switch self {
        case .original: return nil
        case .square: return 1
        case .portrait4x5: return 4.0 / 5.0
        case .story9x16: return 9.0 / 16.0
        case .landscape16x9: return 16.0 / 9.0
        }
    }

    var label: String {
        switch self {
        case .original: return "—"
        case .square: return "1:1"
        case .portrait4x5: return "4:5"
        case .story9x16: return "9:16"
        case .landscape16x9: return "16:9"
        }
    }

    var title: String {
        switch self {
        case .original: return "Исходный"
        case .square: return "Квадрат"
        case .portrait4x5: return "Портрет"
        case .story9x16: return "Сторис"
        case .landscape16x9: return "Широкий"
        }
    }

    var englishTitle: String {
        switch self {
        case .original: return "Original"
        case .square: return "Square"
        case .portrait4x5: return "Portrait"
        case .story9x16: return "Story"
        case .landscape16x9: return "Wide"
        }
    }

    static func named(_ raw: String?) -> CropAspect? {
        guard let raw = raw else { return nil }
        let value = ImageEditing.normalizedWord(raw).replacingOccurrences(of: "x", with: ":")
            .replacingOccurrences(of: "/", with: ":").replacingOccurrences(of: "×", with: ":")
        if let exact = CropAspect(rawValue: raw.trimmingCharacters(in: .whitespaces)) { return exact }
        switch value {
        case "1:1", "square", "квадрат", "квадратный": return .square
        case "4:5", "portrait", "портрет", "портретный", "portrait4:5": return .portrait4x5
        case "9:16", "story", "stories", "сторис", "история", "vertical", "вертикальный", "story9:16": return .story9x16
        case "16:9", "landscape", "wide", "широкий", "пейзаж", "горизонтальный", "landscape16:9": return .landscape16x9
        case "original", "исходный", "оригинал", "none": return .original
        default: return nil
        }
    }
}

/// Оформление надписи.
enum TextStyle: String, CaseIterable, Identifiable {
    case plain, outline, banner, neon

    var id: String { rawValue }

    var title: String {
        switch self {
        case .plain: return "Обычный"
        case .outline: return "Контур"
        case .banner: return "Плашка"
        case .neon: return "Неон"
        }
    }

    var englishTitle: String {
        switch self {
        case .plain: return "Plain"
        case .outline: return "Outline"
        case .banner: return "Banner"
        case .neon: return "Neon"
        }
    }

    static func named(_ raw: String?) -> TextStyle? {
        guard let raw = raw else { return nil }
        let value = ImageEditing.normalizedWord(raw)
        if let exact = TextStyle(rawValue: value) { return exact }
        if value.hasPrefix("обыч") || value.hasPrefix("прост") { return .plain }
        if value.hasPrefix("контур") || value.hasPrefix("обвод") || value == "stroke" { return .outline }
        if value.hasPrefix("плаш") || value.hasPrefix("фон") || value == "label" || value == "box" { return .banner }
        if value.hasPrefix("неон") || value == "glow" || value.hasPrefix("свеч") { return .neon }
        return nil
    }
}

/// Новый фон под вырезанным объектом.
enum EditBackground {
    case color(UIColor)
    /// Размытие исходного фото; радиус задан для снимка ~1000 px и масштабируется.
    case blur(radius: Double)
    case image(UIImage)
    case gradient([UIColor])
}

// MARK: - Движок

enum ImageEditing {
    /// Общий контекст Core Image (потокобезопасен).
    static let context = CIContext(options: [.useSoftwareRenderer: false])

    // MARK: Загрузка и сохранение

    /// Загрузить фото с учётом ориентации, ограничив длинную сторону.
    static func load(_ url: URL, maxPixel: CGFloat = 4096) -> UIImage? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: max(16, maxPixel)
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return UIImage(cgImage: cgImage, scale: 1, orientation: .up)
    }

    /// Папка вложений приложения (та же, что у AttachmentService).
    static func attachmentsDirectory() throws -> URL {
        let directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                    appropriateFor: nil, create: true)
            .appendingPathComponent("HonorPKAgent/Attachments", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    /// Сохранить результат во вложения. PNG — для прозрачности, иначе JPEG 0.9.
    static func save(_ image: UIImage, preferPNG: Bool) throws -> URL {
        let data: Data?
        if preferPNG {
            data = upright(image).pngData()
        } else {
            let flat = hasAlphaChannel(image) ? flattened(image, background: .white) : image
            data = flat.jpegData(compressionQuality: 0.9)
        }
        guard let encoded = data else { throw MediaEditingError.saveFailed }
        let url = try attachmentsDirectory()
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension(preferPNG ? "png" : "jpg")
        try encoded.write(to: url, options: .atomic)
        return url
    }

    // MARK: Вспомогательное

    static func normalizedWord(_ raw: String) -> String {
        raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .replacingOccurrences(of: "ё", with: "е")
    }

    static func rendererFormat(scale: CGFloat, opaque: Bool = false) -> UIGraphicsImageRendererFormat {
        let format = UIGraphicsImageRendererFormat.preferred()
        format.scale = scale
        format.opaque = opaque
        format.preferredRange = .standard
        return format
    }

    /// Картинка без EXIF-поворота и с CGImage внутри.
    static func upright(_ image: UIImage) -> UIImage {
        if image.imageOrientation == .up && image.cgImage != nil { return image }
        let format = rendererFormat(scale: image.scale)
        let renderer = UIGraphicsImageRenderer(size: image.size, format: format)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: image.size))
        }
    }

    static func pixelSize(_ image: UIImage) -> CGSize {
        CGSize(width: image.size.width * image.scale, height: image.size.height * image.scale)
    }

    static func hasAlphaChannel(_ image: UIImage) -> Bool {
        guard let info = image.cgImage?.alphaInfo else { return false }
        switch info {
        case .none, .noneSkipFirst, .noneSkipLast: return false
        default: return true
        }
    }

    /// Есть ли в картинке действительно прозрачные пиксели (проверка по уменьшенной копии).
    static func hasTransparency(_ image: UIImage) -> Bool {
        guard hasAlphaChannel(image), let cgImage = image.cgImage else { return false }
        let side = 48
        let bytesPerRow = side * 4
        var pixels = [UInt8](repeating: 255, count: side * side * 4)
        let space = CGColorSpaceCreateDeviceRGB()
        let drawn: Bool = pixels.withUnsafeMutableBytes { (buffer: UnsafeMutableRawBufferPointer) -> Bool in
            guard let context = CGContext(data: buffer.baseAddress, width: side, height: side, bitsPerComponent: 8,
                                          bytesPerRow: bytesPerRow, space: space,
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.clear(CGRect(x: 0, y: 0, width: side, height: side))
            context.draw(cgImage, in: CGRect(x: 0, y: 0, width: side, height: side))
            return true
        }
        guard drawn else { return false }
        var index = 3
        while index < pixels.count {
            if pixels[index] < 250 { return true }
            index += 4
        }
        return false
    }

    static func flattened(_ image: UIImage, background: UIColor) -> UIImage {
        let format = rendererFormat(scale: image.scale, opaque: true)
        let renderer = UIGraphicsImageRenderer(size: image.size, format: format)
        return renderer.image { context in
            background.setFill()
            context.fill(CGRect(origin: .zero, size: image.size))
            image.draw(in: CGRect(origin: .zero, size: image.size))
        }
    }

    private static func ciImage(from image: UIImage) -> CIImage? {
        if let cgImage = upright(image).cgImage { return CIImage(cgImage: cgImage) }
        return image.ciImage
    }

    private static func render(_ output: CIImage, extent: CGRect, scale: CGFloat) -> UIImage? {
        guard let cgImage = context.createCGImage(output, from: extent) else { return nil }
        return UIImage(cgImage: cgImage, scale: scale, orientation: .up)
    }

    private static func named(_ name: String, _ input: CIImage, _ parameters: [String: Any] = [:]) -> CIImage {
        guard let filter = CIFilter(name: name) else { return input }
        filter.setValue(input, forKey: kCIInputImageKey)
        for (key, value) in parameters {
            filter.setValue(value, forKey: key)
        }
        return filter.outputImage ?? input
    }

    private static func colorControls(_ input: CIImage, brightness: Double, contrast: Double, saturation: Double) -> CIImage {
        named("CIColorControls", input, [
            kCIInputBrightnessKey: brightness,
            kCIInputContrastKey: contrast,
            kCIInputSaturationKey: saturation
        ])
    }

    /// amount > 0 — теплее, < 0 — холоднее (-1...1).
    private static func temperature(_ input: CIImage, amount: Double) -> CIImage {
        let clamped = min(max(amount, -1), 1)
        let neutral = CIVector(x: CGFloat(6500 + 3000 * clamped), y: 0)
        let target = CIVector(x: 6500, y: 0)
        return named("CITemperatureAndTint", input, ["inputNeutral": neutral, "inputTargetNeutral": target])
    }

    private static func vignette(_ input: CIImage, extent: CGRect, intensity: Double) -> CIImage {
        let center = CIVector(x: extent.midX, y: extent.midY)
        let radius = Double(max(extent.width, extent.height)) * 0.45
        return named("CIVignetteEffect", input, [
            kCIInputCenterKey: center,
            kCIInputRadiusKey: radius,
            kCIInputIntensityKey: intensity,
            "inputFalloff": 0.6
        ])
    }

    private static func gaussianBlur(_ input: CIImage, radius: Double) -> CIImage {
        let extent = input.extent
        let blurred = named("CIGaussianBlur", input.clampedToExtent(), [kCIInputRadiusKey: radius])
        return blurred.cropped(to: extent)
    }

    // MARK: Фильтры и настройки

    /// Фильтр на уровне CIImage — общий для фото и видео.
    static func filteredCIImage(_ input: CIImage, filter: EditFilter, intensity: Double = 1) -> CIImage {
        let extent = input.extent
        let output: CIImage
        switch filter {
        case .original:
            return input
        case .vivid:
            output = colorControls(input, brightness: 0.02, contrast: 1.08, saturation: 1.45)
        case .warm:
            output = colorControls(temperature(input, amount: 0.6), brightness: 0.01, contrast: 1.03, saturation: 1.08)
        case .cool:
            output = colorControls(temperature(input, amount: -0.6), brightness: 0, contrast: 1.03, saturation: 1.0)
        case .mono:
            output = named("CIPhotoEffectMono", input)
        case .noir:
            output = named("CIPhotoEffectNoir", input)
        case .sepia:
            output = named("CISepiaTone", input, [kCIInputIntensityKey: 0.9])
        case .fade:
            output = named("CIPhotoEffectFade", input)
        case .chrome:
            output = named("CIPhotoEffectChrome", input)
        case .instant:
            output = named("CIPhotoEffectInstant", input)
        case .dramatic:
            let contrasted = colorControls(input, brightness: -0.03, contrast: 1.32, saturation: 1.12)
            output = vignette(contrasted, extent: extent, intensity: 0.7)
        case .vignette:
            output = vignette(input, extent: extent, intensity: 1.0)
        case .sharpen:
            output = named("CISharpenLuminance", input, [kCIInputSharpnessKey: 0.9])
        case .blur:
            let radius = Double(max(extent.width, extent.height)) * 0.012
            output = gaussianBlur(input, radius: max(2, radius))
        }
        let cropped = output.cropped(to: extent)
        let amount = min(max(intensity, 0), 1)
        if amount >= 0.999 { return cropped }
        guard let dissolve = CIFilter(name: "CIDissolveTransition") else { return cropped }
        dissolve.setValue(input, forKey: kCIInputImageKey)
        dissolve.setValue(cropped, forKey: kCIInputTargetImageKey)
        dissolve.setValue(amount, forKey: kCIInputTimeKey)
        return dissolve.outputImage?.cropped(to: extent) ?? cropped
    }

    static func applyFilter(_ image: UIImage, _ filter: EditFilter, intensity: Double = 1) -> UIImage {
        if filter == .original || intensity <= 0.001 { return image }
        guard let input = ciImage(from: image) else { return image }
        let output = filteredCIImage(input, filter: filter, intensity: intensity)
        return render(output, extent: input.extent, scale: image.scale) ?? image
    }

    /// Все параметры в диапазоне -1...1, 0 — без изменений.
    static func adjustedCIImage(_ input: CIImage, brightness: Double, contrast: Double,
                                saturation: Double, warmth: Double) -> CIImage {
        var output = input
        if abs(warmth) > 0.001 {
            output = temperature(output, amount: warmth)
        }
        if abs(brightness) > 0.001 || abs(contrast) > 0.001 || abs(saturation) > 0.001 {
            let b = min(max(brightness, -1), 1) * 0.3
            let c = 1 + min(max(contrast, -1), 1) * 0.4
            let s = 1 + min(max(saturation, -1), 1)
            output = colorControls(output, brightness: b, contrast: c, saturation: s)
        }
        return output.cropped(to: input.extent)
    }

    static func adjust(_ image: UIImage, brightness: Double, contrast: Double, saturation: Double, warmth: Double) -> UIImage {
        let values: [Double] = [brightness, contrast, saturation, warmth]
        if values.allSatisfy({ (value: Double) -> Bool in abs(value) < 0.001 }) { return image }
        guard let input = ciImage(from: image) else { return image }
        let output = adjustedCIImage(input, brightness: brightness, contrast: contrast,
                                     saturation: saturation, warmth: warmth)
        return render(output, extent: input.extent, scale: image.scale) ?? image
    }

    /// Размытие всей картинки; радиус задан для снимка ~1000 px.
    static func blurred(_ image: UIImage, radius: Double) -> UIImage {
        guard let input = ciImage(from: image) else { return image }
        let longest = Double(max(input.extent.width, input.extent.height))
        let effective = max(1, radius * longest / 1000)
        let output = gaussianBlur(input, radius: effective)
        return render(output, extent: input.extent, scale: image.scale) ?? image
    }

    // MARK: Геометрия

    static func rotate(_ image: UIImage, clockwise: Bool) -> UIImage {
        let size = image.size
        let target = CGSize(width: size.height, height: size.width)
        let format = rendererFormat(scale: image.scale)
        let renderer = UIGraphicsImageRenderer(size: target, format: format)
        return renderer.image { context in
            let cg = context.cgContext
            cg.translateBy(x: target.width / 2, y: target.height / 2)
            cg.rotate(by: clockwise ? CGFloat.pi / 2 : -CGFloat.pi / 2)
            image.draw(in: CGRect(x: -size.width / 2, y: -size.height / 2, width: size.width, height: size.height))
        }
    }

    static func flipHorizontally(_ image: UIImage) -> UIImage {
        let size = image.size
        let renderer = UIGraphicsImageRenderer(size: size, format: rendererFormat(scale: image.scale))
        return renderer.image { context in
            context.cgContext.translateBy(x: size.width, y: 0)
            context.cgContext.scaleBy(x: -1, y: 1)
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }

    static func flipVertically(_ image: UIImage) -> UIImage {
        let size = image.size
        let renderer = UIGraphicsImageRenderer(size: size, format: rendererFormat(scale: image.scale))
        return renderer.image { context in
            context.cgContext.translateBy(x: 0, y: size.height)
            context.cgContext.scaleBy(x: 1, y: -1)
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }

    /// Нормализованный прямоугольник обрезки по центру для нужных пропорций.
    static func centerCropRect(for size: CGSize, aspect: CropAspect) -> CGRect {
        guard let ratio = aspect.ratio, size.width > 0, size.height > 0 else {
            return CGRect(x: 0, y: 0, width: 1, height: 1)
        }
        let imageRatio = size.width / size.height
        if imageRatio > ratio {
            let width = ratio / imageRatio
            return CGRect(x: (1 - width) / 2, y: 0, width: width, height: 1)
        }
        let height = imageRatio / ratio
        return CGRect(x: 0, y: (1 - height) / 2, width: 1, height: height)
    }

    static func crop(_ image: UIImage, aspect: CropAspect) -> UIImage {
        if aspect == .original { return image }
        return crop(image, to: centerCropRect(for: image.size, aspect: aspect))
    }

    /// Обрезка по нормализованному прямоугольнику (0...1, начало — левый верхний угол).
    static func crop(_ image: UIImage, to normalizedRect: CGRect) -> UIImage {
        let source = upright(image)
        guard let cgImage = source.cgImage else { return image }
        let width = CGFloat(cgImage.width)
        let height = CGFloat(cgImage.height)
        let unit = CGRect(x: 0, y: 0, width: 1, height: 1)
        let rect = normalizedRect.standardized.intersection(unit)
        guard !rect.isNull, rect.width > 0.001, rect.height > 0.001 else { return image }
        let pixelRect = CGRect(x: (rect.minX * width).rounded(), y: (rect.minY * height).rounded(),
                               width: max(1, (rect.width * width).rounded()),
                               height: max(1, (rect.height * height).rounded()))
        guard let cropped = cgImage.cropping(to: pixelRect) else { return image }
        return UIImage(cgImage: cropped, scale: source.scale, orientation: .up)
    }

    /// Уменьшить так, чтобы длинная сторона (в пикселях) не превышала maxSide.
    static func resize(_ image: UIImage, maxSide: CGFloat) -> UIImage {
        let pixels = pixelSize(image)
        let longest = max(pixels.width, pixels.height)
        guard maxSide > 0, longest > maxSide else { return image }
        let factor = maxSide / longest
        let target = CGSize(width: max(1, (pixels.width * factor).rounded()),
                            height: max(1, (pixels.height * factor).rounded()))
        let renderer = UIGraphicsImageRenderer(size: target, format: rendererFormat(scale: 1))
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
    }

    // MARK: Надписи, стикеры, слои

    static func contrastingColor(for color: UIColor) -> UIColor {
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        guard color.getRed(&red, green: &green, blue: &blue, alpha: &alpha) else { return .black }
        let luminance: CGFloat = 0.299 * red + 0.587 * green + 0.114 * blue
        return luminance > 0.6 ? .black : .white
    }

    /// Светлая сердцевина неоновой надписи.
    static func neonCore(for color: UIColor) -> UIColor {
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        guard color.getRed(&red, green: &green, blue: &blue, alpha: &alpha) else { return .white }
        let mix: CGFloat = 0.6
        return UIColor(red: red + (1 - red) * mix, green: green + (1 - green) * mix,
                       blue: blue + (1 - blue) * mix, alpha: 1)
    }

    /// Надпись. position — центр текста (0...1), fontSize — доля ширины фото (например 0.08).
    static func addText(_ image: UIImage, text: String, position: CGPoint, color: UIColor,
                        fontSize: CGFloat, style: TextStyle) -> UIImage {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return image }
        let size = image.size
        let pointSize: CGFloat = max(6, min(max(fontSize, 0.01), 0.5) * size.width)
        let font = UIFont.systemFont(ofSize: pointSize, weight: .heavy)
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = .center
        var attributes: [NSAttributedString.Key: Any] = [.font: font, .paragraphStyle: paragraph, .foregroundColor: color]
        switch style {
        case .plain:
            let shadow = NSShadow()
            shadow.shadowColor = UIColor.black.withAlphaComponent(0.4)
            shadow.shadowOffset = CGSize(width: 0, height: pointSize * 0.04)
            shadow.shadowBlurRadius = pointSize * 0.12
            attributes[.shadow] = shadow
        case .outline:
            attributes[.strokeColor] = contrastingColor(for: color)
            attributes[.strokeWidth] = -6.0
        case .banner:
            attributes[.foregroundColor] = contrastingColor(for: color)
        case .neon:
            attributes[.foregroundColor] = neonCore(for: color)
            let glow = NSShadow()
            glow.shadowColor = color
            glow.shadowOffset = .zero
            glow.shadowBlurRadius = pointSize * 0.5
            attributes[.shadow] = glow
        }
        let string = trimmed as NSString
        let options: NSStringDrawingOptions = [.usesLineFragmentOrigin, .usesFontLeading]
        let bounding = string.boundingRect(with: CGSize(width: size.width * 0.92, height: CGFloat.greatestFiniteMagnitude),
                                           options: options, attributes: attributes, context: nil)
        let textSize = CGSize(width: ceil(bounding.width), height: ceil(bounding.height))
        let center = CGPoint(x: min(max(position.x, 0), 1) * size.width,
                             y: min(max(position.y, 0), 1) * size.height)
        let textRect = CGRect(x: center.x - textSize.width / 2, y: center.y - textSize.height / 2,
                              width: textSize.width, height: textSize.height)
        let renderer = UIGraphicsImageRenderer(size: size, format: rendererFormat(scale: image.scale))
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
            if style == .banner {
                let plate = textRect.insetBy(dx: -pointSize * 0.45, dy: -pointSize * 0.2)
                color.setFill()
                UIBezierPath(roundedRect: plate, cornerRadius: pointSize * 0.3).fill()
            }
            string.draw(with: textRect, options: options, attributes: attributes, context: nil)
            if style == .neon {
                string.draw(with: textRect, options: options, attributes: attributes, context: nil)
            }
        }
    }

    /// Эмодзи-стикер. scale 1 — примерно 18% ширины фото.
    static func addSticker(_ image: UIImage, emoji: String, position: CGPoint, scale: CGFloat) -> UIImage {
        let trimmed = emoji.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return image }
        let size = image.size
        let pointSize: CGFloat = max(8, size.width * 0.18 * min(max(scale, 0.1), 8))
        let attributes: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: pointSize)]
        let string = trimmed as NSString
        let textSize = string.size(withAttributes: attributes)
        let center = CGPoint(x: min(max(position.x, 0), 1) * size.width,
                             y: min(max(position.y, 0), 1) * size.height)
        let origin = CGPoint(x: center.x - textSize.width / 2, y: center.y - textSize.height / 2)
        let renderer = UIGraphicsImageRenderer(size: size, format: rendererFormat(scale: image.scale))
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
            string.draw(at: origin, withAttributes: attributes)
        }
    }

    /// Наложить слой (рисунок) на всю площадь фото.
    static func overlay(_ image: UIImage, with layer: UIImage) -> UIImage {
        let size = image.size
        let renderer = UIGraphicsImageRenderer(size: size, format: rendererFormat(scale: image.scale))
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
            layer.draw(in: CGRect(origin: .zero, size: size))
        }
    }

    // MARK: Фон

    /// Вырезать объект (фон становится прозрачным).
    static func removeBackground(_ image: UIImage) async throws -> UIImage {
        let task = Task.detached(priority: .userInitiated) { () -> UIImage in
            try removeBackgroundSync(image)
        }
        return try await task.value
    }

    private static func removeBackgroundSync(_ image: UIImage) throws -> UIImage {
        let source = upright(image)
        guard let cgImage = source.cgImage else { throw MediaEditingError.invalidImage }
        if #available(iOS 17.0, *) {
            do {
                if let result = try foregroundCutout(cgImage: cgImage, scale: source.scale) {
                    return result
                }
                throw MediaEditingError.noSubject
            } catch let error as MediaEditingError {
                throw error
            } catch {
                // Запрос недоступен (например, в симуляторе) — пробуем выделение людей.
            }
        }
        return try personCutout(cgImage: cgImage, scale: source.scale)
    }

    @available(iOS 17.0, *)
    private static func foregroundCutout(cgImage: CGImage, scale: CGFloat) throws -> UIImage? {
        let request = VNGenerateForegroundInstanceMaskRequest()
        let handler = VNImageRequestHandler(cgImage: cgImage, orientation: .up, options: [:])
        try handler.perform([request])
        guard let observation = request.results?.first else { return nil }
        let instances = observation.allInstances
        if instances.isEmpty { return nil }
        let buffer = try observation.generateMaskedImage(ofInstances: instances, from: handler,
                                                         croppedToInstancesExtent: false)
        let masked = CIImage(cvPixelBuffer: buffer)
        return render(masked, extent: masked.extent, scale: scale)
    }

    private static func personCutout(cgImage: CGImage, scale: CGFloat) throws -> UIImage {
        let request = VNGeneratePersonSegmentationRequest()
        request.qualityLevel = .accurate
        request.outputPixelFormat = kCVPixelFormatType_OneComponent8
        let handler = VNImageRequestHandler(cgImage: cgImage, orientation: .up, options: [:])
        do {
            try handler.perform([request])
        } catch {
            throw MediaEditingError(russian: "Не удалось найти объект на фото (удаление фона недоступно на этом устройстве)",
                                    english: "No subject found (background removal is unavailable on this device)")
        }
        guard let mask = request.results?.first?.pixelBuffer, maskCoverage(mask) > 0.004 else {
            throw MediaEditingError.noSubject
        }
        let original = CIImage(cgImage: cgImage)
        let maskImage = CIImage(cvPixelBuffer: mask)
        let scaleX = original.extent.width / max(1, maskImage.extent.width)
        let scaleY = original.extent.height / max(1, maskImage.extent.height)
        let scaledMask = maskImage.transformed(by: CGAffineTransform(scaleX: scaleX, y: scaleY))
        let clear = CIImage(color: CIColor(red: 0, green: 0, blue: 0, alpha: 0)).cropped(to: original.extent)
        guard let blend = CIFilter(name: "CIBlendWithMask") else { throw MediaEditingError.noSubject }
        blend.setValue(original, forKey: kCIInputImageKey)
        blend.setValue(clear, forKey: kCIInputBackgroundImageKey)
        blend.setValue(scaledMask, forKey: kCIInputMaskImageKey)
        guard let output = blend.outputImage?.cropped(to: original.extent),
              let result = render(output, extent: original.extent, scale: scale) else {
            throw MediaEditingError.noSubject
        }
        return result
    }

    /// Доля пикселей маски, занятых объектом.
    private static func maskCoverage(_ buffer: CVPixelBuffer) -> Double {
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return 0 }
        let width = CVPixelBufferGetWidth(buffer)
        let height = CVPixelBufferGetHeight(buffer)
        let rowBytes = CVPixelBufferGetBytesPerRow(buffer)
        let pointer = base.assumingMemoryBound(to: UInt8.self)
        let step = max(1, min(width, height) / 64)
        var hits = 0
        var sampled = 0
        var y = 0
        while y < height {
            var x = 0
            while x < width {
                if pointer[y * rowBytes + x] > 127 { hits += 1 }
                sampled += 1
                x += step
            }
            y += step
        }
        return sampled == 0 ? 0 : Double(hits) / Double(sampled)
    }

    /// Вырезать объект и положить на новый фон.
    static func replaceBackground(_ image: UIImage, with background: EditBackground) async throws -> UIImage {
        let subject = try await removeBackground(image)
        return composite(subject: subject, over: background, original: image)
    }

    /// Положить уже вырезанный объект на фон. original — исходник для размытого фона.
    static func composite(subject: UIImage, over background: EditBackground, original: UIImage? = nil) -> UIImage {
        let size = subject.size
        let rect = CGRect(origin: .zero, size: size)
        var blurredBackground: UIImage? = nil
        if case .blur(let radius) = background {
            blurredBackground = blurred(original ?? subject, radius: radius)
        }
        let renderer = UIGraphicsImageRenderer(size: size, format: rendererFormat(scale: subject.scale))
        return renderer.image { context in
            switch background {
            case .color(let color):
                color.setFill()
                context.fill(rect)
            case .blur:
                blurredBackground?.draw(in: rect)
            case .image(let picture):
                drawAspectFill(picture, in: rect)
            case .gradient(let colors):
                drawGradient(colors, in: rect, context: context.cgContext)
            }
            subject.draw(in: rect)
        }
    }

    private static func drawAspectFill(_ picture: UIImage, in rect: CGRect) {
        let source = picture.size
        guard source.width > 0, source.height > 0 else { return }
        let scale: CGFloat = max(rect.width / source.width, rect.height / source.height)
        let drawn = CGSize(width: source.width * scale, height: source.height * scale)
        let origin = CGPoint(x: rect.midX - drawn.width / 2, y: rect.midY - drawn.height / 2)
        picture.draw(in: CGRect(origin: origin, size: drawn))
    }

    private static func drawGradient(_ colors: [UIColor], in rect: CGRect, context: CGContext) {
        var list: [UIColor] = colors
        if list.isEmpty { list = [.white, .lightGray] }
        if list.count == 1 { list.append(list[0]) }
        var components: [CGFloat] = []
        for color in list {
            var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 1
            if !color.getRed(&red, green: &green, blue: &blue, alpha: &alpha) {
                red = 1; green = 1; blue = 1; alpha = 1
            }
            components.append(contentsOf: [red, green, blue, alpha])
        }
        let space = CGColorSpaceCreateDeviceRGB()
        guard let gradient = CGGradient(colorSpace: space, colorComponents: components,
                                        locations: nil, count: list.count) else { return }
        context.drawLinearGradient(gradient,
                                   start: CGPoint(x: rect.minX, y: rect.minY),
                                   end: CGPoint(x: rect.maxX, y: rect.maxY),
                                   options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
    }

    // MARK: Цвета

    /// Цвет из "#RRGGBB", "#RGB", "#RRGGBBAA" или названия (рус./англ.). "прозрачный" — .clear.
    static func parseColor(_ raw: String?) -> UIColor? {
        guard let raw = raw else { return nil }
        let value = normalizedWord(raw)
        if value.isEmpty { return nil }
        if let hex = hexColor(value) { return hex }
        let english: [String: UIColor] = [
            "red": rgb(0.93, 0.22, 0.21), "blue": rgb(0.18, 0.42, 0.95), "white": .white, "black": .black,
            "green": rgb(0.2, 0.75, 0.35), "yellow": rgb(1, 0.84, 0.1), "pink": rgb(1, 0.45, 0.7),
            "purple": rgb(0.58, 0.32, 0.9), "violet": rgb(0.58, 0.32, 0.9), "orange": rgb(1, 0.58, 0.1),
            "gray": rgb(0.55, 0.55, 0.57), "grey": rgb(0.55, 0.55, 0.57), "transparent": .clear, "clear": .clear,
            "none": .clear, "cyan": rgb(0.3, 0.8, 0.95), "lightblue": rgb(0.45, 0.75, 1), "brown": rgb(0.55, 0.36, 0.22),
            "gold": rgb(0.95, 0.75, 0.25), "beige": rgb(0.96, 0.92, 0.82), "navy": rgb(0.08, 0.14, 0.4)
        ]
        let compact = value.replacingOccurrences(of: " ", with: "")
        if let color = english[compact] { return color }
        let russian: [(String, UIColor)] = [
            ("прозрачн", .clear), ("красн", rgb(0.93, 0.22, 0.21)), ("син", rgb(0.18, 0.42, 0.95)),
            ("голуб", rgb(0.45, 0.75, 1)), ("бел", .white), ("черн", .black), ("зелен", rgb(0.2, 0.75, 0.35)),
            ("желт", rgb(1, 0.84, 0.1)), ("розов", rgb(1, 0.45, 0.7)), ("фиолет", rgb(0.58, 0.32, 0.9)),
            ("сиренев", rgb(0.72, 0.55, 0.95)), ("оранж", rgb(1, 0.58, 0.1)), ("сер", rgb(0.55, 0.55, 0.57)),
            ("коричн", rgb(0.55, 0.36, 0.22)), ("золот", rgb(0.95, 0.75, 0.25)), ("бежев", rgb(0.96, 0.92, 0.82))
        ]
        for (prefix, color) in russian where value.hasPrefix(prefix) {
            return color
        }
        return nil
    }

    private static func rgb(_ red: CGFloat, _ green: CGFloat, _ blue: CGFloat) -> UIColor {
        UIColor(red: red, green: green, blue: blue, alpha: 1)
    }

    private static func hexColor(_ value: String) -> UIColor? {
        var hex = value
        if hex.hasPrefix("#") { hex.removeFirst() } else if hex.hasPrefix("0x") { hex.removeFirst(2) } else { return nil }
        if hex.count == 3 {
            hex = hex.map { (character: Character) -> String in String(repeating: String(character), count: 2) }.joined()
        }
        guard hex.count == 6 || hex.count == 8, let number = UInt64(hex, radix: 16) else { return nil }
        if hex.count == 8 {
            return UIColor(red: CGFloat((number >> 24) & 255) / 255, green: CGFloat((number >> 16) & 255) / 255,
                           blue: CGFloat((number >> 8) & 255) / 255, alpha: CGFloat(number & 255) / 255)
        }
        return UIColor(red: CGFloat((number >> 16) & 255) / 255, green: CGFloat((number >> 8) & 255) / 255,
                       blue: CGFloat(number & 255) / 255, alpha: 1)
    }
}

// MARK: - Операции для инструмента ИИ

/// Одна правка фото, которую присылает модель.
struct ImageEditOperation: Codable, Equatable {
    var type: String
    var value: String?
    var text: String?
    var color: String?
    var style: String?
    var x: Double?
    var y: Double?
    var width: Double?
    var height: Double?
    var size: Double?
    var amount: Double?
    var brightness: Double?
    var contrast: Double?
    var saturation: Double?
    var warmth: Double?

    init(type: String, value: String? = nil, text: String? = nil, color: String? = nil, style: String? = nil,
         x: Double? = nil, y: Double? = nil, width: Double? = nil, height: Double? = nil,
         size: Double? = nil, amount: Double? = nil, brightness: Double? = nil, contrast: Double? = nil,
         saturation: Double? = nil, warmth: Double? = nil) {
        self.type = type
        self.value = value
        self.text = text
        self.color = color
        self.style = style
        self.x = x
        self.y = y
        self.width = width
        self.height = height
        self.size = size
        self.amount = amount
        self.brightness = brightness
        self.contrast = contrast
        self.saturation = saturation
        self.warmth = warmth
    }

    static let supportedTypes: [String] = [
        "remove_background", "background_color", "background_blur", "background_gradient",
        "filter", "adjust", "rotate", "flip", "crop", "text", "sticker", "resize"
    ]

    /// Тип операции после разбора синонимов.
    var normalizedType: String {
        let raw = ImageEditing.normalizedWord(type)
            .replacingOccurrences(of: "-", with: "_")
            .replacingOccurrences(of: " ", with: "_")
        switch raw {
        case "remove_background", "remove_bg", "removebackground", "removebg", "cutout", "cut_out",
             "убрать_фон", "удалить_фон", "вырезать", "вырезать_фон":
            return "remove_background"
        case "background_color", "background_colour", "background", "bg", "bg_color", "set_background",
             "replace_background", "фон", "цвет_фона", "заменить_фон":
            return "background_color"
        case "background_blur", "blur_background", "bg_blur", "размыть_фон", "размытый_фон":
            return "background_blur"
        case "background_gradient", "gradient", "gradient_background", "градиент", "градиентный_фон":
            return "background_gradient"
        case "filter", "effect", "фильтр", "эффект":
            return "filter"
        case "adjust", "adjustment", "adjustments", "brightness", "contrast", "saturation", "warmth", "temperature",
             "настройки", "яркость", "контраст", "насыщенность", "теплота":
            return "adjust"
        case "rotate", "rotation", "turn", "повернуть", "поворот":
            return "rotate"
        case "flip", "mirror", "отразить", "зеркало", "отражение":
            return "flip"
        case "crop", "обрезать", "обрезка":
            return "crop"
        case "text", "caption", "add_text", "title", "label", "надпись", "текст", "подпись":
            return "text"
        case "sticker", "emoji", "add_sticker", "стикер", "эмодзи", "смайлик":
            return "sticker"
        case "resize", "scale", "downscale", "размер", "уменьшить":
            return "resize"
        default:
            if EditFilter.named(raw) != nil { return "filter" }
            return raw
        }
    }
}

extension ImageEditing {
    /// Применить список правок по порядку.
    static func apply(_ operations: [ImageEditOperation], to image: UIImage) async throws -> UIImage {
        var current = upright(image)
        var cutoutSubject: UIImage? = nil
        var cutoutOriginal: UIImage? = nil
        for operation in operations {
            let kind = operation.normalizedType
            switch kind {
            case "remove_background":
                let original = current
                let subject = try await removeBackground(current)
                cutoutSubject = subject
                cutoutOriginal = original
                current = subject
            case "background_color", "background_blur", "background_gradient":
                let background = try backgroundFor(operation, kind: kind)
                let subject: UIImage
                let original: UIImage
                if let existing = cutoutSubject, let source = cutoutOriginal, existing === current {
                    subject = existing
                    original = source
                } else {
                    original = current
                    subject = try await removeBackground(current)
                }
                cutoutSubject = subject
                cutoutOriginal = original
                if let background = background {
                    current = composite(subject: subject, over: background, original: original)
                } else {
                    current = subject
                }
            default:
                current = try applySimple(operation, kind: kind, to: current)
                cutoutSubject = nil
                cutoutOriginal = nil
            }
            try Task.checkCancellation()
        }
        return current
    }

    /// nil — прозрачный фон.
    private static func backgroundFor(_ operation: ImageEditOperation, kind: String) throws -> EditBackground? {
        switch kind {
        case "background_blur":
            let radius = operation.amount ?? operation.size ?? Double(operation.value ?? "") ?? 25
            return .blur(radius: min(max(radius, 1), 120))
        case "background_gradient":
            let source = operation.value ?? operation.color ?? "#6E8BFF,#C86DD7"
            let parts = source.components(separatedBy: CharacterSet(charactersIn: ",;/|"))
            let colors: [UIColor] = parts.compactMap { (part: String) -> UIColor? in parseColor(part) }
            guard colors.count >= 1 else {
                throw MediaEditingError(russian: "Не понял цвета градиента «\(source)»",
                                        english: "Could not understand gradient colors '\(source)'")
            }
            return .gradient(colors)
        default:
            let source = operation.color ?? operation.value ?? "white"
            let valueWord = ImageEditing.normalizedWord(operation.value ?? "")
            if valueWord.hasPrefix("размыт") || valueWord == "blur" {
                return .blur(radius: operation.amount ?? 25)
            }
            guard let color = parseColor(source) else {
                throw MediaEditingError(russian: "Не понял цвет «\(source)». Используйте #RRGGBB или название цвета",
                                        english: "Unknown color '\(source)'. Use #RRGGBB or a color name")
            }
            if color.cgColor.alpha < 0.01 { return nil }
            return .color(color)
        }
    }

    /// Значение как доля: 50 → 0.5, 0.5 → 0.5.
    private static func fraction(_ value: Double?) -> Double? {
        guard let value = value else { return nil }
        return abs(value) > 1 ? value / 100 : value
    }

    private static func applySimple(_ operation: ImageEditOperation, kind: String, to image: UIImage) throws -> UIImage {
        switch kind {
        case "filter":
            let name = EditFilter.named(operation.value) != nil ? operation.value
                : (EditFilter.named(operation.style) != nil ? operation.style : operation.type)
            guard let filter = EditFilter.named(name) else {
                let names = EditFilter.allCases.map { (filter: EditFilter) -> String in filter.rawValue }.joined(separator: ", ")
                throw MediaEditingError(russian: "Неизвестный фильтр «\(operation.value ?? "")». Доступны: \(names)",
                                        english: "Unknown filter '\(operation.value ?? "")'. Available: \(names)")
            }
            let intensity = fraction(operation.amount) ?? 1
            return applyFilter(image, filter, intensity: min(max(intensity, 0), 1))
        case "adjust":
            return applyAdjust(operation, to: image)
        case "rotate":
            return applyRotate(operation, to: image)
        case "flip":
            let word = ImageEditing.normalizedWord(operation.value ?? "")
            if word.hasPrefix("vert") || word.hasPrefix("верт") || word == "y" || word.hasPrefix("up") {
                return flipVertically(image)
            }
            return flipHorizontally(image)
        case "crop":
            return try applyCrop(operation, to: image)
        case "text":
            let content = operation.text ?? operation.value ?? ""
            guard !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw MediaEditingError(russian: "Для надписи нужен текст (поле text)",
                                        english: "The text operation needs a 'text' field")
            }
            let colorSource = operation.color ?? (operation.text != nil ? operation.value : nil)
            let color = parseColor(colorSource) ?? .white
            let x = fraction(operation.x) ?? 0.5
            let y = fraction(operation.y) ?? 0.85
            var size = operation.size ?? 0.08
            if size > 1 { size = size / 100 }
            let style = TextStyle.named(operation.style) ?? .outline
            return addText(image, text: content, position: CGPoint(x: x, y: y), color: color,
                           fontSize: CGFloat(size), style: style)
        case "sticker":
            let emoji = operation.value ?? operation.text ?? "⭐️"
            let x = fraction(operation.x) ?? 0.5
            let y = fraction(operation.y) ?? 0.5
            let scale = operation.size ?? operation.amount ?? 1
            return addSticker(image, emoji: emoji, position: CGPoint(x: x, y: y), scale: CGFloat(scale))
        case "resize":
            let side = operation.size ?? operation.amount ?? operation.width ?? Double(operation.value ?? "") ?? 0
            guard side >= 16 else {
                throw MediaEditingError(russian: "Для resize укажите size — длинную сторону в пикселях",
                                        english: "resize needs 'size' — the long side in pixels")
            }
            return resize(image, maxSide: CGFloat(side))
        default:
            let list = ImageEditOperation.supportedTypes.joined(separator: ", ")
            throw MediaEditingError(russian: "Неизвестная операция «\(operation.type)». Поддерживаются: \(list)",
                                    english: "Unknown operation '\(operation.type)'. Supported: \(list)")
        }
    }

    private static func applyAdjust(_ operation: ImageEditOperation, to image: UIImage) -> UIImage {
        let raw = ImageEditing.normalizedWord(operation.type)
        var brightness = fraction(operation.brightness) ?? 0
        var contrast = fraction(operation.contrast) ?? 0
        var saturation = fraction(operation.saturation) ?? 0
        var warmth = fraction(operation.warmth) ?? 0
        let generic = fraction(operation.amount) ?? fraction(Double(operation.value ?? ""))
        if let generic = generic {
            if raw == "brightness" || raw == "яркость" { brightness = generic }
            if raw == "contrast" || raw == "контраст" { contrast = generic }
            if raw == "saturation" || raw == "насыщенность" { saturation = generic }
            if raw == "warmth" || raw == "temperature" || raw == "теплота" { warmth = generic }
        }
        func clamp(_ value: Double) -> Double { min(max(value, -1), 1) }
        return adjust(image, brightness: clamp(brightness), contrast: clamp(contrast),
                      saturation: clamp(saturation), warmth: clamp(warmth))
    }

    private static func applyRotate(_ operation: ImageEditOperation, to image: UIImage) -> UIImage {
        let word = ImageEditing.normalizedWord(operation.value ?? "")
        var degrees: Double = operation.amount ?? Double(word) ?? 90
        if word.hasPrefix("left") || word.hasPrefix("влев") || word.hasPrefix("против") || word.hasPrefix("counter")
            || word == "ccw" {
            degrees = -abs(operation.amount ?? 90)
        } else if word.hasPrefix("right") || word.hasPrefix("вправ") || word == "cw" || word.hasPrefix("clockwise")
                    || word.hasPrefix("по_час") || word.hasPrefix("по час") {
            degrees = abs(operation.amount ?? 90)
        }
        let steps = Int((degrees / 90).rounded())
        let normalized = ((steps % 4) + 4) % 4
        var result = image
        if normalized == 3 {
            result = rotate(result, clockwise: false)
        } else if normalized > 0 {
            for _ in 0..<normalized {
                result = rotate(result, clockwise: true)
            }
        }
        return result
    }

    private static func applyCrop(_ operation: ImageEditOperation, to image: UIImage) throws -> UIImage {
        if let width = operation.width, let height = operation.height {
            let rect = CGRect(x: fraction(operation.x) ?? 0, y: fraction(operation.y) ?? 0,
                              width: fraction(width) ?? 1, height: fraction(height) ?? 1)
            return crop(image, to: rect)
        }
        if let aspect = CropAspect.named(operation.value ?? operation.style) {
            return crop(image, aspect: aspect)
        }
        throw MediaEditingError(russian: "Для обрезки укажите value (square, 4:5, 9:16, 16:9) или x, y, width, height",
                                english: "crop needs value (square, 4:5, 9:16, 16:9) or x, y, width, height")
    }

    // MARK: Разбор аргументов

    /// Разобрать операции: массив словарей, один словарь, {"operations": [...]}, JSON-строка
    /// или короткие строки вроде "filter mono".
    static func parseOperations(_ json: Any?) -> [ImageEditOperation] {
        guard let json = json else { return [] }
        if let string = json as? String {
            let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.hasPrefix("[") || trimmed.hasPrefix("{"),
               let data = trimmed.data(using: .utf8),
               let object = try? JSONSerialization.jsonObject(with: data, options: []) {
                return parseOperations(object)
            }
            if let single = shorthandOperation(trimmed) { return [single] }
            return []
        }
        if let array = json as? [Any] {
            return array.flatMap { (item: Any) -> [ImageEditOperation] in parseOperations(item) }
        }
        if let dictionary = json as? [String: Any] {
            var lowered: [String: Any] = [:]
            for (key, value) in dictionary {
                lowered[key.lowercased()] = value
            }
            for key in ["operations", "ops", "edits", "steps", "actions"] {
                if let nested = lowered[key] { return parseOperations(nested) }
            }
            if let operation = operation(from: lowered) { return [operation] }
        }
        return []
    }

    private static func shorthandOperation(_ string: String) -> ImageEditOperation? {
        guard !string.isEmpty else { return nil }
        let separators = CharacterSet(charactersIn: " :=")
        if let range = string.rangeOfCharacter(from: separators) {
            let type = String(string[string.startIndex..<range.lowerBound])
            let rest = String(string[range.upperBound...]).trimmingCharacters(in: .whitespaces)
            var operation = ImageEditOperation(type: type)
            if operation.normalizedType == "text" {
                operation.text = rest
            } else {
                operation.value = rest.isEmpty ? nil : rest
            }
            return operation
        }
        return ImageEditOperation(type: string)
    }

    private static func firstValue(_ dictionary: [String: Any], _ keys: [String]) -> Any? {
        for key in keys {
            if let value = dictionary[key], !(value is NSNull) { return value }
        }
        return nil
    }

    private static func stringValue(_ value: Any?) -> String? {
        guard let value = value else { return nil }
        if let string = value as? String { return string }
        if let number = value as? NSNumber { return number.stringValue }
        if let array = value as? [Any] {
            let parts = array.compactMap { (item: Any) -> String? in stringValue(item) }
            return parts.isEmpty ? nil : parts.joined(separator: ",")
        }
        return nil
    }

    private static func doubleValue(_ value: Any?) -> Double? {
        guard let value = value else { return nil }
        if let number = value as? NSNumber { return number.doubleValue }
        if let double = value as? Double { return double }
        if let int = value as? Int { return Double(int) }
        if let string = value as? String {
            let cleaned = string.trimmingCharacters(in: .whitespaces)
                .replacingOccurrences(of: ",", with: ".")
                .replacingOccurrences(of: "%", with: "")
                .replacingOccurrences(of: "px", with: "")
                .replacingOccurrences(of: "°", with: "")
            return Double(cleaned)
        }
        return nil
    }

    private static func operation(from dictionary: [String: Any]) -> ImageEditOperation? {
        guard let type = stringValue(firstValue(dictionary, ["type", "op", "operation", "action", "tool", "kind"])),
              !type.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        var operation = ImageEditOperation(type: type)
        operation.value = stringValue(firstValue(dictionary, ["value", "filter", "emoji", "aspect", "direction",
                                                              "mode", "name", "colors", "background", "degrees"]))
        operation.text = stringValue(firstValue(dictionary, ["text", "caption", "title", "label"]))
        operation.color = stringValue(firstValue(dictionary, ["color", "colour", "text_color", "textcolor", "цвет"]))
        operation.style = stringValue(firstValue(dictionary, ["style", "font_style", "стиль"]))
        operation.x = doubleValue(firstValue(dictionary, ["x", "left"]))
        operation.y = doubleValue(firstValue(dictionary, ["y", "top"]))
        operation.width = doubleValue(firstValue(dictionary, ["width", "w"]))
        operation.height = doubleValue(firstValue(dictionary, ["height", "h"]))
        operation.size = doubleValue(firstValue(dictionary, ["size", "font_size", "fontsize", "scale", "max_side", "maxside"]))
        operation.amount = doubleValue(firstValue(dictionary, ["amount", "intensity", "radius", "strength", "angle"]))
        operation.brightness = doubleValue(firstValue(dictionary, ["brightness", "яркость"]))
        operation.contrast = doubleValue(firstValue(dictionary, ["contrast", "контраст"]))
        operation.saturation = doubleValue(firstValue(dictionary, ["saturation", "насыщенность"]))
        operation.warmth = doubleValue(firstValue(dictionary, ["warmth", "temperature", "теплота"]))
        if operation.normalizedType == "rotate", operation.amount == nil, let degrees = doubleValue(dictionary["degrees"]) {
            operation.amount = degrees
        }
        if let clockwise = dictionary["clockwise"] as? Bool, operation.normalizedType == "rotate", operation.value == nil {
            operation.value = clockwise ? "right" : "left"
        }
        return operation
    }
}
