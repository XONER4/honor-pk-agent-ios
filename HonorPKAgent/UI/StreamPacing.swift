import Foundation
import QuartzCore
import Combine

/// Скорость печати ответа: сколько символов показать в очередном кадре.
///
/// Прежняя схема держала базовую скорость 30 символов в секунду и переключала
/// множитель ступенями (×1,8 → ×3 → ×6 → ×10) по величине отставания. Сервис
/// присылает текст быстрее 30 символов в секунду, поэтому отставание росло,
/// множитель прыгал со ступени на ступень, и печать то ползла, то срывалась
/// вперёд — это и выглядело как рывки.
///
/// Здесь скорость меняется непрерывно: печать держится примерно на `targetLatency`
/// секунд позади потока, а изменение скорости сглаживается, поэтому пачки текста
/// из сети превращаются в ровный набор без скачков. После конца потока хвост
/// дописывается ускоренно, но тоже плавно, и не дольше `maximumTail` секунд.
struct StreamPace: Equatable {
    /// Нижняя граница скорости, символов в секунду: с неё начинается ответ.
    var minimumRate: Double = 110
    /// Верхняя граница скорости, символов в секунду.
    var maximumRate: Double = 3000
    /// На сколько секунд печать отстаёт от потока, пока ответ идёт.
    var targetLatency: Double = 0.35
    /// То же после конца потока: хвост догоняется быстрее.
    var closingLatency: Double = 0.18
    /// Насколько быстро скорость подстраивается (1/с). Меньше — мягче.
    var responsiveness: Double = 8
    /// Хвост после конца потока показывается целиком не позже этого срока.
    var maximumTail: Double = 1.0
    /// Нижняя граница скорости после конца потока: последние символы не ползут.
    var closingMinimumRate: Double = 260

    /// Сколько символов должно быть видно после этого кадра.
    ///
    /// - Parameters:
    ///   - state: состояние печати, изменяется на месте.
    ///   - available: сколько символов уже пришло.
    ///   - elapsed: сколько секунд прошло с прошлого кадра.
    ///   - streamOpen: поток ещё идёт (могут прийти новые символы).
    /// - Returns: число видимых символов (никогда не уменьшается и не превышает `available`).
    func step(state: inout StreamPaceState, available: Int, elapsed: Double, streamOpen: Bool) -> Int {
        // Кадр мог задержаться (приложение уходило в фон, система притормозила):
        // большой шаг времени дал бы рывок, поэтому ограничиваем его.
        let dt = max(0, min(elapsed, 1.0 / 20.0))
        if !streamOpen { state.sinceClose += dt }
        let lag = Double(available) - state.shown
        guard lag > 0 else {
            state.shown = min(state.shown, Double(available))
            return Int(state.shown)
        }
        if !streamOpen, state.sinceClose >= maximumTail {
            state.shown = Double(available)
            return available
        }
        let latency = streamOpen ? targetLatency : closingLatency
        let floor = streamOpen ? minimumRate : max(minimumRate, closingMinimumRate)
        let desired = min(max(lag / latency, floor), maximumRate)
        if state.rate <= 0 { state.rate = minimumRate }
        // Экспоненциальное сглаживание: скорость тянется к нужной, но без скачков.
        let blend = 1 - exp(-responsiveness * dt)
        state.rate += (desired - state.rate) * blend
        state.shown = min(state.shown + state.rate * dt, Double(available))
        return Int(state.shown)
    }
}

/// Состояние печати одного текста.
struct StreamPaceState: Equatable {
    /// Сколько символов видно (дробная часть копится между кадрами).
    var shown: Double = 0
    /// Текущая скорость, символов в секунду.
    var rate: Double = 0
    /// Сколько секунд прошло после конца потока.
    var sinceClose: Double = 0

    var revealedCount: Int { Int(shown) }

    mutating func restart() { self = StreamPaceState() }
}

/// Печатаемый текст: полученная часть и показанная часть.
///
/// Показанная часть растёт от кадра к кадру и всегда является началом полученной.
/// Если полученный текст заменили целиком (перевод, очистка служебной строки),
/// показанная часть обрезается до общего начала и печать продолжается оттуда —
/// без пустого экрана и без перепечатывания ответа с нуля.
struct TypedText {
    private(set) var target = ""
    private(set) var shown = ""
    private(set) var targetCount = 0
    private(set) var shownCount = 0
    /// Позиция конца показанной части в полученном тексте (байты UTF-8).
    private var shownOffset = 0

    var isComplete: Bool { shownCount >= targetCount }

    /// Новая версия полученного текста. Возвращает, сколько символов уже показано.
    @discardableResult
    mutating func setTarget(_ text: String) -> Int {
        guard !isTarget(text) else { return shownCount }
        if Self.bytes(text, startWith: target) {
            // Обычный случай: к тексту дописали хвост.
            let tail = text.utf8.index(text.utf8.startIndex, offsetBy: target.utf8.count)
            targetCount += text[tail...].count
            target = text
            return shownCount
        }
        // Текст заменили. Оставляем видимым общее начало, дальше печать продолжится.
        var common = text.startIndex
        var otherIndex = shown.startIndex
        var kept = 0
        while common < text.endIndex, otherIndex < shown.endIndex, text[common] == shown[otherIndex] {
            common = text.index(after: common)
            otherIndex = shown.index(after: otherIndex)
            kept += 1
        }
        target = text
        targetCount = text.count
        shown = String(text[..<common])
        shownCount = kept
        shownOffset = text.utf8.distance(from: text.utf8.startIndex, to: common)
        return kept
    }

    /// Показать `count` символов (не больше полученных).
    mutating func reveal(upTo count: Int) {
        let wanted = min(count, targetCount)
        guard wanted > shownCount else { return }
        let start = target.utf8.index(target.utf8.startIndex, offsetBy: shownOffset)
        let end = target.index(start, offsetBy: wanted - shownCount, limitedBy: target.endIndex) ?? target.endIndex
        shown += target[start..<end]
        shownOffset = target.utf8.distance(from: target.utf8.startIndex, to: end)
        shownCount = end == target.endIndex ? targetCount : wanted
    }

    mutating func revealAll() {
        shown = target
        shownCount = targetCount
        shownOffset = target.utf8.count
    }

    mutating func clear() { self = TypedText() }

    /// Совпадает ли текст с полученным. Сравнение побайтовое: `==` и `hasPrefix`
    /// у String сравнивают с Unicode-нормализацией, и на длинном русском ответе
    /// каждый новый кусок потока обходился бы всё дороже.
    func isTarget(_ text: String) -> Bool {
        text.utf8.count == target.utf8.count && Self.bytes(text, startWith: target)
    }

    static func bytes(_ text: String, startWith prefix: String) -> Bool {
        let count = prefix.utf8.count
        guard count > 0 else { return true }
        guard text.utf8.count >= count else { return false }
        let fast: Bool?? = text.utf8.withContiguousStorageIfAvailable { whole in
            prefix.utf8.withContiguousStorageIfAvailable { head in
                memcmp(whole.baseAddress!, head.baseAddress!, count) == 0
            }
        }
        if case .some(.some(let value)) = fast { return value }
        return text.utf8.starts(with: prefix.utf8)
    }
}

/// Плавная печать текущего ответа: и рассуждения, и итогового текста.
///
/// Раньше за печать отвечало само представление (`TimelineView` в каждой строке).
/// Оттуда было три беды: текст начинался с полного ответа и схлопывался до пары
/// букв; разметка обновлялась шагами по 24 символа, поэтому текст стоял и прыгал;
/// хвост короче 24 символов не показывался вовсе, пока экран не пересоздавался.
/// Теперь печать ведёт один объект на весь ответ: он получает текст из потока,
/// сам выдаёт видимую часть каждый кадр экрана и гарантированно доводит её до конца.
@MainActor
final class TypingPacer: ObservableObject {
    /// Ответ, который сейчас печатается.
    @Published private(set) var messageID: UUID?
    /// Видимая часть ответа.
    @Published private(set) var content = ""
    /// Видимая часть рассуждения.
    @Published private(set) var reasoning = ""
    /// Время начала ответа: по нему идёт счётчик «Размышляю… N с».
    @Published private(set) var startedAt = Date()
    /// Когда закончилось рассуждение (пришёл первый символ ответа).
    @Published private(set) var reasoningEndedAt: Date?
    /// Шаги работы над ответом: поиск, чтение страниц, рисование.
    @Published private(set) var steps: [GenerationStep] = []
    /// Срабатывает несколько раз в секунду, пока текст растёт: по нему лента
    /// едет вслед за ответом.
    let grew = PassthroughSubject<Void, Never>()

    /// Печать закончилась: весь текст показан, поток закрыт.
    var onFinished: ((UUID) -> Void)?

    private var contentText = TypedText()
    private var reasoningText = TypedText()
    private var contentPace = StreamPaceState()
    private var reasoningPace = StreamPaceState()
    private var streamOpen = false
    private var link: CADisplayLink?
    private var lastFrame: CFTimeInterval = 0
    private var lastGrowthSignal: CFTimeInterval = 0
    private let rule = StreamPace()
    /// Рассуждение печатается быстрее: его читают вполглаза.
    private let reasoningRule = StreamPace(minimumRate: 160, targetLatency: 0.25, closingLatency: 0.12, maximumTail: 0.6)

    var isActive: Bool { messageID != nil }

    /// Начать новый ответ.
    func begin(messageID: UUID) {
        stopLink()
        contentText.clear(); reasoningText.clear()
        contentPace.restart(); reasoningPace.restart()
        content = ""; reasoning = ""
        startedAt = Date()
        reasoningEndedAt = nil
        steps = []
        streamOpen = true
        self.messageID = messageID
    }

    func setSteps(_ value: [GenerationStep]) {
        guard messageID != nil, value != steps else { return }
        steps = value
        grew.send()
    }

    /// Новый полученный текст ответа и рассуждения.
    func update(content newContent: String, reasoning newReasoning: String) {
        guard messageID != nil else { return }
        if !reasoningText.isTarget(newReasoning) {
            let kept = reasoningText.setTarget(newReasoning)
            if Double(kept) < reasoningPace.shown { reasoningPace.shown = Double(kept); reasoning = reasoningText.shown }
        }
        if !contentText.isTarget(newContent) {
            if reasoningEndedAt == nil, !newContent.isEmpty { reasoningEndedAt = Date() }
            let kept = contentText.setTarget(newContent)
            if Double(kept) < contentPace.shown { contentPace.shown = Double(kept); content = contentText.shown }
        }
        startLinkIfNeeded()
    }

    /// Поток закончен. Текст допечатывается плавно и быстро; потом печать завершается.
    func close(content finalContent: String, reasoning finalReasoning: String) {
        guard messageID != nil else { return }
        update(content: finalContent, reasoning: finalReasoning)
        streamOpen = false
        contentPace.sinceClose = 0
        reasoningPace.sinceClose = 0
        if contentText.isComplete && reasoningText.isComplete { finish() } else { startLinkIfNeeded() }
    }

    /// Остановить печать сразу и показать всё полученное.
    func cancel() {
        guard messageID != nil else { return }
        contentText.revealAll(); reasoningText.revealAll()
        content = contentText.shown; reasoning = reasoningText.shown
        streamOpen = false
        finish()
    }

    private func finish() {
        stopLink()
        guard let id = messageID else { return }
        streamOpen = false
        messageID = nil
        grew.send()
        onFinished?(id)
    }

    private func startLinkIfNeeded() {
        guard link == nil, messageID != nil else { return }
        guard !contentText.isComplete || !reasoningText.isComplete || !streamOpen else { return }
        let link = CADisplayLink(target: DisplayLinkProxy(self), selector: #selector(DisplayLinkProxy.tick(_:)))
        if #available(iOS 15.0, *) {
            // На экранах 120 Гц печать идёт со 120 кадрами в секунду — ещё плавнее.
            link.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
        }
        link.add(to: .main, forMode: .common)
        self.link = link
        lastFrame = 0
    }

    private func stopLink() {
        link?.invalidate()
        link = nil
        lastFrame = 0
    }

    fileprivate func tick(_ link: CADisplayLink) {
        let now = link.timestamp
        let elapsed = lastFrame == 0 ? link.duration : now - lastFrame
        lastFrame = now
        var changed = false

        if !reasoningText.isComplete {
            let count = reasoningRule.step(state: &reasoningPace, available: reasoningText.targetCount,
                                           elapsed: elapsed, streamOpen: streamOpen)
            if count > reasoningText.shownCount {
                reasoningText.reveal(upTo: count)
                reasoning = reasoningText.shown
                changed = true
            }
        }
        // Ответ начинает печататься, когда рассуждение уже показано целиком:
        // иначе ответ и хвост рассуждения печатались бы одновременно.
        if reasoningText.isComplete || !streamOpen, !contentText.isComplete {
            let count = rule.step(state: &contentPace, available: contentText.targetCount,
                                  elapsed: elapsed, streamOpen: streamOpen)
            if count > contentText.shownCount {
                contentText.reveal(upTo: count)
                content = contentText.shown
                changed = true
            }
        }
        if changed, now - lastGrowthSignal >= 0.05 {
            lastGrowthSignal = now
            grew.send()
        }
        if contentText.isComplete && reasoningText.isComplete {
            if streamOpen {
                // Всё полученное показано — ждём новых символов без таймера.
                stopLink()
            } else {
                finish()
            }
        }
    }
}

/// CADisplayLink держит цель сильной ссылкой — прокладка не даёт утечь печати.
/// Ссылка добавлена в главный цикл, поэтому вызов всегда приходит в главном потоке.
@MainActor
private final class DisplayLinkProxy: NSObject {
    weak var owner: TypingPacer?
    init(_ owner: TypingPacer) { self.owner = owner }
    @objc func tick(_ link: CADisplayLink) {
        guard let owner else { link.invalidate(); return }
        owner.tick(link)
    }
}
