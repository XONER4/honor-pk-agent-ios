import XCTest
import UIKit
@testable import HonorPKAgent

/// Диагностика пути «инструменты → ответ»: пошагово пишет, что происходит,
/// чтобы видеть точную причину, а не догадываться.
@MainActor
final class ToolsDiagnosticsTests: XCTestCase {
    func testToolsPathDiagnostics() async throws {
        guard !DeepSeekConfiguration.bundled.apiKey.isEmpty else { throw XCTSkip("Bundled API key required") }
        var other = Conversation(title: "Отпуск в Сочи")
        var question = ChatMessage(role: .user, content: "Сколько дней мы отдыхали в Сочи?")
        question.createdAt = Date().addingTimeInterval(-600)
        var reply = ChatMessage(role: .assistant, content: "Мы отдыхали в Сочи 12 дней, с 3 по 15 августа.")
        reply.createdAt = Date().addingTimeInterval(-590)
        other.messages = [question, reply]
        var current = Conversation(title: "Новый вопрос")
        current.messages = [ChatMessage(role: .user, content: "Прочитай чат про отпуск.")]
        let store = ChatStore(storageURL: FileManager.default.temporaryDirectory.appendingPathComponent("diag-\(UUID()).json"))
        store.conversations = [current, other]
        store.selectedConversationID = current.id
        store.reasoningEnabled = false
        store.draft = "Прочитай чат про отпуск и скажи, сколько дней мы отдыхали в Сочи."
        store.send()
        let start = Date()
        while store.isGenerating && Date().timeIntervalSince(start) < 150 {
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        if store.isGenerating { store.stop() }
        let final = try XCTUnwrap(store.messages.last)
        print("HONER_DIAG_TOOLS final=[\(final.content)] len=\(final.content.count) needsRecovery=\(ChatStore.needsAnswerRecovery(final.content)) announcement=\(ChatStore.isToolAnnouncement(final.content))")
    }
}
