import SwiftUI

/// Карточка файла в сообщении: значок по типу, имя и краткое описание
/// («Таблица Excel: 2 листа», «Аудио 1:24 · расшифровано»).
struct AttachmentFileCard: View {
    let attachment: MessageAttachment
    var scale: Double = 1
    @AppStorage("honor.language") private var language = "ru"

    private var fileExtension: String { (attachment.name as NSString).pathExtension.lowercased() }

    private var symbol: String {
        switch attachment.kind {
        case .audio: return "waveform"
        case .video: return "play.rectangle.fill"
        case .image: return "photo"
        case .sticker: return "face.smiling"
        case .document, .text:
            return fileExtension == "pdf" ? "doc.richtext.fill" : DocumentReader.symbol(forExtension: fileExtension)
        }
    }

    private var tint: Color {
        switch fileExtension {
        case "xlsx", "xlsm", "xls", "csv", "tsv", "ods", "numbers": return Color(red: 0.15, green: 0.62, blue: 0.35)
        case "docx", "doc", "rtf", "odt", "pages": return Color(red: 0.2, green: 0.45, blue: 0.9)
        case "pptx", "ppt", "odp", "key": return Color(red: 0.92, green: 0.45, blue: 0.2)
        case "pdf": return Color(red: 0.88, green: 0.25, blue: 0.25)
        default:
            if attachment.kind == .audio { return Color(red: 0.6, green: 0.35, blue: 0.95) }
            if attachment.kind == .text { return Color(red: 0.35, green: 0.4, blue: 0.5) }
            return HonorTheme.accent
        }
    }

    private var subtitle: String {
        if let summary = attachment.summary, !summary.isEmpty { return summary }
        if attachment.kind == .text || attachment.kind == .document {
            return DocumentReader.kind(forExtension: fileExtension)
        }
        return language == "en" ? "File" : "Файл"
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.system(size: 18 * scale, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 42 * scale, height: 42 * scale)
                .background(tint.gradient, in: RoundedRectangle(cornerRadius: 11, style: .continuous))
            VStack(alignment: .leading, spacing: 3) {
                Text(attachment.name)
                    .font(.system(size: 14.5 * scale, weight: .semibold))
                    .foregroundStyle(HonorTheme.foreground)
                    .lineLimit(2)
                Text(subtitle)
                    .font(.system(size: 12 * scale))
                    .foregroundStyle(HonorTheme.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            Image(systemName: attachment.kind == .audio ? "play.circle.fill" : "chevron.right")
                .font(.system(size: attachment.kind == .audio ? 26 * scale : 13 * scale, weight: .semibold))
                .foregroundStyle(attachment.kind == .audio ? tint : HonorTheme.secondary)
        }
        .padding(10)
        .frame(maxWidth: 360, alignment: .leading)
        .background(HonorTheme.surface, in: RoundedRectangle(cornerRadius: 15, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 15, style: .continuous).stroke(HonorTheme.divider, lineWidth: 0.8))
        .transition(.opacity.combined(with: .scale(scale: 0.96)))
    }
}

/// Процитированный фрагмент: полоска слева и текст в две-три строки.
struct QuoteChip: View {
    let text: String
    var scale: Double = 1

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            RoundedRectangle(cornerRadius: 2)
                .fill(HonorTheme.accent)
                .frame(width: 3)
            Text(text)
                .font(.system(size: 14 * scale))
                .foregroundStyle(HonorTheme.secondary)
                .lineLimit(3)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .fixedSize(horizontal: false, vertical: true)
        .padding(.vertical, 6)
        .padding(.horizontal, 10)
        .background(HonorTheme.raised.opacity(0.7), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .accessibilityIdentifier("message.quote")
    }
}
