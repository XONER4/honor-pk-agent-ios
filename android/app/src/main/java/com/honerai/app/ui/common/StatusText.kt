package com.honerai.app.ui.common

/**
 * Строки о ходе работы («Ищу в интернете…», «Читаю страницу») на языке приложения.
 * Модель и шаги пишут их по-русски; в английском интерфейсе они переводятся при показе —
 * так переводятся и шаги уже сохранённых ответов (порт StatusText.swift).
 */
object StatusText {
    val english: Map<String, String> = mapOf(
        "Размышляю…" to "Thinking…", "Отвечаю…" to "Answering…", "Дописываю ответ…" to "Finishing the answer…",
        "Формулирую ответ…" to "Composing the answer…", "Перевожу на русский…" to "Translating…",
        "Перевожу ответ на русский…" to "Translating the answer…",
        "Выполняю действие" to "Working", "Выполняю действие…" to "Working…",
        "Ищу в интернете" to "Searching the web", "Ищу в интернете…" to "Searching the web…",
        "Искал в интернете" to "Searched the web",
        "Читаю страницу" to "Reading a page", "Читаю страницу…" to "Reading a page…",
        "Читаю много сайтов" to "Reading many sites", "Читаю сайты…" to "Reading sites…",
        "Делаю скриншот страницы" to "Taking a page screenshot", "Делаю скриншот страницы…" to "Taking a page screenshot…",
        "Ищу фотографии" to "Finding photos", "Ищу изображения…" to "Finding images…",
        "Ищу видео" to "Finding videos", "Ищу видео…" to "Finding videos…",
        "Ищу на YouTube" to "Searching YouTube", "Ищу на YouTube…" to "Searching YouTube…",
        "Смотрю видео YouTube" to "Watching a YouTube video", "Смотрю видео…" to "Watching the video…",
        "Смотрю GitHub" to "Checking GitHub", "Смотрю GitHub…" to "Checking GitHub…",
        "Ищу товары" to "Searching products", "Ищу товары…" to "Searching products…",
        "Читаю ВКонтакте" to "Reading VK", "Читаю ВКонтакте…" to "Reading VK…",
        "Читаю канал Telegram" to "Reading a Telegram channel", "Читаю Telegram…" to "Reading Telegram…",
        "Смотрю погоду" to "Checking the weather", "Смотрю погоду…" to "Checking the weather…",
        "Рисую картинку" to "Drawing a picture", "Рисую…" to "Drawing…",
        "Рассматриваю изображение" to "Looking at the image", "Рассматриваю изображение…" to "Looking at the image…",
        "Слушаю и расшифровываю" to "Listening and transcribing", "Расшифровываю запись…" to "Transcribing…",
        "Редактирую фото" to "Editing the photo", "Редактирую фото…" to "Editing the photo…",
        "Создаю таблицу" to "Creating a table", "Создаю таблицу…" to "Creating a table…",
        "Обновляю таблицу" to "Updating the table", "Обновляю таблицу…" to "Updating the table…",
        "Смотрю таблицу" to "Checking the table", "Смотрю таблицу…" to "Checking the table…",
        "Смотрю список чатов" to "Listing chats", "Смотрю список чатов…" to "Listing chats…",
        "Читаю чат" to "Reading a chat", "Читаю чат…" to "Reading a chat…",
        "Обновляю чаты" to "Updating chats", "Обновляю чаты…" to "Updating chats…",
        "Пишу в другой чат" to "Writing to another chat", "Отправляю сообщение в чат…" to "Sending to a chat…",
        "Запоминаю" to "Remembering", "Запоминаю…" to "Remembering…",
        "Смотрю память" to "Checking memory", "Смотрю память…" to "Checking memory…",
        "Исправляю факт в памяти" to "Updating a memory fact", "Удаляю факт из памяти" to "Deleting a memory fact",
        "Обновляю память…" to "Updating memory…",
        "Смотрю настройки" to "Checking settings", "Смотрю настройки…" to "Checking settings…",
        "Меняю настройку" to "Changing a setting", "Меняю настройку…" to "Changing a setting…",
        "Ищу контакт" to "Finding a contact", "Копирую в буфер обмена" to "Copying to clipboard",
        "Открываю игру" to "Opening a game", "Открываю игру…" to "Opening a game…",
        "фото" to "photos", "видео" to "videos", "рисунок" to "drawing", "чаты" to "chats", "память" to "memory",
        "таблица" to "table", "Скачиваю запись" to "Downloading the recording",
    )

    // «Прочитано 12 из 60», «Собрано ссылок: 40», «Расшифровано 50 из 300 с», «сайтов: 5», «Шаги: 3».
    private val patterns: List<Pair<Regex, String>> = listOf(
        Regex("Прочитано (\\d+) из (\\d+)") to "Read $1 of $2",
        Regex("Расшифровано (\\d+) из (\\d+) с") to "Transcribed $1 of $2 s",
        Regex("Собрано ссылок: (\\d+)") to "Links collected: $1",
        Regex("сайтов: (\\d+)") to "sites: $1",
        Regex("Шаги: (\\d+)") to "Steps: $1",
    )

    /** Перевод строки хода работы, если интерфейс на английском. */
    fun localized(text: String, english: Boolean): String {
        if (!english || text.isEmpty()) return text
        this.english[text]?.let { return it }
        var value = text
        for ((regex, template) in patterns) value = regex.replace(value, template)
        // Составные строки «Искал в интернете · сайтов: 5 · фото».
        if (value.contains(" · ")) {
            value = value.split(" · ").joinToString(" · ") { this.english[it] ?: it }
        }
        return value
    }
}
