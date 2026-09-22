#if DEBUG
import Foundation

/// Test fixtures are compiled out of the distributed Release app.
@MainActor
enum UITestSupport {
    static var arguments: [String] { ProcessInfo.processInfo.arguments }
    static var isEnabled: Bool { arguments.contains(where: { $0.hasPrefix("-UITest") }) }

    static func makeEnvironment() -> (settings: AppSettings, store: ChatStore) {
        let suite = "com.honorpk.agent.uitests"
        let defaults = UserDefaults(suiteName: suite)!
        let storage = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("HonorPKUITests/history.json")
        if arguments.contains("-UITestReset") {
            defaults.removePersistentDomain(forName: suite)
            try? FileManager.default.removeItem(at: storage)
        }
        let settings = AppSettings(defaults: defaults)
        let mode = arguments.contains("-UITestSlow") ? "slow" : arguments.contains("-UITestError") ? "error" : "normal"
        let client: DeepSeekStreaming? = arguments.contains("-UITestFixture") ? UITestStreamingClient(mode: mode) : nil
        let store = ChatStore(client: client, storageURL: storage)
        return (settings, store)
    }

    static func seedIfNeeded(store: ChatStore) {
        guard isEnabled else { return }
        if arguments.contains("-UITestDemo") {
            let user = ChatMessage(id: UUID(uuidString: "11111111-1111-4111-8111-111111111111")!, role: .user, content: "Привет, как твои дела?")
            let answer = ChatMessage(id: UUID(uuidString: "22222222-2222-4222-8222-222222222222")!, role: .assistant,
                                     content: "Привет! У меня всё отлично, спасибо, что спросил. А как твои дела?",
                                     reasoning: "Это демонстрационный текст для проверки раскрывающейся панели интерфейса.", reasoningSeconds: 1)
            let chat = Conversation(id: UUID(uuidString: "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA")!, title: "Приветствие", messages: [user, answer])
            let pinned = Conversation(id: UUID(uuidString: "BBBBBBBB-BBBB-4BBB-8BBB-BBBBBBBBBBBB")!, title: "Идеи для проекта", pinned: true)
            var yesterday = Conversation(title: "Выбор смартфона")
            yesterday.updatedAt = Calendar.current.date(byAdding: .day, value: -1, to: Date())!
            var earlier = Conversation(title: "Дизайн приложения")
            earlier.updatedAt = Calendar.current.date(byAdding: .day, value: -3, to: Date())!
            store.conversations = [chat, pinned, yesterday, earlier]
            store.selectedConversationID = chat.id
            store.persistNow()
        }
        if arguments.contains("-UITestLargeHistory") {
            let messages = (0..<400).map { index in
                ChatMessage(role: index.isMultiple(of: 2) ? .user : .assistant,
                            content: "Сообщение \(index). " + String(repeating: "Текст длинной сохранённой переписки. ", count: 12))
            }
            store.conversations = [Conversation(title: "Большая переписка", messages: messages)] + (0..<499).map {
                Conversation(title: "Сохранённый чат \($0)", messages: [ChatMessage(role: .user, content: "Проект \($0)")])
            }
            store.selectedConversationID = store.conversations[0].id
            store.persistNow()
        }
    }
}

private final class UITestStreamingClient: DeepSeekStreaming {
    let mode: String
    private let lock = NSLock()
    private var requests = 0
    init(mode: String) { self.mode = mode }

    func stream(messages: [ChatMessage], thinking: Bool, systemInstruction: String, searchContext: String) -> AsyncThrowingStream<DeepSeekDelta, Error> {
        lock.lock()
        requests += 1
        let number = requests
        lock.unlock()
        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    if mode == "error" && number == 1 { throw URLError(.notConnectedToInternet) }
                    if thinking { continuation.yield(.init(reasoning: "Демонстрация тестового потока для проверки интерфейса.")) }
                    if mode == "slow" && number == 1 {
                        for index in 0..<160 {
                            try Task.checkCancellation()
                            continuation.yield(.init(content: "Слово \(index). "))
                            try await Task.sleep(nanoseconds: 100_000_000)
                        }
                    } else {
                        continuation.yield(.init(content: "Готово. Тестовый ответ Honor."))
                    }
                    continuation.yield(.init(finishReason: "stop"))
                    continuation.finish()
                } catch { continuation.finish(throwing: error) }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
#endif
