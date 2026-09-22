import SwiftUI
import UIKit

struct ChatRootView: View {
    @EnvironmentObject private var store: ChatStore
    @EnvironmentObject private var settings: AppSettings
    @StateObject private var speech = SpeechService()
    @ScaledMetric(relativeTo: .body) private var dynamicScale = 1.0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var drawerOpen = false
    @State private var attachmentsOpen = false
    @State private var settingsOpen = false
    @State private var selectedText: SelectedText?
    @State private var shareItem: SharedText?
    @State private var deviceError: String?
    @State private var toast: String?
    @State private var toastTask: Task<Void, Never>?
    @State private var speechDraftPrefix = ""
    @FocusState private var composerFocused: Bool

    private func text(_ ru: String, _ en: String) -> String { settings.text(ru, en) }

    var body: some View {
        GeometryReader { geometry in
            let drawerWidth = min(geometry.size.width * 0.79, 360)
            ZStack(alignment: .leading) {
                HonorTheme.sidebar.ignoresSafeArea()
                HistoryDrawer(onClose: closeDrawer, onSettings: {
                    closeDrawer()
                    settingsOpen = true
                })
                .frame(width: drawerWidth)
                .opacity(drawerOpen ? 1 : 0)
                .accessibilityHidden(!drawerOpen)

                mainScreen
                    .frame(width: geometry.size.width)
                    .background(HonorTheme.background.ignoresSafeArea())
                    .clipShape(RoundedRectangle(cornerRadius: drawerOpen ? 28 : 0, style: .continuous))
                    .overlay {
                        if drawerOpen {
                            Color.black.opacity(0.13)
                                .contentShape(Rectangle())
                                .onTapGesture(perform: closeDrawer)
                                .accessibilityLabel(text("Закрыть историю", "Close history"))
                                .accessibilityAddTraits(.isButton)
                        }
                    }
                    .offset(x: drawerOpen ? drawerWidth : 0)
                    .accessibilityHidden(drawerOpen)
            }
            .simultaneousGesture(DragGesture(minimumDistance: 24).onEnded { value in
                guard abs(value.translation.width) > abs(value.translation.height) * 1.4 else { return }
                if drawerOpen && value.translation.width < -45 { closeDrawer() }
                else if !drawerOpen && value.startLocation.x < 24 && value.translation.width > 60 { openDrawer() }
            })
        }
        .background(HonorTheme.background.ignoresSafeArea())
        .foregroundStyle(HonorTheme.foreground)
        .sheet(isPresented: $settingsOpen) {
            SettingsView().environmentObject(store).environmentObject(settings)
        }
        .sheet(item: $selectedText) { item in
            SelectableTextSheet(content: item.content)
                .environmentObject(settings)
        }
        .sheet(item: $shareItem) { item in ActivitySheet(items: [item.content]) }
        .alert(text("Не удалось выполнить действие", "Unable to complete action"),
               isPresented: Binding(get: { deviceError != nil }, set: { if !$0 { deviceError = nil } })) {
            Button("OK", role: .cancel) { deviceError = nil }
        } message: { Text(deviceError ?? "") }
        .onChange(of: speech.transcript) { transcript in
            store.draft = speechDraftPrefix + transcript
        }
        .onChange(of: speech.errorMessage) { error in
            if let error { deviceError = error }
        }
        .onChange(of: store.isGenerating) { generating in
            if !generating, settings.autoRead,
               let last = store.messages.last, last.role == .assistant,
               !last.content.isEmpty, last.error == nil, !last.isInterrupted {
                speak(last.content)
            }
        }
        .onDisappear { speech.stopRecording(); speech.stopSpeaking(); toastTask?.cancel() }
    }

    private var mainScreen: some View {
        VStack(spacing: 0) {
            header
            if store.messages.isEmpty {
                welcome
            } else {
                MessageTimeline(store: store, settings: settings,
                                onCopy: copy, onSelect: { selectedText = SelectedText(content: $0) },
                                onShare: { shareItem = SharedText(content: $0) }, onSpeak: speak)
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 0) {
                if let toast {
                    Text(toast).font(.system(size: 13, weight: .medium))
                        .padding(.horizontal, 16).padding(.vertical, 9)
                        .background(.ultraThinMaterial, in: Capsule())
                        .padding(.bottom, 10)
                        .transition(.opacity.combined(with: .move(edge: .bottom)))
                }
                composer
                    .padding(.horizontal, 12)
                    .padding(.bottom, 6)
                if attachmentsOpen {
                    AttachmentTray(onAttachment: { attachment in
                        store.attachments.append(attachment)
                    }, onError: { deviceError = $0 })
                    .environmentObject(settings)
                    .padding(.bottom, 10)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .background(HonorTheme.background)
        }
    }

    private var header: some View {
        HStack(spacing: 10) {
            HonorCircleButton(symbol: "line.3.horizontal.decrease",
                              label: text("История чатов", "Chat history"), action: openDrawer)
                .accessibilityIdentifier("chat.sidebar")
            Text(store.selectedConversation?.title ?? "")
                .font(.system(size: 17 * settings.fontScale * dynamicScale, weight: .semibold))
                .lineLimit(1)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 4)
            HStack(spacing: 0) {
                Button {
                    settings.autoRead.toggle()
                    if !settings.autoRead { speech.stopSpeaking() }
                } label: {
                    Image(systemName: settings.autoRead ? "speaker.wave.2" : "speaker.slash")
                        .font(.system(size: 19))
                        .frame(width: 44, height: 42)
                }
                .accessibilityLabel(text(settings.autoRead ? "Выключить озвучивание" : "Включить озвучивание",
                                         settings.autoRead ? "Disable read aloud" : "Enable read aloud"))
                Button {
                    speech.stopRecording(); speech.stopSpeaking()
                    store.newChat(); attachmentsOpen = false
                } label: {
                    Image(systemName: "bubble.left.and.bubble.right")
                        .font(.system(size: 20))
                        .overlay(alignment: .center) {
                            Image(systemName: "plus").font(.system(size: 9, weight: .bold))
                                .offset(x: -2, y: -2)
                        }
                        .frame(width: 44, height: 42)
                }
                .accessibilityLabel(text("Новый чат", "New chat"))
                .accessibilityIdentifier("chat.new")
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 1)
            .background(HonorTheme.surface.opacity(0.65), in: Capsule())
            .overlay(Capsule().stroke(HonorTheme.divider, lineWidth: 0.7))
        }
        .padding(.horizontal, 14)
        .padding(.top, 6)
        .padding(.bottom, 12)
    }

    private var welcome: some View {
        GeometryReader { geometry in
            VStack(spacing: 23) {
                HonorMark(size: 47)
                Text(text("Привет! О чём хотите\nпоговорить сегодня?", "Hi! What would you like\nto talk about today?"))
                    .font(.system(size: 22 * settings.fontScale * dynamicScale, weight: .bold))
                    .lineSpacing(5)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("welcomeMessage")
            }
            .padding(.horizontal, 30)
            .frame(width: geometry.size.width)
            .position(x: geometry.size.width / 2, y: geometry.size.height * 0.43)
        }
        .contentShape(Rectangle())
        .onTapGesture { composerFocused = false }
    }

    private var composer: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let error = store.errorMessage, store.messages.last?.error == nil {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "exclamationmark.circle").foregroundStyle(.orange)
                    Text(error).font(.system(size: 12)).fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                    Button { store.errorMessage = nil } label: { Image(systemName: "xmark") }
                        .accessibilityLabel(text("Закрыть ошибку", "Dismiss error"))
                }
                .foregroundStyle(HonorTheme.secondary)
                .padding(.horizontal, 4).padding(.top, 4)
            }
            if store.editingMessageID != nil {
                HStack {
                    Label(text("Редактирование сообщения", "Editing message"), systemImage: "pencil")
                        .font(.system(size: 12))
                    Spacer()
                    Button { store.cancelEditing() } label: {
                        Image(systemName: "xmark.circle.fill").frame(width: 32, height: 32)
                    }.accessibilityLabel(text("Отменить редактирование", "Cancel editing"))
                }.foregroundStyle(HonorTheme.accent)
            }
            if !store.attachments.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(store.attachments) { attachment in
                            HStack(spacing: 5) {
                                Image(systemName: attachment.kind == .image ? "photo" : "doc.text")
                                Text(attachment.name).lineLimit(1).frame(maxWidth: 160)
                                Button { store.attachments.removeAll { $0.id == attachment.id } } label: {
                                    Image(systemName: "xmark.circle.fill").frame(width: 30, height: 32)
                                }.accessibilityLabel(text("Удалить вложение ", "Remove attachment ") + attachment.name)
                            }
                            .font(.system(size: 12))
                            .padding(.leading, 10).padding(.trailing, 2)
                            .background(HonorTheme.raised, in: RoundedRectangle(cornerRadius: 12))
                        }
                    }
                }
            }
            TextField(text("Напишите сообщение…", "Message Honor…"), text: $store.draft, axis: .vertical)
                .font(.system(size: 17 * settings.fontScale * dynamicScale))
                .lineLimit(1...6)
                .focused($composerFocused)
                .tint(HonorTheme.accent)
                .padding(.horizontal, 5)
                .padding(.top, 4)
                .frame(minHeight: 42, alignment: .topLeading)
                .accessibilityLabel(text("Сообщение", "Message"))
                .accessibilityIdentifier("chat.composer")
                .onChange(of: composerFocused) { focused in
                    if focused && attachmentsOpen { animate { attachmentsOpen = false } }
                }
            HStack(spacing: 5) {
                modePill(symbol: "atom", label: text("Рассуждение", "Reason"), selected: store.reasoningEnabled) {
                    store.reasoningEnabled.toggle()
                }.accessibilityIdentifier("composer.reasoning")
                modePill(symbol: "globe", label: text("Поиск", "Search"), selected: store.searchEnabled) {
                    store.searchEnabled.toggle()
                }.accessibilityIdentifier("composer.search")
                Spacer(minLength: 0)
                Button {
                    composerFocused = false
                    animate { attachmentsOpen.toggle() }
                } label: {
                    Image(systemName: attachmentsOpen ? "xmark.circle" : "plus.circle")
                        .font(.system(size: 25, weight: .regular)).frame(width: 40, height: 44)
                }
                .accessibilityLabel(text(attachmentsOpen ? "Закрыть вложения" : "Добавить вложение",
                                         attachmentsOpen ? "Close attachments" : "Add attachment"))
                .accessibilityIdentifier("composer.attachments")
                if store.isGenerating {
                    Button { store.stop() } label: {
                        Image(systemName: "stop.fill").font(.system(size: 13))
                            .frame(width: 30, height: 30)
                            .background(HonorTheme.foreground, in: Circle())
                            .foregroundStyle(HonorTheme.background)
                            .frame(width: 40, height: 44)
                    }.accessibilityLabel(text("Остановить ответ", "Stop response"))
                } else if store.canSend {
                    Button(action: send) {
                        Image(systemName: "arrow.up").font(.system(size: 18, weight: .semibold))
                            .frame(width: 32, height: 32)
                            .background(HonorTheme.accent, in: Circle())
                            .foregroundStyle(.white)
                            .frame(width: 40, height: 44)
                    }
                    .accessibilityLabel(text("Отправить", "Send"))
                    .accessibilityIdentifier("chat.send")
                } else {
                    Button(action: toggleRecording) {
                        Image(systemName: speech.isRecording ? "stop.circle.fill" : "waveform.circle")
                            .font(.system(size: 26))
                            .foregroundStyle(speech.isRecording ? HonorTheme.accent : HonorTheme.foreground)
                            .frame(width: 40, height: 44)
                    }
                    .accessibilityLabel(text(speech.isRecording ? "Остановить запись" : "Голосовой ввод",
                                             speech.isRecording ? "Stop recording" : "Voice input"))
                }
            }
            .buttonStyle(.plain)
            if speech.isRecording {
                HStack(spacing: 6) {
                    Circle().fill(Color.red).frame(width: 6, height: 6)
                    Text(text("Слушаю… Нажмите, чтобы завершить", "Listening… Tap to finish"))
                    Spacer()
                    Button(text("Готово", "Done")) { speech.stopRecording() }
                }
                .font(.system(size: 12))
                .foregroundStyle(HonorTheme.secondary)
                .padding(.horizontal, 5).padding(.bottom, 4)
            }
        }
        .padding(.horizontal, 10).padding(.top, 10).padding(.bottom, 5)
        .background(HonorTheme.surface, in: RoundedRectangle(cornerRadius: 27, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 27, style: .continuous).stroke(HonorTheme.divider, lineWidth: 0.7))
    }

    private func modePill(symbol: String, label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Image(systemName: symbol).font(.system(size: 15))
                Text(label).font(.system(size: 13, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.8)
            }
            .foregroundStyle(selected ? HonorTheme.accent : HonorTheme.secondary)
            .padding(.horizontal, 9).frame(height: 32)
            .background(selected ? HonorTheme.accent.opacity(0.18) : Color.clear, in: Capsule())
            .overlay(Capsule().stroke(selected ? HonorTheme.accent.opacity(0.27) : HonorTheme.divider, lineWidth: 0.8))
            .frame(minHeight: 44)
            .contentShape(Capsule())
        }
        .accessibilityLabel(label)
        .accessibilityValue(text(selected ? "Включено" : "Выключено", selected ? "On" : "Off"))
    }

    private func send() {
        speech.stopRecording(); speech.stopSpeaking()
        store.systemInstruction = settings.customInstructions
        store.send()
        composerFocused = false
        animate { attachmentsOpen = false }
    }

    private func toggleRecording() {
        if speech.isRecording { speech.stopRecording(); return }
        composerFocused = false
        speechDraftPrefix = store.draft.isEmpty ? "" : store.draft + " "
        Task { await speech.startRecording(language: settings.speechLanguage) }
    }

    private func speak(_ content: String) {
        if speech.isSpeaking { speech.stopSpeaking() }
        else { speech.speak(content, voiceIdentifier: settings.voiceIdentifier, language: settings.speechLanguage) }
    }

    private func copy(_ content: String) {
        UIPasteboard.general.string = content
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        toastTask?.cancel()
        animate { toast = text("Скопировано", "Copied") }
        toastTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_800_000_000)
            guard !Task.isCancelled else { return }
            animate { toast = nil }
        }
    }

    private func openDrawer() {
        composerFocused = false
        animate { drawerOpen = true; attachmentsOpen = false }
    }
    private func closeDrawer() { animate { drawerOpen = false } }
    private func animate(_ action: () -> Void) {
        withAnimation(reduceMotion ? nil : .spring(response: 0.32, dampingFraction: 0.91), action)
    }
}

private struct MessageTimeline: View {
    @ObservedObject var store: ChatStore
    @ObservedObject var settings: AppSettings
    let onCopy: (String) -> Void
    let onSelect: (String) -> Void
    let onShare: (String) -> Void
    let onSpeak: (String) -> Void
    @State private var followLatest = true
    @ScaledMetric(relativeTo: .body) private var dynamicScale = 1.0

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 25) {
                    Text(settings.text("Сгенерированный ИИ ответ, только для справки.", "AI-generated answers are for reference."))
                        .font(.system(size: 13 * settings.fontScale * dynamicScale, weight: .medium))
                        .foregroundStyle(HonorTheme.secondary)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                        .padding(.horizontal, 28).padding(.top, 23).padding(.bottom, 3)
                    ForEach(store.messages) { message in
                        MessageRow(message: message,
                                   streaming: store.isGenerating && message.id == store.messages.last?.id,
                                   status: store.generationStatus,
                                   settings: settings,
                                   onCopy: onCopy, onSelect: onSelect, onShare: onShare, onSpeak: onSpeak,
                                   onRetry: { store.regenerate(messageID: message.id) },
                                   onEdit: { store.edit(messageID: message.id) },
                                   onFeedback: { store.setFeedback(messageID: message.id, feedback: $0) })
                            .id(message.id)
                    }
                    Color.clear.frame(height: 8).id("message-bottom")
                }
                .padding(.horizontal, 22)
                .padding(.bottom, 8)
            }
            .scrollDismissesKeyboard(.interactively)
            .simultaneousGesture(DragGesture(minimumDistance: 15).onChanged { _ in followLatest = false })
            .overlay(alignment: .bottomTrailing) {
                if !followLatest {
                    Button {
                        followLatest = true
                        withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo("message-bottom", anchor: .bottom) }
                    } label: {
                        Image(systemName: "arrow.down").font(.system(size: 15, weight: .semibold))
                            .frame(width: 38, height: 38)
                            .background(.ultraThinMaterial, in: Circle())
                            .overlay(Circle().stroke(HonorTheme.divider))
                            .frame(width: 44, height: 44)
                    }
                    .accessibilityLabel(settings.text("К последнему сообщению", "Jump to latest message"))
                    .padding(.trailing, 14).padding(.bottom, 6)
                }
            }
            .onAppear { proxy.scrollTo("message-bottom", anchor: .bottom) }
            .onChange(of: store.messages.count) { _ in
                followLatest = true
                proxy.scrollTo("message-bottom", anchor: .bottom)
            }
            .onChange(of: store.selectedConversationID) { _ in
                followLatest = true
                proxy.scrollTo("message-bottom", anchor: .bottom)
            }
            .onChange(of: store.messages.last?.content) { _ in
                if followLatest { proxy.scrollTo("message-bottom", anchor: .bottom) }
            }
            .onChange(of: store.messages.last?.reasoning) { _ in
                if followLatest { proxy.scrollTo("message-bottom", anchor: .bottom) }
            }
        }
    }
}

private struct MessageRow: View {
    let message: ChatMessage
    let streaming: Bool
    let status: String?
    @ObservedObject var settings: AppSettings
    let onCopy: (String) -> Void
    let onSelect: (String) -> Void
    let onShare: (String) -> Void
    let onSpeak: (String) -> Void
    let onRetry: () -> Void
    let onEdit: () -> Void
    let onFeedback: (MessageFeedback?) -> Void
    @State private var reasoningOpen = false
    @ScaledMetric(relativeTo: .body) private var dynamicScale = 1.0

    private func text(_ ru: String, _ en: String) -> String { settings.text(ru, en) }

    var body: some View {
        Group {
            if message.role == .user { userMessage }
            else { assistantMessage }
        }
        .contextMenu {
            Button { onCopy(message.content) } label: { Label(text("Копировать", "Copy"), systemImage: "square.on.square") }
            Button { onSelect(message.content) } label: { Label(text("Выбрать текст", "Select text"), systemImage: "text.cursor") }
            if message.role == .user {
                Button(action: onEdit) { Label(text("Редактировать", "Edit"), systemImage: "pencil") }
            } else {
                Button(action: onRetry) { Label(text("Повторить", "Retry"), systemImage: "arrow.clockwise") }
                Button { onFeedback(message.feedback == .like ? nil : .like) } label: {
                    Label(text("Нравится", "Like"), systemImage: "hand.thumbsup")
                }
                Button { onFeedback(message.feedback == .dislike ? nil : .dislike) } label: {
                    Label(text("Не нравится", "Dislike"), systemImage: "hand.thumbsdown")
                }
                Button { onSpeak(message.content) } label: { Label(text("Читать вслух", "Read aloud"), systemImage: "speaker.wave.2") }
            }
            Button { onShare(message.content) } label: { Label(text("Поделиться", "Share"), systemImage: "square.and.arrow.up") }
        }
    }

    private var userMessage: some View {
        HStack {
            Spacer(minLength: 35)
            VStack(alignment: .leading, spacing: 7) {
                attachmentLabels
                if !message.content.isEmpty {
                    Text(message.content)
                        .font(.system(size: 17 * settings.fontScale * dynamicScale))
                        .lineSpacing(4)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.horizontal, 15).padding(.vertical, 11)
            .background(HonorTheme.bubble, in: MessageBubble())
        }
        .padding(.top, 2)
        .accessibilityElement(children: .combine)
        .accessibilityHint(text("Ваше сообщение. Удерживайте для действий.", "Your message. Touch and hold for actions."))
    }

    private var assistantMessage: some View {
        VStack(alignment: .leading, spacing: 13) {
            if !message.reasoning.isEmpty || (streaming && message.content.isEmpty) {
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) { reasoningOpen.toggle() }
                } label: {
                    HStack(spacing: 7) {
                        if streaming && message.content.isEmpty { ProgressView().scaleEffect(0.7).tint(HonorTheme.secondary) }
                        Text(reasoningTitle).font(.system(size: 16 * settings.fontScale * dynamicScale, weight: .medium))
                        Image(systemName: reasoningOpen ? "chevron.down" : "chevron.right").font(.system(size: 12, weight: .medium))
                    }
                    .foregroundStyle(HonorTheme.secondary)
                    .frame(minHeight: 30, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(reasoningTitle)
                .accessibilityHint(text("Открыть или свернуть рассуждение", "Expand or collapse reasoning"))
                if reasoningOpen && !message.reasoning.isEmpty {
                    Text(message.reasoning)
                        .font(.system(size: 14 * settings.fontScale * dynamicScale))
                        .foregroundStyle(HonorTheme.secondary)
                        .lineSpacing(5)
                        .textSelection(.enabled)
                        .padding(.leading, 13)
                        .overlay(alignment: .leading) { Rectangle().fill(HonorTheme.divider).frame(width: 2) }
                }
            }
            if !message.content.isEmpty {
                MarkdownMessage(content: message.content, fontSize: 17 * settings.fontScale * dynamicScale, onCopy: onCopy)
            }
            if let error = message.error {
                Label(error, systemImage: "exclamationmark.circle")
                    .font(.system(size: 14 * settings.fontScale * dynamicScale))
                    .foregroundStyle(Color.orange)
                    .fixedSize(horizontal: false, vertical: true)
                Button(action: onRetry) { Label(text("Повторить запрос", "Try again"), systemImage: "arrow.clockwise") }
                    .font(.system(size: 14, weight: .medium)).tint(HonorTheme.accent)
                    .padding(.vertical, 4)
            }
            if message.isInterrupted {
                Text(text("Ответ остановлен", "Response stopped"))
                    .font(.system(size: 12)).foregroundStyle(HonorTheme.secondary)
            }
            if !message.sources.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text(text("Источники", "Sources")).font(.system(size: 12, weight: .semibold)).foregroundStyle(HonorTheme.secondary)
                    ForEach(Array(message.sources.enumerated()), id: \.element.id) { index, source in
                        Link(destination: source.url) {
                            HStack(alignment: .top, spacing: 7) {
                                Text("\(index + 1)").font(.system(size: 10, weight: .bold))
                                    .frame(width: 18, height: 18)
                                    .background(HonorTheme.accent.opacity(0.15), in: Circle())
                                Text(source.title).font(.system(size: 13)).lineLimit(2)
                            }
                        }.tint(HonorTheme.accent)
                    }
                }.padding(.vertical, 3)
            }
            if !streaming && !message.content.isEmpty {
                HStack(spacing: 0) {
                    HonorActionButton(symbol: "square.on.square", label: text("Копировать", "Copy")) { onCopy(message.content) }
                    HonorActionButton(symbol: message.feedback == .like ? "hand.thumbsup.fill" : "hand.thumbsup",
                                      label: text("Нравится", "Like"), selected: message.feedback == .like) {
                        onFeedback(message.feedback == .like ? nil : .like)
                    }
                    HonorActionButton(symbol: message.feedback == .dislike ? "hand.thumbsdown.fill" : "hand.thumbsdown",
                                      label: text("Не нравится", "Dislike"), selected: message.feedback == .dislike) {
                        onFeedback(message.feedback == .dislike ? nil : .dislike)
                    }
                    HonorActionButton(symbol: "speaker.wave.2", label: text("Читать вслух", "Read aloud")) { onSpeak(message.content) }
                    HonorActionButton(symbol: "square.and.arrow.up", label: text("Поделиться", "Share")) { onShare(message.content) }
                    Spacer(minLength: 0)
                    HonorActionButton(symbol: "arrow.clockwise", label: text("Повторить", "Retry"), action: onRetry)
                }
                .padding(.leading, -8)
                .padding(.trailing, -7)
                .padding(.top, -1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var attachmentLabels: some View {
        ForEach(message.attachments) { attachment in
            Label(attachment.name, systemImage: attachment.kind == .image ? "photo" : "doc.text")
                .font(.system(size: 12)).foregroundStyle(HonorTheme.secondary).lineLimit(2)
        }
    }

    private var reasoningTitle: String {
        if streaming && message.content.isEmpty { return status ?? text("Размышляет…", "Thinking…") }
        let seconds = max(message.reasoningSeconds, 1)
        let ending: String
        let last = seconds % 10, lastTwo = seconds % 100
        if last == 1 && lastTwo != 11 { ending = "секунду" }
        else if (2...4).contains(last) && !(12...14).contains(lastTwo) { ending = "секунды" }
        else { ending = "секунд" }
        return text("Размышлял \(seconds) \(ending)", "Thought for \(seconds)s")
    }
}

private struct MessageBubble: Shape {
    func path(in rect: CGRect) -> Path {
        let path = UIBezierPath(roundedRect: rect, byRoundingCorners: [.topLeft, .topRight, .bottomLeft],
                                cornerRadii: CGSize(width: 22, height: 22))
        return Path(path.cgPath)
    }
}

private struct HistoryDrawer: View {
    @EnvironmentObject private var store: ChatStore
    @EnvironmentObject private var settings: AppSettings
    let onClose: () -> Void
    let onSettings: () -> Void
    @State private var search = ""
    @State private var selecting = false
    @State private var selectedIDs: Set<UUID> = []
    @State private var renameTarget: UUID?
    @State private var renameTitle = ""
    @State private var deleteTargets: Set<UUID> = []
    @FocusState private var searchFocused: Bool
    @ScaledMetric(relativeTo: .body) private var dynamicScale = 1.0

    private func text(_ ru: String, _ en: String) -> String { settings.text(ru, en) }

    var body: some View {
        VStack(spacing: 0) {
            if selecting {
                HStack {
                    Text(text("Выберите чаты", "Select chats")).font(.system(size: 18, weight: .semibold))
                    Spacer()
                    HonorCircleButton(symbol: "xmark", label: text("Отмена", "Cancel"), diameter: 36) {
                        selecting = false; selectedIDs.removeAll()
                    }
                }.padding(.horizontal, 14).padding(.top, 7).padding(.bottom, 12)
            } else {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass").font(.system(size: 17))
                    TextField(text("Поиск в содержимом…", "Search conversations…"), text: $search)
                        .font(.system(size: 16)).focused($searchFocused).submitLabel(.search)
                        .accessibilityIdentifier("historySearch")
                    if !search.isEmpty {
                        Button { search = "" } label: { Image(systemName: "xmark.circle.fill") }
                            .accessibilityLabel(text("Очистить поиск", "Clear search"))
                    }
                }
                .foregroundStyle(HonorTheme.secondary)
                .padding(.horizontal, 13).frame(height: 44)
                .background(HonorTheme.surface.opacity(0.4), in: Capsule())
                .overlay(Capsule().stroke(HonorTheme.divider, lineWidth: 0.7))
                .padding(.horizontal, 14).padding(.top, 12).padding(.bottom, 13)
            }
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 17) {
                    if groups.isEmpty {
                        VStack(spacing: 12) {
                            Image(systemName: search.isEmpty ? "bubble.left.and.bubble.right" : "magnifyingglass")
                                .font(.system(size: 28))
                            Text(search.isEmpty ? text("Ваши чаты появятся здесь", "Your conversations appear here") : text("Ничего не найдено", "No results"))
                                .font(.system(size: 15)).multilineTextAlignment(.center)
                        }
                        .foregroundStyle(HonorTheme.secondary)
                        .frame(maxWidth: .infinity).padding(.top, 90)
                    }
                    ForEach(groups) { group in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(group.title).font(.system(size: 14, weight: .semibold))
                                Spacer()
                                if group.id == groups.first?.id && !selecting {
                                    Button { selecting = true } label: {
                                        Image(systemName: "checklist").frame(width: 40, height: 32)
                                    }.accessibilityLabel(text("Выбрать чаты", "Select chats"))
                                }
                            }
                            .foregroundStyle(HonorTheme.secondary)
                            .padding(.leading, 14).padding(.trailing, 6)
                            .frame(minHeight: 29)
                            ForEach(group.chats) { chat in historyRow(chat) }
                        }
                        if group.id == "pinned" {
                            Rectangle().fill(HonorTheme.divider.opacity(0.7)).frame(height: 0.5)
                                .padding(.horizontal, 14).padding(.vertical, 7)
                        }
                    }
                }
                .padding(.horizontal, 7).padding(.bottom, 10)
            }
            .scrollDismissesKeyboard(.interactively)
            if selecting {
                HStack(spacing: 0) {
                    Button {
                        store.togglePin(ids: selectedIDs)
                        selectedIDs.removeAll(); selecting = false
                    } label: { Label(text("Закрепить", "Pin"), systemImage: "pin") }
                    Spacer(minLength: 5)
                    Button(role: .destructive) { deleteTargets = selectedIDs } label: {
                        Label(text("Удалить", "Delete"), systemImage: "trash")
                    }
                }
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(selectedIDs.isEmpty ? HonorTheme.secondary : HonorTheme.foreground)
                .disabled(selectedIDs.isEmpty)
                .padding(.horizontal, 15).frame(height: 58)
            } else {
                Button(action: onSettings) {
                    HStack(spacing: 12) {
                        Image(systemName: "person.crop.circle.fill")
                            .font(.system(size: 29)).foregroundStyle(HonorTheme.secondary)
                        Text(settings.displayName.isEmpty ? "Honor PK" : settings.displayName)
                            .font(.system(size: 16, weight: .semibold)).lineLimit(1)
                        Spacer(minLength: 0)
                        Image(systemName: "ellipsis").font(.system(size: 20)).foregroundStyle(HonorTheme.secondary)
                    }
                    .padding(.horizontal, 15).frame(height: 60).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(text("Профиль и настройки", "Profile and settings"))
                .accessibilityIdentifier("sidebar.settings")
            }
        }
        .foregroundStyle(HonorTheme.foreground)
        .accessibilityAction(.escape, onClose)
        .alert(text("Переименовать чат", "Rename chat"),
               isPresented: Binding(get: { renameTarget != nil }, set: { if !$0 { renameTarget = nil } })) {
            TextField(text("Название", "Title"), text: $renameTitle)
            Button(text("Сохранить", "Save")) {
                if let id = renameTarget { store.renameChat(id: id, title: renameTitle) }
                renameTarget = nil
            }
            Button(text("Отмена", "Cancel"), role: .cancel) { renameTarget = nil }
        }
        .confirmationDialog(text("Удалить выбранные чаты?", "Delete selected chats?"),
                            isPresented: Binding(get: { !deleteTargets.isEmpty }, set: { if !$0 { deleteTargets.removeAll() } }),
                            titleVisibility: .visible) {
            Button(text("Удалить", "Delete"), role: .destructive) {
                store.deleteChats(ids: deleteTargets)
                selectedIDs.subtract(deleteTargets)
                deleteTargets.removeAll()
                if selectedIDs.isEmpty { selecting = false }
            }
            Button(text("Отмена", "Cancel"), role: .cancel) { deleteTargets.removeAll() }
        }
    }

    private func historyRow(_ chat: Conversation) -> some View {
        Button {
            if selecting {
                if selectedIDs.contains(chat.id) { selectedIDs.remove(chat.id) }
                else { selectedIDs.insert(chat.id) }
            } else {
                searchFocused = false
                store.selectChat(id: chat.id)
                onClose()
            }
        } label: {
            HStack(spacing: 10) {
                if selecting {
                    Image(systemName: selectedIDs.contains(chat.id) ? "checkmark.circle.fill" : "circle")
                        .font(.system(size: 20))
                        .foregroundStyle(selectedIDs.contains(chat.id) ? HonorTheme.accent : HonorTheme.secondary.opacity(0.5))
                }
                Text(chat.title).font(.system(size: 16 * settings.fontScale * dynamicScale, weight: .medium))
                    .lineLimit(1)
                Spacer(minLength: 0)
                if store.selectedConversationID == chat.id && !selecting {
                    Image(systemName: "ellipsis").font(.system(size: 16)).foregroundStyle(HonorTheme.secondary)
                }
            }
            .foregroundStyle(store.selectedConversationID == chat.id && !selecting ? HonorTheme.accent : HonorTheme.foreground)
            .padding(.horizontal, 13).frame(minHeight: 44)
            .background(store.selectedConversationID == chat.id && !selecting ? HonorTheme.accent.opacity(0.21) : Color.clear,
                        in: RoundedRectangle(cornerRadius: 15, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button { store.togglePin(ids: [chat.id]) } label: {
                Label(chat.pinned ? text("Открепить", "Unpin") : text("Закрепить", "Pin"), systemImage: chat.pinned ? "pin.slash" : "pin")
            }
            Button { renameTitle = chat.title; renameTarget = chat.id } label: {
                Label(text("Переименовать", "Rename"), systemImage: "pencil")
            }
            Button { selectedIDs = [chat.id]; selecting = true } label: {
                Label(text("Выбрать", "Select"), systemImage: "checkmark.circle")
            }
            Button(role: .destructive) { deleteTargets = [chat.id] } label: {
                Label(text("Удалить", "Delete"), systemImage: "trash")
            }
        }
        .accessibilityAddTraits(store.selectedConversationID == chat.id ? .isSelected : [])
    }

    private var groups: [HistoryGroup] {
        let query = search.trimmingCharacters(in: .whitespacesAndNewlines)
        let chats = store.conversations.filter { chat in
            query.isEmpty || chat.title.localizedCaseInsensitiveContains(query) || chat.messages.contains {
                $0.content.localizedCaseInsensitiveContains(query) || $0.reasoning.localizedCaseInsensitiveContains(query)
            }
        }.sorted { $0.updatedAt > $1.updatedAt }
        let calendar = Calendar.current
        let weekAgo = calendar.date(byAdding: .day, value: -7, to: Date()) ?? .distantPast
        let unpinned = chats.filter { !$0.pinned }
        return [
            HistoryGroup(id: "pinned", title: text("Закреплено", "Pinned"), chats: chats.filter(\.pinned)),
            HistoryGroup(id: "today", title: text("Сегодня", "Today"), chats: unpinned.filter { calendar.isDateInToday($0.updatedAt) }),
            HistoryGroup(id: "yesterday", title: text("Вчера", "Yesterday"), chats: unpinned.filter { calendar.isDateInYesterday($0.updatedAt) }),
            HistoryGroup(id: "week", title: text("7 дней", "Previous 7 days"), chats: unpinned.filter {
                !calendar.isDateInToday($0.updatedAt) && !calendar.isDateInYesterday($0.updatedAt) && $0.updatedAt >= weekAgo
            }),
            HistoryGroup(id: "older", title: text("Ранее", "Older"), chats: unpinned.filter { $0.updatedAt < weekAgo })
        ].filter { !$0.chats.isEmpty }
    }
}

private struct HistoryGroup: Identifiable {
    let id: String
    let title: String
    let chats: [Conversation]
}

private struct SelectedText: Identifiable {
    let id = UUID()
    let content: String
}

private struct SharedText: Identifiable {
    let id = UUID()
    let content: String
}

private struct SelectableTextSheet: View {
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    let content: String

    var body: some View {
        NavigationStack {
            SelectableTextView(content: content, fontSize: 17 * settings.fontScale)
                .background(HonorTheme.background)
                .navigationTitle(settings.text("Выбрать текст", "Select text"))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(settings.text("Готово", "Done")) { dismiss() }
                    }
                }
        }
        .tint(HonorTheme.accent)
    }
}

private struct SelectableTextView: UIViewRepresentable {
    let content: String
    let fontSize: Double
    func makeUIView(context: Context) -> UITextView {
        let view = UITextView()
        view.isEditable = false
        view.isSelectable = true
        view.backgroundColor = .clear
        view.textContainerInset = UIEdgeInsets(top: 20, left: 17, bottom: 25, right: 17)
        return view
    }
    func updateUIView(_ view: UITextView, context: Context) {
        view.text = content
        view.font = UIFontMetrics.default.scaledFont(for: .systemFont(ofSize: fontSize))
        view.adjustsFontForContentSizeCategory = true
        view.textColor = .label
    }
}

/// Inline Markdown and fenced code without a web view or remote renderer.
private struct MarkdownMessage: View {
    let content: String
    let fontSize: Double
    let onCopy: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                if block.code {
                    VStack(alignment: .leading, spacing: 0) {
                        HStack {
                            Text(block.language.isEmpty ? "code" : block.language).font(.system(size: 11, weight: .medium))
                            Spacer()
                            Button { onCopy(block.text) } label: {
                                Image(systemName: "square.on.square").frame(width: 44, height: 32)
                            }.accessibilityLabel("Copy code")
                        }
                        .foregroundStyle(HonorTheme.secondary)
                        .padding(.leading, 12).padding(.trailing, 2)
                        .background(HonorTheme.raised)
                        ScrollView(.horizontal, showsIndicators: false) {
                            Text(block.text)
                                .font(.system(size: fontSize * 0.82, design: .monospaced))
                                .textSelection(.enabled)
                                .padding(12)
                        }
                    }
                    .background(HonorTheme.surface)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(HonorTheme.divider, lineWidth: 0.6))
                } else {
                    Text(attributed(block.text))
                        .font(.system(size: fontSize))
                        .lineSpacing(5)
                        .tint(HonorTheme.accent)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    private func attributed(_ source: String) -> AttributedString {
        (try? AttributedString(markdown: source, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(source)
    }

    private var blocks: [MarkdownBlock] {
        let parts = content.components(separatedBy: "```")
        return parts.enumerated().compactMap { index, part in
            if index % 2 == 0 {
                let value = part.trimmingCharacters(in: .newlines)
                return value.isEmpty ? nil : MarkdownBlock(text: value, code: false, language: "")
            }
            let pieces = part.split(separator: "\n", maxSplits: 1, omittingEmptySubsequences: false)
            let language = pieces.first.map(String.init)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let body = pieces.count > 1 ? String(pieces[1]).trimmingCharacters(in: .newlines) : ""
            return MarkdownBlock(text: body, code: true, language: language)
        }
    }
}

private struct MarkdownBlock {
    let text: String
    let code: Bool
    let language: String
}
