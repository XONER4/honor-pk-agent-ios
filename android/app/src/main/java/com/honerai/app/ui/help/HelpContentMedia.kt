package com.honerai.app.ui.help

// media: статья о поисковиках, приложениях на телефоне и загрузке медиа.

internal val articleSearchAppsMedia: HelpArticle get() {
    return HelpArticle(
        id = "search-apps-media", symbol = "globe", tint = HelpTint.orange,
        titleRU = "Поисковики, приложения и загрузки", titleEN = "Search engines, apps and downloads",
        summaryRU = "Яндекс, Google, Bing и DuckDuckGo сразу; кнопки «Открыть» для Карт и Почты; скачивание фото, видео и музыки.",
        summaryEN = "Yandex, Google, Bing and DuckDuckGo at once; “Open” buttons for Maps and Mail; saving photos, videos and music.",
        bodyRU = listOf(
            "С кнопкой «Поиск» Honer AI спрашивает сразу несколько поисковиков — **Яндекс, Google, Bing, DuckDuckGo**, а также Brave и Википедию — и ставит выше страницы, которые нашли сразу несколько из них. В карточке «Источники ответа» видно, какой поисковик нашёл каждую страницу.",
            "Нейросеть может подготовить кнопку **«Открыть …»**: маршрут в Google или Яндекс Картах, черновик письма в Gmail, событие в Календаре, поиск на YouTube, Яндекс Музыку, Погоду, Go, а также Google Pay, Кошелёк, Mir Pay или СБПэй. Приложение открывается только по вашему нажатию; платить и переводить деньги нейросеть не может.",
            "Фото, видео и свободную музыку (Internet Archive, Wikimedia Commons) Honer AI присылает карточками: аудио играет прямо в чате, у каждой карточки есть кнопки **«Скачать»** и **«Поделиться»**. Файлы сохраняются в папки Pictures, Movies и Music → «Honer AI», по окончании приходит уведомление."
        ),
        bodyEN = listOf(
            "With the Search button on, Honer AI asks several search engines at once — **Yandex, Google, Bing, DuckDuckGo**, plus Brave and Wikipedia — and ranks higher the pages found by several of them. The “Answer sources” card shows which engine found each page.",
            "The AI can prepare an **“Open …”** button: a route in Google or Yandex Maps, a Gmail draft, a Calendar event, a YouTube search, Yandex Music, Weather, Go, or Google Pay, Koshelek, Mir Pay and SBPay. The app opens only when you tap; the AI can never pay or transfer money.",
            "Photos, videos and free music (Internet Archive, Wikimedia Commons) arrive as cards: audio plays right in the chat, and every card has **Download** and **Share** buttons. Files go to Pictures, Movies and Music → “Honer AI”, with a notification when done."
        ),
        stepsRU = listOf(
            "Попросите: «Проложи маршрут до Красной площади в Яндекс Картах» или «Пришли спокойную фортепианную музыку».",
            "Нажмите кнопку «Открыть …» или «Скачать» под ответом.",
            "Отключить приложения можно в Настройки → Интеграции → «Приложения на телефоне»."
        ),
        stepsEN = listOf(
            "Ask: “Route to Red Square in Yandex Maps” or “Send some calm piano music”.",
            "Tap the “Open …” or “Download” button under the answer.",
            "Turn apps off in Settings → Integrations → “Apps on the phone”."
        ),
        tipsRU = listOf(
            "Английские подписи к фото из интернета приложение переводит на русский само."
        ),
        tipsEN = listOf(
            "The app translates English captions of web photos into the interface language."
        ),
        related = listOf("web-search", "web-media", "youtube")
    )
}
