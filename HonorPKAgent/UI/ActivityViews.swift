import SwiftUI

/// Лента «что делает Honer AI»: поиск, чтение страниц, рисование и другие шаги.
/// Текущий шаг с индикатором, готовые — с галочкой; сайты показаны значками.
struct ActivityTimeline: View {
    let steps: [GenerationStep]
    let fontSize: Double
    var live: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(steps.enumerated()), id: \.element.id) { index, step in
                HStack(alignment: .top, spacing: 10) {
                    VStack(spacing: 0) {
                        marker(step)
                        if index < steps.count - 1 {
                            Rectangle().fill(HonorTheme.divider).frame(width: 1.5).frame(maxHeight: .infinity)
                        }
                    }
                    .frame(width: 22)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(step.title)
                            .font(.system(size: fontSize, weight: .semibold))
                            .foregroundStyle(HonorTheme.foreground)
                        if !step.detail.isEmpty {
                            Text(step.detail)
                                .font(.system(size: fontSize * 0.9))
                                .foregroundStyle(HonorTheme.secondary)
                                .lineLimit(2)
                        }
                        if !step.sites.isEmpty {
                            SiteChips(sites: step.sites)
                        }
                    }
                    .padding(.bottom, index < steps.count - 1 ? 12 : 0)
                    Spacer(minLength: 0)
                }
                .transition(.asymmetric(insertion: .move(edge: .top).combined(with: .opacity), removal: .opacity))
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("activity.step." + step.kind)
            }
        }
        .animation(.easeOut(duration: 0.25), value: steps)
        .accessibilityIdentifier("activity.timeline")
    }

    @ViewBuilder
    private func marker(_ step: GenerationStep) -> some View {
        ZStack {
            Circle().fill(step.done ? HonorTheme.accent.opacity(0.18) : HonorTheme.surface)
                .overlay(Circle().stroke(step.done ? HonorTheme.accent.opacity(0.5) : HonorTheme.divider, lineWidth: 1))
            if step.done || !live {
                Image(systemName: Self.symbol(step.kind))
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(HonorTheme.accent)
            } else {
                ProgressView().scaleEffect(0.55).tint(HonorTheme.accent)
            }
        }
        .frame(width: 22, height: 22)
    }

    static func symbol(_ kind: String) -> String {
        switch kind {
        case "search": return "magnifyingglass"
        case "read": return "doc.text"
        case "images": return "photo"
        case "videos": return "play.rectangle"
        case "screenshot": return "camera.viewfinder"
        case "weather": return "cloud.sun"
        case "draw": return "paintbrush"
        case "chats": return "bubble.left.and.bubble.right"
        case "memory": return "brain"
        case "contact": return "person.crop.circle"
        default: return "gearshape"
        }
    }

    /// Короткий итог шагов для свёрнутого заголовка: «Искал в интернете · 5 страниц».
    static func summary(_ steps: [GenerationStep]) -> String {
        var parts: [String] = []
        let sites = Set(steps.flatMap(\.sites))
        if steps.contains(where: { $0.kind == "search" }) { parts.append("Искал в интернете") }
        if !sites.isEmpty { parts.append("сайтов: \(sites.count)") }
        if steps.contains(where: { $0.kind == "images" }) { parts.append("фото") }
        if steps.contains(where: { $0.kind == "videos" }) { parts.append("видео") }
        if steps.contains(where: { $0.kind == "draw" }) { parts.append("рисунок") }
        if steps.contains(where: { $0.kind == "chats" }) { parts.append("чаты") }
        if steps.contains(where: { $0.kind == "memory" }) { parts.append("запомнил") }
        if parts.isEmpty { parts.append("Шаги: \(steps.count)") }
        return parts.joined(separator: " · ")
    }
}

/// Значки сайтов: первая буква и адрес в капсуле.
private struct SiteChips: View {
    let sites: [String]

    var body: some View {
        let unique = sites.reduce(into: [String]()) { list, host in
            let clean = host.replacingOccurrences(of: "www.", with: "")
            if !list.contains(clean) { list.append(clean) }
        }
        HStack(spacing: 5) {
            ForEach(unique.prefix(4), id: \.self) { host in
                HStack(spacing: 4) {
                    Text(String(host.prefix(1)).uppercased())
                        .font(.system(size: 9, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 15, height: 15)
                        .background(Self.color(for: host), in: Circle())
                    Text(host)
                        .font(.system(size: 11))
                        .foregroundStyle(HonorTheme.secondary)
                        .lineLimit(1)
                }
                .padding(.horizontal, 6).padding(.vertical, 3)
                .background(HonorTheme.surface, in: Capsule())
                .overlay(Capsule().stroke(HonorTheme.divider, lineWidth: 0.6))
            }
            if unique.count > 4 {
                Text("+\(unique.count - 4)")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(HonorTheme.secondary)
            }
        }
    }

    static func color(for host: String) -> Color {
        let palette: [Color] = [.blue, .purple, .teal, .orange, .pink, .indigo, .green, .red]
        let hash = host.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) & 0xFFFF }
        return palette[hash % palette.count]
    }
}
