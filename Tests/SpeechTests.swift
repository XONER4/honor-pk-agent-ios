import AVFoundation
import XCTest
@testable import HonorPKAgent

final class SpeechTests: XCTestCase {
    func testSanitizerOmitsEmojiFamiliesFlagsAndKeycapsButPreservesNumbers() {
        let result = SpeechService.sanitizedSpeechText("## Привет, **мир**! 👨‍👩‍👧‍👦 🚀 🇷🇺 1️⃣\nЦена 13 500 ₽, 25% и номер 13.")
        XCTAssertEqual(result, "Привет, мир!\nЦена 13 500 ₽, 25% и номер 13.")
    }

    func testSanitizerKeepsNamedLinkLabelsButOmitsNumericCitationsAndURLs() {
        let result = SpeechService.sanitizedSpeechText("Читайте [документацию](https://example.com/docs). [1](https://example.com/ref) [2, 3]\nАдрес: https://example.com/very-long-path?x=7\nПродолжение.")
        XCTAssertTrue(result.contains("Читайте документацию."))
        XCTAssertTrue(result.contains("Продолжение."))
        XCTAssertFalse(result.contains("https"))
        XCTAssertFalse(result.contains("example"))
        XCTAssertFalse(result.contains("1"))
        XCTAssertFalse(result.contains("2, 3"))
        XCTAssertFalse(result.contains("["))
    }

    func testSanitizerDoesNotReadCodeFencesOrPrivateCitationMarkers() {
        let input = "Итог: **готово**.\n```swift\nlet noisy = 123; print(noisy)\n```\nИспользуйте `Honer`. citeturn0search0"
        let result = SpeechService.sanitizedSpeechText(input)
        XCTAssertTrue(result.contains("Итог: готово."))
        XCTAssertTrue(result.contains("Используйте Honer."))
        XCTAssertFalse(result.contains("noisy"))
        XCTAssertFalse(result.contains("turn0search0"))
        XCTAssertFalse(result.contains("`"))
    }

    func testSanitizerPreservesPlainProseAndMathematicalValues() {
        let input = "Температура −5 °C, время 13:45, сумма 2 × 3 = 6.\nHoner AI 10.0."
        XCTAssertEqual(SpeechService.sanitizedSpeechText(input), input)
        XCTAssertEqual(SpeechService.sanitizedSpeechText("😀 ❤️ ✨\n"), "")
    }

    @MainActor
    func testVoiceSelectionUsesOnlyAvailableLanguageAndHighestQualityFallback() throws {
        let voices = SpeechService.availableVoices(language: "ru-RU")
        XCTAssertTrue(voices.allSatisfy { $0.language.hasPrefix("ru") })
        for (first, second) in zip(voices, voices.dropFirst()) {
            XCTAssertGreaterThanOrEqual(first.quality.rawValue, second.quality.rawValue)
        }
        if let first = voices.first {
            XCTAssertEqual(SpeechService.preferredVoice(identifier: "missing.voice", language: "ru-RU")?.identifier, first.identifier)
            if let english = AVSpeechSynthesisVoice.speechVoices().first(where: { $0.language.hasPrefix("en") }) {
                XCTAssertEqual(SpeechService.preferredVoice(identifier: english.identifier, language: "ru-RU")?.identifier, first.identifier)
            }
        }
    }

    @MainActor
    func testCancelAndFinishWhileIdleReturnNoStaleWordsOrRecordingState() async {
        let service = SpeechService()
        service.errorMessage = "Old failure"
        service.clearError()
        service.cancelRecording()
        let transcript = await service.finishRecording()
        XCTAssertEqual(transcript, "")
        XCTAssertEqual(service.transcript, "")
        XCTAssertEqual(service.audioLevel, 0)
        XCTAssertFalse(service.isRecording)
        XCTAssertFalse(service.isPreparingRecording)
        XCTAssertFalse(service.isFinalizingRecording)
        XCTAssertNil(service.errorMessage)
    }
}
