import AVFoundation
import SwiftUI

// MARK: - Контекст карточки вопросов

/// Где показана карточка вопросов: в каком сообщении и последнее ли оно.
/// Таймер идёт только у вопросов в последнем сообщении чата, когда ответ уже дописан.
struct QuestionContext: Equatable {
    var messageID: UUID?
    var isLatest: Bool
}

private struct QuestionContextKey: EnvironmentKey {
    static let defaultValue = QuestionContext(messageID: nil, isLatest: false)
}

extension EnvironmentValues {
    var questionContext: QuestionContext {
        get { self[QuestionContextKey.self] }
        set { self[QuestionContextKey.self] = newValue }
    }
}

// MARK: - Медиа внутри вопроса

/// Картинка, звук, видео или файл, прикреплённые к вопросу.
struct QuestionMedia: Equatable {
    enum Kind: String { case image, audio, video, file }
    var kind: Kind
    var value: String

    var url: URL? {
        guard let url = URL(string: value.trimmingCharacters(in: .whitespaces)),
              let scheme = url.scheme?.lowercased(), scheme == "https" || scheme == "http" else { return nil }
        return url
    }

    /// Строка вида `@audio https://…`, `@image …`, `@video …`, `@file имя`.
    static func parse(_ line: String) -> QuestionMedia? {
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        guard trimmed.hasPrefix("@") else { return nil }
        let body = trimmed.dropFirst()
        guard let space = body.firstIndex(of: " ") else { return nil }
        let name = body[..<space].lowercased()
        let value = body[space...].trimmingCharacters(in: .whitespaces)
        guard !value.isEmpty else { return nil }
        switch name {
        case "image", "photo", "picture", "картинка", "фото": return QuestionMedia(kind: .image, value: value)
        case "audio", "voice", "sound", "аудио", "голос", "звук": return QuestionMedia(kind: .audio, value: value)
        case "video", "видео": return QuestionMedia(kind: .video, value: value)
        case "file", "файл": return QuestionMedia(kind: .file, value: value)
        default: return nil
        }
    }

    /// Картинка Markdown `![подпись](адрес)` внутри вопроса.
    static func markdownImage(_ line: String) -> QuestionMedia? {
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        guard trimmed.hasPrefix("!["), let open = trimmed.range(of: "]("),
              trimmed.hasSuffix(")") else { return nil }
        let start = open.upperBound
        let end = trimmed.index(before: trimmed.endIndex)
        guard start < end else { return nil }
        let address = String(trimmed[start..<end]).trimmingCharacters(in: .whitespaces)
        let lower = address.lowercased()
        let isVideo = URL(string: address).map { MediaLinks.isVideo($0) } ?? false
        let kind: Kind = isVideo ? .video
            : (lower.hasSuffix(".mp3") || lower.hasSuffix(".m4a") || lower.hasSuffix(".wav") || lower.hasSuffix(".ogg") ? .audio : .image)
        return QuestionMedia(kind: kind, value: address)
    }
}

// MARK: - Настройки блока вопросов

/// Строки-настройки в начале блока ```ask: `@timer 10`, `@mode quiz`, `@title …`.
struct QuestionnaireHeader: Equatable {
    /// Секунд на один вопрос; 0 — без таймера.
    var timer: Int = 10
    var quiz = false
    var title = ""

    static let defaultTimer = 10

    static func parse(_ body: String) -> QuestionnaireHeader {
        var header = QuestionnaireHeader()
        for rawLine in body.components(separatedBy: .newlines) {
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            guard line.hasPrefix("@") else { continue }
            let lower = line.lowercased()
            if lower.hasPrefix("@timer") || lower.hasPrefix("@таймер") {
                let value = lower.drop(while: { $0 != " " }).trimmingCharacters(in: .whitespaces)
                if value == "off" || value == "нет" || value == "0" {
                    header.timer = 0
                } else if let seconds = Int(value.filter(\.isNumber)) {
                    header.timer = min(600, max(3, seconds))
                }
            } else if lower.hasPrefix("@mode quiz") || lower == "@quiz" || lower.hasPrefix("@тест") || lower.hasPrefix("@mode test") {
                header.quiz = true
            } else if lower.hasPrefix("@title") || lower.hasPrefix("@заголовок") {
                header.title = String(line.drop(while: { $0 != " " })).trimmingCharacters(in: .whitespaces)
            }
        }
        return header
    }
}

// MARK: - Состояние ответов

/// Ответы на карточки вопросов. Живут отдельно от представления: карточка может
/// пересоздаваться при прокрутке, а выбор и таймер должны сохраниться.
@MainActor
final class QuestionnaireStore: ObservableObject {
    static let shared = QuestionnaireStore()

    struct Entry: Codable, Equatable {
        /// nil — вопрос ещё впереди, "" — ответа не было (время вышло или пропущен).
        var answers: [String?]
        var current: Int = 0
        var finished = false
        var sent = false
        var deadline: Date?
        var pausedRemaining: Double?
        var updatedAt = Date()
    }

    @Published private(set) var entries: [String: Entry] = [:]
    private let defaultsKey = "honer.questionnaires.v1"
    private var saveTask: Task<Void, Never>?

    private init() {
        if let data = UserDefaults.standard.data(forKey: defaultsKey),
           let saved = try? JSONDecoder().decode([String: Entry].self, from: data) {
            entries = saved
        }
    }

    func entry(_ key: String, count: Int) -> Entry {
        var value = entries[key] ?? Entry(answers: Array(repeating: nil, count: count))
        if value.answers.count < count {
            value.answers += Array(repeating: nil, count: count - value.answers.count)
        }
        return value
    }

    func update(_ key: String, count: Int, _ change: (inout Entry) -> Void) {
        var value = entry(key, count: count)
        change(&value)
        value.updatedAt = Date()
        entries[key] = value
        scheduleSave()
    }

    private func scheduleSave() {
        saveTask?.cancel()
        saveTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 400_000_000)
            guard let self, !Task.isCancelled else { return }
            // Храним только свежие карточки: старые ответы уже есть в самом чате.
            if self.entries.count > 300 {
                let keep = self.entries.sorted { $0.value.updatedAt > $1.value.updatedAt }.prefix(300)
                self.entries = Dictionary(uniqueKeysWithValues: keep.map { ($0.key, $0.value) })
            }
            if let data = try? JSONEncoder().encode(self.entries) {
                UserDefaults.standard.set(data, forKey: self.defaultsKey)
            }
        }
    }
}

// MARK: - Итог для модели

enum QuestionnaireReport {
    /// Правильный ли ответ в тесте: совпадает с одним из отмеченных вариантов.
    static func isCorrect(_ answer: String?, _ question: QuickQuestion) -> Bool {
        guard let answer, !answer.isEmpty, !question.correct.isEmpty else { return false }
        let normalized = answer.trimmingCharacters(in: .whitespaces).lowercased()
        return question.correct.contains { index in
            index < question.options.count
                && question.options[index].trimmingCharacters(in: .whitespaces).lowercased() == normalized
        }
    }

    static func correctText(_ question: QuickQuestion) -> String {
        question.correct.compactMap { $0 < question.options.count ? question.options[$0] : nil }.joined(separator: " / ")
    }

    static func score(_ answers: [String?], _ questions: [QuickQuestion]) -> Int {
        zip(answers, questions).filter { isCorrect($0.0, $0.1) }.count
    }

    /// Сообщение, которое уходит в чат после последнего вопроса.
    static func message(answers: [String?], questions: [QuickQuestion], quiz: Bool, title: String, english: Bool) -> String {
        let noAnswer = english ? "no answer (time ran out)" : "нет ответа (время вышло)"
        let answered = answers.contains { ($0 ?? "").isEmpty == false }
        if !answered {
            return english
                ? "I didn't answer the questions in time. Decide yourself how best to proceed and continue."
                : "Я не ответил на вопросы за отведённое время. Реши сам, как лучше поступить, и продолжай."
        }
        if questions.count == 1 && !quiz {
            return answers.first.flatMap { $0 } ?? ""
        }
        var lines: [String] = []
        if quiz {
            let points = score(answers, questions)
            let name = title.isEmpty ? "" : (english ? " \"\(title)\"" : " «\(title)»")
            lines.append(english ? "Test results\(name): \(points) of \(questions.count)."
                                 : "Результаты теста\(name): \(points) из \(questions.count).")
        } else {
            lines.append(english ? "My answers:" : "Мои ответы:")
        }
        for (index, question) in questions.enumerated() {
            let answer = index < answers.count ? (answers[index] ?? "") : ""
            var line = "\(index + 1). \(question.text) — "
            if answer.isEmpty {
                line += noAnswer
            } else {
                line += (english ? "my answer: " : "мой ответ: ") + answer
            }
            if quiz && !question.correct.isEmpty {
                if isCorrect(answer, question) {
                    line += " ✓"
                } else {
                    line += english ? " ✗ (correct: \(correctText(question)))" : " ✗ (правильно: \(correctText(question)))"
                }
            }
            lines.append(line)
        }
        if !quiz && answers.contains(where: { ($0 ?? "").isEmpty }) {
            lines.append(english ? "Where there is no answer, decide yourself." : "Где ответа нет — реши сам.")
        }
        return lines.joined(separator: "\n")
    }
}

// MARK: - Карточка вопросов

/// Вопросы Honer AI: по одному на экране, с вариантами, таймером и итогом.
/// До 30 вопросов; для теста — подсветка правильных ответов и счёт.
struct QuestionsCardView: View {
    let questions: [QuickQuestion]
    /// Текст блока целиком: из него читаются настройки @timer, @mode, @title.
    var rawBody: String = ""
    /// Номер блока в сообщении — вместе с сообщением даёт ключ состояния.
    var blockID: Int = 0
    let fontSize: Double
    var stillStreaming: Bool = false
    /// Отправка ответа в чат. `false` — отправить сейчас нельзя.
    var onAnswer: (String) -> Bool

    @Environment(\.questionContext) private var context
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject private var store = QuestionnaireStore.shared
    @AppStorage("honor.language") private var language = "ru"
    @State private var visible = false
    @State private var draft = ""
    @FocusState private var customFocused: Bool

    private var english: Bool { language == "en" }
    private func text(_ ru: String, _ en: String) -> String { english ? en : ru }
    private var header: QuestionnaireHeader { QuestionnaireHeader.parse(rawBody) }
    private var isQuiz: Bool { header.quiz || questions.contains { !$0.correct.isEmpty } }
    private var key: String { (context.messageID?.uuidString ?? "preview") + "#\(blockID)" }
    private var entry: QuestionnaireStore.Entry { store.entry(key, count: questions.count) }
    private var currentIndex: Int { min(entry.current, max(0, questions.count - 1)) }
    private var current: QuickQuestion? { questions.isEmpty ? nil : questions[currentIndex] }

    /// Карточка ждёт ответа: последнее сообщение, ответ дописан, приложение открыто.
    private var active: Bool {
        context.messageID != nil && context.isLatest && !stillStreaming && !entry.finished
            && !questions.isEmpty && scenePhase == .active
    }
    /// Карточка в старом сообщении, на которую так и не ответили.
    private var closed: Bool { context.messageID != nil && !context.isLatest && !entry.finished }
    private var timerRunning: Bool { header.timer > 0 && active && visible && !customFocused }
    private var timerTaskID: String { "\(key)|\(entry.current)|\(timerRunning)" }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            QuestionnaireTitleBar(title: titleText, counter: counterText, quiz: isQuiz,
                                  fontSize: fontSize, streaming: stillStreaming,
                                  deadline: timerRunning ? entry.deadline : nil, total: Double(header.timer))
            content
        }
        .padding(15)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(HonorTheme.surface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous)
            .stroke(HonorTheme.accent.opacity(active ? 0.55 : 0.3), lineWidth: active ? 1.2 : 0.9))
        .animation(.spring(response: 0.42, dampingFraction: 0.86), value: entry.current)
        .animation(.easeInOut(duration: 0.3), value: entry.finished)
        .task(id: timerTaskID) { await runTimer() }
        .onAppear { visible = true }
        .onDisappear { visible = false; pauseTimer() }
        .onChange(of: customFocused) { focused in if focused { pauseTimer() } }
        .onChange(of: scenePhase) { phase in if phase != .active { pauseTimer() } }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("message.questions")
    }

    @ViewBuilder
    private var content: some View {
        if entry.finished {
            QuestionnaireSummaryView(questions: questions, answers: entry.answers, quiz: isQuiz,
                                     sent: entry.sent, fontSize: fontSize, english: english,
                                     onResend: sendResults)
                .transition(.opacity.combined(with: .scale(scale: 0.97)))
        } else if closed {
            closedView
        } else if let current {
            if questions.count > 1 { QuestionnaireProgress(index: currentIndex, count: questions.count) }
            QuestionPageView(question: current, index: currentIndex, answer: entry.answers[currentIndex],
                             quiz: isQuiz, fontSize: fontSize, enabled: !stillStreaming, english: english,
                             onChoose: { value in choose(value) })
                .id(currentIndex)
                .transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity),
                                        removal: .move(edge: .leading).combined(with: .opacity)))
            if current.allowsCustom || current.options.isEmpty { customField }
            footer
        }
    }

    private var titleText: String {
        if !header.title.isEmpty { return header.title }
        if isQuiz { return text("Тест", "Quiz") }
        return questions.count == 1 ? text("Вопрос Honer AI", "Question from Honer AI")
                                    : text("Вопросы Honer AI", "Questions from Honer AI")
    }

    private var counterText: String {
        guard questions.count > 1, !entry.finished else { return "" }
        return "\(currentIndex + 1)/\(questions.count)"
    }

    private var closedView: some View {
        HStack(spacing: 8) {
            Image(systemName: "lock.fill").font(.system(size: 12))
            Text(text("Вопросы закрыты: разговор пошёл дальше", "Questions closed: the conversation moved on"))
                .font(.system(size: fontSize * 0.78))
        }
        .foregroundStyle(HonorTheme.secondary)
        .accessibilityIdentifier("question.closed")
    }

    private var customField: some View {
        HStack(spacing: 8) {
            TextField(text("Свой ответ", "Your answer"), text: $draft)
                .font(.system(size: fontSize * 0.95))
                .textFieldStyle(.plain)
                .focused($customFocused)
                .submitLabel(.send)
                .onSubmit(sendCustom)
                .padding(.horizontal, 12)
                .frame(minHeight: 42)
                .background(HonorTheme.raised, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(HonorTheme.divider, lineWidth: 0.7))
                .accessibilityIdentifier("question.custom.field")
            Button(action: sendCustom) {
                Image(systemName: "arrow.up")
                    .font(.system(size: 14, weight: .semibold))
                    .frame(width: 38, height: 38)
                    .background(HonorTheme.accent, in: Circle())
                    .foregroundStyle(.white)
            }
            .buttonStyle(.plain)
            .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            .accessibilityIdentifier("question.custom.send")
        }
    }

    private var footer: some View {
        HStack {
            if header.timer > 0 && active {
                Text(text("На ответ \(header.timer) с — потом решу сам", "\(header.timer) s to answer, then I'll decide"))
                    .font(.system(size: fontSize * 0.66))
                    .foregroundStyle(HonorTheme.secondary)
            }
            Spacer(minLength: 0)
            if active && questions.count > 1 {
                Button(text("Пропустить", "Skip")) { choose("") }
                    .font(.system(size: fontSize * 0.72, weight: .medium))
                    .foregroundStyle(HonorTheme.secondary)
                    .accessibilityIdentifier("question.skip")
            }
        }
    }

    // MARK: Действия

    private func sendCustom() {
        let value = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return }
        draft = ""
        customFocused = false
        choose(value)
    }

    private func choose(_ value: String) {
        guard !entry.finished, !questions.isEmpty, !stillStreaming else { return }
        let index = currentIndex
        guard entry.answers[index] == nil else { return }
        store.update(key, count: questions.count) { item in
            item.answers[index] = value
            item.deadline = nil
            item.pausedRemaining = nil
        }
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        if isQuiz, let question = current, !value.isEmpty, !question.correct.isEmpty {
            let right = QuestionnaireReport.isCorrect(value, question)
            UINotificationFeedbackGenerator().notificationOccurred(right ? .success : .error)
        }
        let delay: UInt64 = isQuiz ? 1_000_000_000 : 350_000_000
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: delay)
            advance(from: index)
        }
    }

    private func advance(from index: Int) {
        let now = store.entry(key, count: questions.count)
        guard now.current == index, !now.finished else { return }
        if index + 1 < questions.count {
            store.update(key, count: questions.count) { $0.current = index + 1 }
        } else {
            store.update(key, count: questions.count) { $0.finished = true }
            sendResults()
        }
    }

    private func sendResults() {
        let latest = store.entry(key, count: questions.count)
        guard !latest.sent else { return }
        let message = QuestionnaireReport.message(answers: latest.answers, questions: questions, quiz: isQuiz,
                                                  title: header.title, english: english)
        guard !message.isEmpty, onAnswer(message) else { return }
        store.update(key, count: questions.count) { $0.sent = true }
    }

    // MARK: Таймер

    private func runTimer() async {
        guard timerRunning else { return }
        let index = currentIndex
        var deadline = entry.deadline
        if deadline == nil {
            let remaining = entry.pausedRemaining ?? Double(header.timer)
            let end = Date().addingTimeInterval(remaining)
            deadline = end
            store.update(key, count: questions.count) { item in
                item.deadline = end
                item.pausedRemaining = nil
            }
        }
        guard let end = deadline else { return }
        let wait = end.timeIntervalSinceNow
        if wait > 0 { try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000)) }
        guard !Task.isCancelled else { return }
        let now = store.entry(key, count: questions.count)
        guard now.current == index, !now.finished, now.answers[index] == nil else { return }
        // Время вышло: вопрос закрывается, решение остаётся за Honer AI.
        UINotificationFeedbackGenerator().notificationOccurred(.warning)
        store.update(key, count: questions.count) { item in
            item.answers[index] = ""
            item.deadline = nil
        }
        try? await Task.sleep(nanoseconds: 450_000_000)
        advance(from: index)
    }

    private func pauseTimer() {
        guard let deadline = entry.deadline, !entry.finished else { return }
        let remaining = max(1, deadline.timeIntervalSinceNow)
        store.update(key, count: questions.count) { item in
            item.pausedRemaining = remaining
            item.deadline = nil
        }
    }
}

// MARK: - Части карточки

/// Заголовок: значок, название, счётчик и кольцо таймера.
private struct QuestionnaireTitleBar: View {
    let title: String
    let counter: String
    let quiz: Bool
    let fontSize: Double
    let streaming: Bool
    let deadline: Date?
    let total: Double

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: quiz ? "checkmark.seal" : "questionmark.bubble")
                .font(.system(size: 14, weight: .semibold))
            Text(title)
                .font(.system(size: fontSize * 0.8, weight: .semibold))
                .lineLimit(1)
            if streaming { ProgressView().scaleEffect(0.6) }
            Spacer(minLength: 0)
            if !counter.isEmpty {
                Text(counter)
                    .font(.system(size: fontSize * 0.72, weight: .semibold, design: .rounded))
                    .foregroundStyle(HonorTheme.secondary)
                    .monospacedDigit()
            }
            if let deadline, total > 0 {
                CountdownRing(deadline: deadline, total: total)
                    .frame(width: 30, height: 30)
                    .transition(.scale.combined(with: .opacity))
            }
        }
        .foregroundStyle(HonorTheme.accent)
        .animation(.easeOut(duration: 0.2), value: deadline == nil)
    }
}

/// Кольцо обратного отсчёта: плавно убывает, к концу краснеет.
private struct CountdownRing: View {
    let deadline: Date
    let total: Double

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { timeline in
            let remaining = max(0, deadline.timeIntervalSince(timeline.date))
            ring(remaining: remaining)
        }
        .accessibilityIdentifier("question.timer")
    }

    private func ring(remaining: Double) -> some View {
        let fraction = CGFloat(min(1, remaining / max(total, 1)))
        let color: Color = remaining <= 3 ? .red : (remaining <= 6 ? .orange : HonorTheme.accent)
        return ZStack {
            Circle().stroke(HonorTheme.divider, lineWidth: 3)
            Circle()
                .trim(from: 0, to: fraction)
                .stroke(color, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                .rotationEffect(.degrees(-90))
            Text("\(Int(ceil(remaining)))")
                .font(.system(size: 11, weight: .bold, design: .rounded))
                .foregroundStyle(color)
                .monospacedDigit()
        }
        .accessibilityLabel("\(Int(ceil(remaining)))")
    }
}

/// Полоса прогресса по вопросам.
private struct QuestionnaireProgress: View {
    let index: Int
    let count: Int

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                Capsule().fill(HonorTheme.raised)
                Capsule()
                    .fill(LinearGradient(colors: [HonorTheme.accent, Color(red: 0.6, green: 0.45, blue: 1)],
                                         startPoint: .leading, endPoint: .trailing))
                    .frame(width: geometry.size.width * CGFloat(index + 1) / CGFloat(max(count, 1)))
            }
        }
        .frame(height: 5)
        .accessibilityIdentifier("question.progress")
    }
}

/// Один вопрос: текст, медиа и варианты ответа.
private struct QuestionPageView: View {
    let question: QuickQuestion
    let index: Int
    let answer: String?
    let quiz: Bool
    let fontSize: Double
    let enabled: Bool
    let english: Bool
    let onChoose: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(question.text)
                .font(.system(size: fontSize, weight: .semibold))
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("question.text")
            ForEach(Array(question.media.enumerated()), id: \.offset) { _, media in
                QuestionMediaView(media: media, fontSize: fontSize)
            }
            ForEach(Array(question.options.enumerated()), id: \.offset) { optionIndex, option in
                QuestionOptionButton(option: option, letter: QuestionOptionButton.letter(optionIndex, english: english),
                                     state: state(for: optionIndex, option: option), fontSize: fontSize,
                                     enabled: enabled && answer == nil) {
                    onChoose(option)
                }
            }
            if answer == "" {
                Label(english ? "No answer — Honer AI will decide" : "Без ответа — Honer AI решит сам",
                      systemImage: "hourglass")
                    .font(.system(size: fontSize * 0.74))
                    .foregroundStyle(.orange)
                    .transition(.opacity)
                    .accessibilityIdentifier("question.timeout")
            }
        }
    }

    private func state(for optionIndex: Int, option: String) -> QuestionOptionButton.Mark {
        guard let answer, !answer.isEmpty else { return answer == "" && quiz && question.correct.contains(optionIndex) ? .correct : .none }
        let chosen = answer == option
        if quiz && !question.correct.isEmpty {
            if question.correct.contains(optionIndex) { return .correct }
            return chosen ? .wrong : .dimmed
        }
        return chosen ? .selected : .dimmed
    }
}

/// Вариант ответа: буква, текст и цвет состояния.
private struct QuestionOptionButton: View {
    enum Mark { case none, selected, correct, wrong, dimmed }
    let option: String
    let letter: String
    let state: Mark
    let fontSize: Double
    let enabled: Bool
    let action: () -> Void

    static func letter(_ index: Int, english: Bool) -> String {
        let alphabet = Array(english ? "ABCDEFGHIJKLMNOPQRST" : "АБВГДЕЖЗИКЛМНОПРСТУ")
        return index < alphabet.count ? String(alphabet[index]) : "\(index + 1)"
    }

    private var tint: Color {
        switch state {
        case .correct: return .green
        case .wrong: return .red
        case .selected: return HonorTheme.accent
        case .none, .dimmed: return HonorTheme.divider
        }
    }

    private var highlighted: Bool { state == .correct || state == .wrong || state == .selected }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Text(letter)
                    .font(.system(size: fontSize * 0.74, weight: .bold))
                    .foregroundStyle(highlighted ? Color.white : HonorTheme.secondary)
                    .frame(width: 24, height: 24)
                    .background(highlighted ? tint : HonorTheme.raised, in: Circle())
                Text(option)
                    .font(.system(size: fontSize * 0.96))
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
                badge
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(minHeight: 46)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(highlighted ? tint.opacity(0.16) : HonorTheme.raised,
                        in: RoundedRectangle(cornerRadius: 13, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 13, style: .continuous)
                .stroke(highlighted ? tint.opacity(0.75) : HonorTheme.divider.opacity(0.7), lineWidth: 1))
            .opacity(state == .dimmed ? 0.55 : 1)
            .contentShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
        }
        .buttonStyle(.plain)
        .foregroundStyle(HonorTheme.foreground)
        .disabled(!enabled)
        .animation(.easeInOut(duration: 0.22), value: state)
        .accessibilityIdentifier("question.option." + option)
        .accessibilityLabel("\(letter). \(option)")
    }

    @ViewBuilder
    private var badge: some View {
        switch state {
        case .correct: Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
        case .wrong: Image(systemName: "xmark.circle.fill").foregroundStyle(.red)
        case .selected: Image(systemName: "checkmark").foregroundStyle(HonorTheme.accent)
        case .none, .dimmed: EmptyView()
        }
    }
}

/// Картинка, звук, видео или файл в вопросе.
private struct QuestionMediaView: View {
    let media: QuestionMedia
    let fontSize: Double

    var body: some View {
        switch media.kind {
        case .image:
            if let url = media.url {
                CachedRemoteImage(url: url, caption: "", fontSize: fontSize * 0.8)
                    .frame(maxHeight: 240)
            }
        case .video:
            if let url = media.url {
                VideoCardView(url: url, caption: "", fontSize: fontSize * 0.8)
            }
        case .audio:
            if let url = media.url {
                QuestionAudioPlayer(url: url, fontSize: fontSize)
            }
        case .file:
            Label(media.value, systemImage: "doc.fill")
                .font(.system(size: fontSize * 0.8, weight: .medium))
                .padding(.horizontal, 12).padding(.vertical, 9)
                .background(HonorTheme.raised, in: Capsule())
        }
    }
}

/// Проигрыватель звука внутри вопроса.
private struct QuestionAudioPlayer: View {
    let url: URL
    let fontSize: Double
    @State private var player: AVPlayer?
    @State private var playing = false
    @State private var progress: Double = 0
    @State private var observer: Any?

    var body: some View {
        HStack(spacing: 12) {
            Button(action: toggle) {
                Image(systemName: playing ? "pause.fill" : "play.fill")
                    .font(.system(size: 15, weight: .bold))
                    .frame(width: 38, height: 38)
                    .background(HonorTheme.accent, in: Circle())
                    .foregroundStyle(.white)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("question.audio.play")
            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    Capsule().fill(HonorTheme.divider)
                    Capsule().fill(HonorTheme.accent)
                        .frame(width: geometry.size.width * CGFloat(progress))
                }
            }
            .frame(height: 4)
            Image(systemName: "waveform").foregroundStyle(HonorTheme.secondary)
        }
        .padding(10)
        .background(HonorTheme.raised, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .onDisappear(perform: stop)
    }

    private func toggle() {
        if player == nil {
            let item = AVPlayer(url: url)
            player = item
            observer = item.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.2, preferredTimescale: 600), queue: .main) { time in
                let duration = item.currentItem?.duration.seconds ?? 0
                if duration.isFinite, duration > 0 { progress = min(1, time.seconds / duration) }
                if progress >= 0.999 { playing = false }
            }
        }
        if playing { player?.pause() } else {
            try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
            if progress >= 0.999 { player?.seek(to: .zero); progress = 0 }
            player?.play()
        }
        playing.toggle()
    }

    private func stop() {
        player?.pause()
        if let observer { player?.removeTimeObserver(observer) }
        observer = nil
        player = nil
        playing = false
    }
}

/// Итог: счёт теста или список ответов, и отметка об отправке.
private struct QuestionnaireSummaryView: View {
    let questions: [QuickQuestion]
    let answers: [String?]
    let quiz: Bool
    let sent: Bool
    let fontSize: Double
    let english: Bool
    let onResend: () -> Void

    private var score: Int { QuestionnaireReport.score(answers, questions) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if quiz { scoreHeader }
            ForEach(Array(questions.enumerated()), id: \.offset) { index, question in
                row(index: index, question: question)
            }
            status
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("question.summary")
    }

    private var scoreHeader: some View {
        let fraction = CGFloat(score) / CGFloat(max(questions.count, 1))
        return HStack(spacing: 14) {
            ZStack {
                Circle().stroke(HonorTheme.divider, lineWidth: 6)
                Circle().trim(from: 0, to: fraction)
                    .stroke(fraction >= 0.7 ? Color.green : (fraction >= 0.4 ? Color.orange : Color.red),
                            style: StrokeStyle(lineWidth: 6, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                Text("\(score)/\(questions.count)")
                    .font(.system(size: 15, weight: .heavy, design: .rounded))
            }
            .frame(width: 62, height: 62)
            VStack(alignment: .leading, spacing: 3) {
                Text(english ? "Result" : "Результат")
                    .font(.system(size: fontSize * 0.9, weight: .bold))
                Text(verdict(fraction))
                    .font(.system(size: fontSize * 0.75))
                    .foregroundStyle(HonorTheme.secondary)
            }
        }
        .accessibilityIdentifier("question.score")
    }

    private func verdict(_ fraction: CGFloat) -> String {
        if fraction >= 0.9 { return english ? "Excellent!" : "Отлично!" }
        if fraction >= 0.7 { return english ? "Good job" : "Хороший результат" }
        if fraction >= 0.4 { return english ? "Not bad — let's review mistakes" : "Неплохо — разберём ошибки" }
        return english ? "Let's go through it together" : "Давайте разберём вместе"
    }

    private func row(index: Int, question: QuickQuestion) -> some View {
        let answer = index < answers.count ? (answers[index] ?? "") : ""
        let right = QuestionnaireReport.isCorrect(answer, question)
        let symbol = answer.isEmpty ? "hourglass" : (quiz && !question.correct.isEmpty ? (right ? "checkmark.circle.fill" : "xmark.circle.fill") : "checkmark.circle")
        let color: Color = answer.isEmpty ? .orange : (quiz && !question.correct.isEmpty ? (right ? .green : .red) : HonorTheme.accent)
        return HStack(alignment: .top, spacing: 9) {
            Image(systemName: symbol).foregroundStyle(color).font(.system(size: 14))
            VStack(alignment: .leading, spacing: 2) {
                Text(question.text).font(.system(size: fontSize * 0.8, weight: .medium)).lineLimit(3)
                Text(answer.isEmpty ? (english ? "no answer" : "нет ответа") : answer)
                    .font(.system(size: fontSize * 0.74))
                    .foregroundStyle(HonorTheme.secondary)
                if quiz && !right && !question.correct.isEmpty {
                    Text((english ? "Correct: " : "Правильно: ") + QuestionnaireReport.correctText(question))
                        .font(.system(size: fontSize * 0.74, weight: .medium))
                        .foregroundStyle(.green)
                }
            }
        }
    }

    @ViewBuilder
    private var status: some View {
        if sent {
            Label(english ? "Answers sent to Honer AI" : "Ответы отправлены Honer AI", systemImage: "paperplane.fill")
                .font(.system(size: fontSize * 0.74))
                .foregroundStyle(HonorTheme.accent)
                .accessibilityIdentifier("question.sent")
        } else {
            Button(action: onResend) {
                Label(english ? "Send answers" : "Отправить ответы", systemImage: "paperplane")
                    .font(.system(size: fontSize * 0.8, weight: .semibold))
                    .padding(.horizontal, 14).padding(.vertical, 9)
                    .background(HonorTheme.accent.opacity(0.15), in: Capsule())
            }
            .buttonStyle(.plain)
            .foregroundStyle(HonorTheme.accent)
            .accessibilityIdentifier("question.send")
        }
    }
}
