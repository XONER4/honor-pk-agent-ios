import Foundation
import Combine
import UIKit

/// Живой буфер печатаемого ответа.
///
/// Зачем отдельный объект: если писать текст в модель чата на каждом куске потока,
/// SwiftUI перерисовывает весь список сообщений — на длинных ответах и больших
/// переписках это выглядит как зависание. Здесь обновляется только последняя строка.
@MainActor
final class StreamBuffer: ObservableObject {
    @Published var content = ""
    @Published var reasoning = ""
    @Published var reasoningSeconds = 0
}

@MainActor
final class ChatStore: ObservableObject {
    @Published var conversations: [Conversation] = []
    @Published var selectedConversationID: UUID?
    @Published var draft: String = "" { didSet { scheduleSave() } }
    @Published var attachments: [MessageAttachment] = [] {
        didSet {
            if !isLoading {
                let retainedIDs = Set(attachments.map(\.id))
                removeUnreferencedAttachments(oldValue.filter { !retainedIDs.contains($0.id) })
            }
            scheduleSave()
        }
    }
    @Published private(set) var isGenerating = false
    @Published private(set) var isLoadingHistory = false
    @Published var reasoningEnabled = true { didSet { UserDefaults.standard.set(reasoningEnabled, forKey: "honor.reasoningEnabled") } }
    @Published var searchEnabled = false { didSet { UserDefaults.standard.set(searchEnabled, forKey: "honor.searchEnabled") } }
    @Published var errorMessage: String?
    @Published private(set) var generationStatus: String?
    @Published private(set) var editingMessageID: UUID?
    @Published private(set) var memories: [HonorMemory] = []
    /// Реакция агента на сообщение пользователя: агент может поставить эмодзи
    /// отдельной строкой «РЕАКЦИЯ: 🙂» — она убирается из текста и показывается бейджем.
    private func extractAssistantReaction(chatID: UUID, messageID: UUID) {
        guard let chatIndex = conversations.firstIndex(where: { $0.id == chatID }),
              let messageIndex = conversations[chatIndex].messages.firstIndex(where: { $0.id == messageID })
        else { return }
        let content = conversations[chatIndex].messages[messageIndex].content
        let pattern = "(?m)^\\s*(?:РЕАКЦИЯ|РЕАКЦИЯ НА СООБЩЕНИЕ|REACTION)\\s*[:\\-]\\s*(\\S{1,4})\\s*$"
        guard let expression = try? NSRegularExpression(pattern: pattern) else { return }
        let range = NSRange(content.startIndex..., in: content)
        guard let match = expression.firstMatch(in: content, range: range),
              let whole = Range(match.range, in: content),
              let emojiRange = Range(match.range(at: 1), in: content) else { return }
        let emoji = String(content[emojiRange])
        let remainder = content.replacingCharacters(in: whole, with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        // Если вся реплика состояла из строки реакции, стирать её нельзя: иначе
        // в чате остаётся сообщение без текста и без ошибки — полная тишина.
        guard !remainder.isEmpty else { return }
        conversations[chatIndex].messages[messageIndex].content = remainder
        if let userIndex = conversations[chatIndex].messages[..<messageIndex].lastIndex(where: { $0.role == .user }) {
            conversations[chatIndex].messages[userIndex].assistantReaction = emoji
        }
    }

    /// Страховка: сообщение ассистента не может остаться без текста и без ошибки.
    /// Проверяется в самом конце генерации, когда все ветки уже отработали.
    private func ensureVisibleOutcome(chatID: UUID, messageID: UUID) {
        guard let chatIndex = conversations.firstIndex(where: { $0.id == chatID }),
              let messageIndex = conversations[chatIndex].messages.firstIndex(where: { $0.id == messageID })
        else { return }
        let message = conversations[chatIndex].messages[messageIndex]
        let isEmpty = message.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        guard isEmpty, message.error == nil, !message.isInterrupted else { return }
        conversations[chatIndex].messages[messageIndex].error = HonorError.emptyResponse.errorDescription
    }

    /// Статистика использования приложения (раздел «Статистика» в настройках).
    @Published private(set) var statistics = UsageStatistics() {
        didSet { persistStatistics() }
    }
    @Published var memoryEnabled = true { didSet { scheduleSave() } }
    @Published private var configuration: DeepSeekConfiguration

    static let maximumMemoryCount = 5000
    static let maximumMemoryLength = 1200

    var systemInstruction = ""
    var profileName = ""
    /// Мост к настройкам приложения: нужен, чтобы модель могла их менять
    /// инструментом set_app_setting (пункт 16 ТЗ). Заполняется из вида.
    weak var settingsBridge: AppSettings?
    var selectedConversation: Conversation? { conversations.first { $0.id == selectedConversationID && $0.archivedAt == nil } }
    var archivedConversations: [Conversation] { conversations.filter { $0.archivedAt != nil }.sorted { ($0.archivedAt ?? .distantPast) > ($1.archivedAt ?? .distantPast) } }
    var messages: [ChatMessage] { selectedConversation?.messages ?? [] }
    var hasAPIKey: Bool { !configuration.apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    /// Живой буфер печатаемого ответа. Обновляется отдельно от модели чата, поэтому
    /// при каждом куске текста перерисовывается только последняя строка, а не весь
    /// список сообщений. Раньше обновлялась модель и перерисовывался весь чат — это и
    /// давало «зависания» на длинных ответах и в больших переписках.
    let live = StreamBuffer()

    /// Клиент для самопроверки связи. Есть только у настоящего клиента:
    /// у тестовых заглушек его нет, и проверка просто не запускается.
    var connectionChecker: DeepSeekClient? { injectedClient as? DeepSeekClient }
    var canSend: Bool { !isLoadingHistory && !isGenerating && (!draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !attachments.isEmpty) }

    private let injectedClient: DeepSeekStreaming?
    private var searchClient: WebSearching
    private let storageURL: URL
    private let persistence: HistoryPersistence
    private var generationTask: Task<Void, Never>?
    private var persistenceTask: Task<Void, Never>?
    private var activeRunID: UUID?
    private var activeConversationID: UUID?
    private var activeMessageID: UUID?
    private var flushStreamingBuffer: (@MainActor () -> Void)?
    private var isLoading = true

    init(configuration: DeepSeekConfiguration = .bundled,
         client: DeepSeekStreaming? = nil,
         searchClient: WebSearching = WebSearchClient(),
         storageURL: URL? = nil,
         loadHistoryAsynchronously: Bool = false) {
        self.configuration = configuration
        self.injectedClient = client
        self.searchClient = searchClient
        let resolvedStorageURL = storageURL ?? Self.defaultStorageURL
        self.storageURL = resolvedStorageURL
        self.persistence = HistoryPersistence(url: resolvedStorageURL)
        // Режимы «Рассуждение» и «Поиск» раньше не сохранялись и сбрасывались сами —
        // теперь запоминаются между запусками.
        // Режим рассуждения по умолчанию выключен: он давал долгие паузы («Размышлял 8 секунд»)
        // и риск пустого ответа. Пользователь включает его сам кнопкой в панели ввода.
        self.reasoningEnabled = UserDefaults.standard.object(forKey: "honor.reasoningEnabled") as? Bool ?? false
        self.searchEnabled = UserDefaults.standard.object(forKey: "honor.searchEnabled") as? Bool ?? false
        if let data = UserDefaults.standard.data(forKey: "honor.statistics"),
           let saved = try? JSONDecoder().decode(UsageStatistics.self, from: data) {
            self.statistics = saved
        }
        if loadHistoryAsynchronously {
            isLoadingHistory = true
            Task { [weak self] in
                let result = await Task.detached(priority: .userInitiated) { HistoryArchiveIO.readHistory(resolvedStorageURL) }.value
                guard let self else { return }
                self.applyLoadedHistory(result)
                self.isLoading = false
                self.isLoadingHistory = false
            }
        } else {
            applyLoadedHistory(HistoryArchiveIO.readHistory(resolvedStorageURL))
            isLoading = false
        }
    }

    static var defaultStorageURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("HonorPK", isDirectory: true).appendingPathComponent("history.json")
    }

    func updateAPIKey(_ key: String) {
        configuration.apiKey = key.trimmingCharacters(in: .whitespacesAndNewlines)
        if var web = searchClient as? WebSearchClient {
            web.urlDiscovery = DeepSeekURLDiscovery(configuration: configuration)
            searchClient = web
        }
        errorMessage = nil
    }

    @discardableResult
    func addMemory(_ text: String) -> Bool {
        guard let text = validatedMemory(text, excluding: nil) else { return false }
        guard memories.count < Self.maximumMemoryCount else {
            errorMessage = "В памяти уже \(Self.maximumMemoryCount) записей. Удалите ненужную запись, чтобы добавить новую."
            return false
        }
        memories.append(HonorMemory(text: text, keywords: Self.keywords(in: text)))
        errorMessage = nil
        saveSnapshot()
        return true
    }

    @discardableResult
    func updateMemory(id: UUID, text: String) -> Bool {
        guard let index = memories.firstIndex(where: { $0.id == id }),
              let text = validatedMemory(text, excluding: id) else { return false }
        memories[index].text = text
        errorMessage = nil
        saveSnapshot()
        return true
    }

    func deleteMemory(id: UUID) {
        memories.removeAll { $0.id == id }
        saveSnapshot()
    }

    func clearMemories() {
        memories = []
        saveSnapshot()
    }

    private func validatedMemory(_ value: String, excluding id: UUID?) -> String? {
        let text = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, text.count <= Self.maximumMemoryLength else {
            errorMessage = "Запись памяти должна содержать от 1 до \(Self.maximumMemoryLength) символов."
            return nil
        }
        guard !memories.contains(where: { $0.id != id && $0.text.caseInsensitiveCompare(text) == .orderedSame }) else {
            errorMessage = "Такая запись уже есть в памяти Honor."
            return nil
        }
        return text
    }

    /// Собирает системную инструкцию: имя пользователя, инструкция чата и память,
    /// подобранная под текущий вопрос. Используется в beginGeneration.
    func systemInstruction(forChat chatID: UUID, query: String) -> String {
        var instruction = systemInstruction
        let name = String(profileName.trimmingCharacters(in: .whitespacesAndNewlines).prefix(80))
        if !name.isEmpty {
            instruction += "\nИмя пользователя в локальном профиле (данные): \(String(reflecting: name)). Обращайся по имени естественно, без повторения в каждом ответе."
        }
        if memoryEnabled, !memories.isEmpty {
            let scoped = Self.relevantMemories(memories, for: query)
            let entries = scoped.map { "• \($0.text)" }.joined(separator: "\n")
            instruction += "\n\nПамять Honer AI — факты и предпочтения, которые пользователь сохранил. Учитывай их, когда они относятся к запросу; последнее сообщение пользователя важнее сохранённых предпочтений.\n\(entries)"
        }
        if let chat = conversations.first(where: { $0.id == chatID }) {
            let prompt = chat.systemPrompt.trimmingCharacters(in: .whitespacesAndNewlines)
            if !prompt.isEmpty {
                instruction += "\n\nИнструкция этого чата (задана пользователем, действует только здесь):\n\(prompt)"
            }
        }
        return instruction
    }

    /// Отбирает факты памяти, относящиеся к текущему вопросу.
    /// При выключенной «памяти между чатами» каждый факт всё равно доступен —
    /// просто передаются только самые релевантные, а не весь список целиком.
    static func relevantMemories(_ memories: [HonorMemory], for query: String, limit: Int = 40) -> [HonorMemory] {
        let words = Set(keywords(in: query))
        guard !words.isEmpty else { return Array(memories.suffix(limit)) }
        let scored = memories.map { memory -> (HonorMemory, Int) in
            let own = Set(memory.keywords.isEmpty ? keywords(in: memory.text) : memory.keywords)
            return (memory, own.intersection(words).count)
        }
        let matched = scored.filter { $0.1 > 0 }.sorted { $0.1 > $1.1 }.map(\.0)
        let rest = memories.filter { memory in !matched.contains(where: { $0.id == memory.id }) }
        return Array((matched + rest).prefix(limit))
    }

    private static func memoryBlock(for memories: [HonorMemory], query: String?) -> String {
        let selected = query.map { relevantMemories(memories, for: $0) } ?? memories
        let entries = selected.map { "• \($0.text)" }.joined(separator: "\n")
        return """
        Память Honer AI — факты и предпочтения, которые пользователь сохранил. Учитывай их, когда они относятся к запросу; последнее сообщение пользователя важнее сохранённых предпочтений.
        \(entries)
        """
    }

    /// Ключевые слова факта или запроса — для отбора релевантной памяти без embeddings.
    static func keywords(in text: String) -> [String] {
        let stop: Set<String> = ["и", "в", "во", "не", "что", "он", "на", "я", "с", "со", "как", "а", "то", "все",
                                 "она", "так", "его", "но", "да", "ты", "к", "у", "же", "вы", "за", "бы", "по",
                                 "только", "ее", "мне", "было", "вот", "от", "меня", "еще", "нет", "о", "из", "ему",
                                 "the", "a", "an", "and", "or", "of", "to", "in", "is", "are", "for", "on", "with"]
        let parts = text.lowercased().components(separatedBy: CharacterSet.alphanumerics.inverted)
        var result: [String] = []
        for part in parts where part.count >= 3 && !stop.contains(part) {
            if !result.contains(part) { result.append(part) }
            if result.count >= 24 { break }
        }
        return result
    }

    @discardableResult
    func forkConversation(at messageID: UUID) -> UUID? {
        guard let source = selectedConversation,
              let messageIndex = source.messages.firstIndex(where: { $0.id == messageID }) else { return nil }
        stop()
        // Re-read after stop() so a copied live response is correctly marked interrupted.
        guard let currentSource = conversations.first(where: { $0.id == source.id }) else { return nil }
        let copies = currentSource.messages.prefix(messageIndex + 1).map { original -> ChatMessage in
            var copy = original
            copy.id = UUID()
            copy.attachments = original.attachments.map { attachment in
                var copy = attachment; copy.id = UUID(); return copy
            }
            copy.sources = original.sources.map { source in
                var copy = source; copy.id = UUID(); return copy
            }
            return copy
        }
        let title = String(("Ветка · " + source.title).prefix(100))
        let branch = Conversation(title: title, messages: copies, parentConversationID: source.id, forkedAtMessageID: messageID)
        conversations.insert(branch, at: 0)
        selectedConversationID = branch.id
        editingMessageID = nil
        draft = ""
        attachments = []
        errorMessage = nil
        saveSnapshot()
        return branch.id
    }

    /// - Parameter inputKind: как набрано сообщение (текст, голос, подсказка) — для статистики и меток в чате.
    func send(inputKind: MessageInputKind = .text) {
        guard canSend else { return }
        guard hasAPIKey else { errorMessage = HonorError.missingAPIKey.localizedDescription; return }
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        let outgoingAttachments = attachments
        if selectedConversationID == nil || selectedConversation == nil {
            let conversation = Conversation()
            conversations.insert(conversation, at: 0)
            selectedConversationID = conversation.id
        }
        guard let chatID = selectedConversationID,
              let index = conversations.firstIndex(where: { $0.id == chatID }) else { return }
        var discardedAttachments: [MessageAttachment] = []
        if let editingID = editingMessageID,
           let messageIndex = conversations[index].messages.firstIndex(where: { $0.id == editingID && $0.role == .user }) {
            discardedAttachments = conversations[index].messages[messageIndex...].flatMap(\.attachments)
            conversations[index].messages = Array(conversations[index].messages.prefix(messageIndex))
        }
        var user = ChatMessage(role: .user, content: text, attachments: outgoingAttachments)
        user.inputKind = inputKind
        conversations[index].messages.append(user)
        recordSentMessage(voice: inputKind == .voice)
        if conversations[index].messages.filter({ $0.role == .user }).count == 1 {
            let title = text.isEmpty ? (outgoingAttachments.first?.name ?? "Новый чат") : text
            conversations[index].title = String(title.replacingOccurrences(of: "\n", with: " ").prefix(48))
        }
        conversations[index].updatedAt = Date()
        draft = ""
        attachments = []
        editingMessageID = nil
        removeUnreferencedAttachments(discardedAttachments)
        beginGeneration(in: chatID)
    }

    func stop() {
        guard isGenerating else { return }
        flushStreamingBuffer?()
        // Переносим напечатанное в модель чата: пользователь нажал «остановить»,
        // ответ должен остаться в переписке и сохраниться в истории.
        let printedContent = live.content
        let printedReasoning = live.reasoning
        if let chatID = activeConversationID, let messageID = activeMessageID,
           !printedContent.isEmpty || !printedReasoning.isEmpty {
            mutateMessage(chatID: chatID, messageID: messageID) {
                if !printedContent.isEmpty { $0.content = printedContent }
                if !printedReasoning.isEmpty { $0.reasoning = printedReasoning }
            }
        }
        flushStreamingBuffer = nil
        generationTask?.cancel()
        generationTask = nil
        if let chatID = activeConversationID, let messageID = activeMessageID {
            mutateMessage(chatID: chatID, messageID: messageID) { $0.isInterrupted = true }
        }
        activeRunID = nil
        activeConversationID = nil
        activeMessageID = nil
        isGenerating = false
        generationStatus = nil
        // Живой буфер обязательно очищаем: иначе напечатанный текст остался бы в нём
        // и «переехал» в другой чат, когда пользователь переключится.
        live.content = ""
        live.reasoning = ""
        live.reasoningSeconds = 0
        saveSnapshot()
    }

    func regenerate(messageID: UUID) {
        guard hasAPIKey else { errorMessage = HonorError.missingAPIKey.localizedDescription; return }
        stop()
        guard let chatID = selectedConversationID,
              let chatIndex = conversations.firstIndex(where: { $0.id == chatID }),
              let messageIndex = conversations[chatIndex].messages.firstIndex(where: { $0.id == messageID && $0.role == .assistant }),
              conversations[chatIndex].messages[..<messageIndex].contains(where: { $0.role == .user }) else { return }
        let discardedAttachments = conversations[chatIndex].messages[messageIndex...].flatMap(\.attachments)
        conversations[chatIndex].messages = Array(conversations[chatIndex].messages.prefix(messageIndex))
        editingMessageID = nil
        removeUnreferencedAttachments(discardedAttachments)
        beginGeneration(in: chatID)
    }

    func edit(messageID: UUID) {
        stop()
        guard let message = messages.first(where: { $0.id == messageID && $0.role == .user }) else { return }
        editingMessageID = messageID
        draft = message.content
        attachments = message.attachments
    }

    func cancelEditing() {
        guard editingMessageID != nil else { return }
        editingMessageID = nil
        draft = ""
        attachments = []
    }

    func newChat() {
        stop()
        selectedConversationID = nil
        editingMessageID = nil
        draft = ""
        attachments = []
        errorMessage = nil
        saveSnapshot()
    }

    func selectChat(id: UUID) {
        guard conversations.contains(where: { $0.id == id && $0.archivedAt == nil }) else { return }
        guard selectedConversationID != id else { return }
        stop()
        selectedConversationID = id
        editingMessageID = nil
        draft = ""
        attachments = []
        errorMessage = nil
        saveSnapshot()
    }

    func archiveChat(id: UUID) {
        guard let index = conversations.firstIndex(where: { $0.id == id && $0.archivedAt == nil }) else { return }
        if activeConversationID == id { stop() }
        conversations[index].archivedAt = Date()
        if selectedConversationID == id {
            selectedConversationID = nil; editingMessageID = nil; draft = ""; attachments = []
        }
        saveSnapshot()
    }

    func restoreChat(id: UUID) {
        guard let index = conversations.firstIndex(where: { $0.id == id && $0.archivedAt != nil }) else { return }
        conversations[index].archivedAt = nil
        conversations[index].updatedAt = Date()
        saveSnapshot()
    }

    func deleteChats(ids: Set<UUID>) {
        if let activeConversationID, ids.contains(activeConversationID) { stop() }
        let removedAttachments = conversations.filter { ids.contains($0.id) }.flatMap(\.messages).flatMap(\.attachments)
        conversations.removeAll { ids.contains($0.id) }
        if let selectedConversationID, ids.contains(selectedConversationID) {
            self.selectedConversationID = nil
            editingMessageID = nil
            draft = ""
            attachments = []
        }
        removeUnreferencedAttachments(removedAttachments)
        saveSnapshot()
    }

    func togglePin(ids: Set<UUID>) {
        let matching = conversations.filter { ids.contains($0.id) }
        guard !matching.isEmpty else { return }
        let pin = !matching.allSatisfy(\.pinned)
        for index in conversations.indices where ids.contains(conversations[index].id) { conversations[index].pinned = pin }
        saveSnapshot()
    }

    func renameChat(id: UUID, title: String) {
        let title = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty, let index = conversations.firstIndex(where: { $0.id == id }) else { return }
        conversations[index].title = String(title.prefix(100))
        saveSnapshot()
    }

    func setFeedback(messageID: UUID, feedback: MessageFeedback?) {
        guard let chatID = selectedConversationID else { return }
        mutateMessage(chatID: chatID, messageID: messageID) { $0.feedback = feedback }
        saveSnapshot()
    }

    /// Реакция-эмодзи на сообщение (пункт 38 ТЗ). Агент видит реакцию
    /// пользователя на свой прошлый ответ и учитывает её.
    func setReaction(messageID: UUID, emoji: String?) {
        guard let chatID = selectedConversationID else { return }
        mutateMessage(chatID: chatID, messageID: messageID) { $0.reaction = emoji }
        saveSnapshot()
    }

    /// Реакция агента на сообщение пользователя.
    func setAssistantReaction(messageID: UUID, emoji: String?) {
        guard let chatID = selectedConversationID else { return }
        mutateMessage(chatID: chatID, messageID: messageID) { $0.assistantReaction = emoji }
        saveSnapshot()
    }

    /// Инструкция для конкретного чата (пункт «Промт чата» в меню трёх точек).
    func setChatPrompt(id: UUID, prompt: String) {
        guard let index = conversations.firstIndex(where: { $0.id == id }) else { return }
        conversations[index].systemPrompt = String(prompt.prefix(4000))
        saveSnapshot()
    }

    /// Список для панели чатов: закреплённые всегда сверху и в своём порядке,
    /// остальные — по времени последнего сообщения.
    var sortedConversations: [Conversation] {
        let active = conversations.filter { $0.archivedAt == nil }
        let pinned = active.filter(\.pinned).sorted { lhs, rhs in
            if lhs.pinOrder != rhs.pinOrder { return lhs.pinOrder < rhs.pinOrder }
            return lhs.lastMessageAt > rhs.lastMessageAt
        }
        let others = active.filter { !$0.pinned }.sorted { $0.lastMessageAt > $1.lastMessageAt }
        return pinned + others
    }

    /// Меняет закреплённые чаты местами (только среди закреплённых).
    func movePinned(id: UUID, offset: Int) {
        var pinned = conversations.filter { $0.pinned && $0.archivedAt == nil }
            .sorted { $0.pinOrder < $1.pinOrder }
        guard let position = pinned.firstIndex(where: { $0.id == id }) else { return }
        let target = position + offset
        guard pinned.indices.contains(target) else { return }
        pinned.swapAt(position, target)
        for (order, chat) in pinned.enumerated() {
            if let index = conversations.firstIndex(where: { $0.id == chat.id }) {
                conversations[index].pinOrder = order
            }
        }
        saveSnapshot()
    }

    // MARK: - Статистика использования

    private func persistStatistics() {
        if let data = try? JSONEncoder().encode(statistics) {
            UserDefaults.standard.set(data, forKey: "honor.statistics")
        }
    }

    func recordSentMessage(voice: Bool = false) {
        statistics.sentMessages += 1
        if voice { statistics.voiceMessages += 1 }
    }

    func recordReceivedMessage() {
        statistics.receivedMessages += 1
    }

    /// Добавляет время, проведённое в приложении (вызывается по таймеру и при уходе в фон).
    func addSessionTime(_ seconds: Double) {
        guard seconds > 0, seconds < 3600 else { return }
        statistics.totalSessionSeconds += seconds
    }

    func resetStatistics() {
        let firstLaunch = statistics.firstLaunch
        statistics = UsageStatistics(firstLaunch: firstLaunch)
    }

    /// Автоудаление чатов по сроку хранения (настройка в разделе «Данные»).
    func purgeOldChats(olderThan days: Int) {
        guard days > 0 else { return }
        let threshold = Date().addingTimeInterval(-Double(days) * 86_400)
        // Закреплённые чаты автоудаление не трогает.
        let doomed = conversations.filter { $0.archivedAt == nil && !$0.pinned && $0.lastMessageAt < threshold }
        guard !doomed.isEmpty else { return }
        let ids = Set(doomed.map(\.id))
        let removedAttachments = doomed.flatMap(\.messages).flatMap(\.attachments)
        conversations.removeAll { ids.contains($0.id) }
        if let selected = selectedConversationID, ids.contains(selected) {
            selectedConversationID = conversations.first(where: { $0.archivedAt == nil })?.id
        }
        removeUnreferencedAttachments(removedAttachments)
        saveSnapshot()
    }

    func clearAllChats() {
        stop()
        conversations = []
        selectedConversationID = nil
        editingMessageID = nil
        draft = ""
        attachments = []
        errorMessage = nil
        let attachmentDirectory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("HonorPKAgent/Attachments", isDirectory: true)
        if FileManager.default.fileExists(atPath: attachmentDirectory.path) {
            do { try FileManager.default.removeItem(at: attachmentDirectory) }
            catch { errorMessage = "Не удалось удалить вложения: \(error.localizedDescription)" }
        }
        saveSnapshot()
    }

    private func beginGeneration(in chatID: UUID) {
        guard let index = conversations.firstIndex(where: { $0.id == chatID }) else { return }
        errorMessage = nil
        let input = conversations[index].messages
        let response = ChatMessage(role: .assistant)
        conversations[index].messages.append(response)
        conversations[index].updatedAt = Date()
        let runID = UUID()
        activeRunID = runID
        activeConversationID = chatID
        activeMessageID = response.id
        isGenerating = true
        let thinking = reasoningEnabled
        let query = input.last(where: { $0.role == .user })?.content ?? ""
        let searching = SearchIntent.needsSearch(query: query, searchToggleOn: searchEnabled)
        let recentContext = input.suffix(4).map { String($0.content.prefix(1500)) }.joined(separator: "\n")
        // Инструкция собирается одним методом: имя, инструкция чата и память,
        // подобранная под текущий вопрос (пункты 18 и 24).
        var instruction = systemInstruction(forChat: chatID, query: query)
        instruction += HonerIdentity.context(for: query, recentContext: recentContext)
        let client = injectedClient ?? DeepSeekClient(configuration: configuration)
        let russianNormalizer = client as? RussianTextNormalizing
        generationStatus = searching ? "Ищу в интернете…" : (thinking ? "Размышляю…" : "Отвечаю…")
        saveSnapshot()

        generationTask = Task { [weak self] in
            guard let self else { return }
            do {
                try Task.checkCancellation()
                guard self.activeRunID == runID else { return }
                var context = ""
                if searching {
                    guard !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw HonorError.searchUnavailable }
                    let sources = try await self.searchClient.search(query)
                    try Task.checkCancellation()
                    guard self.activeRunID == runID else { return }
                    self.mutateMessage(chatID: chatID, messageID: response.id) { $0.sources = sources }
                    context = WebSearchClient.context(sources)
                    self.generationStatus = thinking ? "Размышляю…" : "Отвечаю…"
                }
                var firstReasoningAt: Date?
                var reasoningEndedAt: Date?
                var pendingContent = ""
                var pendingReasoning = ""
                var rawContent = ""
                var rawReasoning = ""
                var lastPublished = Date.distantPast
                var lastSaved = Date()
                var finishReason: String?
                /// Вызовы инструментов, собранные из потока (пункт 11 ТЗ).
                var toolCalls: [ToolCallRequest] = []

                @MainActor func flush() {
                    guard !pendingContent.isEmpty || !pendingReasoning.isEmpty else { return }
                    let seconds = firstReasoningAt.map { max(1, Int((reasoningEndedAt ?? Date()).timeIntervalSince($0).rounded())) } ?? 0
                    let contentChanged = !pendingContent.isEmpty
                    let reasoningChanged = !pendingReasoning.isEmpty
                    rawContent += pendingContent
                    rawReasoning += pendingReasoning
                    // Печатаемый текст идёт в отдельный буфер: перерисовывается только
                    // последняя строка. Модель чата обновляем редко — по таймеру ниже.
                    if contentChanged { self.live.content = rawContent }
                    if reasoningChanged { self.live.reasoning = rawReasoning }
                    self.live.reasoningSeconds = seconds
                    pendingContent = ""; pendingReasoning = ""; lastPublished = Date()
                }
                /// Переносит накопленный текст в модель чата: нужно для сохранения
                /// истории, но делать это на каждом куске нельзя — перерисовывается
                /// весь список сообщений.
                @MainActor func publishToModel() {
                    self.mutateMessage(chatID: chatID, messageID: response.id) {
                        $0.reasoningSeconds = self.live.reasoningSeconds
                    }
                }
                self.flushStreamingBuffer = { [weak self] in
                    guard self?.activeRunID == runID else { return }
                    flush()
                }

                // Цикл выполнения инструментов (пункт 11 ТЗ): модель может попросить
                // вызвать функцию, приложение выполняет её и отправляет результат обратно,
                // после чего модель формирует итоговый ответ.
                var toolResults: [ToolCallResult] = []
                var toolRounds = 0

                do {
                    // Инструменты передаём (пункт 16 ТЗ): чтение и переименование чатов,
                    // запись в память, смена настроек. По документации DeepSeek при наличии
                    // tools обязателен полный возврат reasoning_content предыдущих ответов
                    // и результаты вызова отдельными сообщениями с ролью tool — это сделано
                    // в DeepSeekClient.makeRequest, иначе API отвечает 400 и поток обрывается.
                    for try await delta in client.stream(messages: input, thinking: thinking,
                                                         systemInstruction: instruction, searchContext: context,
                                                         tools: HonerTool.apiSchemas) {
                        try Task.checkCancellation()
                        guard self.activeRunID == runID else { return }
                        if !delta.reasoning.isEmpty, firstReasoningAt == nil { firstReasoningAt = Date() }
                        if !delta.content.isEmpty {
                            if firstReasoningAt != nil && reasoningEndedAt == nil { reasoningEndedAt = Date() }
                            if self.generationStatus != "Отвечаю…" { self.generationStatus = "Отвечаю…" }
                        }
                        // Собираем вызовы инструментов, склеивая аргументы по index.
                        // Сопоставление только по id теряло куски: id приходит лишь
                        // в первом куске, а в остальных есть только index. Из-за этого
                        // вызовы дублировались, аргументы ломались, и вместо ответа
                        // в чате оставался обрывок вроде одной буквы.
                        for call in delta.toolCalls {
                            Self.mergeToolCall(call, into: &toolCalls)
                        }
                        pendingContent += delta.content
                        pendingReasoning += delta.reasoning
                        finishReason = delta.finishReason ?? finishReason
                        // Публикуем каждый кусок, который пришёл от сервиса. Раньше здесь
                        // ждали накопления 12 символов, и при мелких кусках текст не появлялся
                        // по ходу ответа — пользователь видел пустое место до самого конца.
                        // Плавность обеспечивает аниматор вывода, а не задержка публикации.
                        flush()
                        if Date().timeIntervalSince(lastSaved) >= 1.5 { self.saveSnapshot(); lastSaved = Date() }
                    }
                    flush()
                } catch {
                    if self.activeRunID == runID { flush() }
                    throw error
                }

                // Выполняем запрошенные инструменты и повторяем запрос с результатами.
                // Включает расширенные права: чтение и правку других чатов, запись в них,
                // запись в память и смена настроек (пункты 7 и 16 ТЗ).
                while !toolCalls.isEmpty, toolRounds < 4, Self.toolsEnabled {
                    toolRounds += 1
                    // Имя не `context`: так уже называется строка с результатами поиска.
                    let toolContext = self.toolExecutionContext()
                    self.generationStatus = "Выполняю действие…"
                    // Запоминаем вызовы этого прохода: их нужно вернуть в API вместе
                    // с результатами, иначе сервис отвечает ошибкой 400.
                    let executedCalls = toolCalls
                    // Результаты отправляем только текущего прохода: накопленные
                    // раньше повторять не нужно, иначе растёт запрос и модель путается.
                    toolResults.removeAll()
                    for call in toolCalls {
                        let result = ToolExecutor.executeExtended(call, context: toolContext)
                        if let effect = result.effect { self.apply(effect) }
                        toolResults.append(result)
                    }
                    toolCalls.removeAll()

                    var followUp = input
                    var assistant = ChatMessage(role: .assistant)
                    assistant.content = rawContent
                    assistant.reasoning = rawReasoning
                    assistant.toolCallsRaw = Self.toolCallsJSON(executedCalls)
                    followUp.append(assistant)
                    // Результат обязан идти сообщением с ролью tool и ссылкой на вызов:
                    // этого требует протокол DeepSeek, иначе запрос отклоняется.
                    for result in toolResults where !result.callID.isEmpty {
                        var toolMessage = ChatMessage(role: .tool)
                        toolMessage.content = result.content
                        toolMessage.toolCallID = result.callID
                        followUp.append(toolMessage)
                    }
                    // Короткая просьба ответить по существу: без неё модель иногда
                    // повторяет вызов инструмента вместо ответа.
                    var nudge = ChatMessage(role: .user)
                    nudge.content = "Используй полученные данные и дай итоговый ответ пользователю. Не вызывай этот инструмент повторно."
                    followUp.append(nudge)

                    self.generationStatus = "Отвечаю…"
                    do {
                        // На повторном проходе инструменты НЕ передаём. Проверено живым
                        // тестом: с инструментами модель снова и снова просит вызов
                        // и возвращает пустой текст, а без них — сразу даёт ответ по
                        // полученным данным. Все нужные действия уже выполнены выше.
                        for try await delta in client.stream(messages: followUp, thinking: thinking,
                                                             systemInstruction: instruction, searchContext: context,
                                                             tools: nil) {
                            try Task.checkCancellation()
                            guard self.activeRunID == runID else { return }
                            for call in delta.toolCalls {
                                Self.mergeToolCall(call, into: &toolCalls)
                            }
                            pendingContent += delta.content
                            pendingReasoning += delta.reasoning
                            finishReason = delta.finishReason ?? finishReason
                            // Каждый кусок публикуем сразу: см. пояснение в основном цикле.
                            flush()
                        }
                        flush()
                    } catch {
                        if self.activeRunID == runID { flush() }
                    }
                }
                guard self.activeRunID == runID else { return }
                // Поток закончился: переносим напечатанный текст в модель чата —
                // дальше с ним работают перевод, сохранение и проверки.
                let printedSeconds = self.live.reasoningSeconds
                self.mutateMessage(chatID: chatID, messageID: response.id) {
                    $0.content = rawContent
                    $0.reasoning = rawReasoning
                    $0.reasoningSeconds = printedSeconds
                }
                if let russianNormalizer {
                    let normalizeContent = RussianTextPolicy.needsNormalization(rawContent)
                    let normalizeReasoning = RussianTextPolicy.needsReasoningNormalization(rawReasoning)
                    self.mutateMessage(chatID: chatID, messageID: response.id) {
                        // Пока идёт перевод, показываем ответ как есть: пустого места
                        // быть не должно, даже если перевод не удастся.
                        $0.content = rawContent
                        if !normalizeReasoning { $0.reasoning = rawReasoning }
                    }
                    if normalizeContent && normalizeReasoning {
                        // Ответ и длинное рассуждение переводим одним запросом: иначе
                        // отдельный перевод рассуждения не проходит по длине и
                        // пользователь видит английский текст.
                        self.generationStatus = "Перевожу на русский…"
                        do {
                            let pair = try await russianNormalizer.normalizeBoth(content: rawContent, reasoning: rawReasoning)
                            try Task.checkCancellation()
                            guard self.activeRunID == runID else { return }
                            #if DEBUG
                            print("HONER_WHY combined ok content=\(pair.content.count) reasoning=\(pair.reasoning.count)")
                            #endif
                            self.mutateMessage(chatID: chatID, messageID: response.id) {
                                $0.content = pair.content
                                $0.reasoning = pair.reasoning
                                $0.reasoningWasTranslated = true
                            }
                        } catch {
                            if Task.isCancelled { throw CancellationError() }
                            #if DEBUG
                            print("HONER_WHY combined failed: \(error)")
                            #endif
                            // Не получилось одним запросом — пробуем по отдельности.
                            await self.normalizeSeparately(normalizer: russianNormalizer, chatID: chatID,
                                                           messageID: response.id, sourceContent: rawContent,
                                                           sourceReasoning: rawReasoning,
                                                           contentNeeded: normalizeContent,
                                                           reasoningNeeded: normalizeReasoning, runID: runID)
                        }
                    } else {
                        if normalizeContent {
                            self.generationStatus = "Перевожу ответ на русский…"
                            do {
                                let translated = try await russianNormalizer.normalizeRussian(rawContent, reasoning: false)
                                try Task.checkCancellation()
                                guard self.activeRunID == runID else { return }
                                self.mutateMessage(chatID: chatID, messageID: response.id) { $0.content = translated }
                            } catch {
                                if Task.isCancelled { throw CancellationError() }
                                // Перевод не удался — оставляем исходный ответ целиком.
                                self.mutateMessage(chatID: chatID, messageID: response.id) {
                                    if $0.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                        $0.content = rawContent
                                    }
                                }
                            }
                        }
                        if normalizeReasoning {
                            do {
                                let translated = try await russianNormalizer.normalizeRussian(rawReasoning, reasoning: true)
                                try Task.checkCancellation()
                                guard self.activeRunID == runID else { return }
                                self.mutateMessage(chatID: chatID, messageID: response.id) { $0.reasoning = translated; $0.reasoningWasTranslated = true }
                            } catch {
                                if Task.isCancelled { throw CancellationError() }
                                // Перевод не удался — оставляем исходный текст рассуждения,
                                // а не заглушку, и помечаем, что он на языке модели:
                                // пользователь должен видеть, о чём думала модель, и понимать,
                                // почему текст не по-русски.
                                self.mutateMessage(chatID: chatID, messageID: response.id) {
                                    if $0.reasoning.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                        $0.reasoning = rawReasoning
                                    }
                                    $0.reasoningStayedForeign = true
                                }
                            }
                        }
                    }
                }
                // Страховка от пустого ответа и обрывка.
                // Раньше при пустом ответе приложение просто молчало: пользователь видел
                // только строку рассуждения и пустоту под ней. Теперь в этом случае
                // запрос автоматически повторяется без режима рассуждения, а если и он
                // ничего не дал — выводится понятное сообщение и кнопка «Повторить».
                let answerIsEmpty = rawContent.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                let answerIsFragment = Self.needsAnswerRecovery(rawContent)
                let serverAnomaly = finishReason == "insufficient_system_resource" || finishReason == "aborted"
                if answerIsEmpty || answerIsFragment || serverAnomaly {
                    self.generationStatus = "Дописываю ответ…"
                    // Собираем запрос на повтор. Если модель уже что-то узнала через
                    // инструменты, эти данные нужно приложить обычным сообщением —
                    // иначе повтор не может ответить и снова обещает «сейчас найду».
                    var recoveryInput = input
                    if !toolResults.isEmpty {
                        if !rawContent.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            var attempt = ChatMessage(role: .assistant)
                            attempt.content = rawContent
                            recoveryInput.append(attempt)
                        }
                        var data = ChatMessage(role: .user)
                        let collected = toolResults.map { "• \($0.content)" }.joined(separator: "\n")
                        data.content = "Вот данные, которые ты запросил:\n\(collected)\n\nИспользуй их и дай итоговый ответ пользователю. Больше инструментов нет — отвечай текстом."
                        recoveryInput.append(data)
                    }
                    // Сначала обычный запрос без потока: он приходит целиком и
                    // обрываться на середине ему нечем. Если и он не дал текста —
                    // повторяем потоком, уже без режима рассуждения.
                    var retryContent = ""
                    do {
                        retryContent = try await client.complete(messages: recoveryInput, thinking: false,
                                                                 systemInstruction: instruction,
                                                                 searchContext: context)
                        if !retryContent.isEmpty {
                            // Публикуем текст напрямую: flush() дописывает pendingContent
                            // к rawContent, и раньше из-за этого текст на секунду удваивался.
                            rawContent = retryContent
                            pendingContent = ""
                            self.mutateMessage(chatID: chatID, messageID: response.id) { $0.content = rawContent }
                        }
                    } catch {
                        if Task.isCancelled { throw CancellationError() }
                    }
                    if Self.needsAnswerRecovery(retryContent) {
                        do {
                            var streamed = ""
                            for try await delta in client.stream(messages: recoveryInput, thinking: false,
                                                                 systemInstruction: instruction,
                                                                 searchContext: context, tools: nil) {
                                try Task.checkCancellation()
                                guard self.activeRunID == runID else { return }
                                streamed += delta.content
                                if !delta.reasoning.isEmpty { rawReasoning += delta.reasoning }
                                rawContent = streamed
                                pendingContent = ""
                                if Date().timeIntervalSince(lastPublished) >= 1.0 / 30.0 {
                                    self.mutateMessage(chatID: chatID, messageID: response.id) { $0.content = streamed }
                                    lastPublished = Date()
                                }
                            }
                            retryContent = streamed
                        } catch {
                            if Task.isCancelled { throw CancellationError() }
                        }
                    }
                    // Последняя попытка: модель уже дважды не дала ответ по существу,
                    // хотя данные инструментов у неё есть. Просим строго ответить текстом,
                    // без рассуждений и без обещаний что-то найти.
                    if Self.needsAnswerRecovery(retryContent), !toolResults.isEmpty {
                        self.generationStatus = "Формулирую ответ…"
                        do {
                            let forced = try await client.complete(messages: recoveryInput, thinking: false,
                                                                   systemInstruction: "Отвечай только итоговым текстом по-русски. Никаких обещаний что-то найти или прочитать, никаких рассуждений о своих действиях. Сразу дай ответ по данным, которые есть в переписке.",
                                                                   searchContext: "")
                            if !Self.needsAnswerRecovery(forced), !forced.isEmpty {
                                // Именно заменяем ответ, а не дописываем: накопленный текст
                                // содержал объявление о действии, и склейка выглядела как
                                // «Сначала найду чат…## Ответ».
                                retryContent = forced
                                rawContent = forced
                                pendingContent = ""
                                self.mutateMessage(chatID: chatID, messageID: response.id) { $0.content = forced }
                            }
                        } catch {
                            if Task.isCancelled { throw CancellationError() }
                        }
                    }
                    // Показываем повтор только если он что-то дал. Иначе сохраняем то,
                    // что уже было: раньше неудачный повтор стирал полный ответ,
                    // который пользователь уже видел.
                    let retryIsAnswer = !Self.needsAnswerRecovery(retryContent)
                    if retryIsAnswer || Self.needsAnswerRecovery(rawContent) {
                        rawContent = retryContent
                        self.mutateMessage(chatID: chatID, messageID: response.id) { $0.content = rawContent }
                    }
                    // Первый проход закончился ничем — его finish_reason
                    // больше не относится к показанному ответу.
                    if retryIsAnswer { finishReason = nil }
                }
                // Последняя очистка перед показом: убираем из ответа объявления
                // о действиях («Сначала найду чат…»). Если после очистки ничего
                // не осталось, но данные инструментов есть — просим нормальный ответ.
                if Self.isToolAnnouncement(rawContent) {
                    let cleaned = Self.strippingToolAnnouncements(rawContent)
                    if !Self.isTooShortToBeAnAnswer(cleaned) {
                        rawContent = cleaned
                        self.mutateMessage(chatID: chatID, messageID: response.id) { $0.content = cleaned }
                    } else if !toolResults.isEmpty {
                        self.generationStatus = "Формулирую ответ…"
                        var synthesisInput = input
                        var data = ChatMessage(role: .user)
                        let collected = toolResults.map { "• \($0.content)" }.joined(separator: "\n")
                        data.content = "Данные, полученные инструментами:\n\(collected)"
                        synthesisInput.append(data)
                        if let answer = try? await client.complete(
                            messages: synthesisInput, thinking: false,
                            systemInstruction: "Ответь пользователю по-русски одним связным ответом, используя данные выше. Без вступлений, без описания своих действий и без markdown-заголовков первого уровня.",
                            searchContext: ""), !Self.isTooShortToBeAnAnswer(answer) {
                            rawContent = answer
                            self.mutateMessage(chatID: chatID, messageID: response.id) { $0.content = answer }
                        }
                    }
                }
                let final = self.conversations.first(where: { $0.id == chatID })?.messages.first(where: { $0.id == response.id })
                guard !(final?.content.isEmpty ?? true) else { throw HonorError.emptyResponse }
                guard !(final.map { Self.isTooShortToBeAnAnswer($0.content) } ?? false) else {
                    self.mutateMessage(chatID: chatID, messageID: response.id) {
                        $0.error = "Ответ пришёл обрывком. Нажмите «Повторить запрос»."
                    }
                    throw HonorError.emptyResponse
                }
                if finishReason == "length" {
                    self.mutateMessage(chatID: chatID, messageID: response.id) { $0.error = "Достигнута максимальная длина ответа. Попросите продолжить." }
                } else if finishReason == "content_filter" {
                    self.mutateMessage(chatID: chatID, messageID: response.id) { $0.error = "Сервис остановил этот ответ." }
                } else if finishReason == "insufficient_system_resource" {
                    self.mutateMessage(chatID: chatID, messageID: response.id) { $0.error = "Ответ оборвался на стороне сервиса. Повторите запрос." }
                }
            } catch {
                guard self.activeRunID == runID else { return }
                if error is CancellationError || (error as? URLError)?.code == .cancelled {
                    self.mutateMessage(chatID: chatID, messageID: response.id) { $0.isInterrupted = true }
                } else {
                    let description = (error as? URLError)?.code == .notConnectedToInternet
                        ? "Нет подключения к интернету. Проверьте соединение и повторите запрос."
                        : error.localizedDescription
                    self.errorMessage = description
                    self.mutateMessage(chatID: chatID, messageID: response.id) { $0.error = description }
                }
            }
            guard self.activeRunID == runID else { return }
            self.activeRunID = nil
            self.activeConversationID = nil
            self.activeMessageID = nil
            self.flushStreamingBuffer = nil
            self.isGenerating = false
            self.generationStatus = nil
            self.generationTask = nil
            // Живой буфер больше не нужен: текст уже перенесён в модель чата.
            self.live.content = ""
            self.live.reasoning = ""
            self.live.reasoningSeconds = 0
            let delivered = self.conversations.first(where: { $0.id == chatID })?
                .messages.first(where: { $0.id == response.id })
            if !(delivered?.content.isEmpty ?? true) {
                self.recordReceivedMessage()
                // Реакция агента на сообщение пользователя (пункт 38 ТЗ).
                self.extractAssistantReaction(chatID: chatID, messageID: response.id)
            }
            // Последняя проверка: сообщение не может остаться без текста и без ошибки.
            self.ensureVisibleOutcome(chatID: chatID, messageID: response.id)
            self.saveSnapshot()
            // Уведомление о готовом ответе, если пользователь вышел из приложения (пункт 36).
            if !(delivered?.content.isEmpty ?? true) {
                NotificationCenterService.shared.notifyAnswerReady(delivered?.content ?? "")
            }
        }
    }

    /// Вызов инструментов моделью включён: модель может читать и править другие чаты,
    /// писать в них, сохранять факты в память и менять настройки приложения.
    /// Важно: при включённых инструментах API требует возвращать `reasoning_content`
    /// предыдущего прохода — это делается в assistant-сообщении ниже.
    static let toolsEnabled = true

    /// Собирает данные для инструментов: список всех чатов и переписку каждого.
    private func toolExecutionContext() -> ToolExecutionContext {
        var overviews: [ChatOverview] = []
        var transcripts: [Int: [ChatTranscriptLine]] = [:]
        for (index, chat) in sortedConversations.enumerated() {
            let number = index + 1
            let preview = chat.messages.last(where: { !$0.content.isEmpty })
                .map { String($0.content.prefix(120)) } ?? ""
            overviews.append(ChatOverview(number: number,
                                          title: chat.title,
                                          messageCount: chat.messages.count,
                                          lastMessageAt: chat.lastMessageAt,
                                          pinned: chat.pinned,
                                          archived: chat.archivedAt != nil,
                                          preview: preview))
            transcripts[number] = chat.messages.suffix(120).map {
                ChatTranscriptLine(role: $0.role.rawValue, text: String($0.content.prefix(2000)))
            }
        }
        return ToolExecutionContext(
            deviceModel: DeviceModel.name,
            systemVersion: UIDevice.current.systemVersion,
            appVersion: "10.26",
            messageCount: messages.count,
            voiceMessageCount: messages.filter { $0.inputKind == .voice }.count,
            chatStartedAt: selectedConversation?.createdAt,
            lastMessageAt: messages.last?.createdAt,
            chats: overviews,
            transcripts: transcripts)
    }

    /// Применяет действие, которое попросила модель: сообщение в другой чат, память,
    /// настройки, переименование и закрепление чатов.
    private func apply(_ effect: ToolEffect) {
        switch effect {
        case .sendToChat(let number, let text):
            guard let chat = chat(forNumber: number) else { return }
            var message = ChatMessage(role: .assistant, content: text)
            message.inputKind = .text
            mutateChat(chatID: chat.id) { conversation in
                conversation.messages.append(message)
                conversation.updatedAt = Date()
            }
            saveSnapshot()
        case .saveMemory(let text):
            _ = addMemory(text)
        case .setSetting(let name, let value):
            applySetting(name: name, value: value)
        }
    }

    private func chat(forNumber number: Int) -> Conversation? {
        let sorted = sortedConversations
        guard number >= 1, number <= sorted.count else { return nil }
        return sorted[number - 1]
    }

    private func mutateChat(chatID: UUID, update: (inout Conversation) -> Void) {
        guard let index = conversations.firstIndex(where: { $0.id == chatID }) else { return }
        update(&conversations[index])
    }

    /// Настройки, которые разрешено менять модели, и служебные команды чатов.
    private func applySetting(name: String, value: String) {
        let lowered = name.lowercased()
        if lowered.hasPrefix("rename_chat:") {
            guard let number = Int(lowered.replacingOccurrences(of: "rename_chat:", with: "")),
                  let chat = chat(forNumber: number) else { return }
            mutateChat(chatID: chat.id) { $0.title = String(value.prefix(100)) }
            saveSnapshot()
            return
        }
        if lowered.hasPrefix("pin_chat:") {
            guard let number = Int(lowered.replacingOccurrences(of: "pin_chat:", with: "")),
                  let chat = chat(forNumber: number) else { return }
            mutateChat(chatID: chat.id) { conversation in
                conversation.pinned = value == "true"
                if !conversation.pinned { conversation.pinOrder = 0 }
            }
            saveSnapshot()
            return
        }
        let flag = ["true", "1", "да", "вкл", "on", "yes"].contains(value.lowercased())
        switch lowered {
        case "reasoning": reasoningEnabled = flag
        case "search": searchEnabled = flag
        case "notifications": settingsBridge?.notificationsEnabled = flag
        case "autoread": settingsBridge?.autoRead = flag
        case "fontscale":
            if let scale = Double(value.replacingOccurrences(of: ",", with: ".")) {
                let clamped = min(max(scale, 0.85), 1.5)
                settingsBridge?.fontScale = clamped
            }
        default: break
        }
    }

    /// Запасной путь перевода: по частям, как было раньше. Нужен, если общий запрос
    /// не прошёл — например, сервис оборвал ответ.
    private func normalizeSeparately(normalizer: RussianTextNormalizing, chatID: UUID, messageID: UUID,
                                     sourceContent: String, sourceReasoning: String,
                                     contentNeeded: Bool, reasoningNeeded: Bool, runID: UUID) async {
        if contentNeeded {
            self.generationStatus = "Перевожу ответ на русский…"
            if let translated = try? await normalizer.normalizeRussian(sourceContent, reasoning: false),
               !Task.isCancelled, self.activeRunID == runID {
                self.mutateMessage(chatID: chatID, messageID: messageID) { $0.content = translated }
            }
        }
        if reasoningNeeded, !Task.isCancelled, self.activeRunID == runID {
            if let translated = try? await normalizer.normalizeRussian(sourceReasoning, reasoning: true),
               !Task.isCancelled, self.activeRunID == runID {
                self.mutateMessage(chatID: chatID, messageID: messageID) {
                    $0.reasoning = translated
                    $0.reasoningWasTranslated = true
                }
            } else {
                self.mutateMessage(chatID: chatID, messageID: messageID) {
                    if $0.reasoning.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        $0.reasoning = sourceReasoning
                    }
                    $0.reasoningStayedForeign = true
                }
            }
        }
    }

    /// Сериализует вызовы инструментов в формат API: id, тип и имя с аргументами.
    /// Нужно, чтобы вернуть их в запросе вместе с результатами.
    static func toolCallsJSON(_ calls: [ToolCallRequest]) -> String {
        let list: [[String: Any]] = calls.filter { !$0.name.isEmpty }.map { call in
            [
                "id": call.id.isEmpty ? "call_\(UUID().uuidString.prefix(8))" : call.id,
                "type": "function",
                "function": ["name": call.name, "arguments": call.arguments.isEmpty ? "{}" : call.arguments]
            ]
        }
        guard !list.isEmpty,
              let data = try? JSONSerialization.data(withJSONObject: list),
              let text = String(data: data, encoding: .utf8) else { return "" }
        return text
    }

    /// Склеивает куски одного вызова инструмента. Куски одного вызова опознаются
    /// по index (он есть всегда), затем по id, затем по имени функции.
    static func mergeToolCall(_ call: ToolCallRequest, into calls: inout [ToolCallRequest]) {
        if let index = call.index,
           let existing = calls.firstIndex(where: { $0.index == index }) {
            calls[existing].arguments += call.arguments
            if calls[existing].id.isEmpty, !call.id.isEmpty { calls[existing].id = call.id }
            if calls[existing].name.isEmpty, !call.name.isEmpty { calls[existing].name = call.name }
            return
        }
        if !call.id.isEmpty, let existing = calls.firstIndex(where: { $0.id == call.id }) {
            calls[existing].arguments += call.arguments
            if calls[existing].name.isEmpty, !call.name.isEmpty { calls[existing].name = call.name }
            if calls[existing].index == nil { calls[existing].index = call.index }
            return
        }
        if !call.name.isEmpty, let existing = calls.firstIndex(where: { $0.name == call.name && $0.index == call.index }) {
            calls[existing].arguments += call.arguments
            if calls[existing].id.isEmpty, !call.id.isEmpty { calls[existing].id = call.id }
            return
        }
        calls.append(call)
    }

    /// Ответ короче этого порога — обрывок, а не ответ.
    static func isTooShortToBeAnAnswer(_ text: String) -> Bool {
        RussianTextPolicy.isTooShortToBeAnAnswer(text)
    }

    /// Модель вместо ответа объявляет о намерении: «Сначала найду чаты, затем открою нужный»,
    /// «Без вызова инструмента не могу». Живой тест показал, что такой текст приходит вместо
    /// результата, и пользователь не получает ответа. Такой ответ считаем незавершённым.
    static func isToolAnnouncement(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.count < 400 else { return false }
        let lowered = trimmed.lowercased()
        let markers = ["сначала найду", "сначала проверю", "сейчас найду", "сейчас прочитаю",
                       "затем открою", "затем прочитаю", "сейчас вызову", "вызову инструмент",
                       "без вызова инструмента", "нашёл ваш чат", "нашел ваш чат",
                       "прочитал его", "прочитал чат", "нашёл чат", "нашел чат",
                       "let me check", "let me look", "i will call", "let me read",
                       "i'll check", "i found your chat"]
        return markers.contains { lowered.contains($0) }
    }

    /// Убирает из ответа объявления о действиях: «Сначала найду чат…», «Сейчас прочитаю…».
    ///
    /// Модель вставляет такие фразы перед настоящим ответом и часто склеивает их
    /// без пробела: «Сначала посмотрю список чатов.В вашем чате написано: 12 дней».
    /// Поэтому берём первое смысловое звено (до точки, вопросительного, восклицательного
    /// знака, многоточия или перевода строки), проверяем, похоже ли оно на обещание
    /// что-то найти или прочитать, и срезаем его. Так уходит и одна фраза, и несколько
    /// подряд («Сейчас найду чат. Затем прочитаю его и отвечу.»). Если после среза
    /// не осталось содержательного текста, возвращаем пустую строку — вызывающий код
    /// запросит нормальный ответ заново.
    static func strippingToolAnnouncements(_ text: String) -> String {
        var value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return value }
        guard let splitter = try? NSRegularExpression(pattern: "(?s)^.*?(?:[.!?…]+|\\n|$)") else { return value }
        let verbs = ["найду", "найти", "прочитаю", "прочитать", "открою", "открыть",
                     "вызову", "вызвать", "проверю", "проверить", "посмотрю", "посмотреть",
                     "отвечу", "ответить", "сформулирую", "уточню", "достану", "извлеку",
                     "разберу", "составлю", "подготовлю", "дам ответ",
                     "let me", "i will", "i'll", "i am going to"]
        let nouns = ["чат", "переписк", "список", "инструмент", "сообщени", "chat", "tool"]
        let markers = ["сначала", "затем", "потом", "теперь", "для этого", "после этого",
                       "first,", "then i"]
        func isAnnouncement(_ sentence: String) -> Bool {
            let lowered = sentence.lowercased()
            guard !lowered.isEmpty, lowered.count < 220 else { return false }
            // В настоящем ответе почти всегда есть числа и списки — обещание их не содержит.
            guard !lowered.contains(where: { $0.isNumber }) else { return false }
            guard verbs.contains(where: { lowered.contains($0) }) else { return false }
            if nouns.contains(where: { lowered.contains($0) }) { return true }
            return markers.contains { lowered.contains($0) }
        }
        for _ in 0..<5 {
            let range = NSRange(value.startIndex..., in: value)
            guard let match = splitter.firstMatch(in: value, range: range),
                  let whole = Range(match.range, in: value) else { break }
            let first = String(value[whole]).trimmingCharacters(in: .whitespacesAndNewlines)
            guard isAnnouncement(first) else { break }
            let rest = String(value[whole.upperBound...]).trimmingCharacters(in: .whitespacesAndNewlines)
            guard rest.count >= 12 else { return "" }
            value = rest
        }
        return value
    }

    /// Нужен ли повтор: ответ пуст, оборван или это объявление о действии без самого ответа.
    static func needsAnswerRecovery(_ text: String) -> Bool {
        isTooShortToBeAnAnswer(text) || isToolAnnouncement(text)
    }

    private func mutateMessage(chatID: UUID, messageID: UUID, update: (inout ChatMessage) -> Void) {
        guard let chatIndex = conversations.firstIndex(where: { $0.id == chatID }),
              let messageIndex = conversations[chatIndex].messages.firstIndex(where: { $0.id == messageID }) else { return }
        update(&conversations[chatIndex].messages[messageIndex])
    }

    private func removeUnreferencedAttachments(_ candidates: [MessageAttachment]) {
        guard !candidates.isEmpty else { return }
        let retained = conversations.flatMap(\.messages).flatMap(\.attachments) + attachments
        let retainedPaths = Set(retained.flatMap(\.allLocalURLs).map { $0.standardizedFileURL.path })
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("HonorPKAgent/Attachments", isDirectory: true).standardizedFileURL.path + "/"
        for candidate in candidates.flatMap(\.allLocalURLs) {
            let url = candidate.standardizedFileURL
            guard url.path.hasPrefix(root), !retainedPaths.contains(url.path) else { continue }
            try? FileManager.default.removeItem(at: url)
        }
    }

    private func scheduleSave() {
        guard !isLoading, persistenceTask == nil else { return }
        persistenceTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 700_000_000)
            guard !Task.isCancelled, let self else { return }
            self.persistenceTask = nil
            self.saveSnapshot()
        }
    }

    private func saveSnapshot() {
        guard !isLoading else { return }
        persistenceTask?.cancel()
        persistenceTask = nil
        persistence.enqueue(archive) { [weak self] error in
            Task { @MainActor in self?.errorMessage = "Не удалось сохранить историю: \(error.localizedDescription)" }
        }
    }

    /// Explicit durability boundary for scene backgrounding and callers that must immediately read disk.
    func persistNow() {
        guard !isLoading else { return }
        persistenceTask?.cancel()
        persistenceTask = nil
        do {
            try persistence.saveSynchronously(archive)
        } catch {
            errorMessage = "Не удалось сохранить историю: \(error.localizedDescription)"
        }
    }

    func exportData() throws -> URL {
        try HistoryArchiveIO.export(archive)
    }

    func exportDataAsync() async throws -> URL {
        let snapshot = archive
        return try await Task.detached(priority: .userInitiated) {
            try HistoryArchiveIO.export(snapshot)
        }.value
    }

    func importData(from url: URL) throws {
        let incoming = try HistoryArchiveIO.prepareImport(from: url, excluding: Set(conversations.map(\.id)))
        try applyImport(incoming)
    }

    func importDataAsync(from url: URL) async throws {
        let existingIDs = Set(conversations.map(\.id))
        let incoming = try await Task.detached(priority: .userInitiated) {
            try HistoryArchiveIO.prepareImport(from: url, excluding: existingIDs)
        }.value
        try applyImport(incoming)
    }

    private func applyImport(_ incoming: HistoryArchive) throws {
        var mergedMemories = memories
        for memory in incoming.memories ?? [] {
            guard !mergedMemories.contains(where: { $0.id == memory.id || $0.text.caseInsensitiveCompare(memory.text) == .orderedSame }) else { continue }
            mergedMemories.append(memory)
        }
        guard mergedMemories.count <= Self.maximumMemoryCount else {
            removeUnreferencedAttachments(incoming.conversations.flatMap(\.messages).flatMap(\.attachments))
            throw HonorError.memoryLimit
        }
        stop()
        let wasEmpty = conversations.isEmpty && memories.isEmpty
        let existing = Set(conversations.map(\.id))
        conversations += incoming.conversations.filter { !existing.contains($0.id) }
        conversations.sort { $0.updatedAt > $1.updatedAt }
        memories = mergedMemories
        if wasEmpty { memoryEnabled = incoming.memoryEnabled ?? true }
        saveSnapshot()
    }

    private var archive: HistoryArchive {
        HistoryArchive(conversations: conversations, selectedConversationID: selectedConversationID, draft: draft, attachments: attachments, inFlightMessageID: activeMessageID, memories: memories, memoryEnabled: memoryEnabled)
    }

    private func applyLoadedHistory(_ result: HistoryReadResult) {
        if let loaded = result.archive {
            conversations = loaded.conversations
            selectedConversationID = conversations.contains(where: { $0.id == loaded.selectedConversationID && $0.archivedAt == nil }) ? loaded.selectedConversationID : nil
            draft = loaded.draft
            attachments = loaded.attachments
            memories = Array((loaded.memories ?? []).filter { !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && $0.text.count <= Self.maximumMemoryLength }.prefix(Self.maximumMemoryCount))
            memoryEnabled = loaded.memoryEnabled ?? true
            // A process termination can leave an incomplete placeholder; make the interrupted state visible.
            // Проверяем ВСЕ сообщения, а не только последнее: пустое сообщение ассистента,
            // после которого пользователь успел написать ещё одно, иначе осталось бы
            // немым навсегда — ни текста, ни ошибки.
            for chatIndex in conversations.indices {
                for messageIndex in conversations[chatIndex].messages.indices {
                    let message = conversations[chatIndex].messages[messageIndex]
                    guard message.role == .assistant else { continue }
                    let isEmpty = message.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    guard isEmpty || message.id == loaded.inFlightMessageID else { continue }
                    guard message.error == nil else { continue }
                    conversations[chatIndex].messages[messageIndex].isInterrupted = true
                }
            }
        }
        if let error = result.error { errorMessage = error }
    }

}

/// A single serial writer coalesces pending snapshots and performs JSON encoding away from the UI.
private final class HistoryPersistence: @unchecked Sendable {
    private struct Pending { let archive: HistoryArchive; let onError: (Error) -> Void }
    private let url: URL
    private let queue = DispatchQueue(label: "com.honorpk.history", qos: .utility)
    private let lock = NSLock()
    private var pending: Pending?
    private var draining = false

    init(url: URL) { self.url = url }

    func enqueue(_ archive: HistoryArchive, onError: @escaping (Error) -> Void) {
        lock.lock()
        pending = Pending(archive: archive, onError: onError)
        let shouldStart = !draining
        draining = true
        lock.unlock()
        if shouldStart { queue.async { self.drain() } }
    }

    private func drain() {
        while true {
            lock.lock()
            guard let next = pending else { draining = false; lock.unlock(); return }
            pending = nil
            lock.unlock()
            do { try write(next.archive) } catch { next.onError(error) }
        }
    }

    func saveSynchronously(_ archive: HistoryArchive) throws {
        lock.lock(); pending = nil; lock.unlock()
        try queue.sync { try self.write(archive) }
    }

    private func write(_ archive: HistoryArchive) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try HistoryArchiveIO.encoder.encode(archive).write(to: url, options: .atomic)
    }
}

private enum HistoryArchiveIO {
    static func readHistory(_ url: URL) -> HistoryReadResult {
        guard FileManager.default.fileExists(atPath: url.path) else { return HistoryReadResult() }
        do {
            let loaded = try decoder.decode(HistoryArchive.self, from: Data(contentsOf: url))
            guard loaded.version == 1 else { throw HonorError.invalidArchive }
            return HistoryReadResult(archive: loaded)
        } catch {
            let backup = url.deletingPathExtension().appendingPathExtension("unreadable-\(Int(Date().timeIntervalSince1970)).json")
            try? FileManager.default.copyItem(at: url, to: backup)
            return HistoryReadResult(error: "Не удалось открыть историю. Исходный файл сохранён отдельно: \(backup.lastPathComponent).")
        }
    }

    static var encoder: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return encoder
    }

    static var decoder: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }

    static func export(_ snapshot: HistoryArchive) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("Honor-История-\(UUID().uuidString.prefix(8)).json")
        var exported = snapshot
        var files: [String: Data] = [:]
        var frameFiles: [String: [Data]] = [:]
        var references: [String: String] = [:]
        var canonicalFiles: [String: String] = [:]
        var totalBytes = 0
        for attachment in snapshot.conversations.flatMap(\.messages).flatMap(\.attachments) + snapshot.attachments {
            let id = attachment.id.uuidString
            guard files[id] == nil, references[id] == nil, let fileURL = attachment.resolvedURL else { continue }
            if let canonicalID = canonicalFiles[fileURL.standardizedFileURL.path] { references[id] = canonicalID; continue }
            let data = try Data(contentsOf: fileURL)
            totalBytes += data.count
            guard totalBytes <= 64 * 1024 * 1024 else { throw HonorError.archiveTooLarge }
            files[id] = data
            if attachment.kind == .video {
                var frames: [Data] = []
                for frameURL in attachment.resolvedFrameURLs.prefix(8) {
                    let frame = try Data(contentsOf: frameURL)
                    totalBytes += frame.count
                    guard totalBytes <= 64 * 1024 * 1024 else { throw HonorError.archiveTooLarge }
                    frames.append(frame)
                }
                frameFiles[id] = frames
            }
            canonicalFiles[fileURL.standardizedFileURL.path] = id
        }
        exported.attachmentFiles = files
        exported.attachmentFileReferences = references
        exported.attachmentFrameFiles = frameFiles
        let data = try encoder.encode(exported)
        guard data.count <= 100 * 1024 * 1024 else { throw HonorError.archiveTooLarge }
        try data.write(to: url, options: .atomic)
        return url
    }

    static func prepareImport(from url: URL, excluding existingIDs: Set<UUID>) throws -> HistoryArchive {
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        let resource = try url.resourceValues(forKeys: [.fileSizeKey])
        guard (resource.fileSize ?? 0) <= 100 * 1024 * 1024 else { throw HonorError.archiveTooLarge }
        let bytes = try Data(contentsOf: url)
        guard bytes.count <= 100 * 1024 * 1024 else { throw HonorError.archiveTooLarge }
        var incoming = try decoder.decode(HistoryArchive.self, from: bytes)
        guard incoming.version == 1, Set(incoming.conversations.map(\.id)).count == incoming.conversations.count,
              incoming.conversations.allSatisfy({ Set($0.messages.map(\.id)).count == $0.messages.count }),
              (incoming.memories?.count ?? 0) <= 50,
              (incoming.memories ?? []).allSatisfy({ !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && $0.text.count <= 1000 }),
              (incoming.attachmentFiles ?? [:]).values.allSatisfy({ $0.count <= 40 * 1024 * 1024 }),
              (incoming.attachmentFrameFiles ?? [:]).values.allSatisfy({ $0.count <= 8 && $0.allSatisfy { $0.count <= 5 * 1024 * 1024 } }),
              (incoming.attachmentFiles ?? [:]).values.reduce(0, { $0 + $1.count }) +
                (incoming.attachmentFrameFiles ?? [:]).values.flatMap({ $0 }).reduce(0, { $0 + $1.count }) <= 64 * 1024 * 1024 else { throw HonorError.invalidArchive }
        incoming.conversations.removeAll { existingIDs.contains($0.id) }
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("HonorPKAgent/Attachments", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var restored: [String: String] = [:]
        var restoredFrames: [String: [String]] = [:]
        var createdURLs: [URL] = []
        do {
            for chatIndex in incoming.conversations.indices {
                for messageIndex in incoming.conversations[chatIndex].messages.indices {
                    if incoming.conversations[chatIndex].messages[messageIndex].id == incoming.inFlightMessageID,
                       incoming.conversations[chatIndex].messages[messageIndex].role == .assistant {
                        incoming.conversations[chatIndex].messages[messageIndex].isInterrupted = true
                    }
                    for attachmentIndex in incoming.conversations[chatIndex].messages[messageIndex].attachments.indices {
                        var attachment = incoming.conversations[chatIndex].messages[messageIndex].attachments[attachmentIndex]
                        let suffix = attachment.localPath.map { URL(fileURLWithPath: $0).pathExtension.lowercased() } ?? ""
                        attachment.localPath = nil // Imported paths can never authorize local file reads.
                        attachment.videoFramePaths = nil
                        let id = attachment.id.uuidString
                        let canonicalID = incoming.attachmentFileReferences?[id] ?? id
                        if let path = restored[canonicalID] { attachment.localPath = path }
                        else if let data = incoming.attachmentFiles?[canonicalID] {
                            let allowed = ["jpg", "jpeg", "png", "gif", "webp", "pdf", "txt", "md", "csv", "json", "mp4", "mov", "m4v"]
                            let ext = allowed.contains(suffix) ? suffix : (attachment.kind == .image ? "jpg" : "txt")
                            let target = directory.appendingPathComponent("\(UUID().uuidString).\(ext)")
                            try data.write(to: target, options: .atomic)
                            createdURLs.append(target)
                            restored[canonicalID] = target.path
                            attachment.localPath = target.path
                        }
                        if attachment.kind == .video {
                            if let paths = restoredFrames[canonicalID] { attachment.videoFramePaths = paths }
                            else if let frames = incoming.attachmentFrameFiles?[canonicalID] {
                                var paths: [String] = []
                                for data in frames {
                                    let target = directory.appendingPathComponent("\(UUID().uuidString).jpg")
                                    try data.write(to: target, options: .atomic)
                                    createdURLs.append(target); paths.append(target.path)
                                }
                                restoredFrames[canonicalID] = paths
                                attachment.videoFramePaths = paths
                            }
                        }
                        incoming.conversations[chatIndex].messages[messageIndex].attachments[attachmentIndex] = attachment
                    }
                }
            }
        } catch {
            for createdURL in createdURLs { try? FileManager.default.removeItem(at: createdURL) }
            throw error
        }
        incoming.attachmentFiles = nil
        incoming.attachmentFileReferences = nil
        incoming.attachmentFrameFiles = nil
        return incoming
    }
}

private struct HistoryReadResult: Sendable {
    var archive: HistoryArchive? = nil
    var error: String? = nil
}

