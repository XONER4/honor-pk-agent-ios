import Foundation
import Combine

@MainActor
final class ChatStore: ObservableObject {
    @Published var conversations: [Conversation] = []
    @Published var selectedConversationID: UUID?
    @Published var draft: String = "" { didSet { scheduleSave() } }
    @Published var attachments: [MessageAttachment] = [] { didSet { scheduleSave() } }
    @Published private(set) var isGenerating = false
    @Published var reasoningEnabled = true
    @Published var searchEnabled = false
    @Published var errorMessage: String?
    @Published private(set) var generationStatus: String?
    @Published private(set) var editingMessageID: UUID?
    @Published private var configuration: DeepSeekConfiguration

    var systemInstruction = ""
    var selectedConversation: Conversation? { conversations.first { $0.id == selectedConversationID } }
    var messages: [ChatMessage] { selectedConversation?.messages ?? [] }
    var hasAPIKey: Bool { !configuration.apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    var canSend: Bool { !isGenerating && (!draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !attachments.isEmpty) }

    private let injectedClient: DeepSeekStreaming?
    private let searchClient: WebSearching
    private let storageURL: URL
    private var generationTask: Task<Void, Never>?
    private var persistenceTask: Task<Void, Never>?
    private var activeRunID: UUID?
    private var activeConversationID: UUID?
    private var activeMessageID: UUID?
    private var isLoading = true

    init(configuration: DeepSeekConfiguration = .bundled,
         client: DeepSeekStreaming? = nil,
         searchClient: WebSearching = WebSearchClient(),
         storageURL: URL? = nil) {
        self.configuration = configuration
        self.injectedClient = client
        self.searchClient = searchClient
        self.storageURL = storageURL ?? Self.defaultStorageURL
        loadHistory()
        isLoading = false
    }

    static var defaultStorageURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("HonorPK", isDirectory: true).appendingPathComponent("history.json")
    }

    func updateAPIKey(_ key: String) {
        configuration.apiKey = key.trimmingCharacters(in: .whitespacesAndNewlines)
        errorMessage = nil
    }

    func send() {
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
        if let editingID = editingMessageID,
           let messageIndex = conversations[index].messages.firstIndex(where: { $0.id == editingID && $0.role == .user }) {
            conversations[index].messages = Array(conversations[index].messages.prefix(messageIndex))
        }
        let user = ChatMessage(role: .user, content: text, attachments: outgoingAttachments)
        conversations[index].messages.append(user)
        if conversations[index].messages.filter({ $0.role == .user }).count == 1 {
            let title = text.isEmpty ? (outgoingAttachments.first?.name ?? "Новый чат") : text
            conversations[index].title = String(title.replacingOccurrences(of: "\n", with: " ").prefix(48))
        }
        conversations[index].updatedAt = Date()
        draft = ""
        attachments = []
        editingMessageID = nil
        beginGeneration(in: chatID)
    }

    func stop() {
        guard isGenerating else { return }
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
        persistNow()
    }

    func regenerate(messageID: UUID) {
        guard hasAPIKey else { errorMessage = HonorError.missingAPIKey.localizedDescription; return }
        stop()
        guard let chatID = selectedConversationID,
              let chatIndex = conversations.firstIndex(where: { $0.id == chatID }),
              let messageIndex = conversations[chatIndex].messages.firstIndex(where: { $0.id == messageID && $0.role == .assistant }),
              conversations[chatIndex].messages[..<messageIndex].contains(where: { $0.role == .user }) else { return }
        conversations[chatIndex].messages = Array(conversations[chatIndex].messages.prefix(messageIndex))
        editingMessageID = nil
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
        persistNow()
    }

    func selectChat(id: UUID) {
        guard conversations.contains(where: { $0.id == id }) else { return }
        guard selectedConversationID != id else { return }
        stop()
        selectedConversationID = id
        editingMessageID = nil
        draft = ""
        attachments = []
        errorMessage = nil
        persistNow()
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
        persistNow()
    }

    func togglePin(ids: Set<UUID>) {
        let matching = conversations.filter { ids.contains($0.id) }
        guard !matching.isEmpty else { return }
        let pin = !matching.allSatisfy(\.pinned)
        for index in conversations.indices where ids.contains(conversations[index].id) { conversations[index].pinned = pin }
        persistNow()
    }

    func renameChat(id: UUID, title: String) {
        let title = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty, let index = conversations.firstIndex(where: { $0.id == id }) else { return }
        conversations[index].title = String(title.prefix(100))
        persistNow()
    }

    func setFeedback(messageID: UUID, feedback: MessageFeedback?) {
        guard let chatID = selectedConversationID else { return }
        mutateMessage(chatID: chatID, messageID: messageID) { $0.feedback = feedback }
        persistNow()
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
        persistNow()
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
        let searching = searchEnabled
        let instruction = systemInstruction
        let client = injectedClient ?? DeepSeekClient(configuration: configuration)
        generationStatus = searching ? "Ищу в интернете…" : (thinking ? "Размышляю…" : "Отвечаю…")
        persistNow()

        generationTask = Task { [weak self] in
            guard let self else { return }
            do {
                try Task.checkCancellation()
                guard self.activeRunID == runID else { return }
                var context = ""
                if searching {
                    let query = input.last(where: { $0.role == .user })?.content ?? ""
                    guard !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw HonorError.searchUnavailable }
                    let sources = try await self.searchClient.search(query)
                    try Task.checkCancellation()
                    guard self.activeRunID == runID else { return }
                    self.mutateMessage(chatID: chatID, messageID: response.id) { $0.sources = sources }
                    context = sources.enumerated().map { "[\($0.offset + 1)] \($0.element.title)\n\($0.element.url.absoluteString)\n\($0.element.snippet)" }.joined(separator: "\n\n")
                    self.generationStatus = thinking ? "Размышляю…" : "Отвечаю…"
                }
                var firstReasoningAt: Date?
                var reasoningEndedAt: Date?
                var pendingContent = ""
                var pendingReasoning = ""
                var lastPublished = Date.distantPast
                var lastSaved = Date()
                var finishReason: String?

                @MainActor func flush() {
                    guard !pendingContent.isEmpty || !pendingReasoning.isEmpty else { return }
                    let seconds = firstReasoningAt.map { max(1, Int((reasoningEndedAt ?? Date()).timeIntervalSince($0).rounded())) } ?? 0
                    self.mutateMessage(chatID: chatID, messageID: response.id) {
                        $0.content += pendingContent
                        $0.reasoning += pendingReasoning
                        $0.reasoningSeconds = seconds
                    }
                    pendingContent = ""; pendingReasoning = ""; lastPublished = Date()
                }

                do {
                    for try await delta in client.stream(messages: input, thinking: thinking, systemInstruction: instruction, searchContext: context) {
                        try Task.checkCancellation()
                        guard self.activeRunID == runID else { return }
                        if !delta.reasoning.isEmpty, firstReasoningAt == nil { firstReasoningAt = Date() }
                        if !delta.content.isEmpty {
                            if firstReasoningAt != nil && reasoningEndedAt == nil { reasoningEndedAt = Date() }
                            self.generationStatus = "Отвечаю…"
                        }
                        pendingContent += delta.content
                        pendingReasoning += delta.reasoning
                        finishReason = delta.finishReason ?? finishReason
                        if Date().timeIntervalSince(lastPublished) >= 0.045 { flush() }
                        if Date().timeIntervalSince(lastSaved) >= 1.5 { self.persistNow(); lastSaved = Date() }
                    }
                    flush()
                } catch {
                    if self.activeRunID == runID { flush() }
                    throw error
                }
                guard self.activeRunID == runID else { return }
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
            self.isGenerating = false
            self.generationStatus = nil
            self.generationTask = nil
            self.persistNow()
        }
    }

    private func mutateMessage(chatID: UUID, messageID: UUID, update: (inout ChatMessage) -> Void) {
        guard let chatIndex = conversations.firstIndex(where: { $0.id == chatID }),
              let messageIndex = conversations[chatIndex].messages.firstIndex(where: { $0.id == messageID }) else { return }
        update(&conversations[chatIndex].messages[messageIndex])
    }

    private func removeUnreferencedAttachments(_ candidates: [MessageAttachment]) {
        let retained = conversations.flatMap(\.messages).flatMap(\.attachments) + attachments
        let retainedPaths = Set(retained.compactMap { $0.resolvedURL?.standardizedFileURL.path })
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("HonorPKAgent/Attachments", isDirectory: true).standardizedFileURL.path + "/"
        for attachment in candidates {
            guard let url = attachment.resolvedURL?.standardizedFileURL,
                  url.path.hasPrefix(root), !retainedPaths.contains(url.path) else { continue }
            try? FileManager.default.removeItem(at: url)
        }
    }

    private func scheduleSave() {
        guard !isLoading, persistenceTask == nil else { return }
        persistenceTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 700_000_000)
            guard !Task.isCancelled, let self else { return }
            self.persistenceTask = nil
            self.persistNow()
        }
    }

    func persistNow() {
        guard !isLoading else { return }
        persistenceTask?.cancel()
        persistenceTask = nil
        do {
            try FileManager.default.createDirectory(at: storageURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            let data = try Self.encoder.encode(archive)
            try data.write(to: storageURL, options: .atomic)
        } catch {
            errorMessage = "Не удалось сохранить историю: \(error.localizedDescription)"
        }
    }

    func exportData() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("Honor-История-\(Int(Date().timeIntervalSince1970)).json")
        var exported = archive
        var files: [String: Data] = [:]
        var totalBytes = 0
        let allAttachments = conversations.flatMap(\.messages).flatMap(\.attachments) + attachments
        for attachment in allAttachments where files[attachment.id.uuidString] == nil {
            guard let fileURL = attachment.resolvedURL else { continue }
            let data = try Data(contentsOf: fileURL)
            totalBytes += data.count
            guard totalBytes <= 64 * 1024 * 1024 else { throw HonorError.requestTooLarge }
            files[attachment.id.uuidString] = data
        }
        exported.attachmentFiles = files
        try Self.encoder.encode(exported).write(to: url, options: .atomic)
        return url
    }

    func importData(from url: URL) throws {
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        let resource = try url.resourceValues(forKeys: [.fileSizeKey])
        guard (resource.fileSize ?? 0) <= 100 * 1024 * 1024 else { throw HonorError.invalidArchive }
        var incoming = try Self.decoder.decode(HistoryArchive.self, from: Data(contentsOf: url))
        guard incoming.version == 1, Set(incoming.conversations.map(\.id)).count == incoming.conversations.count,
              incoming.conversations.allSatisfy({ Set($0.messages.map(\.id)).count == $0.messages.count }) else { throw HonorError.invalidArchive }
        stop()
        let existing = Set(conversations.map(\.id))
        incoming.conversations.removeAll { existing.contains($0.id) }
        let attachmentDirectory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("HonorPKAgent/Attachments", isDirectory: true)
        try FileManager.default.createDirectory(at: attachmentDirectory, withIntermediateDirectories: true)
        var restored: [UUID: String] = [:]
        for chatIndex in incoming.conversations.indices {
            for messageIndex in incoming.conversations[chatIndex].messages.indices {
                for attachmentIndex in incoming.conversations[chatIndex].messages[messageIndex].attachments.indices {
                    var attachment = incoming.conversations[chatIndex].messages[messageIndex].attachments[attachmentIndex]
                    let originalExtension = attachment.localPath.map { URL(fileURLWithPath: $0).pathExtension.lowercased() } ?? ""
                    // Never follow file paths supplied by an imported JSON archive.
                    attachment.localPath = nil
                    if let restoredPath = restored[attachment.id] { attachment.localPath = restoredPath }
                    else if let data = incoming.attachmentFiles?[attachment.id.uuidString], data.count <= 32 * 1024 * 1024 {
                        let allowedExtensions = ["jpg", "jpeg", "png", "gif", "webp", "pdf", "txt", "md", "csv", "json"]
                        let suffix = allowedExtensions.contains(originalExtension) ? originalExtension : (attachment.kind == .image ? "jpg" : "txt")
                        let target = attachmentDirectory.appendingPathComponent("\(UUID().uuidString).\(suffix)")
                        try data.write(to: target, options: .atomic)
                        attachment.localPath = target.path
                        restored[attachment.id] = target.path
                    }
                    incoming.conversations[chatIndex].messages[messageIndex].attachments[attachmentIndex] = attachment
                }
            }
        }
        conversations += incoming.conversations
        conversations.sort { $0.updatedAt > $1.updatedAt }
        persistNow()
    }

    private var archive: HistoryArchive {
        HistoryArchive(conversations: conversations, selectedConversationID: selectedConversationID, draft: draft, attachments: attachments, inFlightMessageID: activeMessageID)
    }

    private func loadHistory() {
        guard FileManager.default.fileExists(atPath: storageURL.path) else { return }
        do {
            let loaded = try Self.decoder.decode(HistoryArchive.self, from: Data(contentsOf: storageURL))
            guard loaded.version == 1 else { throw HonorError.invalidArchive }
            conversations = loaded.conversations
            selectedConversationID = conversations.contains(where: { $0.id == loaded.selectedConversationID }) ? loaded.selectedConversationID : nil
            draft = loaded.draft
            attachments = loaded.attachments
            // A process termination can leave an incomplete placeholder; make the interrupted state visible.
            for chatIndex in conversations.indices {
                guard let last = conversations[chatIndex].messages.indices.last else { continue }
                if conversations[chatIndex].messages[last].role == .assistant,
                   (conversations[chatIndex].messages[last].content.isEmpty || conversations[chatIndex].messages[last].id == loaded.inFlightMessageID),
                   conversations[chatIndex].messages[last].error == nil {
                    conversations[chatIndex].messages[last].isInterrupted = true
                }
            }
        } catch {
            let backup = storageURL.deletingPathExtension().appendingPathExtension("unreadable-\(Int(Date().timeIntervalSince1970)).json")
            try? FileManager.default.copyItem(at: storageURL, to: backup)
            errorMessage = "Не удалось открыть историю. Исходный файл сохранён отдельно: \(backup.lastPathComponent)."
        }
    }

    private static var encoder: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return encoder
    }

    private static var decoder: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }
}
