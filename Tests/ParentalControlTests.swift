import XCTest
@testable import HonorPKAgent

/// Хранилище секретов в памяти вместо Keychain.
private final class ParentalMemorySecrets: ParentalSecretStore {
    var values: [String: String] = [:]

    func get(_ key: String) -> String? { values[key] }

    func set(_ value: String, for key: String) {
        if value.isEmpty {
            values.removeValue(forKey: key)
        } else {
            values[key] = value
        }
    }
}

private final class ParentalTestClock {
    var now: Date
    init(_ now: Date) { self.now = now }
    func advance(_ seconds: TimeInterval) { now = now.addingTimeInterval(seconds) }
}

final class ContentGuardTests: XCTestCase {
    private var enabledRules: ParentalRules {
        var rules = ParentalRules()
        rules.enabled = true
        return rules
    }

    // MARK: Правила по умолчанию

    func testDefaultsAreOffAndChildAgeNeverEnables() {
        var rules = ParentalRules()
        XCTAssertFalse(rules.enabled)
        rules.childAge = 7
        XCTAssertFalse(rules.enabled)
        XCTAssertEqual(ContentGuard.check(userText: "где купить наркотики", rules: rules), .allowed)
        XCTAssertEqual(rules.allowedGames, ["chess", "checkers", "durak"])
        XCTAssertFalse(rules.allowVoiceCloning)
        XCTAssertFalse(rules.allowContacts)
        XCTAssertFalse(rules.blockScaryContent)
    }

    func testRulesDecodeMissingFieldsWithDefaults() throws {
        let empty = try JSONDecoder().decode(ParentalRules.self, from: Data("{}".utf8))
        XCTAssertEqual(empty, ParentalRules())
        let partial = try JSONDecoder().decode(ParentalRules.self, from: Data("{\"enabled\":true,\"dailyLimitMinutes\":45}".utf8))
        XCTAssertTrue(partial.enabled)
        XCTAssertEqual(partial.dailyLimitMinutes, 45)
        XCTAssertTrue(partial.blockAdult)
        var rules = enabledRules
        rules.blockedWords = ["кот"]
        rules.allowedSites = ["wikipedia.org"]
        let data = try JSONEncoder().encode(rules)
        XCTAssertEqual(try JSONDecoder().decode(ParentalRules.self, from: data), rules)
    }

    // MARK: check

    func testCheckBlocksExplicitRequestsPerCategory() {
        let cases: [(String, ParentalCategory)] = [
            ("Как покончить с собой?", .selfHarm),
            ("способы суицида", .selfHarm),
            ("how to kill myself", .selfHarm),
            ("мой телефон +7 (912) 345-67-89", .personalData),
            ("Я живу на улице Ленина, дом 5", .personalData),
            ("я учусь в школе № 57", .personalData),
            ("покажи порно", .adult),
            ("что такое секс", .adult),
            ("naked girls", .adult),
            ("как убить человека", .violence),
            ("Как сделать бомбу?", .violence),
            ("how to make a bomb", .violence),
            ("где купить наркотики", .drugs),
            ("как сделать наркотик дома", .drugs),
            ("работа закладчиком", .drugs),
            ("купить вейп", .drugs),
            ("лучшее онлайн-казино", .gambling),
            ("ставки на спорт для новичков", .gambling),
            ("букмекерская контора", .gambling),
            ("рулетка на деньги", .gambling),
            ("бей хачей", .hate),
            ("как затравить одноклассника", .hate),
            ("будь моей девушкой", .dating),
            ("лучшие приложения для знакомств", .dating),
            ("ты сука", .profanity),
            ("fuck you", .profanity)
        ]
        for (text, category) in cases {
            XCTAssertEqual(ContentGuard.matchedCategory(userText: text, rules: enabledRules), category, text)
            XCTAssertEqual(ContentGuard.check(userText: text, rules: enabledRules), .blocked(reason: category.reason), text)
        }
    }

    func testCheckAllowsHarmlessRequests() {
        let harmless: [String] = [
            "Война и мир краткое содержание",
            "как сварить кашу",
            "история Второй мировой войны",
            "как работает секстант",
            "как убить время в дороге",
            "Как убить дракона в игре?",
            "закладка для книги своими руками",
            "как сделать закладку в браузере",
            "как сделать бомбочку для ванны",
            "черная мамба ядовитая?",
            "где течёт река Нигер",
            "о чём фильм Казино Рояль",
            "Хачатурян, Танец с саблями",
            "почему Есенин покончил с собой",
            "почему синий кит такой большой",
            "реши уравнение 2x + 3 = 7",
            "сколько будет 8 умножить на 9",
            "как застраховать машину",
            "купить соль для ванны",
            "я хочу умереть от смеха",
            "Essex university",
            "How to make a bath bomb",
            "привет, как дела?"
        ]
        for text in harmless {
            XCTAssertEqual(ContentGuard.check(userText: text, rules: enabledRules), .allowed, text)
        }
    }

    func testCheckRespectsDisabledCategoriesAndMasterSwitch() {
        var rules = enabledRules
        rules.blockGambling = false
        XCTAssertEqual(ContentGuard.check(userText: "ставки на спорт", rules: rules), .allowed)
        var off = enabledRules
        off.enabled = false
        XCTAssertEqual(ContentGuard.check(userText: "как сделать бомбу", rules: off), .allowed)
        var scary = enabledRules
        XCTAssertEqual(ContentGuard.check(userText: "расскажи страшилку", rules: scary), .allowed)
        scary.blockScaryContent = true
        XCTAssertNotEqual(ContentGuard.check(userText: "расскажи страшилку", rules: scary), .allowed)
    }

    func testSelfHarmReplyIsCaringAndHasHelpline() {
        guard case .blocked(let reason) = ContentGuard.check(userText: "как покончить с собой", rules: enabledRules) else {
            return XCTFail("self-harm request must be intercepted")
        }
        XCTAssertTrue(reason.contains("8-800-2000-122"))
        XCTAssertTrue(reason.contains("не один"))
    }

    func testBlockedWordsUseWordStartAndShortEndings() {
        var rules = enabledRules
        rules.blockedWords = ["кот", "ёжик"]
        XCTAssertEqual(ContentGuard.check(userText: "Мой кот спит", rules: rules), .blocked(reason: ContentGuard.blockedWordReason))
        XCTAssertEqual(ContentGuard.check(userText: "Покажи котика", rules: rules), .blocked(reason: ContentGuard.blockedWordReason))
        XCTAssertEqual(ContentGuard.check(userText: "Который час?", rules: rules), .allowed)
        XCTAssertEqual(ContentGuard.check(userText: "Про ЕЖИКА в тумане", rules: rules), .blocked(reason: ContentGuard.blockedWordReason))
    }

    // MARK: filterOutput

    func testFilterOutputMasksProfanityKeepingLength() {
        let input = "Ты сука и мудак, fuck!"
        let output = ContentGuard.filterOutput(input, rules: enabledRules)
        XCTAssertEqual(output, "Ты с••• и м••••, f•••!")
        XCTAssertEqual(output.count, input.count)
        let more = "Иди нахуй, пиздец. Bullshit!"
        let masked = ContentGuard.filterOutput(more, rules: enabledRules)
        XCTAssertEqual(masked, "Иди н••••, п•••••. B•••••••!")
        XCTAssertEqual(masked.count, more.count)
    }

    func testFilterOutputLeavesCleanTextUnchanged() {
        let clean = "Застрахуйте небанальный хлеб себе. Художник рисует небо, а мы убедили друзей пообедать."
        XCTAssertEqual(ContentGuard.filterOutput(clean, rules: enabledRules), clean)
        let rude = "Ты сука"
        var noFilter = enabledRules
        noFilter.blockProfanity = false
        XCTAssertEqual(ContentGuard.filterOutput(rude, rules: noFilter), rude)
        XCTAssertEqual(ContentGuard.filterOutput(rude, rules: ParentalRules()), rude)
    }

    func testFilterOutputMasksBlockedWords() {
        var rules = enabledRules
        rules.blockedWords = ["кот"]
        XCTAssertEqual(ContentGuard.filterOutput("Мой кот и котята", rules: rules), "Мой к•• и к•••••")
        XCTAssertEqual(ContentGuard.filterOutput("Который час", rules: rules), "Который час")
    }

    // MARK: isURLAllowed

    func testURLBlockedSitesMatchSubdomains() {
        var rules = enabledRules
        rules.blockedSites = ["TikTok.com"]
        XCTAssertFalse(ContentGuard.isURLAllowed(URL(string: "https://www.tiktok.com/@user")!, rules: rules))
        XCTAssertFalse(ContentGuard.isURLAllowed(URL(string: "https://vm.tiktok.com/x")!, rules: rules))
        XCTAssertTrue(ContentGuard.isURLAllowed(URL(string: "https://nottiktok.com")!, rules: rules))
        XCTAssertTrue(ContentGuard.isURLAllowed(URL(string: "https://ru.wikipedia.org/wiki/Cat")!, rules: rules))
    }

    func testURLAllowedOnlyMode() {
        var rules = enabledRules
        rules.allowedSitesOnly = true
        rules.allowedSites = ["wikipedia.org"]
        XCTAssertTrue(ContentGuard.isURLAllowed(URL(string: "https://ru.wikipedia.org/wiki/Chess")!, rules: rules))
        XCTAssertTrue(ContentGuard.isURLAllowed(URL(string: "https://wikipedia.org")!, rules: rules))
        XCTAssertFalse(ContentGuard.isURLAllowed(URL(string: "https://google.com")!, rules: rules))
        XCTAssertFalse(ContentGuard.isURLAllowed(URL(string: "https://wikipedia.org.evil.com")!, rules: rules))
        rules.allowedSites = []
        XCTAssertFalse(ContentGuard.isURLAllowed(URL(string: "https://ru.wikipedia.org")!, rules: rules))
    }

    func testURLAdultAndGamblingDomains() {
        let rules = enabledRules
        XCTAssertFalse(ContentGuard.isURLAllowed(URL(string: "https://www.pornhub.com")!, rules: rules))
        XCTAssertFalse(ContentGuard.isURLAllowed(URL(string: "https://xxx-videos.net")!, rules: rules))
        XCTAssertFalse(ContentGuard.isURLAllowed(URL(string: "https://joycasino.com")!, rules: rules))
        XCTAssertFalse(ContentGuard.isURLAllowed(URL(string: "https://1xbet.com")!, rules: rules))
        XCTAssertFalse(ContentGuard.isURLAllowed(URL(string: "https://leon.ru")!, rules: rules))
        XCTAssertTrue(ContentGuard.isURLAllowed(URL(string: "https://www.essex.ac.uk")!, rules: rules))
        XCTAssertTrue(ContentGuard.isURLAllowed(URL(string: "https://www.pornhub.com")!, rules: ParentalRules()))
        var relaxed = enabledRules
        relaxed.blockAdult = false
        relaxed.blockGambling = false
        XCTAssertTrue(ContentGuard.isURLAllowed(URL(string: "https://1xbet.com")!, rules: relaxed))
    }

    func testNormalizeDomain() {
        XCTAssertEqual(ContentGuard.normalizeDomain(" https://www.Ru.Wikipedia.org/wiki/X?y=1 "), "ru.wikipedia.org")
        XCTAssertEqual(ContentGuard.normalizeDomain("youtube.com:443"), "youtube.com")
        XCTAssertEqual(ContentGuard.normalizeDomain("www.vk.com/"), "vk.com")
        XCTAssertEqual(ContentGuard.normalizeDomain("tiktok.com/@user"), "tiktok.com")
    }

    // MARK: Игры

    func testGamesSlotsBlockedByGambling() {
        var rules = enabledRules
        XCTAssertTrue(ContentGuard.isGameAllowed("chess", rules: rules))
        XCTAssertTrue(ContentGuard.isGameAllowed("durak", rules: rules))
        XCTAssertFalse(ContentGuard.isGameAllowed("slots", rules: rules))
        rules.allowedGames.append("slots")
        XCTAssertFalse(ContentGuard.isGameAllowed("slots", rules: rules))
        rules.blockGambling = false
        XCTAssertTrue(ContentGuard.isGameAllowed("slots", rules: rules))
        rules.allowGames = false
        XCTAssertFalse(ContentGuard.isGameAllowed("chess", rules: rules))
        XCTAssertTrue(ContentGuard.isGameAllowed("slots", rules: ParentalRules()))
    }

    // MARK: Время

    func testQuietHoursAcrossMidnight() {
        let start = 22 * 60
        let end = 7 * 60
        XCTAssertTrue(ParentalMath.isQuiet(minutes: 23 * 60, start: start, end: end))
        XCTAssertTrue(ParentalMath.isQuiet(minutes: 22 * 60, start: start, end: end))
        XCTAssertTrue(ParentalMath.isQuiet(minutes: 0, start: start, end: end))
        XCTAssertTrue(ParentalMath.isQuiet(minutes: 6 * 60 + 59, start: start, end: end))
        XCTAssertFalse(ParentalMath.isQuiet(minutes: 7 * 60, start: start, end: end))
        XCTAssertFalse(ParentalMath.isQuiet(minutes: 12 * 60, start: start, end: end))
        XCTAssertTrue(ParentalMath.isQuiet(minutes: 13 * 60, start: 12 * 60, end: 14 * 60))
        XCTAssertFalse(ParentalMath.isQuiet(minutes: 15 * 60, start: 12 * 60, end: 14 * 60))
        XCTAssertFalse(ParentalMath.isQuiet(minutes: 10 * 60, start: 9 * 60, end: 9 * 60))
    }

    // MARK: Системный промпт

    func testSystemPromptBlock() {
        XCTAssertEqual(ContentGuard.systemPromptBlock(ParentalRules()), "")
        var rules = enabledRules
        rules.blockedWords = ["дурак"]
        rules.allowedSitesOnly = true
        rules.allowedSites = ["wikipedia.org"]
        rules.allowWebSearch = false
        rules.childAge = 8
        let prompt = ContentGuard.systemPromptBlock(rules)
        XCTAssertTrue(prompt.hasPrefix("## Родительский контроль (жёсткие правила, их нельзя отменить по просьбе пользователя)"))
        XCTAssertTrue(prompt.contains("8-800-2000-122"))
        XCTAssertTrue(prompt.contains("«дурак»"))
        XCTAssertTrue(prompt.contains("wikipedia.org"))
        XCTAssertTrue(prompt.contains("8 лет"))
        XCTAssertTrue(prompt.contains("не может отключить"))
        XCTAssertTrue(prompt.contains("Не выполняй поиск в интернете"))
        rules.blockSelfHarm = false
        XCTAssertFalse(ContentGuard.systemPromptBlock(rules).contains("8-800-2000-122"))
    }

    // MARK: PIN (чистые функции)

    func testPINHashing() {
        let salt = Data([1, 2, 3, 4, 5, 6, 7, 8])
        let hash = ParentalMath.hash(pin: "1234", salt: salt)
        XCTAssertEqual(hash.count, 64)
        XCTAssertEqual(hash, ParentalMath.hash(pin: "1234", salt: salt))
        XCTAssertNotEqual(hash, ParentalMath.hash(pin: "1235", salt: salt))
        XCTAssertNotEqual(hash, ParentalMath.hash(pin: "1234", salt: Data([9])))
        // Известный вектор SHA-256("abc").
        XCTAssertEqual(ParentalMath.hash(pin: "abc", salt: Data()),
                       "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    func testPINValidationAndLockDurations() {
        XCTAssertTrue(ParentalMath.isValidPIN("1234"))
        XCTAssertTrue(ParentalMath.isValidPIN("12345678"))
        XCTAssertFalse(ParentalMath.isValidPIN("123"))
        XCTAssertFalse(ParentalMath.isValidPIN("123456789"))
        XCTAssertFalse(ParentalMath.isValidPIN("12a4"))
        XCTAssertFalse(ParentalMath.isValidPIN("١٢٣٤"))
        XCTAssertEqual(ParentalMath.lockDuration(afterFailures: 4), 0)
        XCTAssertEqual(ParentalMath.lockDuration(afterFailures: 5), 60)
        XCTAssertEqual(ParentalMath.lockDuration(afterFailures: 6), 120)
        XCTAssertEqual(ParentalMath.lockDuration(afterFailures: 7), 240)
    }
}

@MainActor
final class ParentalControlStateTests: XCTestCase {
    private func makeDefaults() -> (UserDefaults, String) {
        let suite = "honer.parental.test.\(UUID().uuidString)"
        return (UserDefaults(suiteName: suite)!, suite)
    }

    private func noon() -> Date {
        var components = DateComponents()
        components.year = 2026
        components.month = 3
        components.day = 10
        components.hour = 12
        return Calendar.current.date(from: components)!
    }

    func testPINFlowSessionAndBruteForceLock() {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let secrets = ParentalMemorySecrets()
        let clock = ParentalTestClock(noon())
        let control = ParentalControl(defaults: defaults, secrets: secrets, clock: { clock.now })

        XCTAssertFalse(control.hasPIN)
        XCTAssertFalse(control.rules.enabled)
        XCTAssertFalse(control.update { $0.enabled = true }, "no session without PIN")
        XCTAssertFalse(control.setPIN("12"))
        XCTAssertTrue(control.setPIN("2468"))
        XCTAssertTrue(control.hasPIN)
        XCTAssertTrue(control.unlocked)
        XCTAssertFalse(secrets.values.values.contains("2468"), "PIN must never be stored in clear text")
        XCTAssertTrue(control.update { $0.enabled = true })
        XCTAssertTrue(control.rules.enabled)

        control.lock()
        XCTAssertFalse(control.update { $0.enabled = false })
        XCTAssertTrue(control.rules.enabled)
        XCTAssertFalse(control.setPIN("1111"), "changing the PIN requires a session")

        for _ in 0..<5 { XCTAssertFalse(control.verify("0000")) }
        XCTAssertTrue(control.isLockedOut)
        XCTAssertNotNil(control.lockedUntil)
        XCTAssertFalse(control.verify("2468"), "locked out even with the right PIN")
        clock.advance(61)
        XCTAssertFalse(control.isLockedOut)
        XCTAssertTrue(control.verify("2468"))
        XCTAssertEqual(control.failedAttempts, 0)

        // Сессия истекает через 5 минут без действий.
        clock.advance(ParentalControl.sessionTimeout + 1)
        XCTAssertFalse(control.update { $0.blockDrugs = false })
        XCTAssertTrue(control.rules.blockDrugs)

        // Переустановка: Keychain сохранил PIN и правила, UserDefaults пуст.
        let (freshDefaults, freshSuite) = makeDefaults()
        defer { freshDefaults.removePersistentDomain(forName: freshSuite) }
        let restored = ParentalControl(defaults: freshDefaults, secrets: secrets, clock: { clock.now })
        XCTAssertTrue(restored.hasPIN)
        XCTAssertTrue(restored.rules.enabled)
        XCTAssertFalse(restored.unlocked)

        XCTAssertFalse(restored.disable(pin: "1357"))
        XCTAssertTrue(restored.rules.enabled)
        XCTAssertTrue(restored.disable(pin: "2468"))
        XCTAssertFalse(restored.rules.enabled)
        XCTAssertTrue(restored.hasPIN)

        XCTAssertTrue(restored.resetEverything(confirmWithPIN: "2468"))
        XCTAssertFalse(restored.hasPIN)
        XCTAssertEqual(restored.rules, ParentalRules())
    }

    func testDailyLimitQuietHoursAndOverride() {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let clock = ParentalTestClock(noon())
        let control = ParentalControl(defaults: defaults, secrets: ParentalMemorySecrets(), clock: { clock.now })
        XCTAssertTrue(control.setPIN("1234"))
        XCTAssertTrue(control.update { rules in
            rules.enabled = true
            rules.dailyLimitMinutes = 30
        })
        control.lock()

        control.recordUsage(seconds: 29 * 60)
        XCTAssertEqual(control.minutesUsedToday, 29)
        XCTAssertFalse(control.isOverDailyLimit)
        XCTAssertNil(control.blockReason)
        control.recordUsage(seconds: 60)
        XCTAssertTrue(control.isOverDailyLimit)
        XCTAssertEqual(control.blockKind, .dailyLimit)
        XCTAssertTrue(control.blockReason?.contains("30") ?? false)

        XCTAssertFalse(control.unlockForToday(pin: "9999"))
        XCTAssertNotNil(control.blockReason)
        XCTAssertTrue(control.unlockForToday(pin: "1234"))
        XCTAssertNil(control.blockReason)
        XCTAssertFalse(control.unlocked, "unlocking the chat must not open the settings")

        // Новый день: счётчик и разрешение родителя сбрасываются.
        clock.advance(24 * 3600)
        control.refresh()
        XCTAssertEqual(control.minutesUsedToday, 0)
        XCTAssertNil(control.blockReason)

        XCTAssertTrue(control.verify("1234"))
        XCTAssertTrue(control.update { rules in
            rules.quietHoursEnabled = true
            rules.quietStart = 11 * 60
            rules.quietEnd = 13 * 60
        })
        XCTAssertTrue(control.isQuietHours)
        XCTAssertEqual(control.blockKind, .quietHours)
        XCTAssertTrue(control.blockReason?.contains("тихие часы") ?? false)
    }
}
