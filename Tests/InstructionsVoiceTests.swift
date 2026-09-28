import XCTest
import AVFoundation
@testable import HonorPKAgent

/// Закреплённые инструкции, их отделение от памяти, озвучка и навигация.
@MainActor
final class InstructionsVoiceTests: XCTestCase {
    private func url() -> URL { FileManager.default.temporaryDirectory.appendingPathComponent("instr-\(UUID()).json") }

    func testPinnedInstructionsAndMemoryAreSeparateSectionsForTheModel() throws {
        let store = ChatStore(configuration: .init(apiKey: "test"), storageURL: url())
        let user = ChatMessage(role: .user, content: "Всегда отвечай списком")
        let answer = ChatMessage(role: .assistant, content: "Формат: сначала вывод, потом детали.")
        let chat = Conversation(title: "Чат", messages: [user, answer])
        store.conversations = [chat]
        store.selectedConversationID = chat.id
        XCTAssertTrue(store.pinInstruction(fromMessage: user.id))
        XCTAssertTrue(store.pinInstruction(fromMessage: answer.id))
        XCTAssertFalse(store.pinInstruction(fromMessage: user.id), "Одна и та же инструкция не закрепляется дважды")
        store.addMemory("Живу в Казани")

        let prompt = store.systemInstruction(forChat: chat.id, query: "Где я живу?")
        let instructionsAt = try XCTUnwrap(prompt.range(of: "## Закреплённые инструкции этого чата"))
        let memoryAt = try XCTUnwrap(prompt.range(of: "## Память Honer AI"))
        XCTAssertLessThan(instructionsAt.lowerBound, memoryAt.lowerBound, "Инструкции идут отдельно и раньше памяти")
        let instructionsPart = String(prompt[instructionsAt.lowerBound..<memoryAt.lowerBound])
        XCTAssertTrue(instructionsPart.contains("Всегда отвечай списком"))
        XCTAssertTrue(instructionsPart.contains("закрепил пользователь; написал пользователь"))
        XCTAssertTrue(instructionsPart.contains("закрепил пользователь; твой прежний ответ"))
        XCTAssertFalse(instructionsPart.contains("Живу в Казани"), "Память не должна смешиваться с инструкциями")
        XCTAssertTrue(String(prompt[memoryAt.lowerBound...]).contains("Живу в Казани"))
        XCTAssertTrue(String(prompt[memoryAt.lowerBound...]).contains("Это НЕ инструкции"))
    }

    func testUnpinKeepsInstructionInLibraryAndItCanBeReused() throws {
        let store = ChatStore(configuration: .init(apiKey: "test"), storageURL: url())
        let first = Conversation(title: "Первый")
        let second = Conversation(title: "Второй")
        store.conversations = [first, second]
        XCTAssertTrue(store.addInstruction(chatID: first.id, text: "Отвечай на ты"))
        let id = try XCTUnwrap(store.conversations[0].instructions?.first?.id)
        store.unpinInstruction(chatID: first.id, id: id)
        XCTAssertTrue(store.conversations[0].instructions?.isEmpty ?? true)
        let saved = try XCTUnwrap(store.instructionLibrary.first)
        XCTAssertEqual(saved.text, "Отвечай на ты")
        XCTAssertTrue(store.applySavedInstruction(id: saved.id, to: second.id))
        XCTAssertEqual(store.conversations[1].instructions?.first?.text, "Отвечай на ты")
        store.updateInstruction(chatID: second.id, id: try XCTUnwrap(store.conversations[1].instructions?.first?.id), text: "Отвечай на вы")
        XCTAssertEqual(store.conversations[1].instructions?.first?.text, "Отвечай на вы")
        store.deleteSavedInstruction(id: saved.id)
        XCTAssertTrue(store.instructionLibrary.isEmpty)
    }

    func testInstructionsSurviveRestartLegacyPromptMigratesAndBranchesKeepThem() throws {
        let storage = url()
        do {
            let store = ChatStore(configuration: .init(apiKey: "test"), storageURL: storage)
            let message = ChatMessage(role: .user, content: "Привет")
            var legacy = Conversation(title: "Старый", messages: [message])
            legacy.systemPrompt = "Старый промт чата"
            store.conversations = [legacy]
            store.selectedConversationID = legacy.id
            store.saveInstructionToLibrary("Черновик инструкции")
            store.persistNow()
        }
        let restored = ChatStore(configuration: .init(apiKey: "test"), storageURL: storage)
        let chat = try XCTUnwrap(restored.conversations.first)
        XCTAssertEqual(chat.instructions?.map(\.text), ["Старый промт чата"], "Старый промт чата становится закреплённой инструкцией")
        XCTAssertEqual(chat.systemPrompt, "")
        XCTAssertEqual(restored.instructionLibrary.map(\.text), ["Черновик инструкции"])
        restored.selectedConversationID = chat.id
        let branchID = try XCTUnwrap(restored.forkConversation(at: try XCTUnwrap(chat.messages.first?.id)))
        XCTAssertEqual(restored.conversations.first(where: { $0.id == branchID })?.instructions?.map(\.text), ["Старый промт чата"])
    }

    func testSpeechSplitsRussianAndEnglishAndReadsSymbolsAsWords() {
        let segments = SpeechService.languageSegments("Открой iPhone Settings и включи режим, пожалуйста.")
        XCTAssertEqual(segments.map(\.english), [false, true, false])
        XCTAssertEqual(segments[1].text.trimmingCharacters(in: .whitespaces), "iPhone Settings")
        XCTAssertEqual(SpeechService.languageSegments("Только русский текст, 25 раз.").count, 1)

        let spoken = SpeechService.spokenForm("Сегодня 25 °C, скидка 15%, цена 990 ₽, т. е. дешевле\n## Итог\nСкорость 60 км/ч, 5–7 дней")
        XCTAssertTrue(spoken.contains("градусов Цельсия"), spoken)
        XCTAssertTrue(spoken.contains("15 процентов"), spoken)
        XCTAssertTrue(spoken.contains("990 рублей"), spoken)
        XCTAssertTrue(spoken.contains("то есть"), spoken)
        XCTAssertTrue(spoken.contains("километров в час"), spoken)
        XCTAssertTrue(spoken.contains("5 до 7"), spoken)
        XCTAssertTrue(spoken.contains("## Итог."), "Строка без знака в конце получает паузу: \(spoken)")
    }

    func testRoboticVoicesAreNeverChosenAndGenderIsRespected() {
        let voices = AVSpeechSynthesisVoice.speechVoices()
        for voice in voices where voice.identifier.lowercased().contains("eloquence") {
            XCTAssertFalse(VoiceCatalog.isUsable(voice), voice.identifier)
        }
        for language in ["ru", "en"] {
            if let chosen = VoiceCatalog.bestVoice(language: language, gender: .male) {
                XCTAssertTrue(VoiceCatalog.isUsable(chosen))
                XCTAssertTrue(chosen.language.hasPrefix(language))
                let maleExists = voices.contains { $0.language.hasPrefix(language) && VoiceCatalog.isUsable($0) && VoiceCatalog.gender(of: $0) == .male }
                if maleExists { XCTAssertEqual(VoiceCatalog.gender(of: chosen), .male, "Мужской голос выбран не был: \(chosen.name)") }
                print("HONER_VOICE \(language) male → \(chosen.name) q=\(chosen.quality.rawValue)")
            }
            if let female = VoiceCatalog.bestVoice(language: language, gender: .female) {
                print("HONER_VOICE \(language) female → \(female.name) q=\(female.quality.rawValue)")
            }
        }
        XCTAssertEqual(AppSettings(defaults: UserDefaults(suiteName: "voice-\(UUID())")!).speechGender, .male,
                       "По умолчанию мужской голос")
    }

    func testNavigationStripMapsTouchesToMessages() {
        let layout = MessageNavigationStrip.Layout(count: 5, height: 800)
        XCTAssertEqual(layout.index(at: layout.y(0)), 0)
        XCTAssertEqual(layout.index(at: layout.y(3) + 2), 3)
        XCTAssertEqual(layout.index(at: -100), 0)
        XCTAssertEqual(layout.index(at: 5000), 4)
        let single = MessageNavigationStrip.Layout(count: 1, height: 800)
        XCTAssertEqual(single.index(at: 400), 0)
    }
}
