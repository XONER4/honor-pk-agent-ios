import Foundation
import Combine

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
        conversations[chatIndex].messages[messageIndex].content =
            content.replacingCharacters(in: whole, with: "").trimmingCharacters(in: .whitespacesAndNewlines)
        if let userIndex = conversations[chatIndex].messages[..<messageIndex].lastIndex(where: { $0.role == .user }) {
            conversations[chatIndex].messages[userIndex].assistantReaction = emoji
        }
    }

    /// Статистика использования приложения (раздел «Статистика» в настройках).
    @Published private(set) var statistics = UsageStatistics() {
        didSet { persistStatistics() }
    }
    @Published var memoryEnabled = true { didSet { scheduleSave() } }
    @Published private var configuration: DeepSeekConfiguration

    static let maximumMemoryCount = 1000
    static let maximumMemoryLength = 4000

    var systemInstruction = ""
    var profileName = ""
    var selectedConversation: Conversation? { conversations.first { $0.id == selectedConversationID && $0.archivedAt == nil } }
    var archivedConversations: [Conversation] { conversations.filter { $0.archivedAt != nil }.sorted { ($0.archivedAt ?? .distantPast) > ($1.archivedAt ?? .distantPast) } }
    var messages: [ChatMessage] { selectedConversation?.messages ?? [] }
    var hasAPIKey: Bool { !configuration.apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
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
        self.reasoningEnabled = UserDefaults.standard.object(forKey: "honor.reasoningEnabled") as? Bool ?? true
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
                    self.mutateMessage(chatID: chatID, messageID: response.id) {
                        if contentChanged { $0.content = rawContent }
                        if reasoningChanged { $0.reasoning = rawReasoning }
                        $0.reasoningSeconds = seconds
                    }
                    pendingContent = ""; pendingReasoning = ""; lastPublished = Date()
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
                        // Собираем вызовы инструментов, склеивая аргументы по индексу.
                        for call in delta.toolCalls {
                            if let existing = toolCalls.firstIndex(where: { $0.id == call.id && !call.id.isEmpty }) {
                                toolCalls[existing].arguments += call.arguments
                                if toolCalls[existing].name.isEmpty { toolCalls[existing].name = call.name }
                            } else {
                                toolCalls.append(call)
                            }
                        }
                        pendingContent += delta.content
                        pendingReasoning += delta.reasoning
                        finishReason = delta.finishReason ?? finishReason
                        if Date().timeIntervalSince(lastPublished) >= 0.1 { flush() }
                        if Date().timeIntervalSince(lastSaved) >= 1.5 { self.saveSnapshot(); lastSaved = Date() }
                    }
                    flush()
                } catch {
                    if self.activeRunID == runID { flush() }
                    throw error
                }

                // Выполняем запрошенные инструменты и повторяем запрос с результатами.
                while !toolCalls.isEmpty, toolRounds < 3 {
                    toolRounds += 1
                    let context = ToolExecutionContext(
                        deviceModel: DeviceModel.name,
                        systemVersion: UIDevice.current.systemVersion,
                        appVersion: "10.6",
                        messageCount: self.messages.count,
                        voiceMessageCount: self.messages.filter { $0.inputKind == .voice }.count,
                        chatStartedAt: self.selectedConversation?.createdAt,
                        lastMessageAt: self.messages.last?.createdAt)
                    self.generationStatus = "Выполняю действие…"
                    for call in toolCalls {
                        let result = ToolExecutor.execute(call, context: context)
                        toolResults.append(result)
                    }
                    toolCalls.removeAll()

                    var followUp = input
                    var assistant = ChatMessage(role: .assistant)
                    assistant.content = rawContent
                    followUp.append(assistant)
                    for result in toolResults {
                        var toolMessage = ChatMessage(role: .user)
                        toolMessage.content = "Результат вызова инструмента «\(result.name)»: \(result.content)\nУчти это в ответе и не вызывай этот инструмент повторно без необходимости."
                        followUp.append(toolMessage)
                    }

                    self.generationStatus = "Отвечаю…"
                    do {
                        for try await delta in client.stream(messages: followUp, thinking: thinking,
                                                             systemInstruction: instruction, searchContext: context,
                                                             tools: HonerTool.apiSchemas) {
                            try Task.checkCancellation()
                            guard self.activeRunID == runID else { return }
                            for call in delta.toolCalls {
                                if let existing = toolCalls.firstIndex(where: { $0.id == call.id && !call.id.isEmpty }) {
                                    toolCalls[existing].arguments += call.arguments
                                } else {
                                    toolCalls.append(call)
                                }
                            }
                            pendingContent += delta.content
                            pendingReasoning += delta.reasoning
                            finishReason = delta.finishReason ?? finishReason
                            if Date().timeIntervalSince(lastPublished) >= 0.1 { flush() }
                        }
                        flush()
                    } catch {
                        if self.activeRunID == runID { flush() }
                    }
                }
                guard self.activeRunID == runID else { return }
                if let russianNormalizer {
                    let normalizeContent = RussianTextPolicy.needsNormalization(rawContent)
                    let normalizeReasoning = RussianTextPolicy.needsNormalization(rawReasoning)
                    self.mutateMessage(chatID: chatID, messageID: response.id) {
                        if !normalizeContent { $0.content = rawContent }
                        if !normalizeReasoning { $0.reasoning = rawReasoning }
                    }
                    if normalizeContent {
                        self.generationStatus = "Перевожу ответ на русский…"
                        let translated = try await russianNormalizer.normalizeRussian(rawContent, reasoning: false)
                        try Task.checkCancellation()
                        guard self.activeRunID == runID else { return }
                        self.mutateMessage(chatID: chatID, messageID: response.id) { $0.content = translated }
                    }
                    if normalizeReasoning {
                        self.generationStatus = "Перевожу описание рассуждения…"
                        do {
                            let translated = try await russianNormalizer.normalizeRussian(rawReasoning, reasoning: true)
                            try Task.checkCancellation()
                            guard self.activeRunID == runID else { return }
                            self.mutateMessage(chatID: chatID, messageID: response.id) { $0.reasoning = translated; $0.reasoningWasTranslated = true }
                        } catch {
                            if Task.isCancelled { throw CancellationError() }
                            self.mutateMessage(chatID: chatID, messageID: response.id) {
                                $0.reasoning = "Описание рассуждения на русском сейчас недоступно. Итоговый ответ сохранён."
                                $0.reasoningWasTranslated = true
                            }
                        }
                    }
                }
                let final = self.conversations.first(where: { $0.id == chatID })?.messages.first(where: { $0.id == response.id })
                guard !(final?.content.isEmpty ?? true) else { throw HonorError.emptyResponse }
                if finishReason == "length" {
                    self.mutateMessage(chatID: chatID, messageID: response.id) { $0.error = "Достигнута максимальная длина ответа. Попросите продолжить." }
                } else if finishReason == "content_filter" {
                    self.mutateMessage(chatID: chatID, messageID: response.id) { $0.error = "Сервис остановил этот ответ." }
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
            let delivered = self.conversations.first(where: { $0.id == chatID })?
                .messages.first(where: { $0.id == response.id })
            if !(delivered?.content.isEmpty ?? true) {
                self.recordReceivedMessage()
                // Реакция агента на сообщение пользователя (пункт 38 ТЗ).
                self.extractAssistantReaction(chatID: chatID, messageID: response.id)
            }
            self.saveSnapshot()
            // Уведомление о готовом ответе, если пользователь вышел из приложения (пункт 36).
            if !(delivered?.content.isEmpty ?? true) {
                NotificationCenterService.shared.notifyAnswerReady(delivered?.content ?? "")
            }
        }
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
            for chatIndex in conversations.indices {
                guard let last = conversations[chatIndex].messages.indices.last else { continue }
                if conversations[chatIndex].messages[last].role == .assistant,
                   (conversations[chatIndex].messages[last].content.isEmpty || conversations[chatIndex].messages[last].id == loaded.inFlightMessageID),
                   conversations[chatIndex].messages[last].error == nil {
                    conversations[chatIndex].messages[last].isInterrupted = true
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

