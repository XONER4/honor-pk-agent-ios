import SwiftUI
import UIKit

// MARK: - Руководство Honer AI: модель данных
//
// Весь справочник хранится в коде на двух языках (русский и английский).
// Экран открывается из Настроек: `NavigationLink { HelpCenterView() } label: { ... }`.
// Скриншоты берутся из Resources/Guide/*.jpg, «мини-видео» рисуются SwiftUI.

/// Анимированная демонстрация («мини-видео») внутри статьи или ответа FAQ.
enum HelpDemo: String, CaseIterable, Identifiable {
    case typing
    case questionTimer
    case quiz
    case dragChat
    case selectAsk
    case table
    case webResearch
    case photoEditor
    case parental
    case voice

    var id: String { rawValue }

    /// Длина одного цикла анимации в секундах.
    var duration: Double {
        switch self {
        case .typing: return 9
        case .questionTimer: return 12.5
        case .quiz: return 10
        case .dragChat: return 8.5
        case .selectAsk: return 10
        case .table: return 10
        case .webResearch: return 10
        case .photoEditor: return 10
        case .parental: return 9
        case .voice: return 8
        }
    }

    /// Кадр, который показывается, когда включено «Уменьшение движения».
    var staticTime: Double {
        switch self {
        case .typing: return 6.2
        case .questionTimer: return 4.2
        case .quiz: return 8.6
        case .dragChat: return 3.6
        case .selectAsk: return 3.6
        case .table: return 5.6
        case .webResearch: return 3.4
        case .photoEditor: return 2.4
        case .parental: return 2.5
        case .voice: return 3.6
        }
    }

    func title(_ english: Bool) -> String {
        switch self {
        case .typing: return english ? "Live answer with reasoning" : "Живой ответ с рассуждением"
        case .questionTimer: return english ? "A question with a 10-second timer" : "Вопрос с таймером на 10 секунд"
        case .quiz: return english ? "Quiz with instant scoring" : "Тест с мгновенной проверкой"
        case .dragChat: return english ? "Dragging a chat into Pinned" : "Перетаскивание чата в «Закреплено»"
        case .selectAsk: return english ? "Ask about a selected fragment" : "Вопрос о выделенном фрагменте"
        case .table: return english ? "Editing a table" : "Редактирование таблицы"
        case .webResearch: return english ? "Researching hundreds of sites" : "Исследование сотен сайтов"
        case .photoEditor: return english ? "Removing a photo background" : "Удаление фона на фото"
        case .parental: return english ? "Turning on parental controls" : "Включение родительского контроля"
        case .voice: return english ? "Voice input" : "Голосовой ввод"
        }
    }
}

/// Статья справочника.
struct HelpArticle: Identifiable {
    let id: String
    let symbol: String
    let tint: Color
    let titleRU: String
    let titleEN: String
    let summaryRU: String
    let summaryEN: String
    let bodyRU: [String]
    let bodyEN: [String]
    let stepsRU: [String]
    let stepsEN: [String]
    let tipsRU: [String]
    let tipsEN: [String]
    let screenshot: String?
    let demo: HelpDemo?
    let related: [String]

    init(id: String, symbol: String, tint: Color,
         titleRU: String, titleEN: String,
         summaryRU: String, summaryEN: String,
         bodyRU: [String], bodyEN: [String],
         stepsRU: [String] = [], stepsEN: [String] = [],
         tipsRU: [String] = [], tipsEN: [String] = [],
         screenshot: String? = nil, demo: HelpDemo? = nil,
         related: [String] = []) {
        self.id = id
        self.symbol = symbol
        self.tint = tint
        self.titleRU = titleRU
        self.titleEN = titleEN
        self.summaryRU = summaryRU
        self.summaryEN = summaryEN
        self.bodyRU = bodyRU
        self.bodyEN = bodyEN
        self.stepsRU = stepsRU
        self.stepsEN = stepsEN
        self.tipsRU = tipsRU
        self.tipsEN = tipsEN
        self.screenshot = screenshot
        self.demo = demo
        self.related = related
    }

    func title(_ english: Bool) -> String { english ? titleEN : titleRU }
    func summary(_ english: Bool) -> String { english ? summaryEN : summaryRU }
    func paragraphs(_ english: Bool) -> [String] { english ? bodyEN : bodyRU }
    func steps(_ english: Bool) -> [String] { english ? stepsEN : stepsRU }
    func tips(_ english: Bool) -> [String] { english ? tipsEN : tipsRU }

    /// Весь текст статьи на обоих языках — для поиска.
    var searchableText: String {
        let parts: [String] = [titleRU, titleEN, summaryRU, summaryEN, id]
        let lists: [[String]] = [bodyRU, bodyEN, stepsRU, stepsEN, tipsRU, tipsEN]
        let joinedLists: [String] = lists.map { $0.joined(separator: " ") }
        return (parts + joinedLists).joined(separator: " ")
    }
}

/// Раздел справочника.
struct HelpSection: Identifiable {
    let id: String
    let symbol: String
    let tint: Color
    let titleRU: String
    let titleEN: String
    let articles: [HelpArticle]

    func title(_ english: Bool) -> String { english ? titleEN : titleRU }
}

/// Вопрос и ответ.
struct HelpFAQ: Identifiable {
    let questionRU: String
    let questionEN: String
    let answerRU: String
    let answerEN: String
    let screenshot: String?
    let demo: HelpDemo?

    init(questionRU: String, questionEN: String, answerRU: String, answerEN: String,
         screenshot: String? = nil, demo: HelpDemo? = nil) {
        self.questionRU = questionRU
        self.questionEN = questionEN
        self.answerRU = answerRU
        self.answerEN = answerEN
        self.screenshot = screenshot
        self.demo = demo
    }

    var id: String { questionRU }
    func question(_ english: Bool) -> String { english ? questionEN : questionRU }
    func answer(_ english: Bool) -> String { english ? answerEN : answerRU }
    var searchableText: String { [questionRU, questionEN, answerRU, answerEN].joined(separator: " ") }
}

/// Вопрос FAQ вместе с его номером в общем списке (для идентификаторов help.faq.<index>).
struct HelpIndexedFAQ: Identifiable {
    let index: Int
    let item: HelpFAQ
    var id: Int { index }
}

/// Короткий практический совет.
struct HelpLifehack: Identifiable {
    let id: String
    let symbol: String
    let tint: Color
    let titleRU: String
    let titleEN: String
    let textRU: String
    let textEN: String

    func title(_ english: Bool) -> String { english ? titleEN : titleRU }
    func text(_ english: Bool) -> String { english ? textEN : textRU }
    var searchableText: String { [titleRU, titleEN, textRU, textEN].joined(separator: " ") }
}

/// Кнопка-фильтр в горизонтальной ленте категорий.
struct HelpChip: Identifiable {
    let id: String
    let symbol: String
    let titleRU: String
    let titleEN: String

    func title(_ english: Bool) -> String { english ? titleEN : titleRU }
}

// MARK: - Библиотека и поиск

enum HelpLibrary {
    static let allCategory = "all"
    static let lifehacksCategory = "lifehacks"
    static let faqCategory = "faq"

    static let sections: [HelpSection] = [
        startSection, chatSection, questionsSection, filesSection,
        editorSection, webSection, integrationsSection, tablesSection
    ] + [
        memorySection, chatsSection, voiceSection, gamesSection,
        backgroundSection, privacySection, parentalSection, appearanceSection
    ]

    static let faq: [HelpFAQ] = faqBasics + faqChats + faqFiles + faqMore

    static let lifehacks: [HelpLifehack] = lifehacksPartOne + lifehacksPartTwo

    static let allArticles: [HelpArticle] = sections.flatMap { $0.articles }

    private static let searchIndex: [String: String] = {
        var index: [String: String] = [:]
        for article in HelpLibrary.allArticles {
            index[article.id] = HelpLibrary.normalize(article.searchableText)
        }
        return index
    }()

    static func article(id: String) -> HelpArticle? {
        allArticles.first { $0.id == id }
    }

    static func section(containing articleID: String) -> HelpSection? {
        sections.first { section in section.articles.contains { $0.id == articleID } }
    }

    static var chips: [HelpChip] {
        var result: [HelpChip] = [HelpChip(id: allCategory, symbol: "square.grid.2x2", titleRU: "Все", titleEN: "All")]
        for section in sections {
            result.append(HelpChip(id: section.id, symbol: section.symbol, titleRU: section.titleRU, titleEN: section.titleEN))
        }
        result.append(HelpChip(id: lifehacksCategory, symbol: "lightbulb", titleRU: "Лайфхаки", titleEN: "Tips & tricks"))
        result.append(HelpChip(id: faqCategory, symbol: "questionmark.circle", titleRU: "Вопросы", titleEN: "FAQ"))
        return result
    }

    /// Приводит текст к виду для поиска: без регистра, «ё» = «е», без диакритики.
    static func normalize(_ text: String) -> String {
        let folded = text.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
        return folded.lowercased().replacingOccurrences(of: "ё", with: "е")
    }

    static func tokens(_ query: String) -> [String] {
        let separators = CharacterSet.whitespacesAndNewlines.union(.punctuationCharacters)
        return normalize(query)
            .components(separatedBy: separators)
            .filter { !$0.isEmpty }
    }

    /// Статьи, в которых встречаются все слова запроса (на любом из двух языков).
    /// Сначала идут статьи, где слово есть в заголовке.
    static func search(_ query: String) -> [HelpArticle] {
        let words = tokens(query)
        if words.isEmpty { return [] }
        var titleHits: [HelpArticle] = []
        var otherHits: [HelpArticle] = []
        for article in allArticles {
            let haystack = searchIndex[article.id] ?? normalize(article.searchableText)
            let matches = words.allSatisfy { haystack.contains($0) }
            if !matches { continue }
            let titles = normalize(article.titleRU + " " + article.titleEN)
            if words.contains(where: { titles.contains($0) }) {
                titleHits.append(article)
            } else {
                otherHits.append(article)
            }
        }
        return titleHits + otherHits
    }

    static func searchFAQ(_ query: String) -> [HelpFAQ] {
        faqEntries(matching: query).map { $0.item }
    }

    /// Вопросы FAQ с исходными номерами. Пустой запрос — все вопросы.
    static func faqEntries(matching query: String) -> [HelpIndexedFAQ] {
        let words = tokens(query)
        var result: [HelpIndexedFAQ] = []
        for (index, item) in faq.enumerated() {
            if words.isEmpty {
                result.append(HelpIndexedFAQ(index: index, item: item))
                continue
            }
            let haystack = normalize(item.searchableText)
            if words.allSatisfy({ haystack.contains($0) }) {
                result.append(HelpIndexedFAQ(index: index, item: item))
            }
        }
        return result
    }

    static func searchLifehacks(_ query: String) -> [HelpLifehack] {
        let words = tokens(query)
        if words.isEmpty { return lifehacks }
        return lifehacks.filter { hack in
            let haystack = normalize(hack.searchableText)
            return words.allSatisfy { haystack.contains($0) }
        }
    }
}

// MARK: - Содержание: начало работы

extension HelpLibrary {
    static var startSection: HelpSection {
        let items: [HelpArticle] = [articleFirstLaunch, articleChatScreen, articleComposer, articleThinking, articleSearchButton]
        return HelpSection(id: "start", symbol: "sparkles", tint: .blue,
                           titleRU: "Начало работы", titleEN: "Getting started", articles: items)
    }

    fileprivate static var articleFirstLaunch: HelpArticle {
        HelpArticle(
            id: "first-launch", symbol: "person.crop.circle.badge.plus", tint: .blue,
            titleRU: "Первый запуск", titleEN: "First launch",
            summaryRU: "Имя, дата рождения и язык: что спрашивает Honer AI при знакомстве и зачем.",
            summaryEN: "Name, birthday and language: what Honer AI asks when you first meet, and why.",
            bodyRU: [
                "При первом запуске Honer AI предлагает познакомиться. Введите имя, ник или позывной — так нейросеть будет к вам обращаться. Имя появится в профиле, его можно поменять в любой момент в **Настройки → Настройки аккаунта**.",
                "Дата рождения необязательна. Если указать её, Honer AI будет знать ваш возраст и сможет, например, подбирать примеры и объяснения по возрасту или поздравить с днём рождения. Если не хотите — просто пропустите этот шаг.",
                "Там же выбирается язык приложения: **Русский** (по умолчанию) или **English**. Нейросеть отвечает на выбранном языке приложения. Если написать ей на другом языке или прямо попросить — она ответит так, как вы просите.",
                "Если вы уже пользовались приложением и сохраняли резервную копию, на первом экране есть кнопка **«Восстановить из резервной копии»** — чаты, вложения и память вернутся."
            ],
            bodyEN: [
                "On first launch Honer AI asks to get acquainted. Enter your name, nickname or callsign — this is how the assistant will address you. The name shows up in your profile and can be changed any time in **Settings → Account settings**.",
                "Your birthday is optional. If you add it, Honer AI knows your age and can, for example, tailor examples and explanations or wish you a happy birthday. If you'd rather not, just skip it.",
                "The same screen lets you pick the app language: **Русский** (the default) or **English**. The assistant replies in the app language. If you write in another language or explicitly ask for one, it will answer the way you ask.",
                "If you used the app before and saved a backup, the first screen has a **Restore from backup** button — your chats, attachments and memory come back."
            ],
            stepsRU: [
                "Откройте Honer AI — появится экран «Давайте познакомимся».",
                "Выберите язык: Русский или English.",
                "Введите имя (до 60 символов) и при желании включите дату рождения.",
                "Нажмите **«Начать общение»** — откроется новый чат."
            ],
            stepsEN: [
                "Open Honer AI — the “Let's get acquainted” screen appears.",
                "Choose a language: Русский or English.",
                "Enter your name (up to 60 characters) and optionally turn on your birthday.",
                "Tap **Start chatting** — a new chat opens."
            ],
            tipsRU: [
                "Язык можно сменить позже: **Настройки → Язык**. Интерфейс и язык ответов переключатся сразу.",
                "Имя можно писать как угодно — «Капитан», «Аня», «Док». Нейросеть будет использовать именно его."
            ],
            tipsEN: [
                "You can switch the language later in **Settings → Language**. Both the interface and the reply language change instantly.",
                "Any name works — “Captain”, “Anna”, “Doc”. The assistant will use exactly that."
            ],
            screenshot: "guide-onboarding",
            related: ["language", "chat-screen", "what-ai-knows"]
        )
    }

    fileprivate static var articleChatScreen: HelpArticle {
        HelpArticle(
            id: "chat-screen", symbol: "bubble.left.and.bubble.right", tint: .indigo,
            titleRU: "Экран чата", titleEN: "The chat screen",
            summaryRU: "Где история чатов, как включить чтение вслух, начать новый чат и открыть меню.",
            summaryEN: "Where chat history lives, how to turn on read-aloud, start a new chat and open the menu.",
            bodyRU: [
                "Вверху слева — кнопка **☰**. Она открывает боковую панель: там история всех чатов, поиск по содержимому, закреплённые чаты и ваш профиль с настройками. Панель также открывается свайпом от левого края.",
                "Справа вверху — две круглые кнопки. **Динамик** включает автоматическое чтение ответов вслух: пока он включён, Honer AI начинает читать ответ сразу, как только тот начинает появляться. Перечёркнутый динамик — озвучка выключена. Кнопка **с плюсом в облачке** создаёт новый чат.",
                "Кнопка **•••** — меню текущего чата: переименовать, закрепить, найти в чате, инструкции чата, информация о чате (все фото, видео, файлы и ссылки), архив и удаление.",
                "Под каждым ответом есть панель действий: реакция, копировать, нравится/не нравится, читать вслух, поделиться и повторить. Внизу — поле ввода сообщения."
            ],
            bodyEN: [
                "Top left is the **☰** button. It opens the sidebar with your whole chat history, content search, pinned chats and your profile with settings. You can also open it by swiping from the left edge.",
                "Top right are two round buttons. The **speaker** turns on automatic read-aloud: while it's on, Honer AI starts reading an answer as soon as it begins to appear. A crossed-out speaker means read-aloud is off. The **bubble with a plus** starts a new chat.",
                "The **•••** button is the menu of the current chat: rename, pin, find in chat, chat instructions, chat info (all photos, videos, files and links), archive and delete.",
                "Under every answer there's an action bar: reaction, copy, like/dislike, read aloud, share and regenerate. The message field is at the bottom."
            ],
            stepsRU: [
                "Нажмите **☰**, чтобы увидеть все чаты.",
                "Нажмите на динамик, чтобы ответы читались вслух автоматически.",
                "Нажмите на облачко с плюсом, чтобы начать новый разговор.",
                "Нажмите **•••**, чтобы управлять текущим чатом."
            ],
            stepsEN: [
                "Tap **☰** to see all chats.",
                "Tap the speaker so answers are read aloud automatically.",
                "Tap the bubble with a plus to start a new conversation.",
                "Tap **•••** to manage the current chat."
            ],
            tipsRU: [
                "Надпись «Сгенерированный ИИ ответ, только для справки» напоминает: важные факты (медицина, деньги, право) стоит перепроверять.",
                "Полоски у правого края экрана — навигация по сообщениям. Подробнее в статье «Линии навигации»."
            ],
            tipsEN: [
                "The “AI-generated answer, for reference only” note is a reminder: double-check important facts (health, money, law).",
                "The short lines at the right edge are message navigation. See “Navigation lines”."
            ],
            screenshot: "guide-conversation",
            related: ["composer", "history", "navigation-lines", "chat-info"]
        )
    }

    fileprivate static var articleComposer: HelpArticle {
        HelpArticle(
            id: "composer", symbol: "square.and.pencil", tint: .teal,
            titleRU: "Поле ввода и кнопки", titleEN: "Message field and buttons",
            summaryRU: "Текст, вложения через «+», голосовой ввод, кнопки «Рассуждение» и «Поиск».",
            summaryEN: "Text, attachments via “+”, voice input, the Reasoning and Search buttons.",
            bodyRU: [
                "Напишите сообщение в поле **«Напишите сообщение…»** и отправьте. Поле растёт вместе с текстом, черновик сохраняется, даже если вы переключитесь на другой чат.",
                "Кнопка **+** открывает вложения: недавние фото и видео, **Камера**, **Альбом** и **Файл**. Можно прикрепить сразу несколько файлов — они появятся над полем ввода миниатюрами.",
                "Кнопка **микрофона** включает голосовой ввод: нажмите один раз и говорите — текст распознаётся на лету. Нажмите ещё раз (кнопка станет квадратом ■), чтобы отправить. **«Отмена»** — выйти без отправки.",
                "Кнопки **«Рассуждение»** и **«Поиск»** под полем включают режим размышления и доступ в интернет. Включённая кнопка подсвечивается синим. Смайлик справа открывает стикеры и эмодзи."
            ],
            bodyEN: [
                "Type into the **Message** field and send. The field grows with your text and the draft is kept even if you switch to another chat.",
                "The **+** button opens attachments: recent photos and videos, **Camera**, **Album** and **File**. You can attach several files at once — they appear above the field as thumbnails.",
                "The **microphone** button starts voice input: tap once and speak — the text is recognised as you talk. Tap again (the button turns into a square ■) to send. **Cancel** leaves without sending.",
                "The **Reasoning** and **Search** buttons below the field turn on thinking mode and internet access. An active button is highlighted in blue. The smiley on the right opens stickers and emoji."
            ],
            stepsRU: [
                "Нажмите на поле и введите вопрос.",
                "При необходимости нажмите **+** и выберите фото или файл.",
                "Включите **«Рассуждение»** для сложной задачи или **«Поиск»** для свежих данных.",
                "Нажмите кнопку отправки."
            ],
            stepsEN: [
                "Tap the field and type your question.",
                "If needed, tap **+** and pick a photo or a file.",
                "Turn on **Reasoning** for a hard task or **Search** for fresh data.",
                "Tap send."
            ],
            tipsRU: [
                "Видео прикрепляются длиной до 2 минут и размером до 40 МБ.",
                "Можно отправить только вложение без текста — Honer AI сам поймёт, что с ним сделать, или спросит."
            ],
            tipsEN: [
                "Videos can be attached up to 2 minutes long and 40 MB in size.",
                "You can send just an attachment with no text — Honer AI will work out what to do with it or ask."
            ],
            screenshot: "guide-welcome",
            related: ["attachments", "voice-input", "thinking-mode", "search-button"]
        )
    }

    fileprivate static var articleThinking: HelpArticle {
        HelpArticle(
            id: "thinking-mode", symbol: "atom", tint: .purple,
            titleRU: "Режим «Рассуждение»", titleEN: "Reasoning mode",
            summaryRU: "Нейросеть сначала думает, а вы видите ход её мыслей в реальном времени.",
            summaryEN: "The assistant thinks first, and you watch its reasoning live.",
            bodyRU: [
                "Когда кнопка **«Рассуждение»** включена, Honer AI перед ответом обдумывает задачу: разбивает её на части, проверяет варианты, ищет ошибки. Это заметно улучшает ответы на задачи по математике, логике, программированию и на сложные вопросы.",
                "Рассуждение показывается вживую над ответом с заголовком **«Размышляю…»** и таймером. Когда ответ готов, заголовок превращается в **«Размышлял N секунд»**, а само рассуждение сворачивается. Нажмите на заголовок, чтобы развернуть или свернуть его снова.",
                "Даже очень длинное рассуждение выводится плавно и не подвешивает приложение. Если рассуждение было на английском, приложение показывает перевод на язык интерфейса."
            ],
            bodyEN: [
                "When **Reasoning** is on, Honer AI thinks the task through before answering: it breaks it down, checks options and looks for mistakes. This noticeably improves answers for maths, logic, programming and tricky questions.",
                "The reasoning appears live above the answer with a **Thinking…** header and a timer. When the answer is ready, the header becomes **Thought for N seconds** and the reasoning collapses. Tap the header to expand or collapse it again.",
                "Even very long reasoning streams smoothly without freezing the app. If the reasoning was written in another language, the app shows a translation into the interface language."
            ],
            stepsRU: [
                "Нажмите **«Рассуждение»** под полем ввода — кнопка станет синей.",
                "Задайте вопрос и наблюдайте за рассуждением.",
                "Нажмите на **«Размышлял…»**, чтобы свернуть или развернуть ход мыслей."
            ],
            stepsEN: [
                "Tap **Reasoning** below the message field — it turns blue.",
                "Ask your question and watch the reasoning.",
                "Tap **Thought for…** to collapse or expand it."
            ],
            tipsRU: [
                "Для простых вопросов («переведи слово», «сколько времени в Токио») рассуждение можно выключить — ответ придёт быстрее.",
                "Режим запоминается: если он включён, он останется включённым и в следующих сообщениях."
            ],
            tipsEN: [
                "For simple questions (“translate a word”, “what time is it in Tokyo”) turn reasoning off — the answer arrives faster.",
                "The mode is remembered: once on, it stays on for the next messages."
            ],
            screenshot: "guide-thinking", demo: .typing,
            related: ["live-answers", "search-button", "composer"]
        )
    }

    fileprivate static var articleSearchButton: HelpArticle {
        HelpArticle(
            id: "search-button", symbol: "globe", tint: .cyan,
            titleRU: "Кнопка «Поиск»", titleEN: "The Search button",
            summaryRU: "Разрешает нейросети выходить в интернет, когда это действительно нужно.",
            summaryEN: "Lets the assistant go online when it's actually needed.",
            bodyRU: [
                "Кнопка **«Поиск»** даёт Honer AI **возможность** искать в интернете — но не обязанность. Нейросеть сама решает, нужен ли поиск: для вопроса «сколько будет 2+2» она ответит сразу, а для «какая погода завтра» или «что нового у Apple» пойдёт в сеть.",
                "Когда поиск идёт, над ответом видна живая лента: какие сайты открываются и читаются. После ответа появляется карточка **«Источники ответа»** со списком прочитанных страниц и цитатами.",
                "Если поиск выключен, нейросеть отвечает по своим знаниям и не открывает сайты. Сведения о событиях после даты обучения модели в этом случае могут быть неполными."
            ],
            bodyEN: [
                "The **Search** button gives Honer AI the **ability** to search the web — not an obligation. The assistant decides whether it's needed: for “what's 2+2” it answers right away, for “what's the weather tomorrow” or “what's new at Apple” it goes online.",
                "While searching, a live feed above the answer shows which sites are being opened and read. After the answer you get an **Answer sources** card with the pages it read and quotes.",
                "With Search off, the assistant answers from its own knowledge and opens no sites. Information about events after the model's training date may then be incomplete."
            ],
            stepsRU: [
                "Нажмите **«Поиск»** — кнопка станет синей.",
                "Спросите о чём-то свежем: новости, цены, погода, расписание.",
                "Нажмите на карточку источников, чтобы открыть страницы и цитаты."
            ],
            stepsEN: [
                "Tap **Search** — it turns blue.",
                "Ask about something current: news, prices, weather, timetables.",
                "Tap the sources card to open the pages and quotes."
            ],
            tipsRU: [
                "Хотите, чтобы поиск был точно? Напишите прямо: «найди в интернете…» или «проверь в сети».",
                "Для большого исследования попросите: «прочитай 300 сайтов и сделай сводку» — см. статью «Глубокое исследование»."
            ],
            tipsEN: [
                "Want to be sure it searches? Say it directly: “search the web for…” or “check online”.",
                "For big research ask: “read 300 sites and summarise” — see “Deep research”."
            ],
            screenshot: "guide-search",
            related: ["web-search", "deep-research", "sources"]
        )
    }
}

// MARK: - Содержание: общение и ответы

extension HelpLibrary {
    static var chatSection: HelpSection {
        let items: [HelpArticle] = [articleLiveAnswers, articleFormatting, articleReactions, articleMessageMenu, articleSelectAsk, articleReadAloud]
        return HelpSection(id: "chat", symbol: "text.bubble", tint: .indigo,
                           titleRU: "Общение и ответы", titleEN: "Conversation and answers", articles: items)
    }

    fileprivate static var articleLiveAnswers: HelpArticle {
        HelpArticle(
            id: "live-answers", symbol: "text.bubble", tint: .blue,
            titleRU: "Живые ответы", titleEN: "Live answers",
            summaryRU: "Ответ печатается плавно, даже если он очень длинный.",
            summaryEN: "Answers type out smoothly, even very long ones.",
            bodyRU: [
                "Honer AI выводит ответ по мере того, как он пишется, — плавно, как будто человек печатает. Не нужно ждать конца: можно читать с первых слов.",
                "Очень длинные ответы (статьи, код на сотни строк, большие таблицы) и длинные рассуждения выводятся без подвисаний: приложение дозирует текст и рисует его кусками.",
                "Ответ можно остановить кнопкой ■ во время генерации. Если ответ оборвался из-за связи, нажмите **«Повторить»** под ним."
            ],
            bodyEN: [
                "Honer AI shows the answer as it's being written — smoothly, like a person typing. No need to wait for the end: start reading from the first words.",
                "Very long answers (articles, hundreds of lines of code, large tables) and long reasoning stream without freezing: the app paces the text and renders it in chunks.",
                "You can stop an answer with the ■ button while it's being generated. If an answer broke off because of the connection, tap **Regenerate** under it."
            ],
            stepsRU: [
                "Отправьте вопрос.",
                "Читайте ответ, пока он печатается.",
                "Чтобы остановить генерацию, нажмите ■."
            ],
            stepsEN: [
                "Send a question.",
                "Read the answer while it types.",
                "To stop generating, tap ■."
            ],
            tipsRU: [
                "Если свернуть приложение, ответ допишется в фоне и придёт уведомление."
            ],
            tipsEN: [
                "If you leave the app, the answer finishes in the background and you get a notification."
            ],
            demo: .typing,
            related: ["thinking-mode", "background-answers", "formatting"]
        )
    }

    fileprivate static var articleFormatting: HelpArticle {
        HelpArticle(
            id: "formatting", symbol: "textformat", tint: .orange,
            titleRU: "Оформление ответов", titleEN: "Rich answers",
            summaryRU: "Заголовки, списки, таблицы, формулы, код с копированием, цветной текст, карточки и диаграммы.",
            summaryEN: "Headings, lists, tables, formulas, copyable code, coloured text, cards and diagrams.",
            bodyRU: [
                "Ответы оформлены как в хорошем документе: **заголовки**, *курсив*, списки, цитаты, ссылки. Таблицы рисуются настоящими таблицами, а математические формулы — формулами, а не набором символов.",
                "Код показывается в отдельных блоках с подсветкой синтаксиса и кнопкой **«Копировать»** — один тап, и весь блок в буфере обмена.",
                "Нейросеть умеет выделять важное **цветным текстом**, собирать информацию в **карточки** (например, карточка товара, фильма или места) и рисовать **диаграммы Mermaid**: схемы процессов, деревья, диаграммы последовательностей.",
                "Если оформление не нужно, так и скажите: «ответь простым текстом без таблиц»."
            ],
            bodyEN: [
                "Answers look like a well-made document: **headings**, *italics*, lists, quotes, links. Tables are real tables, and maths formulas are rendered as formulas, not as a jumble of symbols.",
                "Code appears in separate blocks with syntax highlighting and a **Copy** button — one tap and the whole block is on your clipboard.",
                "The assistant can highlight key points with **coloured text**, pack information into **cards** (a product, a film or a place, for example) and draw **Mermaid diagrams**: flowcharts, trees, sequence diagrams.",
                "If you don't want formatting, just say so: “answer in plain text, no tables”."
            ],
            stepsRU: [
                "Попросите: «сделай таблицу сравнения», «нарисуй схему процесса», «покажи формулу».",
                "Чтобы скопировать код, нажмите **«Копировать»** в углу блока кода."
            ],
            stepsEN: [
                "Ask: “make a comparison table”, “draw a process diagram”, “show the formula”.",
                "To copy code, tap **Copy** in the corner of the code block."
            ],
            tipsRU: [
                "Для схемы скажите: «нарисуй диаграмму mermaid» — получится наглядная картинка прямо в чате.",
                "Попросите «выдели главное цветом» — важные слова станут заметнее."
            ],
            tipsEN: [
                "For a diagram say “draw a mermaid diagram” — you get a clear picture right in the chat.",
                "Ask to “highlight the key points in colour” to make important words stand out."
            ],
            related: ["tables", "live-answers", "message-menu"]
        )
    }

    fileprivate static var articleReactions: HelpArticle {
        HelpArticle(
            id: "reactions", symbol: "face.smiling", tint: .yellow,
            titleRU: "Реакции", titleEN: "Reactions",
            summaryRU: "Ставьте эмодзи на ответы — нейросеть их видит и тоже может реагировать.",
            summaryEN: "Put emoji on answers — the assistant sees them and can react too.",
            bodyRU: [
                "Под ответом есть кнопка со смайликом. Нажмите её или **долго нажмите на ответ** и выберите реакцию: 👍, ❤️, 😂, 😮, 🔥 и другие.",
                "Honer AI **видит ваши реакции** на свои ответы и учитывает их: например, после 👎 постарается объяснить иначе, а 😂 поймёт как «смешно получилось».",
                "Нейросеть тоже может поставить реакцию эмодзи на **ваше** сообщение — когда это уместно, например на хорошую новость."
            ],
            bodyEN: [
                "Under an answer there's a smiley button. Tap it, or **long-press the answer** and choose a reaction: 👍, ❤️, 😂, 😮, 🔥 and more.",
                "Honer AI **sees your reactions** to its answers and takes them into account: after 👎 it will try a different explanation, and 😂 reads as “that was funny”.",
                "The assistant can also react with an emoji to **your** message when it fits — to good news, for example."
            ],
            stepsRU: [
                "Долго нажмите на ответ Honer AI.",
                "Выберите эмодзи в строке реакций.",
                "Чтобы убрать реакцию, нажмите на неё ещё раз."
            ],
            stepsEN: [
                "Long-press an answer from Honer AI.",
                "Pick an emoji in the reactions row.",
                "To remove a reaction, tap it again."
            ],
            tipsRU: [
                "Кнопки 👍/👎 тоже работают как сигнал: нейросеть поймёт, что понравилось, а что нет."
            ],
            tipsEN: [
                "The 👍/👎 buttons work as a signal too: the assistant learns what you liked and what you didn't."
            ],
            screenshot: "guide-assistant-menu",
            related: ["message-menu", "select-ask"]
        )
    }

    fileprivate static var articleMessageMenu: HelpArticle {
        HelpArticle(
            id: "message-menu", symbol: "ellipsis.bubble", tint: .indigo,
            titleRU: "Меню сообщения", titleEN: "Message menu",
            summaryRU: "Копировать, выделить, цитировать, редактировать, повторить, закрепить, ветка, поделиться.",
            summaryEN: "Copy, select, quote, edit, regenerate, pin, branch, share.",
            bodyRU: [
                "Долгое нажатие на любое сообщение открывает меню. Для **вашего сообщения**: Копировать, Закрепить как инструкцию, Выбрать текст, **Редактировать** (исправить и отправить заново), Продолжить в ветке, Запомнить, Поделиться.",
                "Для **ответа Honer AI**: Копировать, Закрепить как инструкцию, Выбрать текст, **Повторить** (сгенерировать ответ заново), Продолжить в ветке, Запомнить, Нравится / Не нравится, **Читать вслух**, Поделиться.",
                "**Цитата**: выберите «Цитировать» в меню сообщения или «Выбрать текст и спросить», выделите фрагмент и нажмите «Спросить Honer AI» — цитата появится над полем ввода, и нейросеть поймёт, о каком именно фрагменте вы спрашиваете. «Подробнее об этом» и «Объяснить проще» отправляют вопрос сразу.",
                "**Запомнить** сохраняет факт из сообщения в долговременную память, а **Закрепить как инструкцию** делает сообщение правилом для всех ответов этого чата."
            ],
            bodyEN: [
                "Long-press any message to open its menu. For **your message**: Copy, Pin as instruction, Select text, **Edit** (fix and resend), Continue in a branch, Remember, Share.",
                "For **an answer from Honer AI**: Copy, Pin as instruction, Select text, **Regenerate**, Continue in a branch, Remember, Like / Dislike, **Read aloud**, Share.",
                "**Quote**: choose Quote in the message menu, or Select text and ask, highlight a fragment and tap Ask Honer AI — the quote appears above the message field and the assistant knows exactly which fragment you mean. Tell me more and Explain simpler send the question right away.",
                "**Remember** saves a fact from the message into long-term memory, and **Pin as instruction** turns the message into a rule for every answer in this chat."
            ],
            stepsRU: [
                "Долго нажмите на сообщение.",
                "Выберите действие в меню.",
                "Для редактирования исправьте текст и нажмите отправить — ответ сгенерируется заново."
            ],
            stepsEN: [
                "Long-press a message.",
                "Choose an action from the menu.",
                "To edit, fix the text and tap send — the answer is generated again."
            ],
            tipsRU: [
                "**«Выбрать текст»** позволяет выделить и скопировать любой кусочек ответа, а не весь ответ целиком.",
                "Предыдущий вариант ответа после **«Повторить»** не теряется, если вы продолжаете в ветке."
            ],
            tipsEN: [
                "**Select text** lets you highlight and copy any part of an answer instead of the whole thing.",
                "The previous answer isn't lost after **Regenerate** if you continue in a branch."
            ],
            screenshot: "guide-message-menu",
            related: ["select-ask", "pinned-instructions", "branches", "memory"]
        )
    }

    fileprivate static var articleSelectAsk: HelpArticle {
        HelpArticle(
            id: "select-ask", symbol: "text.cursor", tint: .pink,
            titleRU: "«Спросить Honer AI» о фрагменте", titleEN: "“Ask Honer AI” about a fragment",
            summaryRU: "Выделите часть ответа и задайте вопрос именно о ней.",
            summaryEN: "Select part of an answer and ask about exactly that part.",
            bodyRU: [
                "Иногда непонятно одно слово или одна фраза в длинном ответе. Выделите её — в меню выделения появится пункт **«Спросить Honer AI»**.",
                "Выделенный фрагмент попадёт в поле ввода как цитата. Допишите вопрос — «объясни проще», «приведи пример», «а почему так?» — и отправьте. Нейросеть ответит именно про этот кусочек.",
                "В том же меню есть **«Копировать»**, если нужно просто взять часть текста."
            ],
            bodyEN: [
                "Sometimes one word or phrase in a long answer is unclear. Select it — the selection menu gets an **Ask Honer AI** item.",
                "The selected fragment goes into the message field as a quote. Add your question — “explain it more simply”, “give an example”, “why is that?” — and send. The assistant answers about that exact piece.",
                "The same menu has **Copy** if you just need part of the text."
            ],
            stepsRU: [
                "Долго нажмите на ответ и выберите **«Выбрать текст»**.",
                "Выделите нужный фрагмент, двигая маркеры.",
                "Нажмите **«Спросить Honer AI»**.",
                "Допишите вопрос и отправьте."
            ],
            stepsEN: [
                "Long-press the answer and choose **Select text**.",
                "Drag the handles to select the fragment.",
                "Tap **Ask Honer AI**.",
                "Add your question and send."
            ],
            tipsRU: [
                "Так удобно разбирать сложные тексты: выделяйте термин за термином и спрашивайте по очереди."
            ],
            tipsEN: [
                "Great for complex texts: select one term at a time and ask about each."
            ],
            demo: .selectAsk,
            related: ["message-menu", "reactions"]
        )
    }

    fileprivate static var articleReadAloud: HelpArticle {
        HelpArticle(
            id: "read-aloud", symbol: "speaker.wave.2", tint: .green,
            titleRU: "Чтение вслух", titleEN: "Read aloud",
            summaryRU: "Нейросеть читает ответы естественным голосом — даже очень длинные.",
            summaryEN: "The assistant reads answers in a natural voice — even very long ones.",
            bodyRU: [
                "Чтобы прослушать один ответ, нажмите кнопку динамика под ним или выберите **«Читать вслух»** в меню сообщения.",
                "Чтобы слушать все ответы автоматически, включите **динамик вверху экрана**. Тогда чтение начинается сразу, как только ответ начинает печататься, — не нужно ждать, пока он допишется.",
                "Длинные тексты читаются целиком: код, таблицы и служебные символы пропускаются или проговариваются понятно. Русский текст читает русский голос, английские слова — английский голос того же пола."
            ],
            bodyEN: [
                "To hear a single answer, tap the speaker button under it or choose **Read aloud** in the message menu.",
                "To hear every answer automatically, turn on the **speaker at the top of the screen**. Reading then starts as soon as the answer begins to type — no need to wait for it to finish.",
                "Long texts are read in full: code, tables and technical symbols are skipped or spoken sensibly. Russian text is read by a Russian voice and English words by an English voice of the same gender."
            ],
            stepsRU: [
                "Нажмите динамик вверху справа — он перестанет быть перечёркнутым.",
                "Задайте вопрос — ответ начнёт читаться сразу.",
                "Чтобы остановить, нажмите динамик ещё раз."
            ],
            stepsEN: [
                "Tap the speaker at the top right — it's no longer crossed out.",
                "Ask something — the answer starts being read right away.",
                "To stop, tap the speaker again."
            ],
            tipsRU: [
                "Скорость чтения и голос (мужской по умолчанию или женский) меняются в **Настройки → Голос**."
            ],
            tipsEN: [
                "Reading speed and voice (male by default, or female) are in **Settings → Voice**."
            ],
            related: ["voices", "voice-input", "chat-screen"]
        )
    }
}

// MARK: - Содержание: вопросы и тесты

extension HelpLibrary {
    static var questionsSection: HelpSection {
        let items: [HelpArticle] = [articleClarifyingQuestions, articleQuizzes]
        return HelpSection(id: "questions", symbol: "questionmark.bubble", tint: .orange,
                           titleRU: "Вопросы и тесты", titleEN: "Questions and quizzes", articles: items)
    }

    fileprivate static var articleClarifyingQuestions: HelpArticle {
        HelpArticle(
            id: "clarifying-questions", symbol: "questionmark.bubble", tint: .orange,
            titleRU: "Уточняющие вопросы", titleEN: "Clarifying questions",
            summaryRU: "Карточки с вариантами А/Б/В, своим ответом и таймером на 10 секунд.",
            summaryEN: "Cards with A/B/C options, your own answer and a 10-second timer.",
            bodyRU: [
                "Если задача неоднозначна, Honer AI может сначала спросить вас — не текстом, а удобной **карточкой с вариантами** А, Б, В. Нажмите на подходящий вариант или напишите **свой ответ** в поле под ними.",
                "Вопросов может быть несколько — до **30** подряд (например, чтобы составить план тренировок или подобрать подарок). Вверху карточки видно, какой это вопрос по счёту.",
                "На каждый вопрос даётся **10 секунд** — это видно по кольцу-таймеру. Если не ответить, вопрос закроется и нейросеть **решит сама**, выбрав самый разумный вариант, и продолжит работу. Так ответ не зависнет, если вы отвлеклись."
            ],
            bodyEN: [
                "If a task is ambiguous, Honer AI may ask you first — not in plain text but with a handy **card of options** A, B, C. Tap the right option or type **your own answer** in the field below.",
                "There can be several questions — up to **30** in a row (to build a workout plan or choose a gift, for example). The card shows which question you're on.",
                "Each question has **10 seconds** — shown by the countdown ring. If you don't answer, the question closes and the assistant **decides by itself**, picking the most sensible option, and carries on. So the answer never gets stuck if you're distracted."
            ],
            stepsRU: [
                "Когда появилась карточка, прочитайте вопрос.",
                "Нажмите вариант А, Б или В — или впишите свой ответ.",
                "Не успели? Ничего страшного: нейросеть выберет сама."
            ],
            stepsEN: [
                "When a card appears, read the question.",
                "Tap option A, B or C — or type your own.",
                "Missed it? No problem: the assistant chooses by itself."
            ],
            tipsRU: [
                "Попросите «задай мне вопросы, чтобы понять, что мне нужно» — нейросеть проведёт короткое интервью.",
                "Не хотите вопросов? Напишите «не задавай уточнений, решай сам»."
            ],
            tipsEN: [
                "Ask “ask me questions to figure out what I need” — the assistant runs a short interview.",
                "Don't want questions? Write “don't ask, just decide yourself”."
            ],
            demo: .questionTimer,
            related: ["quizzes", "message-menu"]
        )
    }

    fileprivate static var articleQuizzes: HelpArticle {
        HelpArticle(
            id: "quizzes", symbol: "checklist", tint: .green,
            titleRU: "Тесты и викторины", titleEN: "Tests and quizzes",
            summaryRU: "Викторины, IQ-тесты и контрольные с картинками, звуком и мгновенной проверкой.",
            summaryEN: "Quizzes, IQ tests and practice tests with pictures, audio and instant scoring.",
            bodyRU: [
                "Попросите Honer AI устроить тест: «проверь мои знания по истории», «IQ-тест на 15 вопросов», «викторина по фильмам Marvel». Вопросы приходят теми же карточками с вариантами.",
                "Внутри вопроса могут быть **картинки** (например, задачи на логику с фигурами), **аудио** (угадай мелодию или произношение) или **файлы**.",
                "Проверка — **мгновенная**: правильный ответ подсвечивается зелёным, неправильный — красным, а в конце показывается итог, например **2 из 3**. Затем нейросеть комментирует результат: объясняет ошибки или продолжает задание."
            ],
            bodyEN: [
                "Ask Honer AI for a test: “check my history knowledge”, “a 15-question IQ test”, “a Marvel movie quiz”. Questions arrive as the same option cards.",
                "A question can include **images** (logic puzzles with shapes, for example), **audio** (guess the tune or the pronunciation) or **files**.",
                "Scoring is **instant**: a correct answer lights up green, a wrong one red, and at the end you see the total, e.g. **2 of 3**. Then the assistant comments on the result: explains mistakes or continues the task."
            ],
            stepsRU: [
                "Напишите: «сделай тест из 10 вопросов по …».",
                "Отвечайте на карточки по одной.",
                "Посмотрите итог и попросите разобрать ошибки."
            ],
            stepsEN: [
                "Write: “make a 10-question test on …”.",
                "Answer the cards one by one.",
                "See your score and ask to go through the mistakes."
            ],
            tipsRU: [
                "Прикрепите конспект или учебник (PDF, Word) и попросите тест по нему — получится подготовка к экзамену.",
                "Скажите «сложнее» или «проще» — следующий тест подстроится."
            ],
            tipsEN: [
                "Attach your notes or a textbook (PDF, Word) and ask for a test on it — instant exam prep.",
                "Say “harder” or “easier” — the next test adapts."
            ],
            demo: .quiz,
            related: ["clarifying-questions", "documents"]
        )
    }
}

// MARK: - Содержание: файлы, фото, видео, голос

extension HelpLibrary {
    static var filesSection: HelpSection {
        let items: [HelpArticle] = [articleAttachments, articlePhotos, articleVideoAudio, articleDocuments]
        return HelpSection(id: "files", symbol: "paperclip", tint: .teal,
                           titleRU: "Файлы, фото, видео, голос", titleEN: "Files, photos, video, voice", articles: items)
    }

    fileprivate static var articleAttachments: HelpArticle {
        HelpArticle(
            id: "attachments", symbol: "paperclip", tint: .blue,
            titleRU: "Как прикрепить файл", titleEN: "Attaching files",
            summaryRU: "Камера, альбом, файлы — и как вложения выглядят в чате.",
            summaryEN: "Camera, album, files — and how attachments look in the chat.",
            bodyRU: [
                "Нажмите **+** слева от микрофона. Откроется панель: **недавние фото и видео**, **Камера** (снять прямо сейчас), **Альбом** (выбрать из «Фото») и **Файл** (из приложения «Файлы», iCloud Drive, почты и т. д.).",
                "Вложения появляются над полем ввода. Лишнее можно убрать крестиком до отправки. В чате фото и видео показываются миниатюрами, документы — карточками с названием и типом.",
                "Нажмите на вложение в чате, чтобы открыть **просмотр**: фото увеличиваются жестами, видео проигрываются, документы открываются в просмотрщике."
            ],
            bodyEN: [
                "Tap **+** to the left of the microphone. A panel opens: **recent photos and videos**, **Camera** (shoot now), **Album** (pick from Photos) and **File** (from the Files app, iCloud Drive, mail, etc.).",
                "Attachments appear above the message field. Remove extras with the cross before sending. In the chat, photos and videos show as thumbnails and documents as cards with name and type.",
                "Tap an attachment in the chat to open a **preview**: photos zoom with gestures, videos play, documents open in a viewer."
            ],
            stepsRU: [
                "Нажмите **+**.",
                "Выберите Камера, Альбом или Файл.",
                "Добавьте вопрос («что на фото?», «сделай выжимку») и отправьте."
            ],
            stepsEN: [
                "Tap **+**.",
                "Choose Camera, Album or File.",
                "Add a question (“what's in the photo?”, “summarise this”) and send."
            ],
            tipsRU: [
                "Чтобы видеть недавние фото прямо в панели, разрешите доступ к «Фото» (**Настройки → Разрешения**).",
                "Видео — до 2 минут и 40 МБ."
            ],
            tipsEN: [
                "To see recent photos right in the panel, allow Photos access (**Settings → Permissions**).",
                "Videos — up to 2 minutes and 40 MB."
            ],
            screenshot: "guide-attachments",
            related: ["photos-vision", "video-audio", "documents", "photo-editor"]
        )
    }

    fileprivate static var articlePhotos: HelpArticle {
        HelpArticle(
            id: "photos-vision", symbol: "photo", tint: .pink,
            titleRU: "Фото: нейросеть видит", titleEN: "Photos: the assistant sees",
            summaryRU: "Honer AI действительно понимает изображения: текст, предметы, графики, задачи.",
            summaryEN: "Honer AI truly understands images: text, objects, charts, problems.",
            bodyRU: [
                "Отправьте фото — и спросите что угодно: «что это за растение?», «переведи надпись», «реши задачу с доски», «что не так с этим графиком?», «сколько калорий в этой тарелке?».",
                "Нейросеть распознаёт текст на снимках (в том числе рукописный), предметы, людей в общем виде, скриншоты интерфейсов, чеки, таблицы и схемы.",
                "Можно отправить несколько фото сразу и попросить сравнить их."
            ],
            bodyEN: [
                "Send a photo and ask anything: “what plant is this?”, “translate the sign”, “solve the problem on the board”, “what's wrong with this chart?”, “how many calories are on this plate?”.",
                "The assistant recognises text in pictures (including handwriting), objects, people in general terms, app screenshots, receipts, tables and diagrams.",
                "You can send several photos at once and ask to compare them."
            ],
            stepsRU: [
                "Нажмите **+** → Камера или Альбом.",
                "Выберите фото и задайте вопрос.",
                "Нажмите отправить."
            ],
            stepsEN: [
                "Tap **+** → Camera or Album.",
                "Pick a photo and ask your question.",
                "Tap send."
            ],
            tipsRU: [
                "Чем чётче снимок, тем точнее ответ: снимайте документы ровно и при хорошем свете.",
                "Хотите изменить фото? Откройте его и нажмите **«Редактировать»** — или попросите нейросеть."
            ],
            tipsEN: [
                "The sharper the shot, the better the answer: photograph documents straight and in good light.",
                "Want to change the photo? Open it and tap **Edit** — or ask the assistant."
            ],
            related: ["attachments", "photo-editor", "ai-edit"]
        )
    }

    fileprivate static var articleVideoAudio: HelpArticle {
        HelpArticle(
            id: "video-audio", symbol: "film", tint: .purple,
            titleRU: "Видео, голосовые и аудио", titleEN: "Video, voice notes and audio",
            summaryRU: "Нейросеть смотрит ключевые кадры и слушает звук; аудио расшифровывается.",
            summaryEN: "The assistant watches key frames and listens to the sound; audio is transcribed.",
            bodyRU: [
                "Для **видео** Honer AI берёт ключевые кадры и смотрит их, а звуковую дорожку расшифровывает с помощью распознавания речи **прямо на iPhone**. Поэтому можно спросить: «о чём это видео?», «что сказал спикер на 1:20?», «что происходит в кадре?».",
                "**Голосовые сообщения и аудиофайлы** (m4a, mp3, wav и другие) расшифровываются в текст, и нейросеть отвечает по содержанию: делает конспект, переводит, выделяет задачи.",
                "Честно о пределах: видео анализируется по отдельным кадрам, а не каждое мгновение, поэтому очень быстрые детали могут быть пропущены. Музыку без слов нейросеть не «слышит» как мелодию — только речь."
            ],
            bodyEN: [
                "For **video**, Honer AI takes key frames and looks at them, and transcribes the audio track with speech recognition **right on the iPhone**. So you can ask “what's this video about?”, “what did the speaker say at 1:20?”, “what's happening in the shot?”.",
                "**Voice messages and audio files** (m4a, mp3, wav and more) are transcribed to text and the assistant works with the content: makes notes, translates, pulls out tasks.",
                "Honest limits: video is analysed frame by frame, not every instant, so very fast details can be missed. Music without words isn't “heard” as a melody — only speech is."
            ],
            stepsRU: [
                "Нажмите **+** и выберите видео или аудиофайл.",
                "Напишите, что нужно: конспект, перевод, ответ на вопрос.",
                "Отправьте и дождитесь расшифровки."
            ],
            stepsEN: [
                "Tap **+** and choose a video or an audio file.",
                "Say what you need: notes, translation, an answer.",
                "Send and wait for the transcription."
            ],
            tipsRU: [
                "Запись лекции на диктофон → «сделай конспект по пунктам» — экономит часы.",
                "Для лучшего распознавания укажите язык записи в **Настройки → Основной язык**."
            ],
            tipsEN: [
                "Lecture recording → “make bullet-point notes” — saves hours.",
                "For better recognition set the recording language in **Settings → Speech language**."
            ],
            related: ["attachments", "video-editor", "voice-input"]
        )
    }

    fileprivate static var articleDocuments: HelpArticle {
        HelpArticle(
            id: "documents", symbol: "doc.richtext", tint: .teal,
            titleRU: "Документы и таблицы-файлы", titleEN: "Documents and spreadsheets",
            summaryRU: "PDF, Word, Excel, CSV, PowerPoint, EPUB, код и многое другое.",
            summaryEN: "PDF, Word, Excel, CSV, PowerPoint, EPUB, code and much more.",
            bodyRU: [
                "Honer AI читает: **PDF**, **Word** (docx), **Excel** (xlsx) и **CSV** — как настоящие таблицы со столбцами и строками, **PowerPoint** (pptx), **OpenDocument** (odt, ods, odp), **RTF**, **HTML**, **EPUB**, блокноты **Jupyter** (ipynb) и **любые файлы с кодом** (swift, py, js, json, yaml и т. д.).",
                "Можно попросить выжимку, перевод, найти ошибки, ответить по тексту, сравнить два договора, посчитать итоги в таблице или построить по ней выводы.",
                "Прикреплённые документы показываются в чате карточками. Нажмите на карточку, чтобы открыть файл."
            ],
            bodyEN: [
                "Honer AI reads **PDF**, **Word** (docx), **Excel** (xlsx) and **CSV** — as real tables with columns and rows, **PowerPoint** (pptx), **OpenDocument** (odt, ods, odp), **RTF**, **HTML**, **EPUB**, **Jupyter** notebooks (ipynb) and **any code file** (swift, py, js, json, yaml and more).",
                "Ask for a summary, a translation, error checks, answers from the text, a comparison of two contracts, totals for a spreadsheet or conclusions from it.",
                "Attached documents show up in the chat as cards. Tap a card to open the file."
            ],
            stepsRU: [
                "Нажмите **+** → **Файл**.",
                "Выберите документ в «Файлах» или iCloud Drive.",
                "Напишите задачу и отправьте."
            ],
            stepsEN: [
                "Tap **+** → **File**.",
                "Pick a document in Files or iCloud Drive.",
                "Describe the task and send."
            ],
            tipsRU: [
                "Сканы без текстового слоя лучше отправлять как фото — нейросеть распознает текст на изображении.",
                "Для Excel с несколькими листами уточните, какой лист смотреть."
            ],
            tipsEN: [
                "Scans without a text layer work better as photos — the assistant reads the text from the image.",
                "For Excel files with several sheets, say which sheet to look at."
            ],
            related: ["attachments", "tables", "quizzes"]
        )
    }
}

// MARK: - Содержание: редактор фото и видео

extension HelpLibrary {
    static var editorSection: HelpSection {
        let items: [HelpArticle] = [articlePhotoEditor, articleVideoEditor, articleAIEdit]
        return HelpSection(id: "editor", symbol: "wand.and.stars", tint: .pink,
                           titleRU: "Редактор фото и видео", titleEN: "Photo and video editor", articles: items)
    }

    fileprivate static var articlePhotoEditor: HelpArticle {
        HelpArticle(
            id: "photo-editor", symbol: "wand.and.stars", tint: .pink,
            titleRU: "Редактор фото", titleEN: "Photo editor",
            summaryRU: "Фильтры, коррекция, обрезка, удаление и замена фона, текст, стикеры, рисование.",
            summaryEN: "Filters, adjustments, crop, background removal and replacement, text, stickers, drawing.",
            bodyRU: [
                "Откройте фото-вложение в чате и нажмите **«Редактировать»**. Внизу — инструменты: **Фильтры**, **Коррекция** (яркость, контраст, насыщенность, тепло, резкость), **Обрезка и поворот**, **Фон**, **Текст**, **Стикеры**, **Рисование**.",
                "**Удалить фон** — одно нажатие: объект вырезается прямо на iPhone, без отправки фото в интернет. Затем фон можно **заменить**: сплошной цвет, размытие исходного фона, градиент или другое фото.",
                "Любое действие можно **отменить**, а кнопкой **«Сравнить»** (удерживайте) — посмотреть оригинал. Готовое фото сохраняется в чат как новое вложение, его можно отправить нейросети или сохранить в «Фото»."
            ],
            bodyEN: [
                "Open a photo attachment in the chat and tap **Edit**. The tools are at the bottom: **Filters**, **Adjust** (brightness, contrast, saturation, warmth, sharpness), **Crop & rotate**, **Background**, **Text**, **Stickers**, **Draw**.",
                "**Remove background** takes one tap: the subject is cut out right on the iPhone, without sending the photo anywhere. Then you can **replace** the background: a solid colour, a blur of the original, a gradient or another photo.",
                "Every action can be **undone**, and holding **Compare** shows the original. The finished photo is saved to the chat as a new attachment — send it to the assistant or save it to Photos."
            ],
            stepsRU: [
                "Нажмите на фото в чате, чтобы открыть его.",
                "Нажмите **«Редактировать»**.",
                "Выберите **Фон → Удалить фон**, затем цвет, размытие или фото для нового фона.",
                "Добавьте фильтр, текст или стикер и нажмите **«Готово»**."
            ],
            stepsEN: [
                "Tap a photo in the chat to open it.",
                "Tap **Edit**.",
                "Choose **Background → Remove background**, then a colour, blur or photo for the new background.",
                "Add a filter, text or sticker and tap **Done**."
            ],
            tipsRU: [
                "Удаление фона лучше всего работает, когда объект чётко отделён от фона: человек, животное, предмет на столе.",
                "Для аватарки: удалите фон → градиент → обрезка «квадрат»."
            ],
            tipsEN: [
                "Background removal works best when the subject stands out clearly: a person, a pet, an object on a table.",
                "For an avatar: remove background → gradient → square crop."
            ],
            demo: .photoEditor,
            related: ["ai-edit", "video-editor", "photos-vision"]
        )
    }

    fileprivate static var articleVideoEditor: HelpArticle {
        HelpArticle(
            id: "video-editor", symbol: "scissors", tint: .orange,
            titleRU: "Редактор видео", titleEN: "Video editor",
            summaryRU: "Обрезка, звук, скорость, фильтры, музыка и озвучка голосом.",
            summaryEN: "Trim, sound, speed, filters, music and voice-over.",
            bodyRU: [
                "Откройте видео-вложение и нажмите **«Редактировать»**. Можно **обрезать** начало и конец, **выключить звук**, изменить **скорость** (замедлить или ускорить), наложить **фильтр**.",
                "Кнопка **«Музыка»** добавляет звуковую дорожку из файла, а **«Озвучка»** позволяет записать свой голос поверх видео прямо в редакторе.",
                "Готовое видео сохраняется в чат новым вложением."
            ],
            bodyEN: [
                "Open a video attachment and tap **Edit**. You can **trim** the start and end, **mute** it, change the **speed** (slow down or speed up) and apply a **filter**.",
                "**Music** adds a soundtrack from a file, and **Voice-over** records your voice on top of the video right in the editor.",
                "The finished video is saved to the chat as a new attachment."
            ],
            stepsRU: [
                "Откройте видео в чате → **«Редактировать»**.",
                "Потяните края полосы кадров, чтобы обрезать.",
                "Выберите скорость, фильтр, музыку или озвучку.",
                "Нажмите **«Готово»**."
            ],
            stepsEN: [
                "Open the video in the chat → **Edit**.",
                "Drag the edges of the frame strip to trim.",
                "Choose speed, filter, music or voice-over.",
                "Tap **Done**."
            ],
            tipsRU: [
                "Сначала обрежьте видео до нужного куска — потом нейросети будет проще его разобрать."
            ],
            tipsEN: [
                "Trim the video to the part you need first — it's easier for the assistant to analyse."
            ],
            related: ["photo-editor", "video-audio"]
        )
    }

    fileprivate static var articleAIEdit: HelpArticle {
        HelpArticle(
            id: "ai-edit", symbol: "sparkles", tint: .purple,
            titleRU: "Нейросеть редактирует фото", titleEN: "The assistant edits photos",
            summaryRU: "Просто попросите: «убери фон», «сделай чёрно-белым», «добавь надпись».",
            summaryEN: "Just ask: “remove the background”, “make it black and white”, “add a caption”.",
            bodyRU: [
                "Не обязательно открывать редактор самому. Прикрепите фото и напишите, что сделать: **«убери фон»**, **«сделай чёрно-белым»**, **«добавь надпись С днём рождения»**, **«обрежь квадратом»**, **«сделай теплее»**.",
                "Honer AI применит те же инструменты редактора и пришлёт готовый результат в чат. Если что-то не так — попросите поправить: «надпись крупнее», «фон синий»."
            ],
            bodyEN: [
                "You don't have to open the editor yourself. Attach a photo and write what to do: **“remove the background”**, **“make it black and white”**, **“add the caption Happy Birthday”**, **“crop it square”**, **“make it warmer”**.",
                "Honer AI applies the same editor tools and sends the result to the chat. If something's off, ask for a fix: “bigger caption”, “blue background”."
            ],
            stepsRU: [
                "Прикрепите фото.",
                "Напишите, что изменить.",
                "Получите результат и при необходимости уточните."
            ],
            stepsEN: [
                "Attach a photo.",
                "Say what to change.",
                "Get the result and refine if needed."
            ],
            tipsRU: [
                "Можно просить несколько правок сразу: «убери фон, поставь белый и добавь подпись снизу»."
            ],
            tipsEN: [
                "You can ask for several edits at once: “remove the background, make it white and add a caption at the bottom”."
            ],
            related: ["photo-editor", "photos-vision"]
        )
    }
}

// MARK: - Содержание: интернет

extension HelpLibrary {
    static var webSection: HelpSection {
        let items: [HelpArticle] = [articleWebSearch, articleDeepResearch, articleSources, articleWebMedia]
        return HelpSection(id: "web", symbol: "globe", tint: .cyan,
                           titleRU: "Интернет", titleEN: "Internet", articles: items)
    }

    fileprivate static var articleWebSearch: HelpArticle {
        HelpArticle(
            id: "web-search", symbol: "magnifyingglass", tint: .cyan,
            titleRU: "Поиск в интернете", titleEN: "Web search",
            summaryRU: "Несколько поисковиков и чтение страниц как в Safari, с JavaScript.",
            summaryEN: "Several search engines and reading pages like Safari, with JavaScript.",
            bodyRU: [
                "Когда включён **«Поиск»**, Honer AI ищет сразу в **нескольких поисковых системах** и сравнивает результаты — так ответы полнее и надёжнее.",
                "Страницы открываются **как в Safari**, с выполнением JavaScript, поэтому нейросеть видит и современные сайты, где содержимое подгружается динамически.",
                "Пока идёт поиск, над ответом видна **живая лента**: какие сайты сейчас читаются. Так понятно, откуда берётся информация."
            ],
            bodyEN: [
                "With **Search** on, Honer AI queries **several search engines** at once and compares the results — so answers are fuller and more reliable.",
                "Pages open **like in Safari**, with JavaScript running, so the assistant also sees modern sites whose content loads dynamically.",
                "While searching, a **live feed** above the answer shows which sites are being read. You always know where information comes from."
            ],
            stepsRU: [
                "Включите **«Поиск»**.",
                "Задайте вопрос о свежих данных.",
                "Следите за лентой сайтов и откройте источники после ответа."
            ],
            stepsEN: [
                "Turn on **Search**.",
                "Ask about current information.",
                "Watch the site feed and open the sources after the answer."
            ],
            tipsRU: [
                "Нужен конкретный сайт? Вставьте ссылку в сообщение — нейросеть откроет и прочитает именно её.",
                "Закрытые страницы (с входом по паролю) нейросеть не открывает — она не входит в ваши аккаунты."
            ],
            tipsEN: [
                "Need a specific site? Paste the link in your message — the assistant opens and reads exactly that page.",
                "Pages behind a login are not accessible — the assistant never signs in to your accounts."
            ],
            screenshot: "guide-search",
            related: ["search-button", "deep-research", "sources"]
        )
    }

    fileprivate static var articleDeepResearch: HelpArticle {
        HelpArticle(
            id: "deep-research", symbol: "books.vertical", tint: .indigo,
            titleRU: "Глубокое исследование", titleEN: "Deep research",
            summaryRU: "Сотни и тысячи страниц за раз — со счётчиком прогресса.",
            summaryEN: "Hundreds or thousands of pages at a time — with a live progress counter.",
            bodyRU: [
                "Для серьёзного вопроса Honer AI умеет быстро прочитать **сотни и даже тысячи страниц**: обзоры, форумы, документацию, новости, магазины.",
                "Во время исследования показывается живой счётчик, например **«Прочитано 132 из 500 сайтов»**, и значки сайтов, которые сейчас обрабатываются.",
                "В конце вы получаете сводку с выводами и ссылками на источники с цитатами. Большое исследование занимает больше времени — можно свернуть приложение, ответ допишется в фоне."
            ],
            bodyEN: [
                "For a serious question Honer AI can quickly read **hundreds or even thousands of pages**: reviews, forums, documentation, news, shops.",
                "During research you see a live counter such as **“Read 132 of 500 sites”** and icons of the sites being processed.",
                "At the end you get a summary with conclusions and links to sources with quotes. Big research takes longer — feel free to leave the app, the answer finishes in the background."
            ],
            stepsRU: [
                "Включите **«Поиск»**.",
                "Напишите: «изучи 300 сайтов и сравни лучшие ноутбуки до 80 000 ₽».",
                "Следите за счётчиком или сверните приложение."
            ],
            stepsEN: [
                "Turn on **Search**.",
                "Write: “study 300 sites and compare the best laptops under $1000”.",
                "Watch the counter or leave the app."
            ],
            tipsRU: [
                "Чем точнее задача (бюджет, страна, критерии), тем полезнее итог.",
                "Попросите в конце таблицу сравнения — её можно будет отредактировать."
            ],
            tipsEN: [
                "The more precise the task (budget, country, criteria), the more useful the result.",
                "Ask for a comparison table at the end — you can edit it afterwards."
            ],
            demo: .webResearch,
            related: ["web-search", "sources", "tables", "background-answers"]
        )
    }

    fileprivate static var articleSources: HelpArticle {
        HelpArticle(
            id: "sources", symbol: "quote.bubble", tint: .blue,
            titleRU: "Источники и цитаты", titleEN: "Sources and quotes",
            summaryRU: "Проверьте, откуда взята информация: страницы, даты, прочитанный текст.",
            summaryEN: "Check where information came from: pages, dates, the text that was read.",
            bodyRU: [
                "В ответе с поиском числа-сноски (**1**, **2**…) ведут к источникам. Под ответом есть карточка **«Источники ответа»** — сколько страниц прочитано и с каких сайтов.",
                "Нажмите на карточку: откроется список источников с названием страницы, адресом, кратким описанием, временем чтения и кнопкой **«Прочитанный текст»** — там видно, какие именно цитаты использовала нейросеть."
            ],
            bodyEN: [
                "In an answer with search, footnote numbers (**1**, **2**…) lead to the sources. Under the answer there's an **Answer sources** card — how many pages were read and from which sites.",
                "Tap the card to see the sources: page title, address, a short description, when it was read and a **Read text** button showing exactly which quotes the assistant used."
            ],
            stepsRU: [
                "Нажмите на карточку **«Источники ответа»**.",
                "Выберите источник и откройте **«Прочитанный текст»**.",
                "Нажмите на адрес, чтобы открыть страницу."
            ],
            stepsEN: [
                "Tap the **Answer sources** card.",
                "Choose a source and open **Read text**.",
                "Tap the address to open the page."
            ],
            tipsRU: [
                "Для важных решений всегда открывайте 1–2 источника и проверяйте цифры."
            ],
            tipsEN: [
                "For important decisions, always open one or two sources and check the numbers."
            ],
            screenshot: "guide-sources",
            related: ["web-search", "deep-research"]
        )
    }

    fileprivate static var articleWebMedia: HelpArticle {
        HelpArticle(
            id: "web-media", symbol: "play.rectangle", tint: .red,
            titleRU: "Фото, видео, скриншоты и погода", titleEN: "Photos, videos, screenshots and weather",
            summaryRU: "Настоящие изображения и видео из интернета прямо в чате.",
            summaryEN: "Real images and videos from the internet right in the chat.",
            bodyRU: [
                "Попросите «покажи фото Эйфелевой башни ночью» — Honer AI найдёт **настоящие фотографии** в интернете и покажет их в чате. Нажмите на фото, чтобы открыть его на весь экран.",
                "**Видео** из интернета (например, с YouTube) воспроизводятся **прямо в приложении**.",
                "Нейросеть может сделать **скриншот страницы** сайта, чтобы показать, как она выглядит, и узнать **погоду** для вашего города или любого другого места."
            ],
            bodyEN: [
                "Ask “show me photos of the Eiffel Tower at night” — Honer AI finds **real photos** online and shows them in the chat. Tap a photo to view it full screen.",
                "**Videos** from the internet (YouTube, for example) play **right inside the app**.",
                "The assistant can take a **screenshot of a web page** to show how it looks, and check the **weather** for your city or anywhere else."
            ],
            stepsRU: [
                "Включите **«Поиск»**.",
                "Попросите: «покажи фото…», «найди видео…», «сделай скриншот сайта…», «какая погода в…».",
                "Нажмите на фото или видео, чтобы открыть."
            ],
            stepsEN: [
                "Turn on **Search**.",
                "Ask: “show photos of…”, “find a video…”, “screenshot the site…”, “what's the weather in…”.",
                "Tap a photo or video to open it."
            ],
            tipsRU: [
                "Для погоды «у меня» разрешите геопозицию — нейросеть узнает ваш город."
            ],
            tipsEN: [
                "For “weather here”, allow location — the assistant learns your city."
            ],
            related: ["youtube", "web-search", "permissions"]
        )
    }
}

// MARK: - Содержание: интеграции

extension HelpLibrary {
    static var integrationsSection: HelpSection {
        let items: [HelpArticle] = [articleYouTube, articleGitHub, articleMarketplaces, articleSocial]
        return HelpSection(id: "integrations", symbol: "square.stack.3d.up", tint: .red,
                           titleRU: "Интеграции", titleEN: "Integrations", articles: items)
    }

    fileprivate static var articleYouTube: HelpArticle {
        HelpArticle(
            id: "youtube", symbol: "play.tv", tint: .red,
            titleRU: "YouTube", titleEN: "YouTube",
            summaryRU: "Поиск видео, информация о ролике, субтитры и расшифровка.",
            summaryEN: "Video search, video details, subtitles and transcripts.",
            bodyRU: [
                "Honer AI ищет видео на **YouTube**, показывает информацию о ролике (название, канал, длительность, просмотры) и умеет получать **субтитры и расшифровку**.",
                "Благодаря расшифровке можно спросить: «о чём это видео?», «сделай конспект», «в какой момент рассказывают про цены?» — без просмотра всего ролика.",
                "Работает через публичные данные: вход в ваш аккаунт Google не нужен и не используется. Если у ролика нет субтитров, пересказ будет по описанию."
            ],
            bodyEN: [
                "Honer AI searches **YouTube**, shows video details (title, channel, duration, views) and can fetch **subtitles and transcripts**.",
                "With a transcript you can ask “what's this video about?”, “make notes”, “when do they talk about prices?” — without watching the whole thing.",
                "It uses public data: your Google account is neither needed nor used. If a video has no subtitles, the summary is based on its description."
            ],
            stepsRU: [
                "Вставьте ссылку на ролик или попросите «найди на YouTube…».",
                "Попросите конспект, перевод или ответ по содержанию."
            ],
            stepsEN: [
                "Paste a video link or ask “find on YouTube…”.",
                "Ask for notes, a translation or an answer about the content."
            ],
            tipsRU: [
                "Длинная лекция → «пять главных мыслей с таймкодами»."
            ],
            tipsEN: [
                "Long lecture → “five key ideas with timestamps”."
            ],
            related: ["web-media", "github", "social"]
        )
    }

    fileprivate static var articleGitHub: HelpArticle {
        HelpArticle(
            id: "github", symbol: "chevron.left.forwardslash.chevron.right", tint: .gray,
            titleRU: "GitHub", titleEN: "GitHub",
            summaryRU: "Репозитории, файлы, issues и релизы открытых проектов.",
            summaryEN: "Repositories, files, issues and releases of public projects.",
            bodyRU: [
                "Honer AI открывает **публичные репозитории GitHub**: читает README и файлы кода, смотрит **issues** и **релизы**, может найти проект по описанию.",
                "Можно спросить: «что делает этот репозиторий?», «как установить?», «что нового в последнем релизе?», «есть ли issue про такую ошибку?».",
                "Приватные репозитории недоступны: нейросеть не входит в ваш аккаунт GitHub."
            ],
            bodyEN: [
                "Honer AI opens **public GitHub repositories**: reads the README and code files, looks at **issues** and **releases**, and can find a project by description.",
                "Ask: “what does this repository do?”, “how do I install it?”, “what's new in the latest release?”, “is there an issue about this error?”.",
                "Private repositories are not available: the assistant doesn't sign in to your GitHub account."
            ],
            stepsRU: [
                "Вставьте ссылку на репозиторий или назовите проект.",
                "Задайте вопрос о коде, issues или релизах."
            ],
            stepsEN: [
                "Paste a repository link or name the project.",
                "Ask about the code, issues or releases."
            ],
            tipsRU: [
                "Ошибка в библиотеке? Попросите «поищи похожие issues в репозитории» — часто решение уже есть."
            ],
            tipsEN: [
                "Bug in a library? Ask to “look for similar issues in the repo” — the fix often already exists."
            ],
            related: ["youtube", "documents"]
        )
    }

    fileprivate static var articleMarketplaces: HelpArticle {
        HelpArticle(
            id: "marketplaces", symbol: "cart", tint: .purple,
            titleRU: "Wildberries, Ozon, Avito", titleEN: "Wildberries, Ozon, Avito",
            summaryRU: "Поиск товаров с ценами и ссылками.",
            summaryEN: "Product search with prices and links.",
            bodyRU: [
                "Honer AI ищет товары на **Wildberries**, **Ozon** и **Avito** и показывает их с **ценами**, фото и **ссылками** — можно сразу открыть карточку товара.",
                "Попросите сравнить варианты, найти подешевле или подобрать по параметрам: «беспроводные наушники до 5000 ₽ с шумоподавлением».",
                "Нейросеть не покупает и не оформляет заказы — только ищет и сравнивает. Цены и наличие меняются, поэтому перед покупкой проверьте их на сайте."
            ],
            bodyEN: [
                "Honer AI searches **Wildberries**, **Ozon** and **Avito** and shows items with **prices**, photos and **links** — you can open the product page right away.",
                "Ask it to compare options, find something cheaper or match your criteria: “wireless noise-cancelling earbuds under 5000 ₽”.",
                "The assistant doesn't buy or place orders — it only searches and compares. Prices and stock change, so check them on the site before buying."
            ],
            stepsRU: [
                "Включите **«Поиск»**.",
                "Напишите: «найди на Ozon…» или «сравни цены на Wildberries и Ozon…».",
                "Нажмите на ссылку, чтобы открыть товар."
            ],
            stepsEN: [
                "Turn on **Search**.",
                "Write: “find on Ozon…” or “compare prices on Wildberries and Ozon…”.",
                "Tap a link to open the product."
            ],
            tipsRU: [
                "Попросите итог таблицей: товар, цена, рейтинг, ссылка."
            ],
            tipsEN: [
                "Ask for the result as a table: item, price, rating, link."
            ],
            related: ["tables", "web-search"]
        )
    }

    fileprivate static var articleSocial: HelpArticle {
        HelpArticle(
            id: "social", symbol: "person.2", tint: .blue,
            titleRU: "ВКонтакте и Telegram", titleEN: "VK and Telegram",
            summaryRU: "Публичные страницы VK и открытые каналы Telegram — без входа в аккаунт.",
            summaryEN: "Public VK pages and open Telegram channels — without logging in.",
            bodyRU: [
                "Honer AI читает **публичные страницы ВКонтакте** и **открытые каналы Telegram**: последние посты, описание, новости сообщества.",
                "Все интеграции работают **через публичные страницы и открытые API, без входа** в ваши аккаунты. Поэтому **закрытые** профили, группы, личные сообщения и приватные каналы недоступны — и это касается любых соцсетей, включая Instagram и Facebook."
            ],
            bodyEN: [
                "Honer AI reads **public VK pages** and **open Telegram channels**: latest posts, descriptions, community news.",
                "All integrations work **through public pages and open APIs, without logging in** to your accounts. So **private** profiles, groups, direct messages and closed channels are not available — and that applies to every social network, including Instagram and Facebook."
            ],
            stepsRU: [
                "Вставьте ссылку на канал или страницу.",
                "Попросите: «что нового за неделю?» или «сделай дайджест»."
            ],
            stepsEN: [
                "Paste a link to the channel or page.",
                "Ask: “what's new this week?” or “make a digest”."
            ],
            tipsRU: [
                "Дайджест из трёх каналов: вставьте три ссылки и попросите «главное за сегодня одним списком»."
            ],
            tipsEN: [
                "A digest from three channels: paste three links and ask for “today's highlights in one list”."
            ],
            related: ["youtube", "web-search"]
        )
    }
}

// MARK: - Содержание: таблицы

extension HelpLibrary {
    static var tablesSection: HelpSection {
        let items: [HelpArticle] = [articleTables, articleTableExport]
        return HelpSection(id: "tables", symbol: "tablecells", tint: .green,
                           titleRU: "Таблицы", titleEN: "Tables", articles: items)
    }

    fileprivate static var articleTables: HelpArticle {
        HelpArticle(
            id: "tables", symbol: "tablecells", tint: .green,
            titleRU: "Таблицы в чате", titleEN: "Tables in the chat",
            summaryRU: "Редактируемые таблицы: ячейки, строки, столбцы — а нейросеть видит ваши правки.",
            summaryEN: "Editable tables: cells, rows, columns — and the assistant sees your edits.",
            bodyRU: [
                "Honer AI создаёт таблицы прямо в чате: бюджет, расписание, список покупок, сравнение товаров. Таблицы бывают двух видов — **редактируемые** и **только для чтения**.",
                "Редактируемую таблицу можно **открыть на весь экран**: менять значения в ячейках, **добавлять и удалять строки и столбцы**, переименовывать заголовки.",
                "Нейросеть **видит ваши правки**. Попросите «пересчитай итог», «добавь столбец с процентами», «отсортируй по цене» — и она изменит таблицу."
            ],
            bodyEN: [
                "Honer AI creates tables right in the chat: a budget, a schedule, a shopping list, a product comparison. Tables come in two kinds — **editable** and **read-only**.",
                "An editable table can be **opened full screen**: change cell values, **add and delete rows and columns**, rename headers.",
                "The assistant **sees your edits**. Ask “recalculate the total”, “add a percentage column”, “sort by price” — and it updates the table."
            ],
            stepsRU: [
                "Попросите: «сделай таблицу бюджета на месяц».",
                "Нажмите на таблицу, чтобы открыть её на весь экран.",
                "Нажмите на ячейку и измените значение; кнопками добавьте строку или столбец.",
                "Вернитесь в чат и попросите нейросеть что-то пересчитать."
            ],
            stepsEN: [
                "Ask: “make a monthly budget table”.",
                "Tap the table to open it full screen.",
                "Tap a cell and change the value; use the buttons to add a row or column.",
                "Go back to the chat and ask the assistant to recalculate something."
            ],
            tipsRU: [
                "Скажите «сделай редактируемую таблицу», если хотите менять её сами.",
                "Прикрепите Excel или CSV и попросите «покажи как таблицу» — данные можно будет править."
            ],
            tipsEN: [
                "Say “make an editable table” if you want to change it yourself.",
                "Attach an Excel or CSV file and ask to “show it as a table” — you can then edit the data."
            ],
            demo: .table,
            related: ["table-export", "documents", "formatting"]
        )
    }

    fileprivate static var articleTableExport: HelpArticle {
        HelpArticle(
            id: "table-export", symbol: "square.and.arrow.up", tint: .teal,
            titleRU: "Экспорт таблицы", titleEN: "Exporting a table",
            summaryRU: "Сохраните таблицу в CSV или скопируйте её.",
            summaryEN: "Save a table as CSV or copy it.",
            bodyRU: [
                "Любую таблицу можно **экспортировать в CSV** — такой файл открывается в Excel, Numbers и Google Таблицах. Или просто **скопировать** её и вставить в заметку, письмо или документ.",
                "Кнопки экспорта и копирования есть в полноэкранном режиме таблицы."
            ],
            bodyEN: [
                "Any table can be **exported to CSV** — the file opens in Excel, Numbers and Google Sheets. Or just **copy** it and paste into a note, email or document.",
                "Export and copy buttons are in the table's full-screen view."
            ],
            stepsRU: [
                "Откройте таблицу на весь экран.",
                "Нажмите **«Поделиться»** → CSV или **«Копировать»**."
            ],
            stepsEN: [
                "Open the table full screen.",
                "Tap **Share** → CSV, or **Copy**."
            ],
            tipsRU: [
                "CSV удобно отправить в «Файлы» и открыть на компьютере."
            ],
            tipsEN: [
                "Save the CSV to Files and open it on your computer."
            ],
            related: ["tables"]
        )
    }
}

// MARK: - Содержание: память и инструкции

extension HelpLibrary {
    static var memorySection: HelpSection {
        let items: [HelpArticle] = [articleMemory, articlePinnedInstructions, articleInstructionLibrary, articleMemoryVsInstructions]
        return HelpSection(id: "memory", symbol: "brain", tint: .pink,
                           titleRU: "Память и инструкции", titleEN: "Memory and instructions", articles: items)
    }

    fileprivate static var articleMemory: HelpArticle {
        HelpArticle(
            id: "memory", symbol: "brain", tint: .pink,
            titleRU: "Долговременная память", titleEN: "Long-term memory",
            summaryRU: "Нейросеть запоминает важное о вас и использует это во всех чатах.",
            summaryEN: "The assistant remembers what matters about you and uses it in every chat.",
            bodyRU: [
                "Honer AI **сам сохраняет факты**, которые стоит помнить: «у меня кошка Муся», «я вегетарианец», «предпочитаю короткие ответы». Эти факты используются **во всех чатах**, а не только в текущем.",
                "Все факты видны в **Настройки → Память Honer AI**. Там их можно **добавить** вручную, **изменить** или **удалить**. Факт до 1200 символов.",
                "Чтобы сохранить что-то из переписки, долго нажмите на сообщение → **«Запомнить»**. Отключить использование памяти между чатами можно в тех же настройках."
            ],
            bodyEN: [
                "Honer AI **saves facts on its own** when they're worth remembering: “I have a cat called Mia”, “I'm vegetarian”, “I prefer short answers”. These facts are used **in every chat**, not just the current one.",
                "All facts are in **Settings → Honer AI memory**. There you can **add** them manually, **edit** or **delete** them. A fact can be up to 1200 characters.",
                "To save something from a conversation, long-press the message → **Remember**. You can turn off cross-chat memory in the same settings."
            ],
            stepsRU: [
                "Откройте **☰ → профиль → Настройки → Память Honer AI**.",
                "Нажмите **+**, чтобы добавить факт, или смахните факт влево, чтобы удалить.",
                "Нажмите на факт, чтобы изменить его."
            ],
            stepsEN: [
                "Open **☰ → profile → Settings → Honer AI memory**.",
                "Tap **+** to add a fact, or swipe a fact left to delete it.",
                "Tap a fact to edit it."
            ],
            tipsRU: [
                "Скажите «запомни: я живу в Казани» — факт сохранится сразу.",
                "Скажите «забудь про мою диету» — нейросеть удалит этот факт."
            ],
            tipsEN: [
                "Say “remember: I live in Kazan” — the fact is saved immediately.",
                "Say “forget about my diet” — the assistant deletes that fact."
            ],
            screenshot: "guide-memory",
            related: ["memory-vs-instructions", "pinned-instructions", "what-ai-knows"]
        )
    }

    fileprivate static var articlePinnedInstructions: HelpArticle {
        HelpArticle(
            id: "pinned-instructions", symbol: "pin", tint: .orange,
            titleRU: "Закреплённые инструкции", titleEN: "Pinned instructions",
            summaryRU: "Превратите сообщение в правило, которому нейросеть следует в каждом ответе чата.",
            summaryEN: "Turn a message into a rule the assistant follows in every answer of the chat.",
            bodyRU: [
                "Долго нажмите на любое сообщение (своё или ответ нейросети) и выберите **«Закрепить как инструкцию»**. Например: «Отвечай только по-английски», «Пиши как для ребёнка 10 лет», «Всегда давай код на Swift».",
                "Закреплённая инструкция показывается **вверху чата**. Honer AI видит её **в каждом ответе этого чата** и понимает, что её закрепили вы.",
                "Инструкцию можно **изменить**, **открепить** или **удалить**. Откреплённые инструкции сохраняются в **библиотеку** — их можно снова закрепить в любом чате."
            ],
            bodyEN: [
                "Long-press any message (yours or the assistant's) and choose **Pin as instruction**. For example: “Reply only in English”, “Write as if for a 10-year-old”, “Always give code in Swift”.",
                "A pinned instruction appears **at the top of the chat**. Honer AI sees it **in every answer in this chat** and knows you pinned it.",
                "You can **edit**, **unpin** or **delete** an instruction. Unpinned instructions go to the **library** — you can pin them again in any chat."
            ],
            stepsRU: [
                "Напишите правило обычным сообщением.",
                "Долго нажмите на него → **«Закрепить как инструкцию»**.",
                "Нажмите на плашку вверху чата, чтобы изменить или открепить."
            ],
            stepsEN: [
                "Write the rule as a normal message.",
                "Long-press it → **Pin as instruction**.",
                "Tap the banner at the top of the chat to edit or unpin."
            ],
            tipsRU: [
                "Сделайте отдельные чаты-помощники: «Переводчик» с инструкцией «переводи всё на английский», «Шеф-повар», «Репетитор по химии»."
            ],
            tipsEN: [
                "Make dedicated helper chats: a “Translator” with the instruction “translate everything into Russian”, a “Chef”, a “Chemistry tutor”."
            ],
            screenshot: "guide-pinned-instruction",
            related: ["instruction-library", "memory-vs-instructions", "message-menu"]
        )
    }

    fileprivate static var articleInstructionLibrary: HelpArticle {
        HelpArticle(
            id: "instruction-library", symbol: "text.book.closed", tint: .indigo,
            titleRU: "Библиотека инструкций", titleEN: "Instruction library",
            summaryRU: "Все закреплённые и сохранённые инструкции в одном месте.",
            summaryEN: "All pinned and saved instructions in one place.",
            bodyRU: [
                "Экран **«Инструкции чата»** показывает, что закреплено в этом чате (и кто закрепил — вы или это ответ Honer AI), а ниже — **сохранённые инструкции** из библиотеки.",
                "Отсюда инструкции можно менять, откреплять, удалять и закреплять снова. Открыть экран можно через **••• → Инструкции чата** или нажав на плашку инструкции вверху чата."
            ],
            bodyEN: [
                "The **Chat instructions** screen shows what's pinned in this chat (and whether it came from you or from a Honer AI answer), and below it the **saved instructions** from the library.",
                "From here you can edit, unpin, delete and re-pin instructions. Open it via **••• → Instructions** or by tapping the instruction banner at the top of the chat."
            ],
            stepsRU: [
                "Нажмите **•••** в чате → **Инструкции**.",
                "Выберите сохранённую инструкцию и нажмите **«Закрепить»**."
            ],
            stepsEN: [
                "Tap **•••** in the chat → **Instructions**.",
                "Pick a saved instruction and tap **Pin**."
            ],
            tipsRU: [
                "Держите в библиотеке готовые стили: «кратко», «подробно с примерами», «официальный тон»."
            ],
            tipsEN: [
                "Keep ready-made styles in the library: “brief”, “detailed with examples”, “formal tone”."
            ],
            screenshot: "guide-instructions",
            related: ["pinned-instructions", "memory"]
        )
    }

    fileprivate static var articleMemoryVsInstructions: HelpArticle {
        HelpArticle(
            id: "memory-vs-instructions", symbol: "arrow.left.arrow.right", tint: .gray,
            titleRU: "Память ≠ инструкции", titleEN: "Memory ≠ instructions",
            summaryRU: "Память — факты о вас во всех чатах. Инструкции — правила ответа в одном чате.",
            summaryEN: "Memory is facts about you in every chat. Instructions are answer rules in one chat.",
            bodyRU: [
                "**Память** — это факты о вас: имя питомца, город, профессия, вкусы. Она общая для всех чатов и хранится в настройках.",
                "**Инструкции** — это правила ответа: язык, стиль, формат, роль. Они действуют только в том чате, где закреплены.",
                "Пример: «я аллергик на орехи» — в память. «Отвечай списком из трёх пунктов» — в инструкцию."
            ],
            bodyEN: [
                "**Memory** is facts about you: your pet's name, your city, job, tastes. It's shared by all chats and lives in settings.",
                "**Instructions** are answer rules: language, style, format, role. They apply only in the chat where they're pinned.",
                "Example: “I'm allergic to nuts” goes to memory. “Answer as a three-point list” goes to an instruction."
            ],
            related: ["memory", "pinned-instructions"]
        )
    }
}

// MARK: - Содержание: чаты

extension HelpLibrary {
    static var chatsSection: HelpSection {
        let items: [HelpArticle] = [articleHistory, articlePinDrag, articleArchive, articleFind, articleBranches, articleNavigationLines, articleChatInfo]
        return HelpSection(id: "chats", symbol: "sidebar.left", tint: .blue,
                           titleRU: "Чаты", titleEN: "Chats", articles: items)
    }

    fileprivate static var articleHistory: HelpArticle {
        HelpArticle(
            id: "history", symbol: "sidebar.left", tint: .blue,
            titleRU: "История чатов", titleEN: "Chat history",
            summaryRU: "Все разговоры по датам, поиск по содержимому, переименование и удаление.",
            summaryEN: "All conversations by date, content search, renaming and deleting.",
            bodyRU: [
                "Нажмите **☰** — откроется боковая панель. Чаты сгруппированы: **Закреплено**, **Сегодня**, **Вчера**, **7 дней** и ранее. Название чата придумывается автоматически по первому сообщению.",
                "Поле **«Поиск в содержимом…»** ищет не только по названиям, но и **по тексту всех сообщений** во всех чатах.",
                "Кнопка **•••** у чата: переименовать, закрепить, в архив, удалить. Кнопка со списком справа от «Закреплено» включает **выбор нескольких чатов** — чтобы удалить или архивировать сразу несколько."
            ],
            bodyEN: [
                "Tap **☰** to open the sidebar. Chats are grouped: **Pinned**, **Today**, **Yesterday**, **7 days** and older. A chat's title is generated automatically from the first message.",
                "The **Search content…** field looks not only at titles but at **the text of every message** in every chat.",
                "The **•••** button next to a chat: rename, pin, archive, delete. The list button to the right of Pinned turns on **multi-select** — to delete or archive several chats at once."
            ],
            stepsRU: [
                "Нажмите **☰**.",
                "Найдите чат в списке или через поиск.",
                "Нажмите **•••** рядом с чатом, чтобы переименовать или удалить."
            ],
            stepsEN: [
                "Tap **☰**.",
                "Find a chat in the list or via search.",
                "Tap **•••** next to a chat to rename or delete it."
            ],
            tipsRU: [
                "В **Настройки → Управление данными** можно включить автоудаление старых чатов; закреплённые чаты при этом сохраняются."
            ],
            tipsEN: [
                "In **Settings → Data management** you can turn on auto-delete for old chats; pinned chats are kept."
            ],
            screenshot: "guide-history",
            related: ["pin-drag", "archive", "find"]
        )
    }

    fileprivate static var articlePinDrag: HelpArticle {
        HelpArticle(
            id: "pin-drag", symbol: "pin.circle", tint: .orange,
            titleRU: "Закрепление и перетаскивание", titleEN: "Pinning and drag & drop",
            summaryRU: "Закрепите важные чаты сверху и расставьте их в нужном порядке.",
            summaryEN: "Keep important chats on top and arrange them in any order.",
            bodyRU: [
                "Закреплённые чаты всегда наверху боковой панели. Закрепить можно через **••• → Закрепить** — или просто **перетащить** чат в область **«Закреплено»**.",
                "Порядок закреплённых чатов меняется **перетаскиванием**: нажмите и удерживайте чат, затем двигайте вверх или вниз. Стрелки ▲▼ рядом с закреплённым чатом делают то же одним нажатием.",
                "Чтобы открепить, выберите **••• → Открепить** или перетащите чат из закреплённых обратно в список."
            ],
            bodyEN: [
                "Pinned chats always stay at the top of the sidebar. Pin via **••• → Pin** — or simply **drag** a chat onto the **Pinned** area.",
                "Reorder pinned chats by **dragging**: press and hold a chat, then move it up or down. The ▲▼ arrows next to a pinned chat do the same with a tap.",
                "To unpin, choose **••• → Unpin** or drag the chat out of Pinned back into the list."
            ],
            stepsRU: [
                "Откройте **☰**.",
                "Нажмите и удерживайте чат, пока он не «приподнимется».",
                "Перетащите его в раздел **«Закреплено»** и отпустите."
            ],
            stepsEN: [
                "Open **☰**.",
                "Press and hold a chat until it lifts.",
                "Drag it into **Pinned** and let go."
            ],
            tipsRU: [
                "Закреплённые чаты не удаляются автоудалением — закрепите всё ценное."
            ],
            tipsEN: [
                "Pinned chats are never auto-deleted — pin anything valuable."
            ],
            demo: .dragChat,
            related: ["history", "archive"]
        )
    }

    fileprivate static var articleArchive: HelpArticle {
        HelpArticle(
            id: "archive", symbol: "archivebox", tint: .brown,
            titleRU: "Архив", titleEN: "Archive",
            summaryRU: "Уберите чат с глаз, не удаляя его, и верните в любой момент.",
            summaryEN: "Hide a chat without deleting it, and bring it back any time.",
            bodyRU: [
                "**В архив** — это «убрать, но не удалять». Чат исчезает из боковой панели, но вся переписка сохраняется.",
                "Архив открывается в **Настройки → Архив чатов** (рядом видно, сколько там чатов). Нажмите **«Восстановить»**, чтобы вернуть чат в список, или удалите его навсегда."
            ],
            bodyEN: [
                "**Archive** means “put away, don't delete”. The chat disappears from the sidebar but the whole conversation is kept.",
                "Open the archive in **Settings → Archived chats** (the count is shown next to it). Tap **Restore** to bring a chat back, or delete it for good."
            ],
            stepsRU: [
                "Чтобы архивировать: **☰ → ••• у чата → В архив**.",
                "Чтобы вернуть: **Настройки → Архив чатов → Восстановить**."
            ],
            stepsEN: [
                "To archive: **☰ → ••• next to the chat → Archive**.",
                "To restore: **Settings → Archived chats → Restore**."
            ],
            tipsRU: [
                "Архивируйте завершённые проекты — список останется коротким, а история не потеряется."
            ],
            tipsEN: [
                "Archive finished projects — your list stays short and nothing is lost."
            ],
            related: ["history", "pin-drag", "backups"]
        )
    }

    fileprivate static var articleFind: HelpArticle {
        HelpArticle(
            id: "find", symbol: "magnifyingglass.circle", tint: .teal,
            titleRU: "Поиск в чате и по всем чатам", titleEN: "Find in chat and across chats",
            summaryRU: "Найдите слово в текущем разговоре или во всей истории.",
            summaryEN: "Find a word in the current conversation or in your whole history.",
            bodyRU: [
                "**Найти в чате**: **••• → Найти в чате**. Введите слово — совпадения подсветятся, счётчик покажет «2 / 5», стрелки ▲▼ переключают между ними.",
                "**Поиск по всем чатам**: откройте **☰** и введите запрос в **«Поиск в содержимом…»** — появятся все чаты, где встречаются эти слова."
            ],
            bodyEN: [
                "**Find in chat**: **••• → Find in chat**. Type a word — matches are highlighted, a counter shows “2 / 5” and the ▲▼ arrows jump between them.",
                "**Search all chats**: open **☰** and type into **Search content…** — every chat containing those words appears."
            ],
            stepsRU: [
                "Нажмите **•••** → **Найти в чате**.",
                "Введите слово.",
                "Переходите по совпадениям стрелками; **✕** закрывает поиск."
            ],
            stepsEN: [
                "Tap **•••** → **Find in chat**.",
                "Type a word.",
                "Jump through matches with the arrows; **✕** closes the search."
            ],
            tipsRU: [
                "Ищите по корню слова: «дел» найдёт и «дела», и «делать»."
            ],
            tipsEN: [
                "Search by word stem: “cook” finds “cook”, “cooking” and “cookies”."
            ],
            screenshot: "guide-find",
            related: ["history", "navigation-lines"]
        )
    }

    fileprivate static var articleBranches: HelpArticle {
        HelpArticle(
            id: "branches", symbol: "arrow.triangle.branch", tint: .purple,
            titleRU: "Ветки: «Продолжить отсюда»", titleEN: "Branches: “Continue from here”",
            summaryRU: "Начните новую линию разговора с любого сообщения, не теряя старую.",
            summaryEN: "Start a new line of conversation from any message without losing the old one.",
            bodyRU: [
                "Хотите попробовать другой вариант с середины разговора? Долго нажмите на сообщение → **«Продолжить в ветке»** (или **«Продолжить отсюда»** в предпросмотре линий навигации).",
                "Создастся новый чат **«Ветка · Название»** со всей перепиской до этого сообщения. Исходный чат не меняется — можно развивать обе версии параллельно."
            ],
            bodyEN: [
                "Want to try a different direction from the middle of a conversation? Long-press a message → **Continue in a branch** (or **Continue from here** in the navigation-line preview).",
                "A new chat **“Branch · Title”** is created with the whole conversation up to that message. The original chat stays unchanged — you can develop both versions in parallel."
            ],
            stepsRU: [
                "Долго нажмите на сообщение.",
                "Выберите **«Продолжить в ветке»**.",
                "Продолжайте разговор в новой ветке."
            ],
            stepsEN: [
                "Long-press a message.",
                "Choose **Continue in a branch**.",
                "Carry on the conversation in the new branch."
            ],
            tipsRU: [
                "Удобно для сравнения: в одной ветке «сделай формально», в другой — «сделай с юмором»."
            ],
            tipsEN: [
                "Handy for comparisons: one branch “make it formal”, another “make it funny”."
            ],
            screenshot: "guide-branches",
            related: ["navigation-lines", "message-menu", "history"]
        )
    }

    fileprivate static var articleNavigationLines: HelpArticle {
        HelpArticle(
            id: "navigation-lines", symbol: "line.3.horizontal", tint: .indigo,
            titleRU: "Линии навигации", titleEN: "Navigation lines",
            summaryRU: "Полоски у правого края: предпросмотр сообщения и быстрый переход.",
            summaryEN: "Lines at the right edge: message preview and quick jumps.",
            bodyRU: [
                "У правого края чата видны короткие **горизонтальные линии** — каждая соответствует сообщению. Длинная линия подсвечивает, где вы сейчас.",
                "**Нажмите и удерживайте** линию — появится карточка-предпросмотр: кто написал, время и начало текста. Ведите пальцем вверх-вниз, чтобы просматривать сообщения, и отпустите, чтобы **перейти** к нужному. В карточке также есть **«Продолжить отсюда»**."
            ],
            bodyEN: [
                "At the right edge of the chat there are short **horizontal lines** — one per message. The longer highlighted line shows where you are.",
                "**Press and hold** a line to see a preview card: who wrote it, the time and the start of the text. Slide your finger up and down to browse messages and let go to **jump** to one. The card also has **Continue from here**."
            ],
            stepsRU: [
                "Нажмите и удерживайте линию у правого края.",
                "Проведите пальцем к нужному сообщению.",
                "Отпустите — чат прокрутится к нему."
            ],
            stepsEN: [
                "Press and hold a line at the right edge.",
                "Slide to the message you want.",
                "Let go — the chat scrolls to it."
            ],
            tipsRU: [
                "В длинных чатах это быстрее, чем листать: сотни сообщений за одно движение."
            ],
            tipsEN: [
                "In long chats this is faster than scrolling: hundreds of messages in one gesture."
            ],
            screenshot: "guide-navigation",
            related: ["branches", "find"]
        )
    }

    fileprivate static var articleChatInfo: HelpArticle {
        HelpArticle(
            id: "chat-info", symbol: "info.circle", tint: .cyan,
            titleRU: "Информация о чате", titleEN: "Chat info",
            summaryRU: "Все фото, видео, голосовые, музыка, файлы и ссылки чата, а также хронология.",
            summaryEN: "All photos, videos, voice notes, music, files and links in a chat, plus a timeline.",
            bodyRU: [
                "**••• → Информация о чате** собирает в одном месте всё, что было в разговоре: **фото, видео, голосовые сообщения, музыку и файлы** — отправленные вами и нейросетью, а также **все посещённые ссылки**.",
                "Есть **фильтры** (только фото, только файлы, только от вас, только от Honer AI) и **поиск**.",
                "Вкладка **«Хронология»** показывает по шагам, что происходило: вопросы, поиски в интернете, прочитанные сайты, созданные таблицы, правки фото."
            ],
            bodyEN: [
                "**••• → Chat info** gathers everything from the conversation in one place: **photos, videos, voice messages, music and files** — sent by you and by the assistant — plus **every visited link**.",
                "There are **filters** (photos only, files only, from you, from Honer AI) and **search**.",
                "The **Timeline** tab shows step by step what happened: questions, web searches, sites read, tables created, photo edits."
            ],
            stepsRU: [
                "Нажмите **•••** в чате.",
                "Выберите **«Информация о чате»**.",
                "Переключайте вкладки и фильтры."
            ],
            stepsEN: [
                "Tap **•••** in the chat.",
                "Choose **Chat info**.",
                "Switch tabs and filters."
            ],
            tipsRU: [
                "Потеряли ссылку, которую нейросеть открывала неделю назад? Она есть в «Ссылках»."
            ],
            tipsEN: [
                "Lost a link the assistant opened last week? It's in Links."
            ],
            related: ["chat-screen", "sources", "attachments"]
        )
    }
}

// MARK: - Содержание: голос

extension HelpLibrary {
    static var voiceSection: HelpSection {
        let items: [HelpArticle] = [articleVoiceInput, articleVoices, articleVoiceClone]
        return HelpSection(id: "voice", symbol: "waveform", tint: .red,
                           titleRU: "Голос", titleEN: "Voice", articles: items)
    }

    fileprivate static var articleVoiceInput: HelpArticle {
        HelpArticle(
            id: "voice-input", symbol: "mic", tint: .red,
            titleRU: "Голосовой ввод", titleEN: "Voice input",
            summaryRU: "Нажмите микрофон, говорите, нажмите ещё раз — и сообщение отправлено.",
            summaryEN: "Tap the mic, speak, tap again — and the message is sent.",
            bodyRU: [
                "Нажмите **микрофон** справа внизу. Появится волна и подсказка **«Говорите. Нажмите ■, чтобы отправить»**. Текст распознаётся прямо во время речи.",
                "Нажмите **■**, чтобы отправить, или **«Отмена»**, чтобы выйти без отправки. Если в поле уже был черновик, он сохранится.",
                "Распознавание работает для русского и английского; основной язык выбирается в **Настройки → Основной язык**."
            ],
            bodyEN: [
                "Tap the **microphone** at the bottom right. A waveform appears with the hint **“Speak. Tap ■ to send”**. Text is recognised while you talk.",
                "Tap **■** to send, or **Cancel** to leave without sending. Any draft already in the field is kept.",
                "Recognition works for Russian and English; choose the main language in **Settings → Speech language**."
            ],
            stepsRU: [
                "Нажмите микрофон.",
                "Говорите.",
                "Нажмите **■** — сообщение уйдёт."
            ],
            stepsEN: [
                "Tap the microphone.",
                "Speak.",
                "Tap **■** — the message is sent."
            ],
            tipsRU: [
                "Включите динамик вверху — получится разговор голосом: вы говорите, нейросеть отвечает вслух."
            ],
            tipsEN: [
                "Turn on the speaker at the top for a voice conversation: you talk, the assistant answers aloud."
            ],
            screenshot: "guide-voice", demo: .voice,
            related: ["voices", "read-aloud", "composer"]
        )
    }

    fileprivate static var articleVoices: HelpArticle {
        HelpArticle(
            id: "voices", symbol: "speaker.wave.3", tint: .green,
            titleRU: "Голоса озвучки", titleEN: "Reading voices",
            summaryRU: "Мужской или женский, русский и английский; как сделать звучание живым.",
            summaryEN: "Male or female, Russian and English; how to make it sound natural.",
            bodyRU: [
                "В **Настройки → Голос** выберите **Мужской** (по умолчанию) или **Женский** голос. Русский текст читает русский голос, английские слова — английский голос того же пола. Кнопка **«Послушать голос»** воспроизводит пример.",
                "Там же настраивается **скорость чтения**.",
                "Для живого, не «роботного» звучания скачайте голос улучшенного качества: настройки iPhone → **Универсальный доступ → Устный контент → Голоса** → Русский → «Милена» (Улучшенный), для английского — «Ava» (Улучшенный). После загрузки голос выберется сам."
            ],
            bodyEN: [
                "In **Settings → Voice** choose a **Male** (default) or **Female** voice. Russian text is read by a Russian voice and English words by an English voice of the same gender. **Listen to voice** plays a sample.",
                "Reading **speed** is set there too.",
                "For a natural, non-robotic sound, download an enhanced voice: iPhone Settings → **Accessibility → Spoken Content → Voices** → Russian → “Milena” (Enhanced), and for English “Ava” (Enhanced). Once downloaded, the voice is picked automatically."
            ],
            stepsRU: [
                "Откройте **Настройки → Голос**.",
                "Выберите пол голоса и скорость.",
                "Нажмите **«Послушать голос»**."
            ],
            stepsEN: [
                "Open **Settings → Voice**.",
                "Choose the voice gender and speed.",
                "Tap **Listen to voice**."
            ],
            tipsRU: [
                "Улучшенные голоса весят 100–400 МБ — скачивайте по Wi-Fi."
            ],
            tipsEN: [
                "Enhanced voices take 100–400 MB — download them over Wi-Fi."
            ],
            screenshot: "guide-voice-settings",
            related: ["voice-clone", "read-aloud"]
        )
    }

    fileprivate static var articleVoiceClone: HelpArticle {
        HelpArticle(
            id: "voice-clone", symbol: "person.wave.2", tint: .purple,
            titleRU: "Свой голос", titleEN: "Your own voice",
            summaryRU: "Клонирование голоса через Fish Audio и «Личный голос» Apple.",
            summaryEN: "Voice cloning with Fish Audio and Apple Personal Voice.",
            bodyRU: [
                "**«Мой голос»** клонирует ваш голос для русского и английского: вы записываете образец, и ответы читаются вашим голосом. Работает через сервис **Fish Audio**, для него **нужен ключ** этого сервиса.",
                "**«Личный голос» Apple** работает прямо на iPhone, без интернета, но **только для английского**. Сначала создайте его в настройках iPhone (Универсальный доступ → Личный голос), затем включите переключатель в **Настройки → Голос**."
            ],
            bodyEN: [
                "**My voice** clones your voice for Russian and English: you record a sample and answers are read in your voice. It uses the **Fish Audio** service and **requires a key** for it.",
                "**Apple Personal Voice** runs right on the iPhone, offline, but **English only**. Create it first in iPhone Settings (Accessibility → Personal Voice), then turn on the switch in **Settings → Voice**."
            ],
            stepsRU: [
                "Откройте **Настройки → Голос → Мой голос**.",
                "Введите ключ Fish Audio и запишите образец голоса.",
                "Включите чтение своим голосом."
            ],
            stepsEN: [
                "Open **Settings → Voice → My voice**.",
                "Enter a Fish Audio key and record a voice sample.",
                "Turn on reading in your voice."
            ],
            tipsRU: [
                "Записывайте образец в тишине и обычным темпом — клон будет звучать естественнее."
            ],
            tipsEN: [
                "Record the sample in a quiet room at your normal pace — the clone sounds more natural."
            ],
            related: ["voices", "parental-limits"]
        )
    }
}

// MARK: - Содержание: игры

extension HelpLibrary {
    static var gamesSection: HelpSection {
        let items: [HelpArticle] = [articleGames, articleChess]
        return HelpSection(id: "games", symbol: "gamecontroller", tint: .orange,
                           titleRU: "Игры", titleEN: "Games", articles: items)
    }

    fileprivate static var articleGames: HelpArticle {
        HelpArticle(
            id: "games", symbol: "gamecontroller", tint: .orange,
            titleRU: "Игры с Honer AI", titleEN: "Games with Honer AI",
            summaryRU: "Шахматы, русские шашки, «Дурак» и слот-машина «Удача» против нейросети.",
            summaryEN: "Chess, Russian checkers, Durak and the “Luck” slot machine against the assistant.",
            bodyRU: [
                "В приложении четыре игры: **Шахматы** (партия против Honer AI), **Шашки** (русские правила), **Дурак** (подкидной, 36 карт) и **Удача** (крутите барабаны).",
                "Нейросеть может **открыть игру по просьбе**: напишите «давай сыграем в шахматы». После партии **результат появляется в чате**, и можно обсудить ход игры.",
                "Игры работают **без интернета**."
            ],
            bodyEN: [
                "There are four games: **Chess** (a match against Honer AI), **Checkers** (Russian rules), **Durak** (the classic card game, 36 cards) and **Luck** (spin the reels).",
                "The assistant can **open a game on request**: write “let's play chess”. After the game **the result appears in the chat** and you can discuss it.",
                "Games work **offline**."
            ],
            stepsRU: [
                "Напишите «давай сыграем в…» или откройте игры из меню.",
                "Выберите игру.",
                "После партии вернитесь в чат — результат уже там."
            ],
            stepsEN: [
                "Write “let's play…” or open games from the menu.",
                "Choose a game.",
                "After the game go back to the chat — the result is already there."
            ],
            tipsRU: [
                "Попросите «разбери мою партию» — нейросеть объяснит ошибки."
            ],
            tipsEN: [
                "Ask “review my game” — the assistant explains your mistakes."
            ],
            screenshot: "guide-games",
            related: ["chess", "parental-limits"]
        )
    }

    fileprivate static var articleChess: HelpArticle {
        HelpArticle(
            id: "chess", symbol: "crown", tint: .indigo,
            titleRU: "Шахматы и шашки", titleEN: "Chess and checkers",
            summaryRU: "Партия с нейросетью, подсветка ходов, отмена хода.",
            summaryEN: "A match with the assistant, move highlights, undo.",
            bodyRU: [
                "Нажмите на фигуру — подсветятся допустимые ходы. Нажмите на клетку, чтобы сходить. Honer AI отвечает своим ходом.",
                "В шашках действуют русские правила: обязательное взятие, дамка ходит на любое число клеток."
            ],
            bodyEN: [
                "Tap a piece to highlight its legal moves. Tap a square to move. Honer AI replies with its move.",
                "Checkers follows Russian rules: capturing is mandatory, and a king moves any number of squares."
            ],
            stepsRU: [
                "Откройте **Шахматы** или **Шашки**.",
                "Нажмите на фигуру, затем на клетку.",
                "Играйте до мата или сдачи."
            ],
            stepsEN: [
                "Open **Chess** or **Checkers**.",
                "Tap a piece, then a square.",
                "Play until checkmate or resignation."
            ],
            tipsRU: [
                "Застряли? Спросите в чате «какой ход лучше в этой позиции?»."
            ],
            tipsEN: [
                "Stuck? Ask in the chat “what's the best move in this position?”."
            ],
            screenshot: "guide-chess",
            related: ["games"]
        )
    }
}

// MARK: - Содержание: уведомления и фон

extension HelpLibrary {
    static var backgroundSection: HelpSection {
        let items: [HelpArticle] = [articleBackgroundAnswers]
        return HelpSection(id: "background", symbol: "bell.badge", tint: .red,
                           titleRU: "Уведомления и фон", titleEN: "Notifications and background", articles: items)
    }

    fileprivate static var articleBackgroundAnswers: HelpArticle {
        HelpArticle(
            id: "background-answers", symbol: "bell.badge", tint: .red,
            titleRU: "Ответы в фоне", titleEN: "Background answers",
            summaryRU: "Сверните приложение — ответ допишется, а уведомление подскажет, что он готов.",
            summaryEN: "Leave the app — the answer finishes and a notification tells you it's ready.",
            bodyRU: [
                "Не нужно держать приложение открытым, пока нейросеть думает или исследует сайты. Сверните его — ответ **допишется в фоне**.",
                "Когда ответ готов, придёт **уведомление** с началом текста и картинкой, если она есть. **Нажмите на уведомление** — откроется именно этот чат.",
                "Уведомления включены по умолчанию. Если они не приходят, проверьте настройки iPhone: **Настройки → Honer AI → Уведомления**."
            ],
            bodyEN: [
                "No need to keep the app open while the assistant thinks or researches sites. Leave it — the answer **finishes in the background**.",
                "When it's ready you get a **notification** with the start of the text and an image if there is one. **Tap the notification** to open that exact chat.",
                "Notifications are on by default. If they don't arrive, check iPhone Settings: **Settings → Honer AI → Notifications**."
            ],
            stepsRU: [
                "Отправьте вопрос.",
                "Сверните приложение.",
                "Нажмите на уведомление, когда оно придёт."
            ],
            stepsEN: [
                "Send a question.",
                "Leave the app.",
                "Tap the notification when it arrives."
            ],
            tipsRU: [
                "Для долгого исследования на сотни сайтов — идеальный вариант: запустили и занялись своими делами."
            ],
            tipsEN: [
                "Perfect for long research across hundreds of sites: start it and get on with your day."
            ],
            related: ["deep-research", "permissions"]
        )
    }
}

// MARK: - Содержание: приватность и безопасность

extension HelpLibrary {
    static var privacySection: HelpSection {
        let items: [HelpArticle] = [articleDataPrivacy, articleBackups, articlePermissions, articleWhatAIKnows]
        return HelpSection(id: "privacy", symbol: "lock.shield", tint: .green,
                           titleRU: "Приватность и безопасность", titleEN: "Privacy and security", articles: items)
    }

    fileprivate static var articleDataPrivacy: HelpArticle {
        HelpArticle(
            id: "data-privacy", symbol: "lock.shield", tint: .green,
            titleRU: "Где хранятся данные", titleEN: "Where your data lives",
            summaryRU: "Чаты, вложения и память хранятся на вашем iPhone.",
            summaryEN: "Chats, attachments and memory are stored on your iPhone.",
            bodyRU: [
                "Вся история, вложения и память Honer AI **хранятся на устройстве**. Аккаунта на сервере нет, регистрироваться не нужно.",
                "Чтобы нейросеть ответила, текст запроса (и нужные вложения) отправляется модели DeepSeek через интернет. Удаление фона на фото и распознавание речи в видео выполняются **прямо на iPhone**.",
                "Если удалить приложение без резервной копии, данные пропадут — поэтому включите автокопирование."
            ],
            bodyEN: [
                "Your history, attachments and Honer AI memory are **stored on the device**. There's no server account and no sign-up.",
                "To get an answer, your request (and any needed attachments) is sent to the DeepSeek model over the internet. Photo background removal and speech recognition for videos run **right on the iPhone**.",
                "If you delete the app without a backup, the data is gone — so turn on automatic backups."
            ],
            tipsRU: [
                "Не отправляйте нейросети пароли, номера карт и коды из SMS — они ей не нужны."
            ],
            tipsEN: [
                "Never send passwords, card numbers or SMS codes to the assistant — it doesn't need them."
            ],
            related: ["backups", "permissions", "what-ai-knows"]
        )
    }

    fileprivate static var articleBackups: HelpArticle {
        HelpArticle(
            id: "backups", symbol: "externaldrive.badge.icloud", tint: .blue,
            titleRU: "Резервные копии", titleEN: "Backups",
            summaryRU: "Автокопирование в папку или iCloud Drive, экспорт и импорт JSON, восстановление.",
            summaryEN: "Auto backup to a folder or iCloud Drive, JSON export/import, restore.",
            bodyRU: [
                "В **Настройки → Управление данными** можно включить **автоматическое резервное копирование** в выбранную папку, например в iCloud Drive — копия будет обновляться сама.",
                "**Экспортировать историю** — сохраняет файл JSON с перепиской, вложениями и памятью (его можно сохранить в «Файлы» или отправить себе). **Импортировать историю** добавляет чаты и факты из такого файла.",
                "После переустановки приложения на первом экране нажмите **«Восстановить из резервной копии»** и выберите файл."
            ],
            bodyEN: [
                "In **Settings → Data management** you can turn on **automatic backup** to a folder of your choice, such as iCloud Drive — the copy updates by itself.",
                "**Export history** saves a JSON file with your conversations, attachments and memory (save it to Files or send it to yourself). **Import history** adds chats and facts from such a file.",
                "After reinstalling the app, tap **Restore from backup** on the first screen and choose the file."
            ],
            stepsRU: [
                "Откройте **Настройки → Управление данными**.",
                "Включите автокопирование и выберите папку в iCloud Drive.",
                "Или нажмите **«Экспортировать историю»** → **Сохранить в Файлы**."
            ],
            stepsEN: [
                "Open **Settings → Data management**.",
                "Turn on auto backup and choose a folder in iCloud Drive.",
                "Or tap **Export history** → **Save to Files**."
            ],
            tipsRU: [
                "Копия в iCloud Drive переживёт даже потерю телефона.",
                "Импорт не удаляет текущие чаты — он добавляет к ним сохранённые."
            ],
            tipsEN: [
                "A copy in iCloud Drive survives even losing your phone.",
                "Import doesn't delete current chats — it adds the saved ones."
            ],
            screenshot: "guide-backup",
            related: ["data-privacy", "first-launch"]
        )
    }

    fileprivate static var articlePermissions: HelpArticle {
        HelpArticle(
            id: "permissions", symbol: "hand.raised", tint: .orange,
            titleRU: "Разрешения", titleEN: "Permissions",
            summaryRU: "Камера, микрофон, фото, контакты, геопозиция, уведомления — зачем они нужны.",
            summaryEN: "Camera, microphone, photos, contacts, location, notifications — what they're for.",
            bodyRU: [
                "Страница **Настройки → Разрешения** показывает, к чему у приложения есть доступ, и позволяет выдать его:",
                "**Камера** — снимать фото и видео для чата. **Микрофон** — голосовой ввод и озвучка видео. **Фото** — выбирать снимки из альбома. **Контакты** — находить людей по имени. **Геопозиция** — знать ваш город и местное время (погода, «что рядом»). **Уведомления** — сообщать о готовом ответе.",
                "Разрешения нужны только для функций, которыми вы пользуетесь. Отключить доступ можно в настройках iPhone."
            ],
            bodyEN: [
                "**Settings → Permissions** shows what the app can access and lets you grant it:",
                "**Camera** — shoot photos and videos for the chat. **Microphone** — voice input and video voice-over. **Photos** — pick pictures from your album. **Contacts** — find people by name. **Location** — know your city and local time (weather, “what's nearby”). **Notifications** — tell you when an answer is ready.",
                "Permissions are only needed for features you use. You can revoke access in iPhone Settings."
            ],
            stepsRU: [
                "Откройте **Настройки → Разрешения**.",
                "Нажмите на нужное разрешение и подтвердите запрос iPhone."
            ],
            stepsEN: [
                "Open **Settings → Permissions**.",
                "Tap the permission and confirm the iPhone prompt."
            ],
            related: ["what-ai-knows", "data-privacy"]
        )
    }

    fileprivate static var articleWhatAIKnows: HelpArticle {
        HelpArticle(
            id: "what-ai-knows", symbol: "person.text.rectangle", tint: .teal,
            titleRU: "Что нейросеть знает о вас", titleEN: "What the assistant knows about you",
            summaryRU: "Имя, возраст, дата создания аккаунта, город, часовой пояс, модель iPhone.",
            summaryEN: "Name, age, account creation date, city, time zone, iPhone model.",
            bodyRU: [
                "Чтобы отвечать точнее, Honer AI знает: ваше **имя**; **дату рождения и возраст**, если вы их указали; **дату создания аккаунта** (первого запуска); **город** — только если разрешена геопозиция; **часовой пояс** и местное время; **модель устройства** (например, iPhone 15 Pro Max) и **версию iOS**.",
                "Плюс факты из **памяти**, которые вы видите и можете удалить. Больше ничего о вас нейросеть не знает — она не читает другие приложения, переписку или файлы без вашего явного вложения."
            ],
            bodyEN: [
                "To answer better, Honer AI knows: your **name**; your **birthday and age** if you set them; your **account creation date** (first launch); your **city** — only if location is allowed; your **time zone** and local time; the **device model** (e.g. iPhone 15 Pro Max) and **iOS version**.",
                "Plus facts from **memory**, which you can see and delete. It knows nothing else about you — it doesn't read other apps, your messages or files unless you attach them."
            ],
            tipsRU: [
                "Спросите «что ты обо мне знаешь?» — нейросеть перечислит всё сама."
            ],
            tipsEN: [
                "Ask “what do you know about me?” — the assistant lists it all."
            ],
            related: ["memory", "permissions", "first-launch"]
        )
    }
}

// MARK: - Содержание: родительский контроль

extension HelpLibrary {
    static var parentalSection: HelpSection {
        let items: [HelpArticle] = [articleParentalSetup, articleParentalFilters, articleParentalLimits]
        return HelpSection(id: "parental", symbol: "figure.2.and.child.holdinghands", tint: .green,
                           titleRU: "Родительский контроль", titleEN: "Parental controls", articles: items)
    }

    fileprivate static var articleParentalSetup: HelpArticle {
        HelpArticle(
            id: "parental-setup", symbol: "figure.2.and.child.holdinghands", tint: .green,
            titleRU: "Включение родительского контроля", titleEN: "Turning on parental controls",
            summaryRU: "Выключен по умолчанию, включается только родителем и защищён PIN-кодом.",
            summaryEN: "Off by default, turned on only by a parent and protected by a PIN.",
            bodyRU: [
                "Родительский контроль **выключен по умолчанию** и **никогда не включается автоматически**. Включить его может только взрослый в настройках приложения.",
                "При включении задаётся **PIN-код**. Без него ребёнок не сможет выключить контроль или изменить ограничения. После нескольких неверных попыток ввод PIN **временно блокируется**.",
                "Укажите **возраст ребёнка** — Honer AI будет отвечать проще и понятнее для этого возраста."
            ],
            bodyEN: [
                "Parental controls are **off by default** and **never turn on automatically**. Only an adult can enable them in the app settings.",
                "Turning them on sets a **PIN**. Without it, a child can't switch the controls off or change the limits. After several wrong attempts, PIN entry is **temporarily locked**.",
                "Set the **child's age** — Honer AI will answer more simply and clearly for that age."
            ],
            stepsRU: [
                "Откройте **Настройки → Родительский контроль**.",
                "Нажмите **«Включить»** и дважды введите PIN.",
                "Укажите возраст ребёнка и настройте фильтры и ограничения."
            ],
            stepsEN: [
                "Open **Settings → Parental controls**.",
                "Tap **Turn on** and enter a PIN twice.",
                "Set the child's age and configure filters and limits."
            ],
            tipsRU: [
                "Выберите PIN, который ребёнок не угадает: не дату рождения и не 1234.",
                "Запишите PIN в надёжном месте — без него настройки не изменить."
            ],
            tipsEN: [
                "Pick a PIN the child won't guess: not a birthday and not 1234.",
                "Keep the PIN somewhere safe — you can't change settings without it."
            ],
            demo: .parental,
            related: ["parental-filters", "parental-limits"]
        )
    }

    fileprivate static var articleParentalFilters: HelpArticle {
        HelpArticle(
            id: "parental-filters", symbol: "shield.lefthalf.filled", tint: .red,
            titleRU: "Фильтры содержимого", titleEN: "Content filters",
            summaryRU: "18+, насилие, наркотики и алкоголь, азартные игры, мат, самоповреждение, ненависть, знакомства.",
            summaryEN: "18+, violence, drugs and alcohol, gambling, profanity, self-harm, hate, dating.",
            bodyRU: [
                "Каждый фильтр включается отдельно: **18+**, **насилие**, **наркотики и алкоголь**, **азартные игры**, **ненормативная лексика**, **самоповреждение**, **ненависть и травля**, **знакомства**.",
                "При теме **самоповреждения** нейросеть не просто отказывает, а бережно поддерживает и показывает **телефон доверия**.",
                "Дополнительно можно задать **запрещённые слова** и **запрещённые сайты**, а также режим **«только разрешённые сайты»** — тогда нейросеть откроет лишь сайты из вашего списка."
            ],
            bodyEN: [
                "Each filter is turned on separately: **18+**, **violence**, **drugs and alcohol**, **gambling**, **profanity**, **self-harm**, **hate and bullying**, **dating**.",
                "For **self-harm** topics the assistant doesn't just refuse — it responds with care and shows a **helpline**.",
                "You can also set **blocked words** and **blocked sites**, and an **allowed sites only** mode — then the assistant opens only sites from your list."
            ],
            stepsRU: [
                "**Настройки → Родительский контроль**, раздел **«Фильтры контента»**.",
                "Включите нужные фильтры.",
                "Добавьте запрещённые слова и сайты."
            ],
            stepsEN: [
                "**Settings → Parental control**, section **Content filters**.",
                "Turn on the filters you need.",
                "Add blocked words and sites."
            ],
            related: ["parental-setup", "parental-limits"]
        )
    }

    fileprivate static var articleParentalLimits: HelpArticle {
        HelpArticle(
            id: "parental-limits", symbol: "hourglass", tint: .orange,
            titleRU: "Ограничения функций и времени", titleEN: "Feature and time limits",
            summaryRU: "Поиск, ссылки, рисование, игры, клонирование голоса, контакты, геопозиция; лимит времени и тихие часы.",
            summaryEN: "Search, links, drawing, games, voice cloning, contacts, location; daily limit and quiet hours.",
            bodyRU: [
                "Можно отключить отдельные функции: **поиск в интернете**, **открытие ссылок**, **рисование**, **игры** (каждую игру отдельно), **клонирование голоса**, доступ к **контактам** и **геопозиции**.",
                "**Дневной лимит времени** ограничивает, сколько минут в день можно пользоваться приложением. **Тихие часы** (например, с 22:00 до 7:00) закрывают доступ ночью."
            ],
            bodyEN: [
                "You can turn off individual features: **web search**, **opening links**, **drawing**, **games** (each game separately), **voice cloning**, access to **contacts** and **location**.",
                "A **daily time limit** caps how many minutes a day the app can be used. **Quiet hours** (e.g. 22:00 to 07:00) block access at night."
            ],
            stepsRU: [
                "**Настройки → Родительский контроль**, разделы **«Возможности»** и **«Разрешённые игры»**.",
                "Отключите лишнее.",
                "Задайте дневной лимит и тихие часы."
            ],
            stepsEN: [
                "**Settings → Parental control**, sections **Features** and **Allowed games**.",
                "Turn off what isn't needed.",
                "Set a daily limit and quiet hours."
            ],
            tipsRU: [
                "Для младшего школьника: поиск выключен, игры — только шахматы, лимит 1 час."
            ],
            tipsEN: [
                "For a young pupil: search off, games — chess only, a one-hour limit."
            ],
            related: ["parental-setup", "parental-filters"]
        )
    }
}

// MARK: - Содержание: оформление

extension HelpLibrary {
    static var appearanceSection: HelpSection {
        let items: [HelpArticle] = [articleAppearance, articleLanguage]
        return HelpSection(id: "appearance", symbol: "paintbrush", tint: .purple,
                           titleRU: "Оформление", titleEN: "Appearance", articles: items)
    }

    fileprivate static var articleAppearance: HelpArticle {
        HelpArticle(
            id: "appearance", symbol: "paintbrush", tint: .purple,
            titleRU: "Тема и размер шрифта", titleEN: "Theme and font size",
            summaryRU: "Тёмная, светлая или системная тема; крупнее или мельче текст.",
            summaryEN: "Dark, light or system theme; larger or smaller text.",
            bodyRU: [
                "**Настройки → Внешний вид**: **Тёмный**, **Светлый** или **Система**.",
                "**Настройки → Размер шрифта** увеличивает или уменьшает текст сообщений — удобно, если мелко читать."
            ],
            bodyEN: [
                "**Settings → Appearance**: **Dark**, **Light** or **System**.",
                "**Settings → Font size** makes message text larger or smaller — handy if it's hard to read."
            ],
            stepsRU: [
                "Откройте **☰ → профиль → Настройки**.",
                "Выберите **Внешний вид** или **Размер шрифта**."
            ],
            stepsEN: [
                "Open **☰ → profile → Settings**.",
                "Choose **Appearance** or **Font size**."
            ],
            screenshot: "guide-settings",
            related: ["language"]
        )
    }

    fileprivate static var articleLanguage: HelpArticle {
        HelpArticle(
            id: "language", symbol: "character.bubble", tint: .blue,
            titleRU: "Язык приложения", titleEN: "App language",
            summaryRU: "Русский или English — и язык ответов нейросети.",
            summaryEN: "Russian or English — and the assistant's reply language.",
            bodyRU: [
                "**Настройки → Язык**: **Русский** или **English**. Меняется интерфейс, и нейросеть отвечает на выбранном языке.",
                "Язык ответа можно поменять и в самом чате: «отвечай по-английски» или закрепите это как инструкцию."
            ],
            bodyEN: [
                "**Settings → Language**: **Русский** or **English**. The interface changes and the assistant answers in the chosen language.",
                "You can also change the reply language in a chat: “answer in Russian”, or pin that as an instruction."
            ],
            stepsRU: [
                "**Настройки → Язык**.",
                "Выберите язык."
            ],
            stepsEN: [
                "**Settings → Language**.",
                "Choose a language."
            ],
            related: ["appearance", "pinned-instructions", "first-launch"]
        )
    }
}

// MARK: - Содержание: вопросы и ответы

extension HelpLibrary {
    static var faqBasics: [HelpFAQ] {
        let items: [HelpFAQ] = [
            HelpFAQ(questionRU: "Почему ответ пришёл на английском?",
                    questionEN: "Why did the answer come in Russian?",
                    answerRU: "Honer AI отвечает на языке приложения. Проверьте **Настройки → Язык** — там должно быть «Русский». Также нейросеть может перейти на английский, если вы написали по-английски, вставили английский текст или в чате закреплена инструкция «отвечай по-английски». Напишите «отвечай по-русски» — и она переключится.",
                    answerEN: "Honer AI answers in the app language. Check **Settings → Language** — it should say English. The assistant may also switch to Russian if you wrote in Russian, pasted Russian text or pinned an instruction like “answer in Russian”. Write “answer in English” and it will switch.",
                    screenshot: "guide-settings"),
            HelpFAQ(questionRU: "Как заставить нейросеть искать в интернете?",
                    questionEN: "How do I make the assistant search the web?",
                    answerRU: "Включите кнопку **«Поиск»** под полем ввода. Это даёт нейросети возможность выходить в сеть, но решение она принимает сама. Чтобы поиск был наверняка, скажите прямо: «найди в интернете», «проверь в сети свежие данные».",
                    answerEN: "Turn on the **Search** button below the message field. It gives the assistant the ability to go online, but it decides by itself. To make sure it searches, say so: “search the web”, “check the latest data online”.",
                    screenshot: "guide-search"),
            HelpFAQ(questionRU: "Что будет, если не ответить на вопрос за 10 секунд?",
                    questionEN: "What happens if I don't answer a question within 10 seconds?",
                    answerRU: "Карточка вопроса закроется, и Honer AI **решит сам** — выберет самый разумный вариант и продолжит работу. Ничего не сломается. Если выбор не понравился, просто напишите, что хотели другое.",
                    answerEN: "The question card closes and Honer AI **decides by itself** — it picks the most sensible option and carries on. Nothing breaks. If you don't like its choice, just say what you wanted instead.",
                    demo: .questionTimer),
            HelpFAQ(questionRU: "Работает ли приложение без интернета?",
                    questionEN: "Does the app work offline?",
                    answerRU: "Для ответов нейросети **нужен интернет** — модель работает на сервере. Без сети доступны: история чатов и поиск по ней, просмотр вложений, **редактор фото и видео**, **игры**, настройки и память.",
                    answerEN: "The assistant's answers **need the internet** — the model runs on a server. Offline you still have chat history and search, attachment viewing, the **photo and video editor**, **games**, settings and memory."),
            HelpFAQ(questionRU: "Сколько сайтов может прочитать нейросеть?",
                    questionEN: "How many sites can the assistant read?",
                    answerRU: "Для обычного вопроса — несколько страниц. Для исследования — **сотни и даже тысячи**: попросите, например, «изучи 500 сайтов». Во время работы виден счётчик «Прочитано N из M сайтов».",
                    answerEN: "For a normal question, a few pages. For research — **hundreds or even thousands**: ask, for example, “study 500 sites”. A “Read N of M sites” counter shows progress.",
                    demo: .webResearch),
            HelpFAQ(questionRU: "Можно ли читать закрытые страницы Instagram или Facebook?",
                    questionEN: "Can it read private Instagram or Facebook pages?",
                    answerRU: "**Нет.** Honer AI работает только с **публичными** страницами и открытыми API и никогда не входит в ваши аккаунты. Закрытые профили, личные сообщения, приватные группы и каналы недоступны в любой соцсети.",
                    answerEN: "**No.** Honer AI only works with **public** pages and open APIs and never signs in to your accounts. Private profiles, direct messages, closed groups and channels are unavailable on every social network."),
            HelpFAQ(questionRU: "Как установить приложение другу?",
                    questionEN: "How can I install the app for a friend?",
                    answerRU: "Honer AI не распространяется через App Store: приложение устанавливается через рассылку владельца приложения. Чтобы установить его другу, обратитесь к владельцу — он добавит новое устройство.",
                    answerEN: "Honer AI isn't distributed through the App Store: it's installed through the app owner's distribution. To install it for a friend, contact the owner — they'll add the new device."),
            HelpFAQ(questionRU: "Нейросеть ошиблась. Что делать?",
                    questionEN: "The assistant made a mistake. What should I do?",
                    answerRU: "Скажите, в чём ошибка, — она исправится. Можно нажать **«Повторить»** под ответом, включить **«Рассуждение»** для сложной задачи или **«Поиск»** для свежих фактов. Важные сведения (здоровье, деньги, право) всегда перепроверяйте.",
                    answerEN: "Say what's wrong and it will correct itself. You can tap **Regenerate** under the answer, turn on **Reasoning** for a hard task or **Search** for current facts. Always double-check important information (health, money, law).")
        ]
        return items
    }

    static var faqChats: [HelpFAQ] {
        let items: [HelpFAQ] = [
            HelpFAQ(questionRU: "Как перетащить чат?",
                    questionEN: "How do I drag a chat?",
                    answerRU: "Откройте **☰**, нажмите на чат и удерживайте, пока он не приподнимется, затем перетащите в раздел **«Закреплено»** — чат закрепится. Закреплённые чаты так же перетаскиваются вверх-вниз, чтобы поменять порядок.",
                    answerEN: "Open **☰**, press and hold a chat until it lifts, then drag it into **Pinned** — the chat gets pinned. Pinned chats can be dragged up and down the same way to reorder them.",
                    demo: .dragChat),
            HelpFAQ(questionRU: "Как вернуть чат из архива?",
                    questionEN: "How do I restore a chat from the archive?",
                    answerRU: "Откройте **Настройки → Архив чатов**, найдите чат и нажмите **«Восстановить»**. Он снова появится в боковой панели со всей перепиской.",
                    answerEN: "Open **Settings → Archived chats**, find the chat and tap **Restore**. It reappears in the sidebar with the whole conversation."),
            HelpFAQ(questionRU: "Как скопировать часть ответа?",
                    questionEN: "How do I copy part of an answer?",
                    answerRU: "Долго нажмите на ответ → **«Выбрать текст»**, выделите нужный кусок маркерами и нажмите **«Копировать»**. Там же есть **«Спросить Honer AI»**, чтобы задать вопрос именно об этом фрагменте.",
                    answerEN: "Long-press the answer → **Select text**, drag the handles over the part you need and tap **Copy**. The same menu has **Ask Honer AI** to ask about that exact fragment.",
                    demo: .selectAsk),
            HelpFAQ(questionRU: "Как найти старый разговор?",
                    questionEN: "How do I find an old conversation?",
                    answerRU: "Откройте **☰** и введите слово в **«Поиск в содержимом…»** — поиск идёт по тексту всех сообщений, а не только по названиям.",
                    answerEN: "Open **☰** and type a word into **Search content…** — it searches the text of every message, not just titles.",
                    screenshot: "guide-history"),
            HelpFAQ(questionRU: "Как начать разговор заново с середины?",
                    questionEN: "How do I restart a conversation from the middle?",
                    answerRU: "Долго нажмите на сообщение → **«Продолжить в ветке»**. Появится новый чат «Ветка · …» с перепиской до этого места, а исходный чат останется как был.",
                    answerEN: "Long-press a message → **Continue in a branch**. A new “Branch · …” chat appears with the conversation up to that point, and the original stays as it was.",
                    screenshot: "guide-branches"),
            HelpFAQ(questionRU: "Как удалить сразу несколько чатов?",
                    questionEN: "How do I delete several chats at once?",
                    answerRU: "В боковой панели нажмите кнопку выбора (значок со списком справа от «Закреплено»), отметьте чаты и выберите **«Удалить»** или **«В архив»**.",
                    answerEN: "In the sidebar tap the select button (the list icon to the right of Pinned), tick the chats and choose **Delete** or **Archive**."),
            HelpFAQ(questionRU: "Где найти все фото и ссылки из чата?",
                    questionEN: "Where can I find all photos and links from a chat?",
                    answerRU: "**••• → Информация о чате**: там все фото, видео, голосовые, музыка, файлы и посещённые ссылки — ваши и нейросети, с фильтрами, поиском и хронологией.",
                    answerEN: "**••• → Chat info**: every photo, video, voice note, music track, file and visited link — yours and the assistant's — with filters, search and a timeline."),
            HelpFAQ(questionRU: "Что за полоски у правого края экрана?",
                    questionEN: "What are the lines at the right edge of the screen?",
                    answerRU: "Это линии навигации: каждая — одно сообщение. Нажмите и удерживайте, чтобы увидеть предпросмотр, ведите пальцем и отпустите, чтобы перейти к сообщению.",
                    answerEN: "Those are navigation lines: one per message. Press and hold to preview, slide your finger and let go to jump to that message.",
                    screenshot: "guide-navigation")
        ]
        return items
    }

    static var faqFiles: [HelpFAQ] {
        let items: [HelpFAQ] = [
            HelpFAQ(questionRU: "Нейросеть не видит мои файлы Excel?",
                    questionEN: "The assistant can't see my Excel files?",
                    answerRU: "Excel (xlsx) и CSV поддерживаются и читаются как таблицы. Проверьте: файл прикреплён через **+ → Файл** и видна карточка над полем ввода; файл не защищён паролем; для книги с несколькими листами укажите нужный лист. Старый формат .xls лучше пересохранить в .xlsx.",
                    answerEN: "Excel (xlsx) and CSV are supported and read as tables. Check that: the file was attached via **+ → File** and its card shows above the message field; it isn't password-protected; for a multi-sheet workbook you named the sheet. Re-save old .xls files as .xlsx.",
                    screenshot: "guide-attachments"),
            HelpFAQ(questionRU: "Какие файлы можно отправить?",
                    questionEN: "Which files can I send?",
                    answerRU: "Фото, видео (до 2 минут и 40 МБ), голосовые и аудио, PDF, Word, Excel, CSV, PowerPoint, OpenDocument, RTF, HTML, EPUB, Jupyter и любые файлы с кодом.",
                    answerEN: "Photos, videos (up to 2 minutes and 40 MB), voice notes and audio, PDF, Word, Excel, CSV, PowerPoint, OpenDocument, RTF, HTML, EPUB, Jupyter and any code files."),
            HelpFAQ(questionRU: "Нейросеть правда смотрит видео?",
                    questionEN: "Does the assistant really watch videos?",
                    answerRU: "Да: она просматривает ключевые кадры и слушает звуковую дорожку — речь распознаётся прямо на iPhone. Очень быстрые детали между кадрами могут быть пропущены.",
                    answerEN: "Yes: it looks at key frames and listens to the soundtrack — speech is recognised right on the iPhone. Very quick details between frames can be missed."),
            HelpFAQ(questionRU: "Как убрать фон с фото?",
                    questionEN: "How do I remove a photo's background?",
                    answerRU: "Откройте фото → **«Редактировать» → Фон → Удалить фон**. Или просто напишите нейросети «убери фон» вместе с фото. Затем можно поставить цвет, размытие, градиент или другое фото.",
                    answerEN: "Open the photo → **Edit → Background → Remove background**. Or just send the photo with “remove the background”. Then add a colour, blur, gradient or another photo.",
                    demo: .photoEditor),
            HelpFAQ(questionRU: "Как отредактировать таблицу, которую сделала нейросеть?",
                    questionEN: "How do I edit a table the assistant made?",
                    answerRU: "Нажмите на таблицу — она откроется на весь экран. Нажмите на ячейку, чтобы изменить значение, используйте кнопки добавления строк и столбцов. Нейросеть увидит ваши правки. Если таблица только для чтения, попросите «сделай её редактируемой».",
                    answerEN: "Tap the table to open it full screen. Tap a cell to change it and use the buttons to add rows and columns. The assistant sees your edits. If the table is read-only, ask it to “make it editable”.",
                    demo: .table),
            HelpFAQ(questionRU: "Голосовой ввод плохо распознаёт речь. Что делать?",
                    questionEN: "Voice input doesn't recognise me well. What can I do?",
                    answerRU: "Выберите правильный язык в **Настройки → Основной язык**, говорите ближе к микрофону и без сильного шума. Проверьте, что у приложения есть доступ к микрофону (**Настройки → Разрешения**).",
                    answerEN: "Choose the right language in **Settings → Speech language**, speak closer to the mic and avoid loud noise. Make sure the app has microphone access (**Settings → Permissions**).",
                    demo: .voice),
            HelpFAQ(questionRU: "Голос озвучки звучит как робот.",
                    questionEN: "The reading voice sounds robotic.",
                    answerRU: "Скачайте улучшенный голос: настройки iPhone → Универсальный доступ → Устный контент → Голоса → Русский → «Милена» (Улучшенный); для английского — «Ava» (Улучшенный). Приложение выберет его само.",
                    answerEN: "Download an enhanced voice: iPhone Settings → Accessibility → Spoken Content → Voices → English → “Ava” (Enhanced); for Russian — “Milena” (Enhanced). The app picks it automatically.",
                    screenshot: "guide-voice-settings"),
            HelpFAQ(questionRU: "Почему ответ начинает читаться вслух сам?",
                    questionEN: "Why does the answer start reading aloud by itself?",
                    answerRU: "Включён динамик вверху экрана — автоматическое чтение. Нажмите на него, чтобы он стал перечёркнутым, и озвучка выключится.",
                    answerEN: "The speaker at the top of the screen — auto read-aloud — is on. Tap it so it's crossed out and reading stops.")
        ]
        return items
    }

    static var faqMore: [HelpFAQ] {
        let items: [HelpFAQ] = [
            HelpFAQ(questionRU: "Как удалить факт из памяти?",
                    questionEN: "How do I delete a fact from memory?",
                    answerRU: "Откройте **Настройки → Память Honer AI**, смахните факт влево и нажмите **«Удалить»**. Или напишите в чате «забудь, что …».",
                    answerEN: "Open **Settings → Honer AI memory**, swipe the fact left and tap **Delete**. Or write in the chat “forget that …”.",
                    screenshot: "guide-memory"),
            HelpFAQ(questionRU: "Чем память отличается от инструкций?",
                    questionEN: "How is memory different from instructions?",
                    answerRU: "Память — факты о вас, общие для всех чатов. Инструкции — правила ответа (язык, стиль, роль), которые действуют только в том чате, где закреплены.",
                    answerEN: "Memory is facts about you, shared by all chats. Instructions are answer rules (language, style, role) that apply only in the chat where they're pinned.",
                    screenshot: "guide-pinned-instruction"),
            HelpFAQ(questionRU: "Как перенести чаты на новый iPhone?",
                    questionEN: "How do I move chats to a new iPhone?",
                    answerRU: "На старом телефоне: **Настройки → Управление данными → Экспортировать историю** и сохраните файл в iCloud Drive (или включите автокопирование). На новом: на первом экране **«Восстановить из резервной копии»** и выберите файл.",
                    answerEN: "On the old phone: **Settings → Data management → Export history** and save the file to iCloud Drive (or turn on auto backup). On the new one: tap **Restore from backup** on the first screen and pick the file.",
                    screenshot: "guide-backup"),
            HelpFAQ(questionRU: "Ребёнок может сам выключить родительский контроль?",
                    questionEN: "Can a child turn parental controls off?",
                    answerRU: "Нет: для выключения и изменения настроек нужен PIN. После нескольких неверных попыток ввод временно блокируется. Контроль выключен по умолчанию и сам никогда не включается.",
                    answerEN: "No: switching off or changing settings requires the PIN. After several wrong attempts, entry is temporarily locked. Controls are off by default and never turn on by themselves.",
                    demo: .parental),
            HelpFAQ(questionRU: "Не приходят уведомления о готовом ответе.",
                    questionEN: "I don't get notifications when an answer is ready.",
                    answerRU: "Разрешите уведомления: настройки iPhone → Honer AI → Уведомления. Также проверьте режим «Фокус» и что в **Настройки → Разрешения** уведомления включены.",
                    answerEN: "Allow notifications: iPhone Settings → Honer AI → Notifications. Also check Focus mode and that notifications are enabled in **Settings → Permissions**."),
            HelpFAQ(questionRU: "Откуда нейросеть знает мой город и модель телефона?",
                    questionEN: "How does the assistant know my city and phone model?",
                    answerRU: "Город — из геопозиции, только если вы её разрешили. Модель iPhone, версию iOS и часовой пояс приложение берёт из системы, чтобы давать точные советы. Больше о вас нейросеть ничего не знает, кроме имени, даты рождения (если указана) и фактов из памяти.",
                    answerEN: "Your city comes from location, only if you allowed it. The iPhone model, iOS version and time zone come from the system so advice is accurate. Beyond that it only knows your name, birthday (if set) and memory facts."),
            HelpFAQ(questionRU: "Может ли нейросеть что-то купить или заказать?",
                    questionEN: "Can the assistant buy or order things?",
                    answerRU: "Нет. Она ищет товары на Wildberries, Ozon и Avito, сравнивает цены и даёт ссылки, но покупку вы делаете сами на сайте магазина.",
                    answerEN: "No. It searches Wildberries, Ozon and Avito, compares prices and gives links, but you make the purchase yourself on the store's site."),
            HelpFAQ(questionRU: "Как сыграть с нейросетью в шахматы?",
                    questionEN: "How do I play chess with the assistant?",
                    answerRU: "Напишите «давай сыграем в шахматы» — игра откроется. Также доступны шашки, «Дурак» и «Удача». Результат партии появится в чате.",
                    answerEN: "Write “let's play chess” — the game opens. Checkers, Durak and Luck are available too. The result appears in the chat.",
                    screenshot: "guide-games")
        ]
        return items
    }
}

// MARK: - Содержание: лайфхаки

extension HelpLibrary {
    static var lifehacksPartOne: [HelpLifehack] {
        let items: [HelpLifehack] = [
            HelpLifehack(id: "hack-voice-chat", symbol: "waveform", tint: .red,
                         titleRU: "Разговор голосом", titleEN: "Voice conversation",
                         textRU: "Включите динамик вверху и отвечайте микрофоном — получится живой диалог без рук: вы говорите, Honer AI отвечает вслух.",
                         textEN: "Turn on the speaker at the top and reply with the microphone — a hands-free dialogue: you talk, Honer AI answers aloud."),
            HelpLifehack(id: "hack-helper-chats", symbol: "pin", tint: .orange,
                         titleRU: "Чаты-помощники", titleEN: "Helper chats",
                         textRU: "Создайте чат «Переводчик» и закрепите инструкцию «переводи всё, что я пишу, на английский». Закрепите сам чат — и он всегда под рукой.",
                         textEN: "Create a “Translator” chat and pin the instruction “translate everything I write into Russian”. Pin the chat itself so it's always at hand."),
            HelpLifehack(id: "hack-lecture", symbol: "mic.badge.plus", tint: .purple,
                         titleRU: "Конспект лекции", titleEN: "Lecture notes",
                         textRU: "Запишите лекцию на диктофон, отправьте файл и попросите «конспект по пунктам + 5 вопросов для самопроверки».",
                         textEN: "Record a lecture, send the file and ask for “bullet-point notes + 5 self-test questions”."),
            HelpLifehack(id: "hack-exam", symbol: "graduationcap", tint: .green,
                         titleRU: "Подготовка к экзамену", titleEN: "Exam prep",
                         textRU: "Прикрепите учебник в PDF и попросите тест из 20 вопросов по главе — ответы проверятся мгновенно, а ошибки нейросеть разберёт.",
                         textEN: "Attach a PDF textbook and ask for a 20-question test on a chapter — answers are scored instantly and mistakes explained."),
            HelpLifehack(id: "hack-receipt", symbol: "doc.text.viewfinder", tint: .teal,
                         titleRU: "Чек → таблица", titleEN: "Receipt → table",
                         textRU: "Сфотографируйте чек и попросите «сделай редактируемую таблицу расходов» — потом её можно поправить и выгрузить в CSV.",
                         textEN: "Photograph a receipt and ask for “an editable expenses table” — then tweak it and export to CSV."),
            HelpLifehack(id: "hack-compare-branches", symbol: "arrow.triangle.branch", tint: .indigo,
                         titleRU: "Два варианта сразу", titleEN: "Two versions at once",
                         textRU: "Не уверены, какой стиль письма лучше? Создайте ветку: в одной попросите официальный тон, в другой — дружеский, и сравните.",
                         textEN: "Not sure which writing style is better? Make a branch: ask for a formal tone in one and a friendly one in the other, then compare."),
            HelpLifehack(id: "hack-ask-fragment", symbol: "text.cursor", tint: .pink,
                         titleRU: "Разбор по кусочкам", titleEN: "Piece by piece",
                         textRU: "В сложном тексте выделяйте непонятные термины и жмите «Спросить Honer AI» — объяснение придёт именно по выделенному.",
                         textEN: "In a complex text, select unclear terms and tap “Ask Honer AI” — you get an explanation of exactly that part.")
        ]
        return items
    }

    static var lifehacksPartTwo: [HelpLifehack] {
        let items: [HelpLifehack] = [
            HelpLifehack(id: "hack-research-background", symbol: "moon.zzz", tint: .blue,
                         titleRU: "Исследование в фоне", titleEN: "Research in the background",
                         textRU: "Запустите «изучи 500 сайтов о …» и сверните приложение. Когда сводка будет готова, придёт уведомление.",
                         textEN: "Start “study 500 sites about …” and leave the app. A notification arrives when the summary is ready."),
            HelpLifehack(id: "hack-avatar", symbol: "person.crop.square", tint: .pink,
                         titleRU: "Аватарка за минуту", titleEN: "An avatar in a minute",
                         textRU: "Селфи → «убери фон, поставь градиент и обрежь квадратом» — готовая аватарка без сторонних приложений.",
                         textEN: "Selfie → “remove the background, add a gradient and crop square” — a ready avatar without other apps."),
            HelpLifehack(id: "hack-shopping", symbol: "cart", tint: .purple,
                         titleRU: "Выгодная покупка", titleEN: "Smart shopping",
                         textRU: "«Сравни цены на Wildberries, Ozon и Avito на … и сделай таблицу: цена, рейтинг, ссылка» — сразу видно, где дешевле.",
                         textEN: "“Compare prices on Wildberries, Ozon and Avito for … as a table: price, rating, link” — you instantly see where it's cheaper."),
            HelpLifehack(id: "hack-youtube", symbol: "play.tv", tint: .red,
                         titleRU: "Час видео за минуту", titleEN: "An hour of video in a minute",
                         textRU: "Вставьте ссылку на длинное видео YouTube и попросите «главные мысли с таймкодами».",
                         textEN: "Paste a long YouTube link and ask for “key ideas with timestamps”."),
            HelpLifehack(id: "hack-memory", symbol: "brain", tint: .pink,
                         titleRU: "Скажите один раз", titleEN: "Say it once",
                         textRU: "«Запомни: я вегетарианец и живу в Самаре» — и все рецепты и советы дальше будут это учитывать, в любом чате.",
                         textEN: "“Remember: I'm vegetarian and live in Samara” — every recipe and tip from then on takes it into account, in any chat."),
            HelpLifehack(id: "hack-reactions", symbol: "hand.thumbsdown", tint: .yellow,
                         titleRU: "Реакция вместо слов", titleEN: "React instead of typing",
                         textRU: "Поставьте 👎 на неудачный ответ — нейросеть это увидит и в следующий раз объяснит иначе.",
                         textEN: "Put 👎 on a weak answer — the assistant sees it and explains differently next time."),
            HelpLifehack(id: "hack-diagram", symbol: "point.3.connected.trianglepath.dotted", tint: .orange,
                         titleRU: "Схема вместо текста", titleEN: "A diagram instead of text",
                         textRU: "Попросите «нарисуй диаграмму mermaid процесса» — сложная последовательность шагов станет наглядной картинкой.",
                         textEN: "Ask to “draw a mermaid diagram of the process” — a complex sequence of steps becomes a clear picture."),
            HelpLifehack(id: "hack-quick-jump", symbol: "line.3.horizontal", tint: .indigo,
                         titleRU: "Быстрый переход", titleEN: "Quick jump",
                         textRU: "В длинном чате нажмите и удерживайте линии справа — пролистайте сотни сообщений одним движением пальца.",
                         textEN: "In a long chat, press and hold the lines on the right — scroll through hundreds of messages with one finger movement.")
        ]
        return items
    }
}

// MARK: - Главный экран руководства

struct HelpCenterView: View {
    @EnvironmentObject private var settings: AppSettings
    @State private var query: String = ""
    @State private var category: String = HelpLibrary.allCategory

    private var english: Bool { settings.language == .english }
    private var trimmedQuery: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HelpHeroHeader()
                HelpSearchField(text: $query, placeholder: searchPlaceholder)
                if trimmedQuery.isEmpty {
                    HelpCategoryBar(selected: $category, english: english)
                }
                content
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 40)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(HonorTheme.background.ignoresSafeArea())
        .navigationTitle(settings.text("Руководство Honer AI", "Honer AI Guide"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("help.page")
    }

    private var searchPlaceholder: String {
        settings.text("Поиск: таблицы, память, фон…", "Search: tables, memory, background…")
    }

    @ViewBuilder private var content: some View {
        if trimmedQuery.isEmpty {
            HelpBrowseContent(category: category, english: english)
        } else {
            HelpSearchResults(
                query: trimmedQuery,
                articles: HelpLibrary.search(trimmedQuery),
                faqs: HelpLibrary.faqEntries(matching: trimmedQuery),
                hacks: HelpLibrary.searchLifehacks(trimmedQuery),
                english: english)
        }
    }
}

// MARK: Шапка

struct HelpHeroHeader: View {
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var visible: Bool = false

    var body: some View {
        ZStack {
            HelpHeroBackground(animated: visible && !reduceMotion)
            heroContent
        }
        .frame(maxWidth: .infinity)
        .frame(minHeight: 236)
        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).stroke(Color.white.opacity(0.14), lineWidth: 1))
        .shadow(color: HonorTheme.accent.opacity(0.28), radius: 18, x: 0, y: 8)
        .onAppear { visible = true }
        .onDisappear { visible = false }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("help.hero")
    }

    private var heroContent: some View {
        VStack(spacing: 10) {
            logo
            Text("Honer AI")
                .font(.system(size: 32, weight: .heavy, design: .rounded))
                .foregroundColor(.white)
            versionBadge
            Text(tagline)
                .font(.system(size: 15, weight: .medium))
                .foregroundColor(Color.white.opacity(0.92))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 24)
        .padding(.horizontal, 20)
    }

    private var logo: some View {
        HonorMark(size: 54)
            .padding(16)
            .background(Circle().fill(Color.white.opacity(0.16)))
            .overlay(Circle().stroke(Color.white.opacity(0.35), lineWidth: 1))
            .shadow(color: Color.black.opacity(0.25), radius: 10, x: 0, y: 5)
    }

    private var versionBadge: some View {
        Text(versionLine)
            .font(.system(size: 13, weight: .semibold))
            .foregroundColor(Color.white.opacity(0.9))
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(Capsule().fill(Color.white.opacity(0.16)))
    }

    private var versionLine: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—"
        return settings.text("Руководство · версия \(version)", "Guide · version \(version)")
    }

    private var tagline: String {
        settings.text("Всё, что умеет ваш ИИ-помощник, — с картинками, мини-видео и лайфхаками.",
                      "Everything your AI assistant can do — with pictures, mini-videos and tips.")
    }
}

struct HelpHeroBackground: View {
    let animated: Bool

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: !animated)) { context in
            HelpHeroGradient(phase: animated ? context.date.timeIntervalSinceReferenceDate : 0)
        }
    }
}

struct HelpHeroGradient: View {
    let phase: Double

    private var angle: Double { phase * 0.35 }

    private var startPoint: UnitPoint {
        UnitPoint(x: CGFloat(0.5 + 0.5 * cos(angle)), y: CGFloat(0.5 + 0.5 * sin(angle)))
    }

    private var endPoint: UnitPoint {
        UnitPoint(x: CGFloat(0.5 - 0.5 * cos(angle)), y: CGFloat(0.5 - 0.5 * sin(angle)))
    }

    private var colors: [Color] {
        [Color(red: 0.22, green: 0.36, blue: 0.95), Color(red: 0.52, green: 0.33, blue: 0.96), Color(red: 0.13, green: 0.68, blue: 0.93)]
    }

    var body: some View {
        ZStack {
            LinearGradient(colors: colors, startPoint: startPoint, endPoint: endPoint)
            blob(Color(red: 0.35, green: 0.9, blue: 1.0), dx: 110 * cos(phase * 0.5), dy: -60 + 30 * sin(phase * 0.7))
            blob(Color(red: 0.95, green: 0.45, blue: 0.9), dx: -120 + 40 * sin(phase * 0.4), dy: 70 * cos(phase * 0.6))
        }
    }

    private func blob(_ color: Color, dx: Double, dy: Double) -> some View {
        Circle()
            .fill(color.opacity(0.45))
            .frame(width: 180, height: 180)
            .blur(radius: 45)
            .offset(x: CGFloat(dx), y: CGFloat(dy))
    }
}

// MARK: Поиск и категории

struct HelpSearchField: View {
    @Binding var text: String
    let placeholder: String
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .foregroundColor(HonorTheme.secondary)
            field
            if !text.isEmpty { clearButton }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(HonorTheme.surface))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous)
            .stroke(focused ? HonorTheme.accent : HonorTheme.divider, lineWidth: focused ? 1.4 : 0.8))
        .animation(.easeOut(duration: 0.2), value: focused)
    }

    private var field: some View {
        TextField(placeholder, text: $text)
            .focused($focused)
            .submitLabel(.search)
            .autocorrectionDisabled(true)
            .font(.system(size: 17))
            .foregroundColor(HonorTheme.foreground)
            .accessibilityIdentifier("help.search")
    }

    private var clearButton: some View {
        Button { text = "" } label: {
            Image(systemName: "xmark.circle.fill")
                .foregroundColor(HonorTheme.secondary)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Clear")
        .accessibilityIdentifier("help.search.clear")
    }
}

struct HelpCategoryBar: View {
    @Binding var selected: String
    let english: Bool

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(HelpLibrary.chips) { chip in
                    HelpChipButton(chip: chip, english: english, isSelected: chip.id == selected) {
                        withAnimation(.easeInOut(duration: 0.22)) { selected = chip.id }
                    }
                }
            }
            .padding(.vertical, 2)
        }
    }
}

struct HelpChipButton: View {
    let chip: HelpChip
    let english: Bool
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: chip.symbol).font(.system(size: 13, weight: .semibold))
                Text(chip.title(english)).font(.system(size: 14, weight: .semibold))
            }
            .foregroundColor(isSelected ? Color.white : HonorTheme.foreground)
            .padding(.horizontal, 13)
            .padding(.vertical, 8)
            .background(Capsule().fill(isSelected ? HonorTheme.accent : HonorTheme.surface))
            .overlay(Capsule().stroke(isSelected ? Color.clear : HonorTheme.divider, lineWidth: 0.8))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("help.chip.\(chip.id)")
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

// MARK: Списки

struct HelpBrowseContent: View {
    let category: String
    let english: Bool

    private var visibleSections: [HelpSection] {
        if category == HelpLibrary.allCategory { return HelpLibrary.sections }
        return HelpLibrary.sections.filter { $0.id == category }
    }

    private var showLifehacks: Bool {
        category == HelpLibrary.allCategory || category == HelpLibrary.lifehacksCategory
    }

    private var showFAQ: Bool {
        category == HelpLibrary.allCategory || category == HelpLibrary.faqCategory
    }

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 28) {
            ForEach(visibleSections) { section in
                HelpSectionBlock(section: section, english: english)
            }
            if showLifehacks {
                HelpLifehacksBlock(items: HelpLibrary.lifehacks, english: english)
            }
            if showFAQ {
                HelpFAQBlock(entries: HelpLibrary.faqEntries(matching: ""), english: english)
            }
        }
    }
}

struct HelpSearchResults: View {
    let query: String
    let articles: [HelpArticle]
    let faqs: [HelpIndexedFAQ]
    let hacks: [HelpLifehack]
    let english: Bool

    private var isEmpty: Bool { articles.isEmpty && faqs.isEmpty && hacks.isEmpty }

    var body: some View {
        VStack(alignment: .leading, spacing: 26) {
            if isEmpty { HelpEmptyResults(query: query, english: english) }
            if !articles.isEmpty { articleBlock }
            if !hacks.isEmpty { HelpLifehacksBlock(items: hacks, english: english) }
            if !faqs.isEmpty { HelpFAQBlock(entries: faqs, english: english) }
        }
    }

    private var articleBlock: some View {
        VStack(alignment: .leading, spacing: 10) {
            HelpBlockHeader(symbol: "doc.text.magnifyingglass", tint: HonorTheme.accent,
                            title: english ? "Articles" : "Статьи", count: articles.count)
            ForEach(articles) { article in
                HelpArticleLink(article: article, english: english)
            }
        }
    }
}

struct HelpEmptyResults: View {
    let query: String
    let english: Bool

    private var headline: String {
        if english { return "Nothing found for “" + query + "”" }
        return "По запросу «" + query + "» ничего не найдено"
    }

    private var hint: String {
        if english { return "Try a different word — for example “photo”, “memory” or “table”." }
        return "Попробуйте другое слово — например «фото», «память» или «таблица»."
    }

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: "doc.text.magnifyingglass")
                .font(.system(size: 34))
                .foregroundColor(HonorTheme.secondary)
            Text(headline)
                .font(.system(size: 17, weight: .semibold))
                .foregroundColor(HonorTheme.foreground)
                .multilineTextAlignment(.center)
            Text(hint)
                .font(.system(size: 14))
                .foregroundColor(HonorTheme.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 36)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("help.search.empty")
    }
}

struct HelpBlockHeader: View {
    let symbol: String
    let tint: Color
    let title: String
    let count: Int

    var body: some View {
        HStack(spacing: 10) {
            HelpIconBadge(symbol: symbol, tint: tint, size: 30)
            Text(title)
                .font(.system(size: 21, weight: .bold, design: .rounded))
                .foregroundColor(HonorTheme.foreground)
            Spacer(minLength: 8)
            Text("\(count)")
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(HonorTheme.secondary)
                .padding(.horizontal, 9)
                .padding(.vertical, 3)
                .background(Capsule().fill(HonorTheme.raised))
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}

struct HelpIconBadge: View {
    let symbol: String
    let tint: Color
    var size: CGFloat = 44

    var body: some View {
        ZStack {
            Circle().fill(tint.opacity(0.18))
            Circle().stroke(tint.opacity(0.35), lineWidth: 0.8)
            Image(systemName: symbol)
                .font(.system(size: size * 0.44, weight: .semibold))
                .foregroundColor(tint)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

struct HelpSectionBlock: View {
    let section: HelpSection
    let english: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HelpBlockHeader(symbol: section.symbol, tint: section.tint,
                            title: section.title(english), count: section.articles.count)
            ForEach(section.articles) { article in
                HelpArticleLink(article: article, english: english)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("help.section.\(section.id)")
    }
}

struct HelpArticleLink: View {
    let article: HelpArticle
    let english: Bool

    var body: some View {
        NavigationLink {
            HelpArticleView(article: article)
        } label: {
            HelpArticleCard(article: article, english: english)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("help.article.\(article.id)")
    }
}

struct HelpArticleCard: View {
    let article: HelpArticle
    let english: Bool

    var body: some View {
        HStack(alignment: .center, spacing: 14) {
            HelpIconBadge(symbol: article.symbol, tint: article.tint, size: 44)
            texts
            Spacer(minLength: 4)
            badges
            Image(systemName: "chevron.right")
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(HonorTheme.secondary)
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(HonorTheme.surface))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(HonorTheme.divider, lineWidth: 0.7))
        .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private var texts: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(article.title(english))
                .font(.system(size: 17, weight: .semibold))
                .foregroundColor(HonorTheme.foreground)
                .multilineTextAlignment(.leading)
            Text(article.summary(english))
                .font(.system(size: 14))
                .foregroundColor(HonorTheme.secondary)
                .lineLimit(3)
                .multilineTextAlignment(.leading)
        }
    }

    private var badges: some View {
        VStack(spacing: 6) {
            if article.demo != nil {
                Image(systemName: "play.circle.fill").foregroundColor(HonorTheme.accent)
            }
            if article.screenshot != nil {
                Image(systemName: "iphone").foregroundColor(HonorTheme.secondary)
            }
        }
        .font(.system(size: 14))
        .accessibilityHidden(true)
    }
}

// MARK: Лайфхаки

struct HelpLifehacksBlock: View {
    let items: [HelpLifehack]
    let english: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HelpBlockHeader(symbol: "lightbulb", tint: .yellow,
                            title: english ? "Tips & tricks" : "Лайфхаки", count: items.count)
            ForEach(items) { hack in
                HelpLifehackCard(hack: hack, english: english)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("help.lifehacks")
    }
}

struct HelpLifehackCard: View {
    let hack: HelpLifehack
    let english: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            HelpIconBadge(symbol: hack.symbol, tint: hack.tint, size: 36)
            VStack(alignment: .leading, spacing: 4) {
                Text("💡 " + hack.title(english))
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(HonorTheme.foreground)
                Text(hack.text(english))
                    .font(.system(size: 14))
                    .foregroundColor(HonorTheme.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(hack.tint.opacity(0.08)))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(hack.tint.opacity(0.25), lineWidth: 0.8))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("help.lifehack.\(hack.id)")
    }
}

// MARK: Вопросы и ответы

struct HelpFAQBlock: View {
    let entries: [HelpIndexedFAQ]
    let english: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HelpBlockHeader(symbol: "questionmark.circle", tint: .orange,
                            title: english ? "Questions & answers" : "Вопросы и ответы", count: entries.count)
            VStack(spacing: 0) {
                ForEach(entries) { entry in
                    HelpFAQRow(entry: entry, english: english, showsDivider: entry.index != entries.last?.index)
                }
            }
            .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(HonorTheme.surface))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(HonorTheme.divider, lineWidth: 0.7))
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("help.faq")
    }
}

struct HelpFAQRow: View {
    let entry: HelpIndexedFAQ
    let english: Bool
    let showsDivider: Bool
    @State private var expanded: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            if expanded {
                answer.transition(AnyTransition.opacity.combined(with: .move(edge: .top)))
            }
            if showsDivider {
                Rectangle().fill(HonorTheme.divider).frame(height: 0.7).padding(.leading, 16)
            }
        }
        .clipped()
    }

    private var header: some View {
        Button {
            withAnimation(.spring(response: 0.38, dampingFraction: 0.86)) { expanded.toggle() }
        } label: {
            HStack(alignment: .top, spacing: 12) {
                Text(entry.item.question(english))
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(HonorTheme.foreground)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.down")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(expanded ? HonorTheme.accent : HonorTheme.secondary)
                    .rotationEffect(.degrees(expanded ? 180 : 0))
                    .padding(.top, 3)
            }
            .padding(16)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("help.faq.\(entry.index)")
        .accessibilityAddTraits(expanded ? .isSelected : [])
    }

    private var answer: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(HelpRichText.make(entry.item.answer(english)))
                .font(.system(size: 15))
                .foregroundColor(HonorTheme.secondary)
                .fixedSize(horizontal: false, vertical: true)
            if let shot = entry.item.screenshot {
                HelpScreenshotFrame(name: shot, width: 190)
            }
            if let demo = entry.item.demo {
                HelpDemoView(demo: demo)
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 16)
    }
}

/// Строка с **жирным** и *курсивом* (inline Markdown), без интерпретации как ключа локализации.
enum HelpRichText {
    static func make(_ text: String) -> AttributedString {
        let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        if let parsed = try? AttributedString(markdown: text, options: options) {
            return parsed
        }
        return AttributedString(text)
    }
}

// MARK: - Статья

struct HelpArticleView: View {
    let article: HelpArticle
    @EnvironmentObject private var settings: AppSettings

    private var english: Bool { settings.language == .english }
    private var scale: CGFloat { CGFloat(settings.fontScale) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 26) {
                HelpArticleHeader(article: article, english: english)
                media
                HelpParagraphs(paragraphs: article.paragraphs(english), scale: scale)
                steps
                tips
                related
            }
            .padding(.horizontal, 18)
            .padding(.top, 12)
            .padding(.bottom, 44)
        }
        .background(HonorTheme.background.ignoresSafeArea())
        .navigationTitle(article.title(english))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("help.articlePage.\(article.id)")
    }

    @ViewBuilder private var media: some View {
        if let shot = article.screenshot {
            HelpScreenshotFrame(name: shot, width: 250)
        }
        if let demo = article.demo {
            HelpDemoView(demo: demo)
        }
    }

    @ViewBuilder private var steps: some View {
        if !article.steps(english).isEmpty {
            HelpStepsBlock(title: english ? "How to do it" : "Как это сделать",
                           steps: article.steps(english), scale: scale)
        }
    }

    @ViewBuilder private var tips: some View {
        if !article.tips(english).isEmpty {
            HelpTipsBlock(title: english ? "Tips & tricks" : "Лайфхаки",
                          tips: article.tips(english), scale: scale)
        }
    }

    @ViewBuilder private var related: some View {
        if !article.related.isEmpty {
            HelpRelatedBlock(ids: article.related, english: english)
        }
    }
}

struct HelpArticleHeader: View {
    let article: HelpArticle
    let english: Bool

    private var sectionTitle: String? {
        HelpLibrary.section(containing: article.id)?.title(english)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                HelpIconBadge(symbol: article.symbol, tint: article.tint, size: 58)
                if let title = sectionTitle {
                    Text(title.uppercased())
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(article.tint)
                }
            }
            Text(article.title(english))
                .font(.system(size: 28, weight: .bold, design: .rounded))
                .foregroundColor(HonorTheme.foreground)
                .fixedSize(horizontal: false, vertical: true)
            Text(article.summary(english))
                .font(.system(size: 17))
                .foregroundColor(HonorTheme.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }
}

struct HelpParagraphs: View {
    let paragraphs: [String]
    let scale: CGFloat

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            ForEach(Array(paragraphs.enumerated()), id: \.offset) { pair in
                Text(HelpRichText.make(pair.element))
                    .font(.system(size: 17 * scale))
                    .foregroundColor(HonorTheme.foreground)
                    .lineSpacing(3)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

struct HelpSubheading: View {
    let symbol: String
    let title: String
    let tint: Color

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(tint)
            Text(title)
                .font(.system(size: 20, weight: .bold, design: .rounded))
                .foregroundColor(HonorTheme.foreground)
        }
        .accessibilityAddTraits(.isHeader)
    }
}

struct HelpStepsBlock: View {
    let title: String
    let steps: [String]
    let scale: CGFloat

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HelpSubheading(symbol: "list.number", title: title, tint: HonorTheme.accent)
            VStack(alignment: .leading, spacing: 14) {
                ForEach(Array(steps.enumerated()), id: \.offset) { pair in
                    HelpStepRow(number: pair.offset + 1, text: pair.element, scale: scale)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(HonorTheme.surface))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(HonorTheme.divider, lineWidth: 0.7))
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("help.steps")
    }
}

struct HelpStepRow: View {
    let number: Int
    let text: String
    let scale: CGFloat

    private var gradient: LinearGradient {
        LinearGradient(colors: [Color(red: 0.3, green: 0.55, blue: 1), Color(red: 0.55, green: 0.4, blue: 1)],
                       startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text("\(number)")
                .font(.system(size: 14, weight: .bold, design: .rounded))
                .foregroundColor(.white)
                .frame(width: 28, height: 28)
                .background(Circle().fill(gradient))
            Text(HelpRichText.make(text))
                .font(.system(size: 16 * scale))
                .foregroundColor(HonorTheme.foreground)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 3)
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }
}

struct HelpTipsBlock: View {
    let title: String
    let tips: [String]
    let scale: CGFloat

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HelpSubheading(symbol: "lightbulb.fill", title: title, tint: .yellow)
            ForEach(Array(tips.enumerated()), id: \.offset) { pair in
                HelpTipRow(text: pair.element, scale: scale)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("help.tips")
    }
}

struct HelpTipRow: View {
    let text: String
    let scale: CGFloat

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Text("💡").font(.system(size: 18))
            Text(HelpRichText.make(text))
                .font(.system(size: 15 * scale))
                .foregroundColor(HonorTheme.foreground)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.yellow.opacity(0.10)))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Color.yellow.opacity(0.3), lineWidth: 0.8))
        .accessibilityElement(children: .combine)
    }
}

struct HelpRelatedBlock: View {
    let ids: [String]
    let english: Bool

    private var articles: [HelpArticle] { ids.compactMap { HelpLibrary.article(id: $0) } }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HelpSubheading(symbol: "link", title: english ? "Related" : "Смотрите также", tint: HonorTheme.accent)
            ForEach(articles) { article in
                NavigationLink {
                    HelpArticleView(article: article)
                } label: {
                    HelpRelatedRow(article: article, english: english)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("help.related.\(article.id)")
            }
        }
    }
}

struct HelpRelatedRow: View {
    let article: HelpArticle
    let english: Bool

    var body: some View {
        HStack(spacing: 12) {
            HelpIconBadge(symbol: article.symbol, tint: article.tint, size: 32)
            Text(article.title(english))
                .font(.system(size: 16, weight: .medium))
                .foregroundColor(HonorTheme.foreground)
                .multilineTextAlignment(.leading)
            Spacer(minLength: 4)
            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(HonorTheme.secondary)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(HonorTheme.surface))
        .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

// MARK: - Скриншоты

/// Загружает скриншоты руководства из бандла: декодирует вне главного потока и кэширует.
final class HelpImageStore {
    static let shared = HelpImageStore()
    private let cache = NSCache<NSString, UIImage>()

    init() {
        cache.countLimit = 16
    }

    /// Путь к скриншоту в бандле (ресурсы лежат в корне или в папке Guide).
    static func url(for name: String, in bundle: Bundle = .main) -> URL? {
        if let url = bundle.url(forResource: name, withExtension: "jpg") { return url }
        return bundle.url(forResource: name, withExtension: "jpg", subdirectory: "Guide")
    }

    func cachedImage(_ name: String) -> UIImage? {
        cache.object(forKey: name as NSString)
    }

    func load(_ name: String) async -> UIImage? {
        if let hit = cachedImage(name) { return hit }
        let decoded: UIImage? = await Task.detached(priority: .userInitiated) { () -> UIImage? in
            HelpImageStore.decode(name)
        }.value
        if let decoded {
            cache.setObject(decoded, forKey: name as NSString)
        }
        return decoded
    }

    private static func decode(_ name: String) -> UIImage? {
        guard let url = url(for: name), let raw = UIImage(contentsOfFile: url.path) else { return nil }
        return raw.preparingForDisplay() ?? raw
    }
}

/// Скриншот в рамке «телефона». Нажатие открывает полноэкранный просмотр с зумом.
struct HelpScreenshotFrame: View {
    let name: String
    var width: CGFloat = 250
    @State private var image: UIImage?
    @State private var showsViewer: Bool = false

    init(name: String, width: CGFloat = 250) {
        self.name = name
        self.width = width
        _image = State(initialValue: HelpImageStore.shared.cachedImage(name))
    }

    private var height: CGFloat { width * 1169.0 / 540.0 }

    var body: some View {
        Button { if image != nil { showsViewer = true } } label: { framed }
            .buttonStyle(.plain)
            .frame(maxWidth: .infinity)
            .accessibilityIdentifier("help.screenshot")
            .accessibilityLabel(Text("Screenshot"))
            .accessibilityHint(Text("Open full screen"))
            .task(id: name) { await loadImage() }
            .fullScreenCover(isPresented: $showsViewer) {
                HelpImageViewer(image: image)
            }
    }

    private var framed: some View {
        ZStack {
            RoundedRectangle(cornerRadius: width * 0.13, style: .continuous)
                .fill(Color.black)
            screen
                .padding(width * 0.028)
        }
        .frame(width: width, height: height + width * 0.056)
        .overlay(RoundedRectangle(cornerRadius: width * 0.13, style: .continuous)
            .stroke(Color.white.opacity(0.16), lineWidth: 1))
        .shadow(color: Color.black.opacity(0.35), radius: 18, x: 0, y: 10)
        .overlay(alignment: .bottomTrailing) { zoomHint }
    }

    @ViewBuilder private var screen: some View {
        if let image {
            Image(uiImage: image)
                .resizable()
                .aspectRatio(contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: width * 0.105, style: .continuous))
        } else {
            RoundedRectangle(cornerRadius: width * 0.105, style: .continuous)
                .fill(HonorTheme.surface)
                .overlay(ProgressView())
        }
    }

    private var zoomHint: some View {
        Image(systemName: "arrow.up.left.and.arrow.down.right")
            .font(.system(size: 12, weight: .bold))
            .foregroundColor(.white)
            .padding(8)
            .background(Circle().fill(Color.black.opacity(0.55)))
            .offset(x: 6, y: 6)
            .accessibilityHidden(true)
    }

    private func loadImage() async {
        if image != nil { return }
        let loaded = await HelpImageStore.shared.load(name)
        image = loaded
    }
}

/// Полноэкранный просмотр скриншота: щипок — зум, двойное касание — приблизить/отдалить.
struct HelpImageViewer: View {
    let image: UIImage?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            if let image {
                HelpZoomableImage(image: image)
                    .ignoresSafeArea()
            }
            closeButton
        }
        .statusBarHidden(true)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("help.screenshot.viewer")
    }

    private var closeButton: some View {
        Button { dismiss() } label: {
            Image(systemName: "xmark")
                .font(.system(size: 16, weight: .bold))
                .foregroundColor(.white)
                .frame(width: 40, height: 40)
                .background(Circle().fill(Color.white.opacity(0.18)))
        }
        .buttonStyle(.plain)
        .padding(.top, 14)
        .padding(.trailing, 16)
        .accessibilityLabel(Text("Close"))
        .accessibilityIdentifier("help.screenshot.close")
    }
}

struct HelpZoomableImage: UIViewRepresentable {
    let image: UIImage

    func makeUIView(context: Context) -> HelpZoomScrollView {
        HelpZoomScrollView(image: image)
    }

    func updateUIView(_ uiView: HelpZoomScrollView, context: Context) {
        if uiView.imageView.image !== image {
            uiView.imageView.image = image
            uiView.setNeedsLayout()
        }
    }
}

final class HelpZoomScrollView: UIScrollView, UIScrollViewDelegate {
    let imageView = UIImageView()
    private var lastBoundsSize: CGSize = .zero

    init(image: UIImage) {
        super.init(frame: .zero)
        delegate = self
        minimumZoomScale = 1
        maximumZoomScale = 5
        bouncesZoom = true
        showsVerticalScrollIndicator = false
        showsHorizontalScrollIndicator = false
        contentInsetAdjustmentBehavior = .never
        decelerationRate = .fast
        backgroundColor = .clear
        imageView.image = image
        imageView.contentMode = .scaleAspectFit
        imageView.isUserInteractionEnabled = true
        addSubview(imageView)
        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        addGestureRecognizer(doubleTap)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        if bounds.size != lastBoundsSize {
            lastBoundsSize = bounds.size
            zoomScale = 1
            imageView.frame = fittedFrame()
            contentSize = imageView.frame.size
        }
        centerImage()
    }

    private func fittedFrame() -> CGRect {
        guard let size = imageView.image?.size, size.width > 0, size.height > 0,
              bounds.width > 0, bounds.height > 0 else { return CGRect(origin: .zero, size: bounds.size) }
        let ratio: CGFloat = min(bounds.width / size.width, bounds.height / size.height)
        return CGRect(x: 0, y: 0, width: size.width * ratio, height: size.height * ratio)
    }

    private func centerImage() {
        let offsetX: CGFloat = max((bounds.width - contentSize.width) * 0.5, 0)
        let offsetY: CGFloat = max((bounds.height - contentSize.height) * 0.5, 0)
        imageView.center = CGPoint(x: contentSize.width * 0.5 + offsetX, y: contentSize.height * 0.5 + offsetY)
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? {
        imageView
    }

    func scrollViewDidZoom(_ scrollView: UIScrollView) {
        centerImage()
    }

    @objc private func handleDoubleTap(_ recognizer: UITapGestureRecognizer) {
        if zoomScale > minimumZoomScale + 0.01 {
            setZoomScale(minimumZoomScale, animated: true)
            return
        }
        let point = recognizer.location(in: imageView)
        let targetScale: CGFloat = 2.6
        let width: CGFloat = bounds.width / targetScale
        let height: CGFloat = bounds.height / targetScale
        let rect = CGRect(x: point.x - width * 0.5, y: point.y - height * 0.5, width: width, height: height)
        zoom(to: rect, animated: true)
    }
}

// MARK: - Мини-видео: плеер

/// 0 до `start`, 1 после `end`, линейно между ними.
fileprivate func helpRamp(_ t: Double, _ start: Double, _ end: Double) -> Double {
    if end <= start { return t >= end ? 1 : 0 }
    return min(1, max(0, (t - start) / (end - start)))
}

/// Плавное начало и конец (smoothstep).
fileprivate func helpEase(_ x: Double) -> Double {
    let v = min(1, max(0, x))
    return v * v * (3 - 2 * v)
}

fileprivate func helpLerp(_ a: Double, _ b: Double, _ f: Double) -> Double {
    a + (b - a) * f
}

/// Первые символы строки — для эффекта «печатается».
fileprivate func helpPrefix(_ text: String, _ fraction: Double) -> String {
    let clamped = min(1, max(0, fraction))
    let count = Int((Double(text.count) * clamped).rounded(.down))
    return String(text.prefix(count))
}

/// Анимированная демонстрация в стиле видеоплеера: цикл, пауза, полоса прогресса.
/// Останавливается, когда уходит с экрана, и показывает статичный кадр при «Уменьшении движения».
struct HelpDemoView: View {
    let demo: HelpDemo
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var playing: Bool = true
    @State private var visible: Bool = false
    @State private var anchor: Date = Date()
    @State private var pausedAt: Double = 0

    private var english: Bool { settings.language == .english }
    private var animating: Bool { playing && visible && !reduceMotion }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            caption
            player
        }
        .onAppear(perform: appear)
        .onDisappear(perform: disappear)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("help.demo.\(demo.rawValue)")
    }

    private var caption: some View {
        HStack(spacing: 6) {
            Image(systemName: "play.rectangle.fill")
                .foregroundColor(HonorTheme.accent)
            Text(english ? "Mini-video" : "Мини-видео")
                .font(.system(size: 13, weight: .bold))
                .foregroundColor(HonorTheme.accent)
            Text("· " + demo.title(english))
                .font(.system(size: 13))
                .foregroundColor(HonorTheme.secondary)
                .lineLimit(1)
        }
    }

    @ViewBuilder private var player: some View {
        if reduceMotion {
            screen(time: demo.staticTime)
        } else {
            TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: !animating)) { context in
                screen(time: loopTime(at: context.date))
            }
        }
    }

    private func screen(time: Double) -> some View {
        ZStack(alignment: .bottom) {
            HelpDemoStage(demo: demo, t: time, english: english)
                .frame(maxWidth: .infinity)
                .frame(height: 300)
                .clipped()
                .contentShape(Rectangle())
                .onTapGesture(perform: toggle)
                .accessibilityHidden(true)
            if !playing && !reduceMotion { pausedOverlay }
            HelpDemoControls(playing: playing && !reduceMotion, time: time, duration: demo.duration,
                             enabled: !reduceMotion, english: english, toggle: toggle)
        }
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(HonorTheme.surface))
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(HonorTheme.divider, lineWidth: 0.7))
    }

    private var pausedOverlay: some View {
        Image(systemName: "play.circle.fill")
            .font(.system(size: 54))
            .foregroundColor(Color.white.opacity(0.92))
            .shadow(color: Color.black.opacity(0.35), radius: 10, x: 0, y: 4)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }

    private func loopTime(at date: Date) -> Double {
        if !animating { return pausedAt }
        let elapsed = max(0, date.timeIntervalSince(anchor))
        return elapsed.truncatingRemainder(dividingBy: demo.duration)
    }

    private func appear() {
        anchor = Date().addingTimeInterval(-pausedAt)
        visible = true
    }

    private func disappear() {
        pausedAt = loopTime(at: Date())
        visible = false
    }

    private func toggle() {
        if reduceMotion { return }
        if playing {
            pausedAt = loopTime(at: Date())
            playing = false
        } else {
            anchor = Date().addingTimeInterval(-pausedAt)
            playing = true
        }
    }
}

struct HelpDemoControls: View {
    let playing: Bool
    let time: Double
    let duration: Double
    let enabled: Bool
    let english: Bool
    let toggle: () -> Void

    private var progress: CGFloat { CGFloat(min(1, max(0, time / duration))) }

    private var timeLabel: String {
        let current = Int(time.rounded(.down))
        let total = Int(duration.rounded(.up))
        return String(format: "0:%02d / 0:%02d", current, total)
    }

    private var buttonLabel: String {
        if playing { return english ? "Pause" : "Пауза" }
        return english ? "Play" : "Воспроизвести"
    }

    var body: some View {
        HStack(spacing: 10) {
            playButton
            HelpDemoProgressBar(progress: progress)
            Text(timeLabel)
                .font(.system(size: 11, weight: .semibold, design: .monospaced))
                .foregroundColor(HonorTheme.secondary)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(Capsule().fill(.ultraThinMaterial))
        .padding(8)
    }

    private var playButton: some View {
        Button(action: toggle) {
            Image(systemName: playing ? "pause.fill" : "play.fill")
                .font(.system(size: 13, weight: .bold))
                .foregroundColor(.white)
                .frame(width: 30, height: 30)
                .background(Circle().fill(HonorTheme.accent))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.45)
        .accessibilityLabel(Text(buttonLabel))
        .accessibilityIdentifier("help.demo.play")
    }
}

struct HelpDemoProgressBar: View {
    let progress: CGFloat

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                Capsule().fill(HonorTheme.divider)
                Capsule().fill(HonorTheme.accent)
                    .frame(width: geometry.size.width * progress)
            }
        }
        .frame(height: 3)
        .accessibilityHidden(true)
    }
}

/// Выбирает нужную сцену. Нижний отступ оставляет место под панель плеера.
struct HelpDemoStage: View {
    let demo: HelpDemo
    let t: Double
    let english: Bool

    var body: some View {
        scene
            .padding(.horizontal, 16)
            .padding(.top, 14)
            .padding(.bottom, 52)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    @ViewBuilder private var scene: some View {
        switch demo {
        case .typing: HelpTypingDemo(t: t, english: english)
        case .questionTimer: HelpQuestionTimerDemo(t: t, english: english)
        case .quiz: HelpQuizDemo(t: t, english: english)
        case .dragChat: HelpDragChatDemo(t: t, english: english)
        case .selectAsk: HelpSelectAskDemo(t: t, english: english)
        case .table: HelpTableDemo(t: t, english: english)
        case .webResearch: HelpWebResearchDemo(t: t, english: english)
        case .photoEditor: HelpPhotoEditorDemo(t: t, english: english)
        case .parental: HelpParentalDemo(t: t, english: english)
        case .voice: HelpVoiceDemo(t: t, english: english)
        }
    }
}

// MARK: Общие детали макетов

struct HelpMockUserBubble: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.system(size: 14))
            .foregroundColor(HonorTheme.foreground)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(HonorTheme.bubble))
    }
}

struct HelpMockPill: View {
    let text: String
    var tint: Color = HonorTheme.accent
    var filled: Bool = false

    var body: some View {
        Text(text)
            .font(.system(size: 12, weight: .semibold))
            .foregroundColor(filled ? Color.white : tint)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(Capsule().fill(filled ? tint : tint.opacity(0.14)))
    }
}

// MARK: 1. Живой ответ

struct HelpTypingDemo: View {
    let t: Double
    let english: Bool

    private var question: String { english ? "Why is the sky blue?" : "Почему небо голубое?" }
    private var reasoning: String {
        english ? "Scattering of light… shorter wavelengths scatter more… so blue dominates."
                : "Рассеяние света… короткие волны рассеиваются сильнее… значит, синий преобладает."
    }
    private var answer: String {
        english ? "Sunlight scatters on air molecules. Blue light has a shorter wavelength, so it scatters much more — that's why we see a blue sky."
                : "Солнечный свет рассеивается на молекулах воздуха. У синего света короче длина волны, поэтому он рассеивается сильнее — и небо кажется голубым."
    }
    private var thinkingDone: Bool { t >= 3.8 }
    private var thinkingSeconds: Int { Int(min(3, max(0, t - 0.8)).rounded(.down)) }
    private var typed: String { helpPrefix(answer, helpRamp(t, 4.0, 7.6)) }
    private var cursorVisible: Bool { t > 3.9 && t < 7.8 && Int(t * 3) % 2 == 0 }
    private var cursorMark: String { cursorVisible ? "▍" : "" }

    private var headerText: String {
        if thinkingDone { return english ? "Thought for 3 seconds" : "Размышлял 3 секунды" }
        return english ? "Thinking… \(thinkingSeconds) s" : "Размышляю… \(thinkingSeconds) с"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Spacer(minLength: 40)
                HelpMockUserBubble(text: question)
            }
            .opacity(helpRamp(t, 0, 0.4))
            header
            if !thinkingDone && t > 0.8 { reasoningBlock }
            answerText
        }
    }

    private var header: some View {
        HStack(spacing: 6) {
            Image(systemName: "sparkles")
                .foregroundColor(HonorTheme.accent)
            Text(headerText)
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(HonorTheme.secondary)
            Image(systemName: thinkingDone ? "chevron.right" : "chevron.down")
                .font(.system(size: 11, weight: .semibold))
                .foregroundColor(HonorTheme.secondary)
        }
        .opacity(t > 0.8 ? 1 : 0)
    }

    private var reasoningBlock: some View {
        Text(helpPrefix(reasoning, helpRamp(t, 0.9, 3.5)))
            .font(.system(size: 13))
            .foregroundColor(HonorTheme.secondary)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.leading, 10)
            .overlay(alignment: .leading) {
                Rectangle().fill(HonorTheme.divider).frame(width: 2)
            }
    }

    private var answerText: some View {
        (Text(typed) + Text(cursorMark).foregroundColor(HonorTheme.accent))
            .font(.system(size: 15))
            .foregroundColor(HonorTheme.foreground)
            .fixedSize(horizontal: false, vertical: true)
    }
}

// MARK: 2. Вопрос с таймером

struct HelpQuestionTimerDemo: View {
    let t: Double
    let english: Bool

    private var remaining: Double { max(0, 10 - max(0, t - 0.6)) }
    private var closed: Bool { t >= 10.6 }
    private var letters: [String] { english ? ["A", "B", "C"] : ["А", "Б", "В"] }
    private var options: [String] {
        english ? ["Briefly", "In detail", "With examples"] : ["Кратко", "Подробно", "С примерами"]
    }

    var body: some View {
        ZStack(alignment: .top) {
            card
                .opacity(closed ? 0 : helpRamp(t, 0, 0.6))
                .scaleEffect(closed ? 0.92 : 1)
            closedNote
                .opacity(helpRamp(t, 10.7, 11.2))
        }
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(english ? "Question 1 of 3" : "Вопрос 1 из 3")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(HonorTheme.secondary)
                Spacer()
                HelpCountdownRing(fraction: remaining / 10, seconds: Int(remaining.rounded(.up)))
            }
            Text(english ? "How detailed should the answer be?" : "Насколько подробно ответить?")
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(HonorTheme.foreground)
            ForEach(0..<3, id: \.self) { index in
                optionRow(letter: letters[index], text: options[index])
            }
            ownAnswerField
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(HonorTheme.raised))
    }

    private func optionRow(letter: String, text: String) -> some View {
        HStack(spacing: 10) {
            Text(letter)
                .font(.system(size: 12, weight: .bold))
                .foregroundColor(HonorTheme.accent)
                .frame(width: 22, height: 22)
                .background(Circle().fill(HonorTheme.accent.opacity(0.15)))
            Text(text)
                .font(.system(size: 14))
                .foregroundColor(HonorTheme.foreground)
            Spacer()
        }
        .padding(.horizontal, 10)
        .frame(height: 30)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(HonorTheme.surface))
    }

    private var ownAnswerField: some View {
        HStack {
            Text(english ? "Your own answer…" : "Свой ответ…")
                .font(.system(size: 13))
                .foregroundColor(HonorTheme.secondary)
            Spacer()
        }
        .padding(.horizontal, 10)
        .frame(height: 28)
        .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(HonorTheme.divider, lineWidth: 0.8))
    }

    private var closedNote: some View {
        VStack(spacing: 10) {
            Image(systemName: "timer")
                .font(.system(size: 34, weight: .semibold))
                .foregroundColor(.orange)
            Text(english ? "Time's up — the question closed" : "Время вышло — вопрос закрыт")
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(HonorTheme.foreground)
            Text(english ? "Honer AI decided by itself: “In detail”" : "Honer AI решил сам: «Подробно»")
                .font(.system(size: 13))
                .foregroundColor(HonorTheme.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 50)
    }
}

struct HelpCountdownRing: View {
    let fraction: Double
    let seconds: Int

    private var ringColor: Color { fraction < 0.3 ? Color.red : HonorTheme.accent }

    var body: some View {
        ZStack {
            Circle().stroke(HonorTheme.divider, lineWidth: 3.5)
            Circle()
                .trim(from: 0, to: CGFloat(min(1, max(0, fraction))))
                .stroke(ringColor, style: StrokeStyle(lineWidth: 3.5, lineCap: .round))
                .rotationEffect(.degrees(-90))
            Text("\(seconds)")
                .font(.system(size: 12, weight: .bold, design: .rounded))
                .foregroundColor(ringColor)
        }
        .frame(width: 32, height: 32)
    }
}

// MARK: 3. Тест

struct HelpQuizDemo: View {
    let t: Double
    let english: Bool

    private var questions: [String] {
        english ? ["2 + 2 × 2 = ?", "Capital of Australia?", "H₂O is…"] : ["2 + 2 × 2 = ?", "Столица Австралии?", "H₂O — это…"]
    }
    private var answers: [String] { english ? ["6", "Sydney", "Water"] : ["6", "Сидней", "Вода"] }
    private var corrections: [String] { english ? ["", "Canberra", ""] : ["", "Канберра", ""] }
    let correct: [Bool] = [true, false, true]
    let starts: [Double] = [0.8, 2.8, 4.8]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(0..<3, id: \.self) { index in
                HelpQuizRow(number: index + 1, question: questions[index], answer: answers[index],
                            correction: corrections[index], isCorrect: correct[index],
                            chosen: helpRamp(t, starts[index], starts[index] + 0.3),
                            judged: t >= starts[index] + 1.0)
            }
            score
                .opacity(helpRamp(t, 7.0, 7.5))
                .scaleEffect(CGFloat(0.9 + 0.1 * helpEase(helpRamp(t, 7.0, 7.5))))
        }
    }

    private var score: some View {
        HStack(spacing: 10) {
            Image(systemName: "star.fill").foregroundColor(.yellow)
            Text(english ? "Score: 2 of 3" : "Итог: 2 из 3")
                .font(.system(size: 16, weight: .bold))
                .foregroundColor(HonorTheme.foreground)
            Spacer()
            Text(english ? "Let's review Q2" : "Разберём вопрос 2")
                .font(.system(size: 12))
                .foregroundColor(HonorTheme.secondary)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(HonorTheme.accent.opacity(0.14)))
    }
}

struct HelpQuizRow: View {
    let number: Int
    let question: String
    let answer: String
    let correction: String
    let isCorrect: Bool
    let chosen: Double
    let judged: Bool

    private var tint: Color {
        if !judged { return HonorTheme.accent }
        return isCorrect ? Color.green : Color.red
    }

    var body: some View {
        HStack(spacing: 10) {
            Text("\(number)")
                .font(.system(size: 12, weight: .bold))
                .foregroundColor(HonorTheme.secondary)
                .frame(width: 20)
            VStack(alignment: .leading, spacing: 2) {
                Text(question)
                    .font(.system(size: 13))
                    .foregroundColor(HonorTheme.secondary)
                answerLine
            }
            Spacer()
            verdict
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(judged ? tint.opacity(0.14) : HonorTheme.raised))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(judged ? tint.opacity(0.6) : Color.clear, lineWidth: 1))
    }

    private var answerLine: some View {
        HStack(spacing: 6) {
            Text(answer)
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(HonorTheme.foreground)
                .strikethrough(judged && !isCorrect)
            if judged && !correction.isEmpty {
                Text("→ " + correction)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(.green)
            }
        }
        .opacity(chosen)
    }

    private var verdict: some View {
        Image(systemName: isCorrect ? "checkmark.circle.fill" : "xmark.circle.fill")
            .font(.system(size: 20))
            .foregroundColor(tint)
            .opacity(judged ? 1 : 0)
    }
}

// MARK: 4. Перетаскивание чата

struct HelpDragChatDemo: View {
    let t: Double
    let english: Bool

    private var lift: Double { helpRamp(t, 0.8, 1.3) - helpRamp(t, 4.2, 4.7) }
    private var travel: Double { helpEase(helpRamp(t, 1.4, 4.2)) }
    private var dropped: Bool { t >= 4.7 }
    private var rowY: Double { helpLerp(190, 56, travel) }
    private var slotHighlighted: Bool { travel > 0.55 && !dropped }
    private var fade: Double { 1 - 0.7 * helpRamp(t, 8.0, 8.5) }

    var body: some View {
        ZStack(alignment: .topLeading) {
            staticLayer
            draggedRow
            hand
        }
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .frame(height: 224, alignment: .top)
        .opacity(fade)
    }

    private var staticLayer: some View {
        ZStack(alignment: .topLeading) {
            HelpSidebarHeader(text: english ? "Pinned" : "Закреплено").offset(y: 0)
            HelpSidebarRow(title: english ? "Project ideas" : "Идеи для проекта", pinned: true).offset(y: 20)
            slot.offset(y: 56)
            Rectangle().fill(HonorTheme.divider).frame(height: 0.7).offset(y: 96)
            HelpSidebarHeader(text: english ? "Today" : "Сегодня").offset(y: 102)
            HelpSidebarRow(title: english ? "Choosing a phone" : "Выбор смартфона", pinned: false).offset(y: 122)
            HelpSidebarRow(title: english ? "Pizza recipe" : "Рецепт пиццы", pinned: false).offset(y: 156)
        }
    }

    private var slot: some View {
        RoundedRectangle(cornerRadius: 10, style: .continuous)
            .stroke(HonorTheme.accent, style: StrokeStyle(lineWidth: 1.2, dash: [5, 4]))
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(HonorTheme.accent.opacity(0.08)))
            .frame(height: 32)
            .opacity(slotHighlighted ? 1 : 0)
    }

    private var draggedRow: some View {
        HelpSidebarRow(title: english ? "Trip plan" : "План поездки", pinned: dropped)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(HonorTheme.raised.opacity(lift > 0.01 ? 1 : 0)))
            .scaleEffect(CGFloat(1 + 0.05 * lift))
            .shadow(color: Color.black.opacity(0.3 * lift), radius: CGFloat(10 * lift), x: 0, y: CGFloat(6 * lift))
            .offset(y: CGFloat(rowY))
    }

    private var hand: some View {
        Image(systemName: "hand.point.up.left.fill")
            .font(.system(size: 26))
            .foregroundColor(HonorTheme.foreground)
            .shadow(color: Color.black.opacity(0.3), radius: 3, x: 0, y: 1)
            .offset(x: 190, y: CGFloat(rowY + 14))
            .opacity(helpRamp(t, 0.5, 0.8) - helpRamp(t, 4.8, 5.2))
    }
}

struct HelpSidebarHeader: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.system(size: 12, weight: .semibold))
            .foregroundColor(HonorTheme.secondary)
            .padding(.leading, 10)
    }
}

struct HelpSidebarRow: View {
    let title: String
    let pinned: Bool

    var body: some View {
        HStack(spacing: 8) {
            Text(title)
                .font(.system(size: 14))
                .foregroundColor(HonorTheme.foreground)
                .lineLimit(1)
            Spacer()
            if pinned {
                Image(systemName: "pin.fill")
                    .font(.system(size: 11))
                    .foregroundColor(HonorTheme.accent)
            }
            Image(systemName: "ellipsis")
                .font(.system(size: 12))
                .foregroundColor(HonorTheme.secondary)
        }
        .padding(.horizontal, 10)
        .frame(height: 32)
        .frame(maxWidth: .infinity)
    }
}

// MARK: 5. «Спросить Honer AI» о фрагменте

struct HelpSelectAskDemo: View {
    let t: Double
    let english: Bool

    private var lineOne: String { english ? "Photosynthesis is the process in which" : "Фотосинтез — это процесс, в котором" }
    private var lineTwoStart: String { english ? "plants " : "растения " }
    private var selected: String { english ? "turn light into energy" : "превращают свет в энергию" }
    private var lineThree: String { english ? "and release oxygen." : "и выделяют кислород." }
    private var question: String { english ? "Explain this more simply" : "Объясни это проще" }

    private var selection: Double { helpEase(helpRamp(t, 1.0, 2.6)) }
    private var menuShown: Bool { t >= 2.8 && t < 4.8 }
    private var askPressed: Bool { t >= 4.1 && t < 4.8 }
    private var quoteShown: Double { helpRamp(t, 5.0, 5.5) }
    private var typed: String { helpPrefix(question, helpRamp(t, 5.8, 7.8)) }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            answerCard
            Spacer(minLength: 8)
            HelpMockComposer(quote: quoteShown > 0 ? selected : nil, quoteOpacity: quoteShown,
                             text: typed, placeholder: english ? "Message" : "Сообщение",
                             sendPulse: helpRamp(t, 8.3, 8.6) - helpRamp(t, 8.8, 9.1))
        }
    }

    private var answerCard: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(lineOne)
            HStack(spacing: 0) {
                Text(lineTwoStart)
                Text(selected)
                    .background(HelpSelectionHighlight(progress: selection))
            }
            Text(lineThree)
        }
        .font(.system(size: 15))
        .foregroundColor(HonorTheme.foreground)
        .lineLimit(1)
        .minimumScaleFactor(0.7)
        .padding(.top, 58)
        .overlay(alignment: .top) {
            HelpSelectionMenu(english: english, askPressed: askPressed)
                .opacity(menuShown ? 1 : 0)
                .scaleEffect(menuShown ? 1 : 0.85)
        }
    }
}

struct HelpSelectionHighlight: View {
    let progress: Double

    var body: some View {
        Rectangle()
            .fill(HonorTheme.accent.opacity(0.32))
            .scaleEffect(x: CGFloat(progress), y: 1, anchor: .leading)
            .overlay(alignment: .leading) { handle.opacity(progress > 0.02 ? 1 : 0) }
            .overlay(alignment: .trailing) { handle.opacity(progress > 0.97 ? 1 : 0) }
    }

    private var handle: some View {
        Capsule().fill(HonorTheme.accent).frame(width: 2.5, height: 22)
    }
}

struct HelpSelectionMenu: View {
    let english: Bool
    let askPressed: Bool

    var body: some View {
        HStack(spacing: 0) {
            item(english ? "Copy" : "Копировать", highlighted: false)
            Rectangle().fill(Color.white.opacity(0.2)).frame(width: 0.7, height: 22)
            item(english ? "✦ Ask Honer AI" : "✦ Спросить Honer AI", highlighted: askPressed)
        }
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color(white: 0.16)))
        .shadow(color: Color.black.opacity(0.3), radius: 8, x: 0, y: 4)
    }

    private func item(_ text: String, highlighted: Bool) -> some View {
        Text(text)
            .font(.system(size: 13, weight: .semibold))
            .foregroundColor(.white)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(highlighted ? HonorTheme.accent : Color.clear))
    }
}

struct HelpMockComposer: View {
    let quote: String?
    let quoteOpacity: Double
    let text: String
    let placeholder: String
    let sendPulse: Double

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let quote {
                HStack(spacing: 8) {
                    Rectangle().fill(HonorTheme.accent).frame(width: 3, height: 18)
                    Text("«" + quote + "»")
                        .font(.system(size: 12))
                        .foregroundColor(HonorTheme.secondary)
                        .lineLimit(1)
                }
                .opacity(quoteOpacity)
            }
            HStack {
                Text(text.isEmpty ? placeholder : text)
                    .font(.system(size: 14))
                    .foregroundColor(text.isEmpty ? HonorTheme.secondary : HonorTheme.foreground)
                Spacer()
                Image(systemName: "arrow.up")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(.white)
                    .frame(width: 28, height: 28)
                    .background(Circle().fill(text.isEmpty ? HonorTheme.secondary : HonorTheme.accent))
                    .scaleEffect(CGFloat(1 + 0.25 * sendPulse))
            }
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(HonorTheme.raised))
    }
}

// MARK: 6. Таблица

struct HelpTableDemo: View {
    let t: Double
    let english: Bool

    private var expand: Double { helpEase(helpRamp(t, 2.2, 3.0)) }
    private var openPressed: Bool { t >= 1.6 && t < 2.2 }

    var body: some View {
        ZStack(alignment: .top) {
            compactCard
                .opacity(1 - expand)
            HelpTableFullScreen(t: t, english: english)
                .opacity(expand)
                .scaleEffect(CGFloat(0.55 + 0.45 * expand), anchor: .top)
        }
    }

    private var compactCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(english ? "📊 Trip budget" : "📊 Бюджет поездки")
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(HonorTheme.foreground)
            HelpTableGrid(rows: HelpTableDemoData.rows(english: english, editedValue: "360", includeNewRow: false),
                          headers: HelpTableDemoData.headers(english: english), editingRow: -1, cursor: false)
            HStack {
                Spacer()
                HelpMockPill(text: english ? "Open" : "Открыть", filled: openPressed)
                    .scaleEffect(openPressed ? 1.1 : 1)
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(HonorTheme.raised))
        .padding(.top, 20)
    }
}

enum HelpTableDemoData {
    static func headers(english: Bool) -> [String] {
        english ? ["Item", "Plan", "Actual"] : ["Статья", "План", "Факт"]
    }

    static func rows(english: Bool, editedValue: String, includeNewRow: Bool) -> [[String]] {
        var result: [[String]] = [
            [english ? "Tickets" : "Билеты", "300", "280"],
            [english ? "Hotel" : "Отель", "400", editedValue],
            [english ? "Food" : "Еда", "150", "170"]
        ]
        if includeNewRow {
            result.append([english ? "Souvenirs" : "Сувениры", "40", ""])
        }
        return result
    }
}

struct HelpTableFullScreen: View {
    let t: Double
    let english: Bool

    private var editing: Bool { t >= 3.6 && t < 6.0 }
    private var cursor: Bool { editing && Int(t * 3) % 2 == 0 }
    private var editedValue: String {
        if t < 4.3 { return "360" }
        if t < 4.7 { return "" }
        return helpPrefix("420", helpRamp(t, 4.7, 5.4))
    }
    private var newRow: Bool { t >= 6.2 }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            header
            toolbar
            HelpTableGrid(rows: HelpTableDemoData.rows(english: english, editedValue: editedValue, includeNewRow: newRow),
                          headers: HelpTableDemoData.headers(english: english),
                          editingRow: editing ? 1 : -1, cursor: cursor)
            toast
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(HonorTheme.background))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(HonorTheme.divider, lineWidth: 0.7))
    }

    private var header: some View {
        HStack {
            Text(english ? "Trip budget" : "Бюджет поездки")
                .font(.system(size: 16, weight: .bold))
                .foregroundColor(HonorTheme.foreground)
            Spacer()
            Image(systemName: "square.and.arrow.up").foregroundColor(HonorTheme.accent)
            Image(systemName: "xmark").foregroundColor(HonorTheme.secondary)
        }
        .font(.system(size: 14, weight: .semibold))
    }

    private var toolbar: some View {
        HStack(spacing: 8) {
            HelpMockPill(text: english ? "+ Row" : "+ Строка", filled: t >= 5.9 && t < 6.3)
            HelpMockPill(text: english ? "+ Column" : "+ Столбец")
            Spacer()
        }
    }

    private var toast: some View {
        HStack(spacing: 6) {
            Image(systemName: "checkmark.circle.fill").foregroundColor(.green)
            Text(english ? "Honer AI sees your edits" : "Honer AI видит ваши правки")
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(HonorTheme.foreground)
        }
        .opacity(helpRamp(t, 7.0, 7.4))
    }
}

struct HelpTableGrid: View {
    let rows: [[String]]
    let headers: [String]
    let editingRow: Int
    let cursor: Bool

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                ForEach(0..<headers.count, id: \.self) { column in
                    HelpTableCell(text: headers[column], isHeader: true, isEditing: false, cursor: false)
                }
            }
            ForEach(0..<rows.count, id: \.self) { rowIndex in
                rowView(rowIndex)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(HonorTheme.divider, lineWidth: 0.7))
    }

    private func rowView(_ rowIndex: Int) -> some View {
        HStack(spacing: 0) {
            ForEach(0..<rows[rowIndex].count, id: \.self) { column in
                HelpTableCell(text: rows[rowIndex][column], isHeader: false,
                              isEditing: rowIndex == editingRow && column == 2,
                              cursor: cursor && rowIndex == editingRow && column == 2)
            }
        }
    }
}

struct HelpTableCell: View {
    let text: String
    let isHeader: Bool
    let isEditing: Bool
    let cursor: Bool

    var body: some View {
        HStack(spacing: 0) {
            Text(text)
                .font(.system(size: 12, weight: isHeader ? .bold : .regular))
                .foregroundColor(HonorTheme.foreground)
                .lineLimit(1)
            if cursor {
                Rectangle().fill(HonorTheme.accent).frame(width: 1.5, height: 13)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 7)
        .frame(height: 26)
        .frame(maxWidth: .infinity)
        .background(isHeader ? HonorTheme.raised : (isEditing ? HonorTheme.accent.opacity(0.14) : Color.clear))
        .overlay(Rectangle().stroke(isEditing ? HonorTheme.accent : HonorTheme.divider, lineWidth: isEditing ? 1.4 : 0.35))
    }
}

// MARK: 7. Исследование сайтов

struct HelpWebResearchDemo: View {
    let t: Double
    let english: Bool

    let total: Int = 500
    private var count: Int { Int((Double(total) * helpEase(helpRamp(t, 0.4, 8.6))).rounded(.down)) }
    private var done: Bool { t >= 8.7 }
    private var domains: [String] {
        ["wikipedia.org", "habr.com", "ixbt.com", "notebookcheck.net", "dns-shop.ru", "youtube.com", "reddit.com", "4pda.to", "theverge.com", "citilink.ru"]
    }

    private var headerTitle: String {
        if done { return english ? "Research complete" : "Исследование готово" }
        return english ? "Researching: best laptops" : "Исследую: лучшие ноутбуки"
    }

    private var counterText: String {
        english ? "Read \(count) of \(total) sites" : "Прочитано \(count) из \(total) сайтов"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            Text(counterText)
                .font(.system(size: 16, weight: .bold, design: .rounded))
                .foregroundColor(HonorTheme.foreground)
            HelpDemoProgressBar(progress: CGFloat(Double(count) / Double(total)))
            HelpFaviconStrip(t: t)
            feed
        }
    }

    private var header: some View {
        HStack(spacing: 8) {
            Image(systemName: done ? "checkmark.seal.fill" : "globe")
                .font(.system(size: 18, weight: .semibold))
                .foregroundColor(done ? .green : HonorTheme.accent)
                .rotationEffect(.degrees(done ? 0 : t * 90))
            Text(headerTitle)
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(HonorTheme.secondary)
        }
    }

    private var feed: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(0..<3, id: \.self) { row in
                feedRow(row)
            }
        }
    }

    private func feedRow(_ row: Int) -> some View {
        let index = (Int(t * 2.5) + row) % domains.count
        let prefix = english ? "Reading · " : "Читаю · "
        return HStack(spacing: 6) {
            Circle().fill(HelpFaviconStrip.color(for: index)).frame(width: 8, height: 8)
            Text(prefix + domains[index])
                .font(.system(size: 12, design: .monospaced))
                .foregroundColor(HonorTheme.secondary)
                .lineLimit(1)
        }
        .opacity(done ? 0.4 : 1 - Double(row) * 0.28)
    }
}

struct HelpFaviconStrip: View {
    let t: Double

    private static let letters: [String] = ["W", "H", "i", "N", "D", "▶", "R", "4", "V", "C"]
    private static let palette: [Color] = [.blue, .orange, .red, .purple, .green, .red, .orange, .teal, .indigo, .pink]

    static func color(for index: Int) -> Color {
        palette[((index % palette.count) + palette.count) % palette.count]
    }

    let spacing: Double = 46

    private var shift: Double { t * 70 }
    private var base: Int { Int(shift / spacing) }
    private var offset: CGFloat { CGFloat(-shift.truncatingRemainder(dividingBy: spacing)) }

    var body: some View {
        HStack(spacing: 10) {
            ForEach(0..<9, id: \.self) { slot in
                icon(base + slot)
            }
        }
        .offset(x: offset)
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(height: 38)
        .clipped()
    }

    private func icon(_ index: Int) -> some View {
        let letter = HelpFaviconStrip.letters[index % HelpFaviconStrip.letters.count]
        return Text(letter)
            .font(.system(size: 15, weight: .bold, design: .rounded))
            .foregroundColor(.white)
            .frame(width: 36, height: 36)
            .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(HelpFaviconStrip.color(for: index)))
    }
}

// MARK: 8. Редактор фото

struct HelpPhotoEditorDemo: View {
    let t: Double
    let english: Bool

    let photoWidth: CGFloat = 200
    let photoHeight: CGFloat = 150
    private var sweep: Double { helpRamp(t, 1.0, 3.2) }
    private var replaced: Double { helpRamp(t, 3.8, 4.6) }
    private var filterIndex: Int {
        if t < 5.6 { return 0 }
        if t < 7.2 { return 1 }
        return 2
    }
    private var filters: [String] {
        english ? ["Original", "B&W", "Warm", "Vintage"] : ["Оригинал", "Ч/Б", "Тёплый", "Винтаж"]
    }

    private var status: String {
        if t < 1.0 { return english ? "Background → Remove" : "Фон → Удалить фон" }
        if t < 3.2 { return english ? "Removing background on iPhone…" : "Удаляю фон на iPhone…" }
        if t < 3.8 { return english ? "Background removed ✓" : "Фон удалён ✓" }
        if t < 5.6 { return english ? "New background: gradient" : "Новый фон: градиент" }
        return english ? "Filters" : "Фильтры"
    }

    var body: some View {
        VStack(spacing: 12) {
            Text(status)
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(HonorTheme.secondary)
            photo
            chips
        }
        .frame(maxWidth: .infinity)
    }

    private var photo: some View {
        ZStack {
            HelpCheckerboard()
            originalBackground
                .mask(alignment: .trailing) {
                    Rectangle().frame(width: photoWidth * CGFloat(1 - sweep))
                }
            newBackground.opacity(replaced)
            subject
            sweepLine
        }
        .frame(width: photoWidth, height: photoHeight)
        .saturation(filterIndex == 1 ? 0 : 1)
        .colorMultiply(filterIndex == 2 ? Color(red: 1.0, green: 0.88, blue: 0.72) : Color.white)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .shadow(color: Color.black.opacity(0.25), radius: 8, x: 0, y: 4)
    }

    private var originalBackground: some View {
        ZStack(alignment: .topTrailing) {
            LinearGradient(colors: [Color(red: 1.0, green: 0.62, blue: 0.35), Color(red: 0.93, green: 0.36, blue: 0.55)],
                           startPoint: .top, endPoint: .bottom)
            Circle().fill(Color.yellow.opacity(0.85)).frame(width: 36, height: 36).padding(16)
        }
    }

    private var newBackground: some View {
        LinearGradient(colors: [Color(red: 0.25, green: 0.5, blue: 1.0), Color(red: 0.3, green: 0.85, blue: 0.95)],
                       startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    private var subject: some View {
        Image(systemName: "person.fill")
            .font(.system(size: 104))
            .foregroundColor(Color(red: 0.18, green: 0.2, blue: 0.26))
            .offset(y: 24)
    }

    private var sweepLine: some View {
        Rectangle()
            .fill(Color.white)
            .frame(width: 3)
            .shadow(color: HonorTheme.accent, radius: 6)
            .offset(x: -photoWidth / 2 + photoWidth * CGFloat(sweep))
            .opacity(sweep > 0 && sweep < 1 ? 1 : 0)
    }

    private var chips: some View {
        HStack(spacing: 6) {
            ForEach(0..<filters.count, id: \.self) { index in
                HelpMockPill(text: filters[index], filled: index == filterIndex && t >= 5.0)
            }
        }
        .opacity(helpRamp(t, 4.8, 5.2))
    }
}

struct HelpCheckerboard: View {
    var body: some View {
        Canvas { context, size in
            let cell: CGFloat = 12
            let columns = Int((size.width / cell).rounded(.up))
            let rows = Int((size.height / cell).rounded(.up))
            for row in 0..<rows {
                for column in 0..<columns where (row + column) % 2 == 0 {
                    let rect = CGRect(x: CGFloat(column) * cell, y: CGFloat(row) * cell, width: cell, height: cell)
                    context.fill(Path(rect), with: .color(Color.gray.opacity(0.35)))
                }
            }
        }
        .background(Color.white.opacity(0.92))
    }
}

// MARK: 9. Родительский контроль

struct HelpParentalDemo: View {
    let t: Double
    let english: Bool

    let pressTimes: [Double] = [0.8, 1.35, 1.9, 2.45]
    let pressedDigits: [String] = ["2", "5", "8", "0"]
    let keys: [[String]] = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], ["", "0", "⌫"]]

    private var filled: Int { pressTimes.filter { t >= $0 }.count }
    private var padOpacity: Double { 1 - helpRamp(t, 3.2, 3.8) }
    private var shieldProgress: Double { helpEase(helpRamp(t, 3.6, 4.4)) }
    private var fade: Double { 1 - helpRamp(t, 8.5, 9.0) }

    var body: some View {
        ZStack(alignment: .top) {
            pinPad.opacity(padOpacity)
            shield
                .opacity(shieldProgress)
                .scaleEffect(CGFloat(0.6 + 0.4 * shieldProgress))
        }
        .frame(maxWidth: .infinity)
        .opacity(fade)
    }

    private func isPressed(_ key: String) -> Bool {
        for (index, time) in pressTimes.enumerated() where pressedDigits[index] == key {
            if t >= time && t < time + 0.22 { return true }
        }
        return false
    }

    private var pinPad: some View {
        VStack(spacing: 10) {
            Text(english ? "Create a parent PIN" : "Придумайте PIN-код родителя")
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(HonorTheme.foreground)
            HStack(spacing: 14) {
                ForEach(0..<4, id: \.self) { index in
                    Circle()
                        .fill(index < filled ? HonorTheme.accent : Color.clear)
                        .overlay(Circle().stroke(HonorTheme.accent, lineWidth: 1.4))
                        .frame(width: 13, height: 13)
                }
            }
            VStack(spacing: 6) {
                ForEach(0..<keys.count, id: \.self) { row in
                    keyRow(keys[row])
                }
            }
        }
    }

    private func keyRow(_ labels: [String]) -> some View {
        HStack(spacing: 8) {
            ForEach(0..<labels.count, id: \.self) { index in
                HelpKeypadKey(label: labels[index], pressed: isPressed(labels[index]))
            }
        }
    }

    private var shield: some View {
        VStack(spacing: 10) {
            Image(systemName: "lock.shield.fill")
                .font(.system(size: 58))
                .foregroundColor(.green)
                .padding(.top, 8)
            Text(english ? "Parental controls are on" : "Родительский контроль включён")
                .font(.system(size: 15, weight: .bold))
                .foregroundColor(HonorTheme.foreground)
            HStack(spacing: 6) {
                HelpMockPill(text: english ? "18+ hidden" : "18+ скрыто", tint: .red).opacity(helpRamp(t, 5.0, 5.4))
                HelpMockPill(text: english ? "1 h a day" : "1 ч в день", tint: .orange).opacity(helpRamp(t, 5.6, 6.0))
                HelpMockPill(text: english ? "Quiet 22–07" : "Тихие 22–07", tint: .indigo).opacity(helpRamp(t, 6.2, 6.6))
            }
        }
    }
}

struct HelpKeypadKey: View {
    let label: String
    let pressed: Bool

    var body: some View {
        Text(label)
            .font(.system(size: 17, weight: .medium, design: .rounded))
            .foregroundColor(pressed ? Color.white : HonorTheme.foreground)
            .frame(width: 56, height: 30)
            .background(RoundedRectangle(cornerRadius: 9, style: .continuous)
                .fill(pressed ? HonorTheme.accent : (label.isEmpty ? Color.clear : HonorTheme.raised)))
            .scaleEffect(pressed ? 0.94 : 1)
    }
}

// MARK: 10. Голосовой ввод

struct HelpVoiceDemo: View {
    let t: Double
    let english: Bool

    private var phrase: String { english ? "Remind me to buy milk and bread" : "Напомни купить молоко и хлеб" }
    private var reply: String { english ? "Sure! I'll remind you at 6 pm 🛒" : "Хорошо! Напомню в 18:00 🛒" }
    private var recording: Bool { t >= 0.9 && t < 5.3 }
    private var sent: Bool { t >= 5.5 }
    private var transcript: String { sent ? "" : helpPrefix(phrase, helpRamp(t, 1.3, 4.8)) }

    private var hint: String {
        if recording { return english ? "Speak. Tap ■ to send" : "Говорите. Нажмите ■, чтобы отправить" }
        if sent { return english ? "Sent" : "Отправлено" }
        return english ? "Tap the microphone" : "Нажмите на микрофон"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            conversation
            Spacer(minLength: 0)
            HelpWaveform(t: t, active: recording)
                .opacity(recording ? 1 : 0.25)
            Text(hint)
                .font(.system(size: 12))
                .foregroundColor(HonorTheme.secondary)
                .frame(maxWidth: .infinity)
            composer
        }
    }

    private var conversation: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Spacer(minLength: 30)
                HelpMockUserBubble(text: phrase)
            }
            .opacity(helpRamp(t, 5.5, 5.9))
            Text(helpPrefix(reply, helpRamp(t, 6.3, 7.4)))
                .font(.system(size: 14))
                .foregroundColor(HonorTheme.foreground)
        }
        .frame(height: 70, alignment: .top)
    }

    private var composer: some View {
        HStack(spacing: 10) {
            Text(transcript.isEmpty ? (english ? "Message" : "Сообщение") : transcript)
                .font(.system(size: 14))
                .foregroundColor(transcript.isEmpty ? HonorTheme.secondary : HonorTheme.foreground)
                .lineLimit(1)
            Spacer()
            if recording {
                Text(english ? "Cancel" : "Отмена")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(HonorTheme.secondary)
            }
            HelpMicButton(t: t, recording: recording)
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(HonorTheme.raised))
    }
}

struct HelpWaveform: View {
    let t: Double
    let active: Bool

    private func height(_ index: Int) -> CGFloat {
        if !active { return 4 }
        let position = Double(index) / 27.0
        let envelope = 0.35 + 0.65 * sin(position * Double.pi)
        let wave = abs(sin(t * 6.0 + Double(index) * 0.55)) * 0.7 + abs(sin(t * 3.7 + Double(index) * 1.3)) * 0.3
        return CGFloat(4 + 26 * envelope * wave)
    }

    var body: some View {
        HStack(alignment: .center, spacing: 3) {
            ForEach(0..<28, id: \.self) { index in
                Capsule()
                    .fill(HonorTheme.accent)
                    .frame(width: 3, height: height(index))
            }
        }
        .frame(height: 32)
        .frame(maxWidth: .infinity)
    }
}

struct HelpMicButton: View {
    let t: Double
    let recording: Bool

    private var pulse: Double { (t * 1.2).truncatingRemainder(dividingBy: 1) }

    var body: some View {
        ZStack {
            if recording {
                Circle()
                    .stroke(HonorTheme.accent.opacity(0.6 * (1 - pulse)), lineWidth: 2)
                    .frame(width: 34, height: 34)
                    .scaleEffect(CGFloat(1 + 0.6 * pulse))
            }
            Circle()
                .fill(recording ? HonorTheme.accent : HonorTheme.background)
                .overlay(Circle().stroke(HonorTheme.divider, lineWidth: recording ? 0 : 1))
                .frame(width: 34, height: 34)
            Image(systemName: recording ? "stop.fill" : "mic.fill")
                .font(.system(size: 14, weight: .bold))
                .foregroundColor(recording ? Color.white : HonorTheme.foreground)
        }
        .frame(width: 40, height: 40)
    }
}
