import SwiftUI

/// Линии навигации по сообщениям у правого края чата.
///
/// Каждая линия — одно сообщение (длинная — ваше, короткая — ответ Honer AI).
/// Касание — переход к сообщению. Если зажать полосу и вести пальцем, над ней
/// появляется превью сообщения; отпустили — чат прокручивается к нему, а в превью
/// остаётся кнопка «Продолжить отсюда»: разговор продолжится в новой ветке с этого
/// места, исходный чат не меняется.
///
/// Раньше линии показывались только после нажатия на них же, а слой с ними не
/// принимал касаний, — поэтому увидеть их было нельзя.
struct MessageNavigationStrip: View {
    let messages: [ChatMessage]
    let settings: AppSettings
    let onJump: (UUID) -> Void
    let onBranch: (UUID) -> Void

    @State private var activeIndex: Int?
    @State private var scrubbing = false
    @State private var shownIndex: Int?
    @State private var hideTask: Task<Void, Never>?

    private var items: [ChatMessage] {
        messages.filter { $0.role != .tool && (!$0.content.isEmpty || !$0.attachments.isEmpty) }
    }

    var body: some View {
        GeometryReader { geometry in
            let list = items
            let layout = Layout(count: list.count, height: geometry.size.height)
            ZStack(alignment: .topLeading) {
                if let index = activeIndex ?? shownIndex, list.indices.contains(index) {
                    let width = min(270, geometry.size.width - 64)
                    preview(list[index], branchable: !scrubbing)
                        .frame(width: width)
                        .position(x: geometry.size.width - 34 - width / 2,
                                  y: min(max(layout.y(index), 70), geometry.size.height - 70))
                        .transition(.opacity.combined(with: .scale(scale: 0.95, anchor: .trailing)))
                }
                ForEach(Array(list.enumerated()), id: \.element.id) { index, message in
                    let active = index == (activeIndex ?? shownIndex)
                    Capsule()
                        .fill(active ? HonorTheme.accent : (message.role == .user ? HonorTheme.secondary.opacity(0.55) : HonorTheme.secondary.opacity(0.9)))
                        .frame(width: active ? 22 : (message.role == .user ? 13 : 8), height: active ? 3.5 : 2.5)
                        .position(x: geometry.size.width - 8 - (active ? 11 : (message.role == .user ? 6.5 : 4)),
                                  y: layout.y(index))
                        .animation(.easeOut(duration: 0.12), value: active)
                }
                Color.clear
                    .frame(width: 34, height: layout.height + 24)
                    .contentShape(Rectangle())
                    .position(x: geometry.size.width - 17, y: layout.top + layout.height / 2)
                    .gesture(
                        DragGesture(minimumDistance: 0)
                            .onChanged { value in
                                let index = layout.index(at: value.location.y)
                                if !scrubbing {
                                    scrubbing = true
                                    hideTask?.cancel()
                                }
                                if index != activeIndex {
                                    UISelectionFeedbackGenerator().selectionChanged()
                                    withAnimation(.easeOut(duration: 0.12)) { activeIndex = index; shownIndex = nil }
                                }
                            }
                            .onEnded { _ in
                                guard let index = activeIndex, list.indices.contains(index) else { return }
                                onJump(list[index].id)
                                withAnimation(.easeOut(duration: 0.15)) {
                                    scrubbing = false
                                    activeIndex = nil
                                    shownIndex = index
                                }
                                scheduleHide()
                            }
                    )
                    .accessibilityElement()
                    .accessibilityLabel(settings.text("Навигация по сообщениям", "Message navigation"))
                    .accessibilityValue(settings.text("\(list.count) сообщений", "\(list.count) messages"))
                    .accessibilityAdjustableAction { direction in
                        let current = shownIndex ?? (list.count - 1)
                        let next = direction == .increment ? min(current + 1, list.count - 1) : max(current - 1, 0)
                        guard list.indices.contains(next) else { return }
                        shownIndex = next
                        onJump(list[next].id)
                    }
                    .accessibilityIdentifier("chat.nav.strip")
            }
        }
        .onDisappear { hideTask?.cancel() }
    }

    private func scheduleHide() {
        hideTask?.cancel()
        hideTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            guard !Task.isCancelled else { return }
            withAnimation(.easeOut(duration: 0.2)) { shownIndex = nil }
        }
    }

    private func preview(_ message: ChatMessage, branchable: Bool) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: message.role == .user ? "person.fill" : "sparkles")
                    .font(.system(size: 11, weight: .semibold))
                Text(message.role == .user ? settings.text("Вы", "You") : "Honer AI")
                    .font(.system(size: 12, weight: .semibold))
                Spacer(minLength: 0)
                Text(message.createdAt, style: .time)
                    .font(.system(size: 11))
                    .foregroundStyle(HonorTheme.secondary)
            }
            .foregroundStyle(HonorTheme.accent)
            Text(Self.previewText(message))
                .font(.system(size: 14))
                .foregroundStyle(HonorTheme.foreground)
                .lineLimit(4)
                .fixedSize(horizontal: false, vertical: true)
            if branchable {
                HStack(spacing: 10) {
                    Button {
                        hideTask?.cancel()
                        shownIndex = nil
                        onBranch(message.id)
                    } label: {
                        Label(settings.text("Продолжить отсюда", "Continue from here"), systemImage: "arrow.triangle.branch")
                            .font(.system(size: 13, weight: .semibold))
                            .padding(.horizontal, 10).padding(.vertical, 7)
                            .background(HonorTheme.accent.opacity(0.16), in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(HonorTheme.accent)
                    .accessibilityIdentifier("chat.nav.branch")
                    Spacer(minLength: 0)
                    Button {
                        hideTask?.cancel()
                        withAnimation { shownIndex = nil }
                    } label: {
                        Image(systemName: "xmark").font(.system(size: 12, weight: .bold)).frame(width: 30, height: 30)
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(HonorTheme.secondary)
                    .accessibilityLabel(settings.text("Закрыть", "Close"))
                    .accessibilityIdentifier("chat.nav.preview.close")
                }
            }
        }
        .padding(12)
        .background(HonorTheme.sidebar, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(HonorTheme.divider, lineWidth: 0.7))
        .shadow(color: .black.opacity(0.22), radius: 14, x: 0, y: 6)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("chat.nav.preview")
    }

    static func previewText(_ message: ChatMessage) -> String {
        let text = SpeechService.sanitizedSpeechText(message.content)
            .replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if !text.isEmpty { return String(text.prefix(220)) }
        return message.attachments.map(\.name).joined(separator: ", ")
    }

    /// Раскладка линий: по центру по высоте, не выше 60 % экрана.
    struct Layout {
        let count: Int
        let top: CGFloat
        let height: CGFloat
        let step: CGFloat

        init(count: Int, height available: CGFloat) {
            self.count = count
            let span = min(available * 0.6, CGFloat(max(count - 1, 1)) * 12)
            height = span
            top = (available - span) / 2
            step = count > 1 ? span / CGFloat(count - 1) : 0
        }

        func y(_ index: Int) -> CGFloat { top + CGFloat(index) * step }

        func index(at y: CGFloat) -> Int {
            guard count > 1, step > 0 else { return 0 }
            let raw = Int(((y - top) / step).rounded())
            return min(max(raw, 0), count - 1)
        }
    }
}
