import Foundation

/// Строки о ходе работы («Ищу в интернете…», «Читаю страницу») на языке приложения.
/// Модель и шаги пишут их по-русски; в английском интерфейсе они переводятся при показе —
/// так переводятся и шаги уже сохранённых ответов.
enum StatusText {
    static var isEnglish: Bool { UserDefaults.standard.string(forKey: "honor.language") == "en" }

    static let english: [String: String] = [
        "Размышляю…": "Thinking…", "Отвечаю…": "Answering…", "Дописываю ответ…": "Finishing the answer…",
        "Формулирую ответ…": "Composing the answer…", "Перевожу на русский…": "Translating…",
        "Перевожу ответ на русский…": "Translating the answer…",
        "Выполняю действие": "Working", "Выполняю действие…": "Working…",
        "Ищу в интернете": "Searching the web", "Ищу в интернете…": "Searching the web…",
        "Искал в интернете": "Searched the web",
        "Читаю страницу": "Reading a page", "Читаю страницу…": "Reading a page…",
        "Читаю много сайтов": "Reading many sites", "Читаю сайты…": "Reading sites…",
        "Делаю скриншот страницы": "Taking a page screenshot", "Делаю скриншот страницы…": "Taking a page screenshot…",
        "Ищу фотографии": "Finding photos", "Ищу изображения…": "Finding images…",
        "Ищу видео": "Finding videos", "Ищу видео…": "Finding videos…",
        "Ищу на YouTube": "Searching YouTube", "Ищу на YouTube…": "Searching YouTube…",
        "Смотрю видео YouTube": "Watching a YouTube video", "Смотрю видео…": "Watching the video…",
        "Смотрю GitHub": "Checking GitHub", "Смотрю GitHub…": "Checking GitHub…",
        "Ищу товары": "Searching products", "Ищу товары…": "Searching products…",
        "Читаю ВКонтакте": "Reading VK", "Читаю ВКонтакте…": "Reading VK…",
        "Читаю канал Telegram": "Reading a Telegram channel", "Читаю Telegram…": "Reading Telegram…",
        "Смотрю погоду": "Checking the weather", "Смотрю погоду…": "Checking the weather…",
        "Рисую картинку": "Drawing a picture", "Рисую…": "Drawing…",
        "Рассматриваю изображение": "Looking at the image", "Рассматриваю изображение…": "Looking at the image…",
        "Слушаю и расшифровываю": "Listening and transcribing", "Расшифровываю запись…": "Transcribing…",
        "Редактирую фото": "Editing the photo", "Редактирую фото…": "Editing the photo…",
        "Создаю таблицу": "Creating a table", "Создаю таблицу…": "Creating a table…",
        "Обновляю таблицу": "Updating the table", "Обновляю таблицу…": "Updating the table…",
        "Смотрю таблицу": "Checking the table", "Смотрю таблицу…": "Checking the table…",
        "Смотрю список чатов": "Listing chats", "Смотрю список чатов…": "Listing chats…",
        "Читаю чат": "Reading a chat", "Читаю чат…": "Reading a chat…",
        "Обновляю чаты": "Updating chats", "Обновляю чаты…": "Updating chats…",
        "Пишу в другой чат": "Writing to another chat", "Отправляю сообщение в чат…": "Sending to a chat…",
        "Запоминаю": "Remembering", "Запоминаю…": "Remembering…",
        "Смотрю память": "Checking memory", "Смотрю память…": "Checking memory…",
        "Исправляю факт в памяти": "Updating a memory fact", "Удаляю факт из памяти": "Deleting a memory fact",
        "Обновляю память…": "Updating memory…",
        "Смотрю настройки": "Checking settings", "Смотрю настройки…": "Checking settings…",
        "Меняю настройку": "Changing a setting", "Меняю настройку…": "Changing a setting…",
        "Ищу контакт": "Finding a contact", "Копирую в буфер обмена": "Copying to clipboard",
        "Открываю игру": "Opening a game", "Открываю игру…": "Opening a game…",
        "фото": "photos", "видео": "videos", "рисунок": "drawing", "чаты": "chats", "память": "memory", "таблица": "table",
        "Скачиваю запись": "Downloading the recording"
    ]

    /// Перевод строки хода работы, если интерфейс на английском.
    static func localized(_ text: String) -> String {
        guard isEnglish, !text.isEmpty else { return text }
        if let exact = english[text] { return exact }
        var value = text
        // «Прочитано 12 из 60», «Собрано ссылок: 40», «Расшифровано 50 из 300 с», «сайтов: 5», «Шаги: 3».
        let patterns: [(String, String)] = [
            ("Прочитано (\\d+) из (\\d+)", "Read $1 of $2"),
            ("Расшифровано (\\d+) из (\\d+) с", "Transcribed $1 of $2 s"),
            ("Собрано ссылок: (\\d+)", "Links collected: $1"),
            ("сайтов: (\\d+)", "sites: $1"),
            ("Шаги: (\\d+)", "Steps: $1")
        ]
        for (pattern, template) in patterns {
            value = value.replacingOccurrences(of: pattern, with: template, options: .regularExpression)
        }
        // Составные строки «Искал в интернете · сайтов: 5 · фото».
        if value.contains(" · ") {
            value = value.components(separatedBy: " · ").map { english[$0] ?? $0 }.joined(separator: " · ")
        }
        return value
    }
}
