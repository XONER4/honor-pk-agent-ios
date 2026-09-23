import SwiftUI
import UIKit
import QuickLook

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
    @State private var memoryDraft: SelectedText?
    @State private var menuMessage: ChatMessage?
    @State private var shareItem: SharedText?
    @State private var deviceError: String?
    @State private var toast: String?
    @State private var toastTask: Task<Void, Never>?
    @State private var speakingContent: String?
    @State private var attachmentGalleryOpen = false
    @State private var previewAttachment: MessageAttachment?
    @State private var sourceSheet: SourceSelection?
    @State private var deleteChatConfirmation = false
    @State private var findOpen = false
    /// Запрос на переход к сообщению по линиям навигации справа.
    @State private var scrollRequest: ScrollRequest?
    /// Сообщение, которое сейчас пишется, — подсвечивается на линиях навигации.
    @State private var streamingMessageID: UUID?
    @State private var findQuery = ""
    @State private var findIndex = 0
    @State private var voiceMode = false
    @State private var voiceHolding = false
    @State private var voiceCancelArmed = false
    @State private var voiceSessionID: UUID?
    @State private var voiceOriginalDraft = ""
    @FocusState private var composerFocused: Bool
    @FocusState private var findFocused: Bool

    private func text(_ ru: String, _ en: String) -> String { settings.text(ru, en) }

    var body: some View {
        GeometryReader { geometry in
            let drawerWidth = min(geometry.size.width * 0.79, 360)
            ZStack(alignment: .leading) {
                HonorTheme.sidebar.ignoresSafeArea()
                if drawerOpen {
                    HistoryDrawer(isOpen: drawerOpen, onClose: closeDrawer, onSettings: {
                        cancelVoice(); speech.stopSpeaking()
                        closeDrawer()
                        settingsOpen = true
                    })
                    .frame(width: drawerWidth)
                    .transition(.opacity)
                }

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
                    .accessibilityHidden(drawerOpen || menuMessage != nil)
            }
            .overlayPreferenceValue(MessageBoundsKey.self) { anchors in
                ZStack {
                if let message = menuMessage, let anchor = anchors[message.id] {
                    let frame = geometry[anchor]
                    let rowHeight = max(CGFloat(45), CGFloat(36 * settings.fontScale * dynamicScale + 8))
                    let contentHeight = CGFloat(MessageMenuAction.available(for: message).count) * rowHeight + 24
                    let menuHeight = min(contentHeight, max(44, geometry.size.height - 24))
                    let menuWidth: CGFloat = min(244, geometry.size.width - 28)
                    let top = min(max(12, message.role == .user ? frame.maxY + 16 : frame.minY + 72),
                                  max(12, geometry.size.height - menuHeight - 12))
                    ZStack(alignment: .topLeading) {
                        Button { animate { menuMessage = nil } } label: {
                            Color.black.opacity(0.04).contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(text("Закрыть меню", "Dismiss menu"))
                        .accessibilityIdentifier("message.menu.dismiss")
                        MessageActionPopup(message: message, settings: settings, rowHeight: rowHeight,
                                           menuHeight: menuHeight, scrolls: contentHeight > menuHeight) { action in
                            performMenuAction(action, message: message)
                        }
                        .frame(width: menuWidth)
                        .position(x: message.role == .user ? geometry.size.width / 2 : 9 + menuWidth / 2,
                                  y: top + menuHeight / 2)
                        .accessibilitySortPriority(1)
                    }
                    .frame(width: geometry.size.width, height: geometry.size.height)
                    .transition(.opacity.combined(with: .scale(scale: 0.97)))
                    .zIndex(5)
                }
                // Линии навигации по сообщениям справа: каждая линия — сообщение.
                // Тап (или долгое нажатие) плавно прокручивает чат к нему.
                if let request = scrollRequest, anchors.count > 1, menuMessage == nil {
                    MessageNavigationLines(messages: store.messages,
                                           anchors: anchors,
                                           geometry: geometry,
                                           streamingMessageID: streamingMessageID,
                                           settings: settings) { id in
                        scrollRequest = ScrollRequest(id: UUID(), messageID: id)
                    }
                    .zIndex(4)
                }
                }
                // A fading-out menu must stop intercepting the next touch immediately.
                .allowsHitTesting(menuMessage != nil)
            }
            .simultaneousGesture(DragGesture(minimumDistance: 24).onEnded { value in
                guard menuMessage == nil else { return }
                guard abs(value.translation.width) > abs(value.translation.height) * 1.4 else { return }
                if drawerOpen && value.translation.width < -45 { closeDrawer() }
                else if !drawerOpen && value.startLocation.x < 24 && value.translation.width > 60 { openDrawer() }
            })
        }
        .allowsHitTesting(!store.isLoadingHistory)
        .onChange(of: voiceMode) { (enabled: Bool) in
            // Голосовой ввод обязан полностью выключаться вместе с режимом:
            // запись, оверлей и таймер распознавания не должны оставаться висеть.
            if !enabled { cancelVoice() }
        }
        .onDisappear { cancelVoice() }
        .background(HonorTheme.background.ignoresSafeArea())
        .foregroundStyle(HonorTheme.foreground)
        .overlay {
            if store.isLoadingHistory {
                ZStack {
                    HonorTheme.background.ignoresSafeArea()
                    ProgressView(text("Загружаю чаты…", "Loading conversations…"))
                        .font(.system(size: 14)).tint(HonorTheme.accent)
                        .accessibilityIdentifier("chat.loading")
                }
            }
        }
        .overlay(alignment: .bottom) {
            if voiceHolding || speech.isFinalizingRecording {
                VoiceRecordingOverlay(speech: speech, cancelling: voiceCancelArmed, settings: settings)
                    .allowsHitTesting(false)
                    .transition(.opacity)
                    .ignoresSafeArea(edges: .bottom)
            }
        }
        .sheet(isPresented: $settingsOpen) {
            SettingsView().environmentObject(store).environmentObject(settings)
        }
        .sheet(item: $selectedText) { item in
            SelectableTextSheet(content: item.content)
                .environmentObject(settings)
        }
        .sheet(item: $memoryDraft) { item in
            MemoryEditorSheet(initialText: item.content, onSaved: {
                showToast(text("Сохранено в память Honer AI", "Saved to Honer AI memory"))
            })
            .environmentObject(store).environmentObject(settings)
        }
        .sheet(item: $shareItem) { item in ActivitySheet(items: [item.content]) }
        .sheet(isPresented: $attachmentGalleryOpen) {
            ChatAttachmentsSheet(attachments: store.messages.flatMap(\.attachments), settings: settings)
        }
        .sheet(item: $previewAttachment) { attachment in AttachmentPreviewSheet(attachment: attachment, settings: settings) }
        .sheet(item: $sourceSheet) { selection in
            SourceDetailsSheet(selection: selection, settings: settings)
                .presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
        }
        .confirmationDialog(text("Удалить этот чат?", "Delete this conversation?"), isPresented: $deleteChatConfirmation, titleVisibility: .visible) {
            Button(text("Удалить", "Delete"), role: .destructive) {
                composerFocused = false; cancelVoice()
                if let id = store.selectedConversationID { store.deleteChats(ids: [id]) }
            }.accessibilityIdentifier("chat.delete.confirm")
            Button(text("Отмена", "Cancel"), role: .cancel) {}.accessibilityIdentifier("chat.delete.cancel")
        }
        .alert(text("Не удалось выполнить действие", "Unable to complete action"),
               isPresented: Binding(get: { deviceError != nil }, set: {
                   if !$0 { deviceError = nil; speech.clearError() }
               })) {
            if speech.needsPermissionSettings {
                Button(text("Настройки iPhone", "iPhone Settings")) {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    deviceError = nil; speech.clearError()
                }.accessibilityIdentifier("device.openSettings")
            }
            Button("OK", role: .cancel) { deviceError = nil; speech.clearError() }
                .accessibilityIdentifier("device.error.dismiss")
        } message: { Text(deviceError ?? "") }
        .onChange(of: speech.errorMessage) { error in
            if let error { deviceError = error }
        }
        .onChange(of: speech.isSpeaking) { speaking in
            if !speaking { speakingContent = nil }
        }
        .onChange(of: store.selectedConversationID) { _ in
            cancelVoice(); speech.stopSpeaking()
            menuMessage = nil
            findOpen = false; findQuery = ""; findIndex = 0
        }
        .onChange(of: store.isGenerating) { generating in
            if !generating, settings.autoRead,
               let last = store.messages.last, last.role == .assistant,
               !last.content.isEmpty, last.error == nil, !last.isInterrupted {
                speak(last.content)
            }
        }
        .onDisappear { cancelVoice(); speech.stopSpeaking(); toastTask?.cancel() }
    }

    private var mainScreen: some View {
        let messages = store.messages
        let matches = matchingMessageIDs(messages)
        let selectedMatch = matches.isEmpty ? nil : matches[min(findIndex, matches.count - 1)]
        return VStack(spacing: 0) {
            header
            branchLineage
            if findOpen { findBar(matches: matches) }
            if messages.isEmpty {
                welcome
            } else {
                MessageTimeline(store: store, settings: settings,
                                findQuery: findOpen ? findQuery : "", selectedMatch: selectedMatch,
                                scrollRequest: $scrollRequest,
                                onCopy: copy, onSelect: { selectedText = SelectedText(content: $0) },
                                onShare: { shareItem = SharedText(content: $0) }, onSpeak: speak,
                                onAttachment: { previewAttachment = $0 }, onSources: { sourceSheet = $0 },
                                onMenu: { message in
                                    composerFocused = false
                                    animate { menuMessage = message }
                                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                })
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
                .accessibilityIdentifier("chat.autoread")
                .accessibilityValue(text(settings.autoRead ? "Включено" : "Выключено", settings.autoRead ? "On" : "Off"))
                Button {
                    cancelVoice(); speech.stopSpeaking()
                    store.newChat(); attachmentsOpen = false
                } label: {
                    NewConversationSymbol()
                        .frame(width: 25, height: 25)
                        .frame(width: 44, height: 42)
                }
                .accessibilityLabel(text("Новый чат", "New chat"))
                .accessibilityIdentifier("chat.new")
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 1)
            .background(HonorTheme.surface.opacity(0.65), in: Capsule())
            .overlay(Capsule().stroke(HonorTheme.divider, lineWidth: 0.7))
            if store.selectedConversationID != nil {
                chatToolsMenu
            }
        }
        .padding(.horizontal, 14)
        .padding(.top, 6)
        .padding(.bottom, 12)
    }

    private var chatToolsMenu: some View {
        Menu {
            Button {
                guard let chat = store.selectedConversation else { return }
                let transcript = chat.messages.map { message in
                    let author = message.role == .user ? text("Вы", "You") : "Honer AI"
                    var block = author + ":\n" + message.content
                    if !message.attachments.isEmpty { block += "\n" + message.attachments.map(\.name).joined(separator: ", ") }
                    if !message.sources.isEmpty {
                        let citations = message.sources.enumerated().map { "[\($0.offset + 1)] \($0.element.title): \($0.element.url.absoluteString)" }
                        block += "\n\n" + citations.joined(separator: "\n")
                    }
                    return block
                }
                shareItem = SharedText(content: ([chat.title, "Honer AI"] + transcript).joined(separator: "\n\n"))
            } label: { Label(text("Поделиться чатом", "Share conversation"), systemImage: "square.and.arrow.up") }
                .accessibilityIdentifier("chat.tools.share")
            Button {
                if let id = store.selectedConversationID { store.togglePin(ids: [id]) }
            } label: {
                Label(store.selectedConversation?.pinned == true ? text("Открепить", "Unpin") : text("Закрепить", "Pin"),
                      systemImage: store.selectedConversation?.pinned == true ? "pin.slash" : "pin")
            }.accessibilityIdentifier("chat.tools.pin")
            Button { composerFocused = false; attachmentGalleryOpen = true } label: {
                Label(text("Загруженные файлы", "Uploaded files"), systemImage: "paperclip")
            }.accessibilityIdentifier("chat.tools.attachments")
            Button {
                composerFocused = false
                animate { findOpen = true }
                findFocused = true
            } label: { Label(text("Найти в чате", "Find in conversation"), systemImage: "magnifyingglass") }
                .accessibilityIdentifier("chat.tools.find")
            Divider()
            Button {
                if let id = store.selectedConversationID {
                    composerFocused = false
                    cancelVoice(); store.archiveChat(id: id)
                    showToast(text("Чат перемещён в архив", "Conversation archived"))
                }
            } label: { Label(text("В архив", "Archive"), systemImage: "archivebox") }
                .accessibilityIdentifier("chat.tools.archive")
            Button(role: .destructive) { deleteChatConfirmation = true } label: {
                Label(text("Удалить", "Delete"), systemImage: "trash")
            }.accessibilityIdentifier("chat.tools.delete")
        } label: {
            Image(systemName: "ellipsis").font(.system(size: 20, weight: .medium))
                .frame(width: 44, height: 44).contentShape(Rectangle())
        }
        .accessibilityLabel(text("Действия с чатом", "Conversation actions"))
        .accessibilityIdentifier("chat.tools")
    }

    private func matchingMessageIDs(_ messages: [ChatMessage]) -> [UUID] {
        guard findOpen, !findQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return [] }
        return messages.filter {
            $0.content.localizedCaseInsensitiveContains(findQuery) || $0.reasoning.localizedCaseInsensitiveContains(findQuery)
        }.map(\.id)
    }

    private func findBar(matches: [UUID]) -> some View {
        HStack(spacing: 5) {
            Image(systemName: "magnifyingglass").foregroundStyle(HonorTheme.secondary)
            TextField(text("Найти в чате", "Find in conversation"), text: $findQuery)
                .font(.system(size: 15)).focused($findFocused).submitLabel(.search)
                .onChange(of: findQuery) { _ in findIndex = 0 }
                .onSubmit { findFocused = false }
                .accessibilityIdentifier("chat.find.field")
            Text(matches.isEmpty ? "0 / 0" : "\(min(findIndex + 1, matches.count)) / \(matches.count)")
                .font(.system(size: 12).monospacedDigit()).foregroundStyle(HonorTheme.secondary)
                .accessibilityLabel(text("Найденные сообщения", "Matching messages"))
                .accessibilityValue(matches.isEmpty ? "0 / 0" : "\(min(findIndex + 1, matches.count)) / \(matches.count)")
                .accessibilityIdentifier("chat.find.count")
            Button {
                guard !matches.isEmpty else { return }
                findFocused = false; findIndex = (findIndex - 1 + matches.count) % matches.count
            } label: { Image(systemName: "chevron.up").frame(width: 30, height: 44) }
                .disabled(matches.isEmpty).accessibilityLabel(text("Предыдущее совпадение", "Previous match"))
                .accessibilityIdentifier("chat.find.previous")
            Button {
                guard !matches.isEmpty else { return }
                findFocused = false; findIndex = (findIndex + 1) % matches.count
            } label: { Image(systemName: "chevron.down").frame(width: 30, height: 44) }
                .disabled(matches.isEmpty).accessibilityLabel(text("Следующее совпадение", "Next match"))
                .accessibilityIdentifier("chat.find.next")
            Button {
                findFocused = false; findQuery = ""; animate { findOpen = false }
            } label: { Image(systemName: "xmark").frame(width: 30, height: 44) }
                .accessibilityLabel(text("Закрыть поиск", "Close find"))
                .accessibilityIdentifier("chat.find.close")
        }
        .buttonStyle(.plain).padding(.leading, 12).padding(.trailing, 3)
        .background(HonorTheme.surface, in: RoundedRectangle(cornerRadius: 14))
        .padding(.horizontal, 14).padding(.bottom, 6)
        .transition(.move(edge: .top).combined(with: .opacity))
    }

    @ViewBuilder private var branchLineage: some View {
        if let parentID = store.selectedConversation?.parentConversationID {
            HStack(spacing: 7) {
                Image(systemName: "arrow.triangle.branch")
                if let parent = store.conversations.first(where: { $0.id == parentID }) {
                    Button { store.selectChat(id: parentID) } label: {
                        HStack(spacing: 4) {
                            Text(text("Ветка: ", "Branch: ") + parent.title).lineLimit(1)
                            Image(systemName: "arrow.up.left").font(.system(size: 10))
                        }.frame(minHeight: 32)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(text("Вернуться к исходному чату: ", "Return to original conversation: ") + parent.title)
                    .accessibilityIdentifier("branch.back")
                } else {
                    Text(text("Ветка разговора", "Conversation branch"))
                }
                Spacer(minLength: 0)
            }
            .font(.system(size: 12 * settings.fontScale))
            .foregroundStyle(HonorTheme.secondary)
            .padding(.horizontal, 22).padding(.bottom, 3)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("branch.banner")
        }
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
                        .accessibilityIdentifier("chat.error.dismiss")
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
                        .accessibilityIdentifier("composer.edit.cancel")
                }.foregroundStyle(HonorTheme.accent)
            }
            if !store.attachments.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(store.attachments) { attachment in
                            HStack(spacing: 5) {
                                Image(systemName: attachmentSymbol(attachment))
                                Text(attachment.name).lineLimit(1).frame(maxWidth: 160)
                                Button { store.attachments.removeAll { $0.id == attachment.id } } label: {
                                    Image(systemName: "xmark.circle.fill").frame(width: 30, height: 32)
                                }.accessibilityLabel(text("Удалить вложение ", "Remove attachment ") + attachment.name)
                                    .accessibilityIdentifier("attachment.remove." + attachment.id.uuidString)
                            }
                            .font(.system(size: 12))
                            .padding(.leading, 10).padding(.trailing, 2)
                            .background(HonorTheme.raised, in: RoundedRectangle(cornerRadius: 12))
                        }
                    }
                }
            }
            if voiceMode {
                Text(text("Удерживайте для голосового ввода", "Hold to speak"))
                    .font(.system(size: 17, weight: .semibold))
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .contentShape(Rectangle())
                    .gesture(DragGesture(minimumDistance: 0, coordinateSpace: .global)
                        .onChanged { value in
                            if !voiceHolding { beginVoice() }
                            let cancelled = value.translation.height < -70
                            if cancelled != voiceCancelArmed {
                                voiceCancelArmed = cancelled
                                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            }
                        }
                        .onEnded { _ in finishVoice() })
                    .accessibilityIdentifier("chat.voice.hold")
                    .accessibilityHint(text("Удерживайте и отпустите для отправки. Сдвиньте вверх для отмены.", "Hold and release to send. Slide up to cancel."))
                    .accessibilityAddTraits(.isButton)
                    .accessibilityAction {
                        if voiceHolding { finishVoice() } else { beginVoice() }
                    }
                    .accessibilityAction(named: Text(text("Отменить запись", "Cancel recording"))) { cancelVoice() }
                Rectangle().fill(HonorTheme.divider).frame(height: 0.5)
            } else {
              HStack(alignment: .top, spacing: 2) {
                TextField(text("Напишите сообщение…", "Message Honer AI…"), text: $store.draft, axis: .vertical)
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
                if store.canSend { voiceButton }
              }
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
                    cancelVoice()
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
                        .accessibilityIdentifier("chat.stop")
                } else if store.canSend && !voiceMode {
                    Button { send() } label: {
                        Image(systemName: "arrow.up").font(.system(size: 18, weight: .semibold))
                            .frame(width: 32, height: 32)
                            .background(HonorTheme.accent, in: Circle())
                            .foregroundStyle(.white)
                            .frame(width: 40, height: 44)
                    }
                    .accessibilityLabel(text("Отправить", "Send"))
                    .accessibilityIdentifier("chat.send")
                } else {
                    voiceButton
                }
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 10).padding(.top, 10).padding(.bottom, 5)
        .background(HonorTheme.surface, in: RoundedRectangle(cornerRadius: 27, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 27, style: .continuous).stroke(HonorTheme.divider, lineWidth: 0.7))
    }

    private var voiceButton: some View {
        Button {
            let wasVoice = voiceMode
            cancelVoice()
            animate { voiceMode.toggle() }
            composerFocused = !voiceMode
            // Возврат к клавиатуре обязан полностью убрать голосовой ввод:
            // раньше запись и оверлей оставались висеть до ручного отключения.
            if wasVoice { cancelVoice() }
        } label: {
            Group {
                if voiceMode {
                    Image(systemName: "keyboard").font(.system(size: 13, weight: .medium))
                        .frame(width: 25, height: 25)
                        .overlay(Circle().stroke(lineWidth: 1.6))
                } else {
                    Image(systemName: "waveform.circle").font(.system(size: 26))
                }
            }.foregroundStyle(HonorTheme.foreground).frame(width: 40, height: 44)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(text(voiceMode ? "Клавиатура" : "Голосовой ввод", voiceMode ? "Keyboard" : "Voice input"))
        .accessibilityIdentifier("chat.voice")
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

    private func send(inputKind: MessageInputKind = .text) {
        cancelVoice(); speech.stopSpeaking()
        store.systemInstruction = settings.customInstructions
        store.send(inputKind: inputKind)
        composerFocused = false
        animate { attachmentsOpen = false }
    }

    private func performMenuAction(_ action: MessageMenuAction, message: ChatMessage) {
        animate { menuMessage = nil }
        switch action {
        case .copy: copy(message.content)
        case .select: selectedText = SelectedText(content: message.content)
        case .edit:
            store.edit(messageID: message.id)
            composerFocused = true
        case .share: shareItem = SharedText(content: message.content)
        case .retry: store.regenerate(messageID: message.id)
        case .like: store.setFeedback(messageID: message.id, feedback: message.feedback == .like ? nil : .like)
        case .dislike: store.setFeedback(messageID: message.id, feedback: message.feedback == .dislike ? nil : .dislike)
        case .speak: speak(message.content)
        case .fork:
            cancelVoice(); speech.stopSpeaking()
            if store.forkConversation(at: message.id) != nil {
                showToast(text("Новая ветка создана", "New branch created"))
                composerFocused = true
            }
        case .remember: memoryDraft = SelectedText(content: message.content)
        }
    }

    private func beginVoice() {
        guard !voiceHolding, !speech.isFinalizingRecording, !store.isGenerating else { return }
        let session = UUID()
        voiceSessionID = session
        voiceOriginalDraft = store.draft
        voiceCancelArmed = false
        animate { voiceHolding = true }
        composerFocused = false
        speech.stopSpeaking()
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        Task { @MainActor in
            await speech.startRecording(language: settings.speechLanguage)
            guard voiceSessionID == session else { return }
            if !speech.isRecording { voiceHolding = false; voiceSessionID = nil }
        }
    }

    private func finishVoice() {
        guard let session = voiceSessionID else { return }
        if voiceCancelArmed || speech.isPreparingRecording { cancelVoice(); return }
        animate { voiceHolding = false }
        Task { @MainActor in
            let transcript = await speech.finishRecording().trimmingCharacters(in: .whitespacesAndNewlines)
            guard voiceSessionID == session else { return }
            voiceSessionID = nil
            voiceCancelArmed = false
            guard !transcript.isEmpty else { return }
            store.draft = voiceOriginalDraft.isEmpty ? transcript : voiceOriginalDraft + " " + transcript
            voiceMode = false
            send(inputKind: .voice)
        }
    }

    private func cancelVoice() {
        voiceSessionID = nil
        voiceHolding = false
        voiceCancelArmed = false
        speech.cancelRecording()
    }

    private func speak(_ content: String) {
        if speech.isSpeaking && speakingContent == content {
            speech.stopSpeaking()
            speakingContent = nil
        } else {
            speech.speak(content, voiceIdentifier: settings.voiceIdentifier, language: "ru-RU",
                         rate: settings.voiceRate)
            speakingContent = content
        }
    }

    private func copy(_ content: String) {
        UIPasteboard.general.string = content
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        showToast(text("Скопировано", "Copied"))
    }

    private func showToast(_ content: String) {
        toastTask?.cancel()
        animate { toast = content }
        toastTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_800_000_000)
            guard !Task.isCancelled else { return }
            animate { toast = nil }
        }
    }

    private func openDrawer() {
        composerFocused = false
        cancelVoice()
        menuMessage = nil
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
    let findQuery: String
    let selectedMatch: UUID?
    @Binding var scrollRequest: ScrollRequest?
    let onCopy: (String) -> Void
    let onSelect: (String) -> Void
    let onShare: (String) -> Void
    let onSpeak: (String) -> Void
    let onAttachment: (MessageAttachment) -> Void
    let onSources: (SourceSelection) -> Void
    let onMenu: (ChatMessage) -> Void
    @State private var followLatest = true
    @State private var pendingScroll: Task<Void, Never>?
    /// Ответ, который сейчас пишется, — для линий навигации справа.
    @State private var streamingMessageID: UUID?
    @ScaledMetric(relativeTo: .body) private var dynamicScale = 1.0

    var body: some View {
        ScrollViewReader { (proxy: ScrollViewProxy) in
            messageScroll(proxy: proxy)
        }
    }

    // Разбито на несколько функций: в одном выражении компилятор не успевал
    // вывести типы (ошибка «unable to type-check this expression in reasonable time»).
    @ViewBuilder
    private func messageScroll(proxy: ScrollViewProxy) -> some View {
        let messages = store.messages
        let tail = messages.last
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 25) {
                disclaimer
                messageList(messages: messages, tail: tail)
                Color.clear.frame(height: 8).id("message-bottom")
            }
            .padding(.horizontal, 22)
            .padding(.bottom, 8)
        }
        .scrollDismissesKeyboard(.interactively)
        .simultaneousGesture(DragGesture(minimumDistance: 15).onChanged { (_: DragGesture.Value) in
            followLatest = false
            pendingScroll?.cancel()
            pendingScroll = nil
        })
        .overlay(alignment: .bottomTrailing) { jumpToLatestButton(proxy: proxy) }
        .onAppear {
            proxy.scrollTo("message-bottom", anchor: .bottom)
            streamingMessageID = store.isGenerating ? tail?.id : nil
        }
        .onChange(of: messages.count) { (_: Int) in
            if messages.last?.role == .user || messages.dropLast().last?.role == .user { followLatest = true }
            if followLatest && findQuery.isEmpty { proxy.scrollTo("message-bottom", anchor: .bottom) }
        }
        .onChange(of: store.selectedConversationID) { (_: UUID?) in
            pendingScroll?.cancel()
            pendingScroll = nil
            followLatest = true
            streamingMessageID = nil
            proxy.scrollTo("message-bottom", anchor: .bottom)
        }
        .onChange(of: store.isGenerating) { (generating: Bool) in
            handleGenerationChange(generating, messages: messages, proxy: proxy)
        }
        .onChange(of: selectedMatch) { (id: UUID?) in
            guard let id else { return }
            followLatest = false
            withAnimation(.easeOut(duration: 0.18)) { proxy.scrollTo(id.uuidString, anchor: .center) }
        }
        .onChange(of: scrollRequest) { (request: ScrollRequest?) in
            guard let request else { return }
            scrollRequest = nil
            followLatest = false
            pendingScroll?.cancel()
            pendingScroll = nil
            withAnimation(.easeInOut(duration: 0.5)) {
                proxy.scrollTo(request.messageID.uuidString, anchor: .center)
            }
        }
        .onDisappear {
            pendingScroll?.cancel()
            pendingScroll = nil
        }
    }

    private var disclaimer: some View {
        Text(settings.text("Сгенерированный ИИ ответ, только для справки.",
                           "AI-generated answers are for reference."))
            .font(.system(size: 13 * settings.fontScale * dynamicScale, weight: .medium))
            .foregroundStyle(HonorTheme.secondary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 28)
            .padding(.top, 23)
            .padding(.bottom, 3)
    }

    @ViewBuilder
    private func messageList(messages: [ChatMessage], tail: ChatMessage?) -> some View {
        ForEach(messages) { (message: ChatMessage) in
            MessageRow(message: message,
                       streaming: store.isGenerating && message.id == tail?.id,
                       status: store.generationStatus,
                       settings: settings,
                       findQuery: findQuery,
                       selectedMatch: selectedMatch == message.id,
                       onCopy: onCopy,
                       onSelect: onSelect,
                       onShare: onShare,
                       onSpeak: onSpeak,
                       onAttachment: onAttachment,
                       onSources: onSources,
                       onRetry: { store.regenerate(messageID: message.id) },
                       onEdit: { store.edit(messageID: message.id) },
                       onFeedback: { (value: MessageFeedback?) in
                           store.setFeedback(messageID: message.id, feedback: value)
                       },
                       onMenu: onMenu)
                .equatable()
                .id(message.id.uuidString)
        }
    }

    @ViewBuilder
    private func jumpToLatestButton(proxy: ScrollViewProxy) -> some View {
        if !followLatest {
            Button {
                followLatest = true
                withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo("message-bottom", anchor: .bottom) }
            } label: {
                Image(systemName: "arrow.down")
                    .font(.system(size: 15, weight: .semibold))
                    .frame(width: 38, height: 38)
                    .background(.ultraThinMaterial, in: Circle())
                    .overlay(Circle().stroke(HonorTheme.divider))
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel(settings.text("К последнему сообщению", "Jump to latest message"))
            .accessibilityIdentifier("chat.scroll.latest")
            .padding(.trailing, 14)
            .padding(.bottom, 6)
        }
    }

    /// Начало нового ответа: один мягкий переход к началу этого ответа.
    /// Раньше прокрутка запускалась на каждом токене (22 раза в секунду) и спорила
    /// с ростом текста — отсюда были рывки и «прыжки» экрана.
    private func handleGenerationChange(_ generating: Bool, messages: [ChatMessage], proxy: ScrollViewProxy) {
        guard generating, let id = messages.last?.id else {
            if !generating { streamingMessageID = nil }
            return
        }
        streamingMessageID = id
        guard followLatest, findQuery.isEmpty else { return }
        pendingScroll?.cancel()
        pendingScroll = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 90_000_000)
            guard !Task.isCancelled, followLatest, findQuery.isEmpty else { return }
            withAnimation(.easeOut(duration: 0.28)) {
                proxy.scrollTo(id.uuidString, anchor: .bottom)
            }
            pendingScroll = nil
        }
    }
}

private struct MessageRow: View, Equatable {
    let message: ChatMessage
    let streaming: Bool
    let status: String?
    @ObservedObject var settings: AppSettings
    let findQuery: String
    let selectedMatch: Bool
    let onCopy: (String) -> Void
    let onSelect: (String) -> Void
    let onShare: (String) -> Void
    let onSpeak: (String) -> Void
    let onAttachment: (MessageAttachment) -> Void
    let onSources: (SourceSelection) -> Void
    let onRetry: () -> Void
    let onEdit: () -> Void
    let onFeedback: (MessageFeedback?) -> Void
    let onMenu: (ChatMessage) -> Void
    @State private var reasoningOpen = false
    @ScaledMetric(relativeTo: .body) private var dynamicScale = 1.0

    private func text(_ ru: String, _ en: String) -> String { settings.text(ru, en) }

    static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.message == rhs.message && lhs.streaming == rhs.streaming && lhs.status == rhs.status &&
        lhs.findQuery == rhs.findQuery && lhs.selectedMatch == rhs.selectedMatch
    }

    var body: some View {
        Group {
            if message.role == .user { userMessage }
            else { assistantMessage }
        }
        .anchorPreference(key: MessageBoundsKey.self, value: .bounds) { [message.id: $0] }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("message." + message.role.rawValue + "." + message.id.uuidString)
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(selectedMatch ? HonorTheme.accent.opacity(0.5) : .clear, lineWidth: 1))
        .onLongPressGesture(minimumDuration: 0.45) { onMenu(message) }
        .accessibilityAction(named: Text(text("Действия с сообщением", "Message actions"))) { onMenu(message) }
        .onChange(of: findQuery) { query in
            if !query.isEmpty && message.reasoning.localizedCaseInsensitiveContains(query) { reasoningOpen = true }
        }
    }

    private var userMessage: some View {
        HStack {
            Spacer(minLength: 35)
            VStack(alignment: .leading, spacing: 7) {
                attachmentLabels
                if !message.content.isEmpty {
                    Text(highlighted(AttributedString(message.content), query: findQuery))
                        .font(.system(size: 17 * settings.fontScale * dynamicScale))
                        .lineSpacing(4)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.horizontal, 15).padding(.vertical, 11)
            .background(HonorTheme.bubble, in: MessageBubble())
            if message.inputKind == .voice {
                // Пометка, что сообщение наговорено голосом, а не набрано (пункт 38).
                Label(text("Голосом", "By voice"), systemImage: "mic.fill")
                    .font(.system(size: 11))
                    .foregroundStyle(HonorTheme.secondary)
                    .padding(.trailing, 4)
                    .accessibilityIdentifier("message.voice." + message.id.uuidString)
            }
        }
        .padding(.top, 2)
        .accessibilityElement(children: message.attachments.isEmpty ? .combine : .contain)
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
                .accessibilityIdentifier("message.reasoning." + message.id.uuidString)
                .accessibilityValue(text(reasoningOpen ? "Развёрнуто" : "Свёрнуто", reasoningOpen ? "Expanded" : "Collapsed"))
                if reasoningOpen && !message.reasoning.isEmpty {
                    if message.reasoningWasTranslated == true {
                        Text(text("Переведено на русский", "Translated into Russian"))
                            .font(.system(size: 11)).foregroundStyle(HonorTheme.secondary)
                            .accessibilityIdentifier("message.reasoning.translated." + message.id.uuidString)
                    }
                    StreamText(target: message.reasoning, streaming: streaming, baseRate: 80) { visible in
                        BlockMarkdownView(content: visible, fontSize: 14 * settings.fontScale * dynamicScale,
                                          sources: [], findQuery: findQuery)
                            .foregroundStyle(HonorTheme.secondary)
                            .padding(.leading, 13)
                            .overlay(alignment: .leading) { Rectangle().fill(HonorTheme.divider).frame(width: 2) }
                    }
                        .accessibilityIdentifier("message.reasoning.text." + message.id.uuidString)
                }
                if reasoningOpen && !message.sources.isEmpty { sourceProgress }
            }
            if !message.content.isEmpty {
                // Плавный посимвольный вывод: текст растёт по кадрам, а не рывками
                // на каждом обновлении стрима.
                StreamText(target: message.content, streaming: streaming, baseRate: 58) { visible in
                    BlockMarkdownView(content: visible, fontSize: 17 * settings.fontScale * dynamicScale,
                                      sources: message.sources, findQuery: findQuery)
                }
                .accessibilityElement(children: .contain)
                .accessibilityLabel(message.content)
                .accessibilityIdentifier("message.content." + message.id.uuidString)
            }
            if let error = message.error {
                Label(error, systemImage: "exclamationmark.circle")
                    .font(.system(size: 14 * settings.fontScale * dynamicScale))
                    .foregroundStyle(Color.orange)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("message.error." + message.id.uuidString)
                Button(action: onRetry) { Label(text("Повторить запрос", "Try again"), systemImage: "arrow.clockwise") }
                    .font(.system(size: 14, weight: .medium)).tint(HonorTheme.accent)
                    .padding(.vertical, 4)
                    .accessibilityIdentifier("message.action.retry." + message.id.uuidString)
            }
            if message.isInterrupted {
                Text(text("Ответ остановлен", "Response stopped"))
                    .font(.system(size: 12)).foregroundStyle(HonorTheme.secondary)
                    .accessibilityIdentifier("message.stopped." + message.id.uuidString)
            }
            if !message.sources.isEmpty {
                Button { onSources(SourceSelection(sources: message.sources, readOnly: false)) } label: {
                    HStack(spacing: 8) {
                        SourceSiteMarks(sources: message.sources)
                        Text(text("\(message.sources.count) веб-страниц", "\(message.sources.count) web pages"))
                    }.font(.system(size: 13, weight: .medium))
                    .padding(.horizontal, 11).frame(minHeight: 34)
                    .overlay(Capsule().stroke(HonorTheme.divider, lineWidth: 0.7))
                }.buttonStyle(.plain).foregroundStyle(HonorTheme.secondary)
                    .accessibilityIdentifier("message.sources." + message.id.uuidString)
            }
            if !streaming && !message.content.isEmpty {
                HStack(spacing: 0) {
                    HonorActionButton(symbol: "square.on.square", label: text("Копировать", "Copy")) { onCopy(message.content) }
                        .accessibilityIdentifier("message.action.copy." + message.id.uuidString)
                    HonorActionButton(symbol: message.feedback == .like ? "hand.thumbsup.fill" : "hand.thumbsup",
                                      label: text("Нравится", "Like"), selected: message.feedback == .like) {
                        onFeedback(message.feedback == .like ? nil : .like)
                    }
                    .accessibilityIdentifier("message.action.like." + message.id.uuidString)
                    HonorActionButton(symbol: message.feedback == .dislike ? "hand.thumbsdown.fill" : "hand.thumbsdown",
                                      label: text("Не нравится", "Dislike"), selected: message.feedback == .dislike) {
                        onFeedback(message.feedback == .dislike ? nil : .dislike)
                    }
                    .accessibilityIdentifier("message.action.dislike." + message.id.uuidString)
                    HonorActionButton(symbol: "speaker.wave.2", label: text("Читать вслух", "Read aloud")) { onSpeak(message.content) }
                        .accessibilityIdentifier("message.action.speak." + message.id.uuidString)
                    HonorActionButton(symbol: "square.and.arrow.up", label: text("Поделиться", "Share")) { onShare(message.content) }
                        .accessibilityIdentifier("message.action.share." + message.id.uuidString)
                    Spacer(minLength: 0)
                    if message.error == nil {
                        HonorActionButton(symbol: "arrow.clockwise", label: text("Повторить", "Retry"), action: onRetry)
                            .accessibilityIdentifier("message.action.retry." + message.id.uuidString)
                    }
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
            Button { onAttachment(attachment) } label: {
                Label(attachment.name, systemImage: attachmentSymbol(attachment))
                    .font(.system(size: 12)).foregroundStyle(HonorTheme.accent).lineLimit(2).frame(minHeight: 32)
            }.buttonStyle(.plain).accessibilityIdentifier("message.attachment." + attachment.id.uuidString)
        }
    }

    private var sourceProgress: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button { onSources(SourceSelection(sources: message.sources, readOnly: false)) } label: {
                HStack(spacing: 7) {
                    Image(systemName: "magnifyingglass")
                    Text(text("Найдено \(message.sources.count) веб-страниц", "Found \(message.sources.count) web pages"))
                    SourceSiteMarks(sources: message.sources)
                }.frame(minHeight: 36)
            }.accessibilityIdentifier("message.sources.found." + message.id.uuidString)
            Button { onSources(SourceSelection(sources: message.sources, readOnly: true)) } label: {
                HStack(spacing: 7) {
                    Image(systemName: "doc.text")
                    Text(text("Прочитано \(message.sources.filter { $0.content != nil }.count) страниц", "Read \(message.sources.filter { $0.content != nil }.count) pages"))
                }.frame(minHeight: 36)
            }.accessibilityIdentifier("message.sources.read." + message.id.uuidString)
        }.font(.system(size: 14)).foregroundStyle(HonorTheme.secondary).buttonStyle(.plain)
    }

    private var reasoningTitle: String {
        if streaming && message.content.isEmpty { return status ?? text("Размышляет…", "Thinking…") }
        if message.reasoningWasTranslated == true { return text("Описание рассуждения · перевод", "Reasoning description · translation") }
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

private struct MessageBoundsKey: PreferenceKey {
    static var defaultValue: [UUID: Anchor<CGRect>] = [:]
    static func reduce(value: inout [UUID: Anchor<CGRect>], nextValue: () -> [UUID: Anchor<CGRect>]) {
        value.merge(nextValue(), uniquingKeysWith: { _, new in new })
    }
}

private enum MessageMenuAction: String {
    case copy, select, edit, share, retry, like, dislike, speak, fork, remember

    static func available(for message: ChatMessage) -> [MessageMenuAction] {
        message.role == .user
            ? [.copy, .select, .edit, .fork, .remember, .share]
            : [.copy, .select, .retry, .fork, .remember, .like, .dislike, .speak, .share]
    }

    var symbol: String {
        switch self {
        case .copy: return "square.on.square"
        case .select: return "text.cursor"
        case .edit: return "pencil"
        case .share: return "arrowshape.turn.up.right"
        case .retry: return "arrow.clockwise"
        case .like: return "hand.thumbsup"
        case .dislike: return "hand.thumbsdown"
        case .speak: return "speaker.wave.2"
        case .fork: return "arrow.triangle.branch"
        case .remember: return "bookmark"
        }
    }

    @MainActor func title(_ settings: AppSettings) -> String {
        switch self {
        case .copy: return settings.text("Копировать", "Copy")
        case .select: return settings.text("Выбрать текст", "Select text")
        case .edit: return settings.text("Редактировать", "Edit")
        case .share: return settings.text("Поделиться", "Share")
        case .retry: return settings.text("Повторить", "Retry")
        case .like: return settings.text("Нравится", "Like")
        case .dislike: return settings.text("Не нравится", "Dislike")
        case .speak: return settings.text("Читать вслух", "Read aloud")
        case .fork: return settings.text("Продолжить в ветке", "Continue in a branch")
        case .remember: return settings.text("Запомнить", "Remember")
        }
    }
}

private struct MessageActionPopup: View {
    let message: ChatMessage
    @ObservedObject var settings: AppSettings
    let rowHeight: CGFloat
    let menuHeight: CGFloat
    let scrolls: Bool
    let onAction: (MessageMenuAction) -> Void
    @ScaledMetric(relativeTo: .body) private var textSize = 17.0

    private var actions: [MessageMenuAction] {
        MessageMenuAction.available(for: message)
    }

    var body: some View {
        ScrollView(.vertical, showsIndicators: scrolls) {
          VStack(spacing: 0) {
            ForEach(actions, id: \.rawValue) { action in
                Button { onAction(action) } label: {
                    HStack(spacing: 15) {
                        Image(systemName: action.symbol)
                            .font(.system(size: 19, weight: .regular))
                            .frame(width: 23)
                        Text(action.title(settings))
                            .font(.system(size: textSize * settings.fontScale, weight: .medium))
                            .lineLimit(2).minimumScaleFactor(0.8)
                        Spacer(minLength: 0)
                    }
                    .foregroundStyle(isSelected(action) ? HonorTheme.accent : HonorTheme.foreground)
                    .frame(height: rowHeight)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("message.menu." + action.rawValue)
                .disabled(requiresContent(action) && message.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
          }
          .padding(.horizontal, 21)
          .padding(.vertical, 12)
        }
        .frame(height: menuHeight)
        .accessibilityIdentifier("message.menu.scroll")
        .background(HonorTheme.sidebar, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).stroke(HonorTheme.divider, lineWidth: 0.8))
        .shadow(color: .black.opacity(0.28), radius: 18, x: 0, y: 10)
        .accessibilityElement(children: .contain)
    }

    private func requiresContent(_ action: MessageMenuAction) -> Bool {
        [.copy, .select, .remember, .speak, .share].contains(action)
    }

    private func isSelected(_ action: MessageMenuAction) -> Bool {
        (action == .like && message.feedback == .like) || (action == .dislike && message.feedback == .dislike)
    }
}

private struct HistoryDrawer: View {
    @EnvironmentObject private var store: ChatStore
    @EnvironmentObject private var settings: AppSettings
    let isOpen: Bool
    let onClose: () -> Void
    let onSettings: () -> Void
    @State private var search = ""
    @State private var selecting = false
    @State private var selectedIDs: Set<UUID> = []
    @State private var renameTarget: UUID?
    @State private var renamePresented = false
    @State private var renameTitle = ""
    @State private var deleteTargets: Set<UUID> = []
    @State private var deleteConfirmation = false
    /// Промт (инструкция) конкретного чата — пункт меню «три точки».
    @State private var chatPromptPresented = false
    @State private var chatPromptTarget: UUID?
    @State private var chatPromptDraft = ""
    @FocusState private var searchFocused: Bool
    @ScaledMetric(relativeTo: .body) private var dynamicScale = 1.0

    private func text(_ ru: String, _ en: String) -> String { settings.text(ru, en) }

    /// Шапка панели чатов: логотип приложения и кнопка нового чата.
    private var drawerHeader: some View {
        HStack(spacing: 10) {
            // Логотип приложения — та же иконка, что и на самом приложении.
            Group {
                if let icon = UIImage(named: "AppIcon") {
                    Image(uiImage: icon).resizable().scaledToFit()
                } else {
                    HonorMark(size: 30)
                }
            }
            .frame(width: 32, height: 32)
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous)
                .stroke(HonorTheme.divider, lineWidth: 0.6))
            .accessibilityHidden(true)

            Text("Honer AI")
                .font(.system(size: 19, weight: .bold))
                .foregroundStyle(HonorTheme.foreground)

            Spacer(minLength: 0)

            Button {
                searchFocused = false
                store.newChat()
                selectedIDs.removeAll()
                selecting = false
                onClose()
            } label: {
                Image(systemName: "square.and.pencil")
                    .font(.system(size: 18, weight: .medium))
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(HonorTheme.foreground)
            .accessibilityLabel(text("Новый чат", "New chat"))
            .accessibilityIdentifier("history.new.chat")
        }
        .padding(.horizontal, 16)
        .padding(.top, 14)
        .padding(.bottom, 6)
    }

    var body: some View {
        let grouped = groups
        return VStack(spacing: 0) {
            if !selecting { drawerHeader }
            if selecting {
                HStack {
                    Text(text("Выберите чаты", "Select chats")).font(.system(size: 18, weight: .semibold))
                    Spacer()
                    HonorCircleButton(symbol: "xmark", label: text("Отмена", "Cancel"), diameter: 36) {
                        selecting = false; selectedIDs.removeAll()
                    }
                    .accessibilityIdentifier("history.selection.cancel")
                }.padding(.horizontal, 14).padding(.top, 7).padding(.bottom, 12)
            } else {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass").font(.system(size: 17))
                    TextField(text("Поиск в содержимом…", "Search conversations…"), text: $search)
                        .font(.system(size: 16)).focused($searchFocused).submitLabel(.search)
                        .accessibilityIdentifier("history.search")
                    if !search.isEmpty {
                        Button { search = "" } label: { Image(systemName: "xmark.circle.fill") }
                            .accessibilityLabel(text("Очистить поиск", "Clear search"))
                            .accessibilityIdentifier("history.clear")
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
                    if grouped.isEmpty {
                        VStack(spacing: 12) {
                            Image(systemName: search.isEmpty ? "bubble.left.and.bubble.right" : "magnifyingglass")
                                .font(.system(size: 28))
                            Text(search.isEmpty ? text("Ваши чаты появятся здесь", "Your conversations appear here") : text("Ничего не найдено", "No results"))
                                .font(.system(size: 15)).multilineTextAlignment(.center)
                        }
                        .foregroundStyle(HonorTheme.secondary)
                        .frame(maxWidth: .infinity).padding(.top, 90)
                    }
                    ForEach(grouped) { group in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(group.title).font(.system(size: 14, weight: .semibold))
                                Spacer()
                                if group.id == grouped.first?.id && !selecting {
                                    Button { searchFocused = false; selecting = true } label: {
                                        Image(systemName: "checklist").frame(width: 40, height: 32)
                                    }.accessibilityLabel(text("Выбрать чаты", "Select chats"))
                                        .accessibilityIdentifier("history.select")
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
                    } label: {
                        Label(allSelectedPinned ? text("Открепить", "Unpin") : text("Закрепить", "Pin"),
                              systemImage: allSelectedPinned ? "pin.slash" : "pin")
                    }
                    .accessibilityIdentifier("history.bulk.pin")
                    Spacer(minLength: 5)
                    Button(role: .destructive) { requestDeletion(selectedIDs) } label: {
                        Label(text("Удалить", "Delete"), systemImage: "trash")
                    }
                    .accessibilityIdentifier("history.bulk.delete")
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
                        Text(settings.displayName.isEmpty ? text("Ваш профиль", "Your profile") : settings.displayName)
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
        .onChange(of: isOpen) { open in
            if !open { searchFocused = false }
        }
        .alert(text("Переименовать чат", "Rename chat"),
               isPresented: $renamePresented) {
            TextField(text("Название", "Title"), text: $renameTitle)
                .accessibilityIdentifier("history.rename.field")
            Button(text("Сохранить", "Save")) {
                if let id = renameTarget { store.renameChat(id: id, title: renameTitle) }
                renameTarget = nil
            }
            .disabled(renameTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            .accessibilityIdentifier("history.rename.save")
            Button(text("Отмена", "Cancel"), role: .cancel) { renameTarget = nil }
                .accessibilityIdentifier("history.rename.cancel")
        }
        .confirmationDialog(text("Удалить выбранные чаты?", "Delete selected chats?"),
                            isPresented: $deleteConfirmation,
                            titleVisibility: .visible) {
            Button(text("Удалить", "Delete"), role: .destructive) {
                store.deleteChats(ids: deleteTargets)
                selectedIDs.subtract(deleteTargets)
                deleteTargets.removeAll()
                if selectedIDs.isEmpty { selecting = false }
            }
            .accessibilityIdentifier("history.delete.confirm")
            Button(text("Отмена", "Cancel"), role: .cancel) { deleteTargets.removeAll() }
                .accessibilityIdentifier("history.delete.cancel")
        }
        .sheet(isPresented: $chatPromptPresented) { chatPromptSheet }
    }

    /// Инструкция только для выбранного чата.
    private var chatPromptSheet: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                Text(text("Инструкция действует только в этом чате и применяется к каждому ответу.",
                         "This instruction applies only to this chat."))
                    .font(.system(size: 13))
                    .foregroundStyle(HonorTheme.secondary)
                TextEditor(text: $chatPromptDraft)
                    .font(.system(size: 15))
                    .frame(minHeight: 200)
                    .padding(8)
                    .background(HonorTheme.surface, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(HonorTheme.divider, lineWidth: 0.7))
                    .accessibilityIdentifier("chat.prompt.editor")
                Spacer(minLength: 0)
            }
            .padding(16)
            .background(HonorTheme.background.ignoresSafeArea())
            .navigationTitle(text("Промт чата", "Chat prompt"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(text("Отмена", "Cancel")) { chatPromptPresented = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(text("Сохранить", "Save")) {
                        if let id = chatPromptTarget {
                            store.setChatPrompt(id: id, prompt: chatPromptDraft)
                        }
                        chatPromptPresented = false
                    }
                    .fontWeight(.semibold)
                    .accessibilityIdentifier("chat.prompt.save")
                }
            }
        }
    }

    private func historyRow(_ chat: Conversation) -> some View {
        HStack(spacing: 0) {
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
                    Spacer(minLength: 6)
                    // Время последнего сообщения: «2 минуты назад», «1 час 34 минуты назад», «12 дней назад».
                    Text(chat.relativeTimestamp)
                        .font(.system(size: 11 * dynamicScale))
                        .foregroundStyle(HonorTheme.secondary)
                        .lineLimit(1)
                        .layoutPriority(-1)
                    if chat.pinned && !selecting {
                        Image(systemName: "pin.fill")
                            .font(.system(size: 11))
                            .foregroundStyle(HonorTheme.accent.opacity(0.8))
                    }
                }
                .foregroundStyle(store.selectedConversationID == chat.id && !selecting ? HonorTheme.accent : HonorTheme.foreground)
                .padding(.leading, 13).frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("history.row." + chat.id.uuidString)
            .accessibilityAddTraits((selecting ? selectedIDs.contains(chat.id) : store.selectedConversationID == chat.id) ? .isSelected : [])
            if !selecting {
                // Закреплённые чаты можно менять местами между собой.
                if chat.pinned {
                    VStack(spacing: 0) {
                        Button { store.movePinned(id: chat.id, offset: -1) } label: {
                            Image(systemName: "chevron.up").font(.system(size: 11, weight: .semibold))
                                .frame(width: 30, height: 22).contentShape(Rectangle())
                        }
                        .accessibilityLabel(text("Выше среди закреплённых", "Move pinned up"))
                        .accessibilityIdentifier("history.pin.up." + chat.id.uuidString)
                        Button { store.movePinned(id: chat.id, offset: 1) } label: {
                            Image(systemName: "chevron.down").font(.system(size: 11, weight: .semibold))
                                .frame(width: 30, height: 22).contentShape(Rectangle())
                        }
                        .accessibilityLabel(text("Ниже среди закреплённых", "Move pinned down"))
                        .accessibilityIdentifier("history.pin.down." + chat.id.uuidString)
                    }
                    .foregroundStyle(HonorTheme.secondary)
                    .buttonStyle(.plain)
                }
                Menu { historyActions(chat) } label: {
                    Image(systemName: "ellipsis").font(.system(size: 16))
                        .foregroundStyle(HonorTheme.secondary)
                        .frame(width: 40, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel(text("Действия с чатом ", "Actions for ") + chat.title)
                .accessibilityIdentifier("history.actions." + chat.id.uuidString)
            }
        }
        .background(store.selectedConversationID == chat.id && !selecting ? HonorTheme.accent.opacity(0.21) : Color.clear,
                    in: RoundedRectangle(cornerRadius: 15, style: .continuous))
        .contextMenu { historyActions(chat) }
    }

    @ViewBuilder private func historyActions(_ chat: Conversation) -> some View {
            Button { store.togglePin(ids: [chat.id]) } label: {
                Label(chat.pinned ? text("Открепить", "Unpin") : text("Закрепить", "Pin"), systemImage: chat.pinned ? "pin.slash" : "pin")
            }
            .accessibilityIdentifier("history.menu.pin")
            Button { chatPromptTarget = chat.id; chatPromptDraft = chat.systemPrompt; chatPromptPresented = true } label: {
                Label(text("Промт чата", "Chat prompt"), systemImage: "text.badge.star")
            }
            .accessibilityIdentifier("history.menu.prompt")
            Button { renameTitle = chat.title; renameTarget = chat.id; renamePresented = true } label: {
                Label(text("Переименовать", "Rename"), systemImage: "pencil")
            }
            .accessibilityIdentifier("history.menu.rename")
            Button { searchFocused = false; selectedIDs = [chat.id]; selecting = true } label: {
                Label(text("Выбрать", "Select"), systemImage: "checkmark.circle")
            }
            .accessibilityIdentifier("history.menu.select")
            Button(role: .destructive) { requestDeletion([chat.id]) } label: {
                Label(text("Удалить", "Delete"), systemImage: "trash")
            }
            .accessibilityIdentifier("history.menu.delete")
    }

    private func requestDeletion(_ ids: Set<UUID>) {
        deleteTargets = ids
        deleteConfirmation = true
    }

    private var allSelectedPinned: Bool {
        !selectedIDs.isEmpty && store.conversations.filter { selectedIDs.contains($0.id) }.allSatisfy(\.pinned)
    }

    private var groups: [HistoryGroup] {
        let query = search.trimmingCharacters(in: .whitespacesAndNewlines)
        // Сортировка — по времени последнего сообщения, закреплённые всегда сверху
        // и в своём порядке (их можно менять местами).
        let chats = store.sortedConversations.filter { chat in
            query.isEmpty || chat.title.localizedCaseInsensitiveContains(query) || chat.messages.contains {
                $0.content.localizedCaseInsensitiveContains(query) || $0.reasoning.localizedCaseInsensitiveContains(query) ||
                $0.attachments.contains {
                    $0.name.localizedCaseInsensitiveContains(query) || $0.extractedText.localizedCaseInsensitiveContains(query)
                }
            }
        }
        let calendar = Calendar.current
        let weekAgo = calendar.date(byAdding: .day, value: -7, to: Date()) ?? .distantPast
        let unpinned = chats.filter { !$0.pinned }
        return [
            HistoryGroup(id: "pinned", title: text("Закреплено", "Pinned"), chats: chats.filter(\.pinned)),
            HistoryGroup(id: "today", title: text("Сегодня", "Today"), chats: unpinned.filter { calendar.isDateInToday($0.lastMessageAt) }),
            HistoryGroup(id: "yesterday", title: text("Вчера", "Yesterday"), chats: unpinned.filter { calendar.isDateInYesterday($0.lastMessageAt) }),
            HistoryGroup(id: "week", title: text("7 дней", "Previous 7 days"), chats: unpinned.filter {
                !calendar.isDateInToday($0.lastMessageAt) && !calendar.isDateInYesterday($0.lastMessageAt) && $0.lastMessageAt >= weekAgo
            }),
            HistoryGroup(id: "older", title: text("Ранее", "Older"), chats: unpinned.filter { $0.lastMessageAt < weekAgo })
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
                            .accessibilityIdentifier("message.selection.done")
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
        view.accessibilityIdentifier = "message.selection.text"
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

func attachmentSymbol(_ attachment: MessageAttachment) -> String {
    switch attachment.kind {
    case .image: return "photo"
    case .video: return "play.rectangle"
    case .document: return "doc"
    case .text: return "doc.text"
    }
}

func highlighted(_ value: AttributedString, query: String) -> AttributedString {
    guard !query.isEmpty else { return value }
    var result = value
    let plain = String(result.characters)
    var cursor = plain.startIndex
    while cursor < plain.endIndex,
          let match = plain.range(of: query, options: [.caseInsensitive, .diacriticInsensitive], range: cursor..<plain.endIndex),
          let lower = AttributedString.Index(match.lowerBound, within: result),
          let upper = AttributedString.Index(match.upperBound, within: result) {
        result[lower..<upper].backgroundColor = Color.yellow.opacity(0.35)
        cursor = match.upperBound
    }
    return result
}

enum CitationPattern {
    static let expression = try? NSRegularExpression(pattern: #"(?<!!)\[(\d+)\](?!\()"#)
}

func linkedCitations(_ content: String, sources: [WebSource]) -> String {
    guard !sources.isEmpty, let expression = CitationPattern.expression else { return content }
    var result = content
    let matches = expression.matches(in: content, range: NSRange(content.startIndex..., in: content))
    for match in matches.reversed() {
        guard let numberRange = Range(match.range(at: 1), in: content),
              let number = Int(content[numberRange]), sources.indices.contains(number - 1),
              let range = Range(match.range, in: result) else { continue }
        let url = sources[number - 1].url.absoluteString.replacingOccurrences(of: "(", with: "%28").replacingOccurrences(of: ")", with: "%29")
        result.replaceSubrange(range, with: "[[\(number)]](\(url))")
    }
    return result
}

private struct SourceSelection: Identifiable {
    let id = UUID()
    let sources: [WebSource]
    let readOnly: Bool
}

private struct SourceSiteMarks: View {
    let sources: [WebSource]
    var body: some View {
        HStack(spacing: -5) {
            ForEach(Array(sources.prefix(4))) { source in SourceSiteIcon(source: source).frame(width: 19, height: 19) }
        }.accessibilityHidden(true)
    }
}

private struct SourceSiteIcon: View {
    let source: WebSource
    private var faviconURL: URL? {
        guard var parts = URLComponents(url: source.url, resolvingAgainstBaseURL: false) else { return nil }
        parts.path = "/favicon.ico"; parts.query = nil; parts.fragment = nil
        return parts.url
    }
    var body: some View {
        AsyncImage(url: faviconURL) { phase in
            if let image = phase.image { image.resizable().scaledToFit().padding(3) }
            else {
                Text(String((source.url.host ?? "W").replacingOccurrences(of: "www.", with: "").prefix(1)).uppercased())
                    .font(.system(size: 10, weight: .semibold)).foregroundStyle(HonorTheme.secondary)
            }
        }
        .background(HonorTheme.raised, in: Circle()).clipShape(Circle())
        .overlay(Circle().stroke(HonorTheme.background, lineWidth: 1))
    }
}

private struct SourceDetailsSheet: View {
    let selection: SourceSelection
    @ObservedObject var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 27) {
                    let displayed = selection.sources.enumerated().filter { !selection.readOnly || $0.element.content != nil }
                    if displayed.isEmpty {
                        Text(settings.text("Полный текст страниц пока не получен. Доступны поисковые фрагменты.", "Full pages have not been retrieved. Search snippets are available."))
                            .foregroundStyle(HonorTheme.secondary).padding(.vertical, 25)
                    }
                    ForEach(displayed, id: \.element.id) { index, source in
                        VStack(alignment: .leading, spacing: 10) {
                            HStack(spacing: 8) {
                                SourceSiteIcon(source: source).frame(width: 24, height: 24)
                                Text(source.url.host ?? source.url.absoluteString).font(.system(size: 13, weight: .semibold))
                                Spacer()
                                Text("[\(index + 1)]").font(.system(size: 12).monospacedDigit()).foregroundStyle(HonorTheme.secondary)
                            }
                            Link(destination: source.url) {
                                Text(source.title).font(.system(size: 17, weight: .semibold))
                                    .foregroundStyle(HonorTheme.foreground).multilineTextAlignment(.leading)
                            }.accessibilityIdentifier("message.source." + source.id.uuidString)
                            Text(source.snippet).font(.system(size: 14)).foregroundStyle(HonorTheme.secondary)
                                .lineLimit(4)
                            if let fetchedAt = source.fetchedAt {
                                Text(settings.text("Прочитано ", "Read ") + fetchedAt.formatted(
                                    Date.FormatStyle(date: .abbreviated, time: .shortened)
                                        .locale(Locale(identifier: settings.language == .russian ? "ru_RU" : "en"))))
                                    .font(.system(size: 11)).foregroundStyle(HonorTheme.secondary)
                            }
                            if let content = source.content {
                                DisclosureGroup(settings.text("Прочитанный текст", "Retrieved text")) {
                                    Text(content).font(.system(size: 13)).textSelection(.enabled)
                                        .padding(.top, 6).foregroundStyle(HonorTheme.secondary)
                                }.font(.system(size: 13)).tint(HonorTheme.accent)
                            }
                        }.accessibilityElement(children: .contain)
                            .accessibilityIdentifier("sources.row." + source.id.uuidString)
                    }
                }.padding(18)
            }
            .background(HonorTheme.surface)
            .navigationTitle(settings.text(selection.readOnly ? "Прочитанные страницы" : "Источники", selection.readOnly ? "Read pages" : "Sources"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(settings.text("Готово", "Done")) { dismiss() }.accessibilityIdentifier("sources.close")
                }
            }
        }.tint(HonorTheme.accent)
    }
}

private struct ChatAttachmentsSheet: View {
    let attachments: [MessageAttachment]
    @ObservedObject var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    @State private var selected: MessageAttachment?
    private var uniqueAttachments: [MessageAttachment] {
        var seen = Set<UUID>()
        return attachments.filter { seen.insert($0.id).inserted }
    }
    var body: some View {
        NavigationStack {
            Group {
                if uniqueAttachments.isEmpty {
                    VStack(spacing: 15) {
                        Image(systemName: "paperclip").font(.system(size: 35)).foregroundStyle(HonorTheme.secondary)
                        Text(settings.text("В этом чате пока нет файлов", "No files in this conversation yet"))
                            .multilineTextAlignment(.center)
                    }.frame(maxWidth: .infinity, maxHeight: .infinity).accessibilityIdentifier("chat.attachments.empty")
                } else {
                    List(uniqueAttachments) { attachment in
                        Button { selected = attachment } label: {
                            HStack(spacing: 12) {
                                // Превью картинки: раньше фото было не видно, только иконка и имя.
                                if let url = attachment.resolvedURL, attachment.kind == .image,
                                   let image = UIImage(contentsOfFile: url.path) {
                                    Image(uiImage: image)
                                        .resizable()
                                        .scaledToFill()
                                        .frame(width: 44, height: 44)
                                        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                                } else {
                                    Image(systemName: attachmentSymbol(attachment))
                                        .font(.system(size: 23)).frame(width: 28)
                                }
                                Text(attachment.name).font(.system(size: 16)).foregroundStyle(HonorTheme.foreground)
                                Spacer()
                                Image(systemName: "chevron.right").font(.system(size: 12)).foregroundStyle(HonorTheme.secondary)
                            }.frame(minHeight: 44)
                        }.accessibilityIdentifier("chat.attachments.file." + attachment.id.uuidString)
                    }.scrollContentBackground(.hidden)
                }
            }.background(HonorTheme.background)
                .navigationTitle(settings.text("Загруженные файлы", "Uploaded files"))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(settings.text("Готово", "Done")) { dismiss() }.accessibilityIdentifier("chat.attachments.close")
                    }
                }
                .sheet(item: $selected) { AttachmentPreviewSheet(attachment: $0, settings: settings) }
        }.tint(HonorTheme.accent)
    }
}

private struct AttachmentPreviewSheet: View {
    let attachment: MessageAttachment
    @ObservedObject var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            Group {
                if let url = attachment.resolvedURL {
                    OriginalFilePreview(url: url).accessibilityIdentifier("attachment.preview.original")
                } else if !attachment.extractedText.isEmpty {
                    ScrollView { Text(attachment.extractedText).textSelection(.enabled).padding(20) }
                        .accessibilityIdentifier("attachment.preview.text")
                } else {
                    Text(settings.text("Оригинал файла недоступен на этом устройстве.", "The original file is unavailable on this device."))
                        .foregroundStyle(HonorTheme.secondary).padding(24)
                }
            }.navigationTitle(attachment.name).navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(settings.text("Готово", "Done")) { dismiss() }.accessibilityIdentifier("attachment.preview.close")
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        if let url = attachment.resolvedURL {
                            ShareLink(item: url) { Image(systemName: "square.and.arrow.up") }
                                .accessibilityLabel(settings.text("Поделиться оригиналом", "Share original"))
                                .accessibilityIdentifier("attachment.preview.share")
                        }
                    }
                }
        }.tint(HonorTheme.accent)
    }
}

private struct OriginalFilePreview: UIViewControllerRepresentable {
    let url: URL
    func makeCoordinator() -> Coordinator { Coordinator(url: url) }
    func makeUIViewController(context: Context) -> QLPreviewController {
        let controller = QLPreviewController()
        controller.dataSource = context.coordinator
        return controller
    }
    func updateUIViewController(_ controller: QLPreviewController, context: Context) {}
    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        let url: URL
        init(url: URL) { self.url = url }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem { url as NSURL }
    }
}

private struct VoiceRecordingOverlay: View {
    @ObservedObject var speech: SpeechService
    let cancelling: Bool
    @ObservedObject var settings: AppSettings
    private var tint: Color { cancelling ? Color(red: 0.96, green: 0.35, blue: 0.37) : HonorTheme.accent }
    var body: some View {
        VStack(spacing: 28) {
            Spacer(minLength: 55)
            Text(speech.isPreparingRecording ? settings.text("Подключаю микрофон…", "Starting microphone…") :
                    speech.isFinalizingRecording ? settings.text("Распознаю…", "Finishing…") :
                    cancelling ? settings.text("Отпустите для отмены", "Release to cancel") :
                    settings.text("Отпустите, чтобы отправить, сдвиньте вверх для отмены", "Release to send, slide up to cancel"))
                .font(.system(size: 14, weight: .medium)).foregroundStyle(HonorTheme.secondary)
                .multilineTextAlignment(.center).frame(maxWidth: 340).padding(.horizontal, 20)
            HStack(alignment: .center, spacing: 2.5) {
                ForEach(0..<48, id: \.self) { index in
                    let weight = 0.3 + 0.7 * abs(sin(Double(index) * 1.7))
                    Capsule().fill(tint).frame(width: 2, height: 4 + 42 * speech.audioLevel * weight)
                }
            }.frame(height: 55)
            Spacer(minLength: 35)
        }
        .frame(maxWidth: .infinity).frame(height: 260)
        .background(LinearGradient(colors: [HonorTheme.background.opacity(0), HonorTheme.background.opacity(0.95),
                                            cancelling ? Color(red: 0.21, green: 0.08, blue: 0.08) : Color(red: 0.15, green: 0.19, blue: 0.27)],
                                   startPoint: .top, endPoint: .bottom))
        .animation(.easeOut(duration: 0.14), value: cancelling)
        .accessibilityIdentifier(cancelling ? "chat.voice.cancel.overlay" : "chat.voice.recording.overlay")
    }
}
