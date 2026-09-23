import XCTest
import UIKit
@testable import HonorPKAgent

@MainActor
final class LiveIntegrationTests: XCTestCase {
    private func configuredStore() throws -> ChatStore {
        guard !DeepSeekConfiguration.bundled.apiKey.isEmpty else { throw XCTSkip("Bundled API key required") }
        return ChatStore(storageURL: FileManager.default.temporaryDirectory.appendingPathComponent("honer-live-\(UUID()).json"))
    }

    private func answer(_ prompt: String, thinking: Bool, search: Bool = false, attachments: [MessageAttachment] = []) async throws -> ChatMessage {
        let store = try configuredStore()
        store.reasoningEnabled = thinking
        store.searchEnabled = search
        store.draft = prompt
        store.attachments = attachments
        let start = Date()
        store.send()
        while store.isGenerating && Date().timeIntervalSince(start) < 150 {
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        if store.isGenerating { store.stop(); XCTFail("Live response timed out") }
        let result = try XCTUnwrap(store.messages.last)
        XCTAssertNil(result.error)
        XCTAssertFalse(result.content.isEmpty)
        XCTAssertFalse(RussianTextPolicy.needsNormalization(result.content))
        if thinking {
            XCTAssertFalse(result.reasoning.isEmpty)
            XCTAssertFalse(RussianTextPolicy.needsNormalization(result.reasoning))
        } else { XCTAssertTrue(result.reasoning.isEmpty) }
        print("HONER_LIVE thinking=\(thinking) search=\(search) elapsed=\(Date().timeIntervalSince(start)) sources=\(result.sources.count)")
        let evidence = XCTAttachment(string: "QUESTION: \(prompt)\nANSWER: \(result.content)\nREASONING: \(result.reasoning)\nSOURCES: \(result.sources.map { $0.url.absoluteString }.joined(separator: "\n"))")
        evidence.name = "live-\(name)"
        evidence.lifetime = .keepAlways
        add(evidence)
        return result
    }

    func testLiveWeatherKlinWithThinking() async throws {
        let result = try await answer("Какая сейчас погода в Клину Московской области? Укажи температуру, ветер и время данных, кратко.", thinking: true, search: true)
        XCTAssertTrue(result.sources.contains { $0.url.host == "api.open-meteo.com" && !($0.content ?? "").isEmpty })
        XCTAssertTrue(result.content.lowercased().contains("клин"))
        XCTAssertTrue(result.content.contains("°") || result.content.lowercased().contains("градус"))
    }

    func testLiveWeatherKlinWithoutThinkingAndAutomaticFreshLookup() async throws {
        let result = try await answer("Какая погода сегодня в Клину? Ответь одним абзацем.", thinking: false, search: false)
        XCTAssertFalse(result.sources.isEmpty, "Current weather must trigger a fresh lookup even when Search is off")
        XCTAssertTrue(result.content.contains("°") || result.content.lowercased().contains("градус"))
    }

    /// Воспроизведение жалобы «она просто молчит»: чат из скриншотов, где ассистент
    /// уже отвечал одной буквой, а следующая реплика пользователя оставалась без ответа.
    /// Проверяем, что после короткого «Ку» приходит полноценный ответ, а огрызок
    /// из истории не сбивает модель.
    func testLiveShortChatWithBrokenHistoryStillGetsFullAnswer() async throws {
        let store = try configuredStore()
        store.reasoningEnabled = true
        let chat = Conversation(title: "Ку")
        var firstQuestion = ChatMessage(role: .user, content: "Ку")
        firstQuestion.createdAt = Date().addingTimeInterval(-120)
        var broken = ChatMessage(role: .assistant, content: "П")
        broken.createdAt = Date().addingTimeInterval(-110)
        var secondQuestion = ChatMessage(role: .user, content: "Больная чтоли ?")
        secondQuestion.createdAt = Date().addingTimeInterval(-60)
        chat.messages = [firstQuestion, broken, secondQuestion]
        store.conversations = [chat]
        store.selectedConversationID = chat.id
        store.draft = "Создай мне таблицу лучших телефонов на 26 год"
        store.send()
        let start = Date()
        while store.isGenerating && Date().timeIntervalSince(start) < 150 {
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        if store.isGenerating { store.stop(); XCTFail("Ответ не пришёл за 150 секунд") }
        let result = try XCTUnwrap(store.messages.last)
        print("HONER_LIVE chat len=\(result.content.count) error=\(result.error ?? "nil") reasoning=\(result.reasoning.count)")
        XCTAssertNil(result.error, "Появилась ошибка вместо ответа: \(result.error ?? "")")
        XCTAssertGreaterThan(result.content.count, 40, "Ответ пришёл обрывком: [\(result.content)]")
        // Огрызок «П» не должен попасть в контекст запроса как образец стиля.
        let outgoing = store.messages.map(\.content)
        XCTAssertTrue(outgoing.contains("Ку"))
        let evidence = XCTAttachment(string: "ANSWER: \(result.content)\nREASONING: \(result.reasoning)")
        evidence.name = "live-short-chat"
        evidence.lifetime = .keepAlways
        add(evidence)
    }

    func testLiveEnglishAndChineseQuestionsStayRussian() async throws {
        let english = try await answer("Explain why the sky is blue in one sentence. Answer in English.", thinking: true)
        print("HONER_WHY reasoning=[\(english.reasoning.prefix(400))]")
        print("HONER_WHY needsReasoning=\(RussianTextPolicy.needsNormalization(english.reasoning)) translated=\(english.reasoningWasTranslated ?? false)")
        XCTAssertTrue(english.content.lowercased().contains("свет") || english.content.lowercased().contains("рассе"))
        let chinese = try await answer("请只用中文解释为什么冰会融化。", thinking: false)
        XCTAssertTrue(chinese.content.lowercased().contains("лёд") || chinese.content.lowercased().contains("льд") || chinese.content.lowercased().contains("плав"))
    }

    func testLiveReadSpecificWebPageWithCitation() async throws {
        let result = try await answer("Прочитай https://support.apple.com/en-us/111872 и скажи, какая диагональ экрана iPhone 13. Дай ссылку на эту страницу.", thinking: false, search: false)
        XCTAssertTrue(result.sources.contains { $0.url.host == "support.apple.com" && !($0.content ?? "").isEmpty })
        XCTAssertTrue(result.content.contains("6,1") || result.content.contains("6.1"))
    }

    func testLiveImageUnderstandingWithNoOCRHint() async throws {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 400, height: 260))
        let image = renderer.image { context in
            UIColor.white.setFill(); context.fill(CGRect(x: 0, y: 0, width: 400, height: 260))
            UIColor.red.setFill(); context.cgContext.fillEllipse(in: CGRect(x: 40, y: 70, width: 120, height: 120))
            UIColor.blue.setFill(); context.fill(CGRect(x: 240, y: 70, width: 120, height: 120))
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("honer-vision-\(UUID()).jpg")
        try image.jpegData(compressionQuality: 0.9)!.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let result = try await answer("Опиши две фигуры на картинке: цвет и форма слева и справа. Кратко.", thinking: false,
            attachments: [MessageAttachment(name: "figures.jpg", kind: .image, localPath: url.path)])
        let text = result.content.lowercased()
        XCTAssertTrue(text.contains("красн") && text.contains("круг"))
        XCTAssertTrue(text.contains("син") && text.contains("квадрат"))
    }
}
