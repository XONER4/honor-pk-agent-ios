import XCTest
import UIKit
@testable import HonorPKAgent

/// Проверка самопроверки связи: приложение должно уметь сказать, что сервис
/// недоступен, вместо молчания в чате.
@MainActor
final class ConnectionCheckTests: XCTestCase {
    func testWorkingKeyReportsNoProblem() async throws {
        let configuration = DeepSeekConfiguration.bundled
        guard !configuration.apiKey.isEmpty else { throw XCTSkip("Bundled API key required") }
        let client = DeepSeekClient(configuration: configuration)
        let problem = await client.checkConnection()
        print("HONER_CHECK ok-case: \(problem ?? "связь есть")")
        XCTAssertNil(problem, "С рабочим ключом проверка связи не должна находить проблему: \(problem ?? "")")
    }

    func testBrokenKeyReportsProblem() async throws {
        let configuration = DeepSeekConfiguration(apiKey: "sk-invalid-key-for-test")
        let client = DeepSeekClient(configuration: configuration)
        let problem = await client.checkConnection()
        print("HONER_CHECK bad-key: \(problem ?? "нет проблемы")")
        XCTAssertNotNil(problem, "С неверным ключом проверка обязана сообщить о проблеме")
        XCTAssertTrue((problem ?? "").contains("ошибкой") || (problem ?? "").contains("связи"),
                      "Сообщение должно объяснять причину: \(problem ?? "")")
    }
}
