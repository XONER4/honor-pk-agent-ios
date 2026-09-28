package com.honerai.app.ui.help

// СГЕНЕРИРОВАНО из HelpCenter.swift (iOS): содержание руководства на двух языках.
// Каждая статья — отдельный геттер, чтобы не упереться в предел размера метода JVM.
// Упоминания iPhone заменены на Android-аналоги.

// MARK: - Содержание: интернет

internal val webSection: HelpSection get() {
    val items: List<HelpArticle> = listOf(articleWebSearch, articleDeepResearch, articleSources, articleWebMedia)
    return HelpSection(id = "web", symbol = "globe", tint = HelpTint.cyan,
                       titleRU = "Интернет", titleEN = "Internet", articles = items)
}

private val articleWebSearch: HelpArticle get() {
    return HelpArticle(
        id = "web-search", symbol = "magnifyingglass", tint = HelpTint.cyan,
        titleRU = "Поиск в интернете", titleEN = "Web search",
        summaryRU = "Несколько поисковиков и чтение страниц как в Chrome, с JavaScript.",
        summaryEN = "Several search engines and reading pages like Chrome, with JavaScript.",
        bodyRU = listOf(
            "Когда включён **«Поиск»**, Honer AI ищет сразу в **нескольких поисковых системах** и сравнивает результаты — так ответы полнее и надёжнее.",
            "Страницы открываются **как в Chrome**, с выполнением JavaScript, поэтому нейросеть видит и современные сайты, где содержимое подгружается динамически.",
            "Пока идёт поиск, над ответом видна **живая лента**: какие сайты сейчас читаются. Так понятно, откуда берётся информация."
        ),
        bodyEN = listOf(
            "With **Search** on, Honer AI queries **several search engines** at once and compares the results — so answers are fuller and more reliable.",
            "Pages open **like in Chrome**, with JavaScript running, so the assistant also sees modern sites whose content loads dynamically.",
            "While searching, a **live feed** above the answer shows which sites are being read. You always know where information comes from."
        ),
        stepsRU = listOf(
            "Включите **«Поиск»**.",
            "Задайте вопрос о свежих данных.",
            "Следите за лентой сайтов и откройте источники после ответа."
        ),
        stepsEN = listOf(
            "Turn on **Search**.",
            "Ask about current information.",
            "Watch the site feed and open the sources after the answer."
        ),
        tipsRU = listOf(
            "Нужен конкретный сайт? Вставьте ссылку в сообщение — нейросеть откроет и прочитает именно её.",
            "Закрытые страницы (с входом по паролю) нейросеть не открывает — она не входит в ваши аккаунты."
        ),
        tipsEN = listOf(
            "Need a specific site? Paste the link in your message — the assistant opens and reads exactly that page.",
            "Pages behind a login are not accessible — the assistant never signs in to your accounts."
        ),
        screenshot = "guide-search",
        related = listOf("search-button", "deep-research", "sources")
    )
}

private val articleDeepResearch: HelpArticle get() {
    return HelpArticle(
        id = "deep-research", symbol = "books.vertical", tint = HelpTint.indigo,
        titleRU = "Глубокое исследование", titleEN = "Deep research",
        summaryRU = "Сотни и тысячи страниц за раз — со счётчиком прогресса.",
        summaryEN = "Hundreds or thousands of pages at a time — with a live progress counter.",
        bodyRU = listOf(
            "Для серьёзного вопроса Honer AI умеет быстро прочитать **сотни и даже тысячи страниц**: обзоры, форумы, документацию, новости, магазины.",
            "Во время исследования показывается живой счётчик, например **«Прочитано 132 из 500 сайтов»**, и значки сайтов, которые сейчас обрабатываются.",
            "В конце вы получаете сводку с выводами и ссылками на источники с цитатами. Большое исследование занимает больше времени — можно свернуть приложение, ответ допишется в фоне."
        ),
        bodyEN = listOf(
            "For a serious question Honer AI can quickly read **hundreds or even thousands of pages**: reviews, forums, documentation, news, shops.",
            "During research you see a live counter such as **“Read 132 of 500 sites”** and icons of the sites being processed.",
            "At the end you get a summary with conclusions and links to sources with quotes. Big research takes longer — feel free to leave the app, the answer finishes in the background."
        ),
        stepsRU = listOf(
            "Включите **«Поиск»**.",
            "Напишите: «изучи 300 сайтов и сравни лучшие ноутбуки до 80 000 ₽».",
            "Следите за счётчиком или сверните приложение."
        ),
        stepsEN = listOf(
            "Turn on **Search**.",
            "Write: “study 300 sites and compare the best laptops under \$1000”.",
            "Watch the counter or leave the app."
        ),
        tipsRU = listOf(
            "Чем точнее задача (бюджет, страна, критерии), тем полезнее итог.",
            "Попросите в конце таблицу сравнения — её можно будет отредактировать."
        ),
        tipsEN = listOf(
            "The more precise the task (budget, country, criteria), the more useful the result.",
            "Ask for a comparison table at the end — you can edit it afterwards."
        ),
        demo = HelpDemo.webResearch,
        related = listOf("web-search", "sources", "tables", "background-answers")
    )
}

private val articleSources: HelpArticle get() {
    return HelpArticle(
        id = "sources", symbol = "quote.bubble", tint = HelpTint.blue,
        titleRU = "Источники и цитаты", titleEN = "Sources and quotes",
        summaryRU = "Проверьте, откуда взята информация: страницы, даты, прочитанный текст.",
        summaryEN = "Check where information came from: pages, dates, the text that was read.",
        bodyRU = listOf(
            "В ответе с поиском числа-сноски (**1**, **2**…) ведут к источникам. Под ответом есть карточка **«Источники ответа»** — сколько страниц прочитано и с каких сайтов.",
            "Нажмите на карточку: откроется список источников с названием страницы, адресом, кратким описанием, временем чтения и кнопкой **«Прочитанный текст»** — там видно, какие именно цитаты использовала нейросеть."
        ),
        bodyEN = listOf(
            "In an answer with search, footnote numbers (**1**, **2**…) lead to the sources. Under the answer there's an **Answer sources** card — how many pages were read and from which sites.",
            "Tap the card to see the sources: page title, address, a short description, when it was read and a **Read text** button showing exactly which quotes the assistant used."
        ),
        stepsRU = listOf(
            "Нажмите на карточку **«Источники ответа»**.",
            "Выберите источник и откройте **«Прочитанный текст»**.",
            "Нажмите на адрес, чтобы открыть страницу."
        ),
        stepsEN = listOf(
            "Tap the **Answer sources** card.",
            "Choose a source and open **Read text**.",
            "Tap the address to open the page."
        ),
        tipsRU = listOf(
            "Для важных решений всегда открывайте 1–2 источника и проверяйте цифры."
        ),
        tipsEN = listOf(
            "For important decisions, always open one or two sources and check the numbers."
        ),
        screenshot = "guide-sources",
        related = listOf("web-search", "deep-research")
    )
}

private val articleWebMedia: HelpArticle get() {
    return HelpArticle(
        id = "web-media", symbol = "play.rectangle", tint = HelpTint.red,
        titleRU = "Фото, видео, скриншоты и погода", titleEN = "Photos, videos, screenshots and weather",
        summaryRU = "Настоящие изображения и видео из интернета прямо в чате.",
        summaryEN = "Real images and videos from the internet right in the chat.",
        bodyRU = listOf(
            "Попросите «покажи фото Эйфелевой башни ночью» — Honer AI найдёт **настоящие фотографии** в интернете и покажет их в чате. Нажмите на фото, чтобы открыть его на весь экран.",
            "**Видео** из интернета (например, с YouTube) воспроизводятся **прямо в приложении**.",
            "Нейросеть может сделать **скриншот страницы** сайта, чтобы показать, как она выглядит, и узнать **погоду** для вашего города или любого другого места."
        ),
        bodyEN = listOf(
            "Ask “show me photos of the Eiffel Tower at night” — Honer AI finds **real photos** online and shows them in the chat. Tap a photo to view it full screen.",
            "**Videos** from the internet (YouTube, for example) play **right inside the app**.",
            "The assistant can take a **screenshot of a web page** to show how it looks, and check the **weather** for your city or anywhere else."
        ),
        stepsRU = listOf(
            "Включите **«Поиск»**.",
            "Попросите: «покажи фото…», «найди видео…», «сделай скриншот сайта…», «какая погода в…».",
            "Нажмите на фото или видео, чтобы открыть."
        ),
        stepsEN = listOf(
            "Turn on **Search**.",
            "Ask: “show photos of…”, “find a video…”, “screenshot the site…”, “what's the weather in…”.",
            "Tap a photo or video to open it."
        ),
        tipsRU = listOf(
            "Для погоды «у меня» разрешите геопозицию — нейросеть узнает ваш город."
        ),
        tipsEN = listOf(
            "For “weather here”, allow location — the assistant learns your city."
        ),
        related = listOf("youtube", "web-search", "permissions")
    )
}

// MARK: - Содержание: интеграции

internal val integrationsSection: HelpSection get() {
    val items: List<HelpArticle> = listOf(articleYouTube, articleGitHub, articleMarketplaces, articleSocial)
    return HelpSection(id = "integrations", symbol = "square.stack.3d.up", tint = HelpTint.red,
                       titleRU = "Интеграции", titleEN = "Integrations", articles = items)
}

private val articleYouTube: HelpArticle get() {
    return HelpArticle(
        id = "youtube", symbol = "play.tv", tint = HelpTint.red,
        titleRU = "YouTube", titleEN = "YouTube",
        summaryRU = "Поиск видео, информация о ролике, субтитры и расшифровка.",
        summaryEN = "Video search, video details, subtitles and transcripts.",
        bodyRU = listOf(
            "Honer AI ищет видео на **YouTube**, показывает информацию о ролике (название, канал, длительность, просмотры) и умеет получать **субтитры и расшифровку**.",
            "Благодаря расшифровке можно спросить: «о чём это видео?», «сделай конспект», «в какой момент рассказывают про цены?» — без просмотра всего ролика.",
            "Работает через публичные данные: вход в ваш аккаунт Google не нужен и не используется. Если у ролика нет субтитров, пересказ будет по описанию."
        ),
        bodyEN = listOf(
            "Honer AI searches **YouTube**, shows video details (title, channel, duration, views) and can fetch **subtitles and transcripts**.",
            "With a transcript you can ask “what's this video about?”, “make notes”, “when do they talk about prices?” — without watching the whole thing.",
            "It uses public data: your Google account is neither needed nor used. If a video has no subtitles, the summary is based on its description."
        ),
        stepsRU = listOf(
            "Вставьте ссылку на ролик или попросите «найди на YouTube…».",
            "Попросите конспект, перевод или ответ по содержанию."
        ),
        stepsEN = listOf(
            "Paste a video link or ask “find on YouTube…”.",
            "Ask for notes, a translation or an answer about the content."
        ),
        tipsRU = listOf(
            "Длинная лекция → «пять главных мыслей с таймкодами»."
        ),
        tipsEN = listOf(
            "Long lecture → “five key ideas with timestamps”."
        ),
        related = listOf("web-media", "github", "social")
    )
}

private val articleGitHub: HelpArticle get() {
    return HelpArticle(
        id = "github", symbol = "chevron.left.forwardslash.chevron.right", tint = HelpTint.gray,
        titleRU = "GitHub", titleEN = "GitHub",
        summaryRU = "Репозитории, файлы, issues и релизы открытых проектов.",
        summaryEN = "Repositories, files, issues and releases of public projects.",
        bodyRU = listOf(
            "Honer AI открывает **публичные репозитории GitHub**: читает README и файлы кода, смотрит **issues** и **релизы**, может найти проект по описанию.",
            "Можно спросить: «что делает этот репозиторий?», «как установить?», «что нового в последнем релизе?», «есть ли issue про такую ошибку?».",
            "Приватные репозитории недоступны: нейросеть не входит в ваш аккаунт GitHub."
        ),
        bodyEN = listOf(
            "Honer AI opens **public GitHub repositories**: reads the README and code files, looks at **issues** and **releases**, and can find a project by description.",
            "Ask: “what does this repository do?”, “how do I install it?”, “what's new in the latest release?”, “is there an issue about this error?”.",
            "Private repositories are not available: the assistant doesn't sign in to your GitHub account."
        ),
        stepsRU = listOf(
            "Вставьте ссылку на репозиторий или назовите проект.",
            "Задайте вопрос о коде, issues или релизах."
        ),
        stepsEN = listOf(
            "Paste a repository link or name the project.",
            "Ask about the code, issues or releases."
        ),
        tipsRU = listOf(
            "Ошибка в библиотеке? Попросите «поищи похожие issues в репозитории» — часто решение уже есть."
        ),
        tipsEN = listOf(
            "Bug in a library? Ask to “look for similar issues in the repo” — the fix often already exists."
        ),
        related = listOf("youtube", "documents")
    )
}

private val articleMarketplaces: HelpArticle get() {
    return HelpArticle(
        id = "marketplaces", symbol = "cart", tint = HelpTint.purple,
        titleRU = "Wildberries, Ozon, Avito", titleEN = "Wildberries, Ozon, Avito",
        summaryRU = "Поиск товаров с ценами и ссылками.",
        summaryEN = "Product search with prices and links.",
        bodyRU = listOf(
            "Honer AI ищет товары на **Wildberries**, **Ozon** и **Avito** и показывает их с **ценами**, фото и **ссылками** — можно сразу открыть карточку товара.",
            "Попросите сравнить варианты, найти подешевле или подобрать по параметрам: «беспроводные наушники до 5000 ₽ с шумоподавлением».",
            "Нейросеть не покупает и не оформляет заказы — только ищет и сравнивает. Цены и наличие меняются, поэтому перед покупкой проверьте их на сайте."
        ),
        bodyEN = listOf(
            "Honer AI searches **Wildberries**, **Ozon** and **Avito** and shows items with **prices**, photos and **links** — you can open the product page right away.",
            "Ask it to compare options, find something cheaper or match your criteria: “wireless noise-cancelling earbuds under 5000 ₽”.",
            "The assistant doesn't buy or place orders — it only searches and compares. Prices and stock change, so check them on the site before buying."
        ),
        stepsRU = listOf(
            "Включите **«Поиск»**.",
            "Напишите: «найди на Ozon…» или «сравни цены на Wildberries и Ozon…».",
            "Нажмите на ссылку, чтобы открыть товар."
        ),
        stepsEN = listOf(
            "Turn on **Search**.",
            "Write: “find on Ozon…” or “compare prices on Wildberries and Ozon…”.",
            "Tap a link to open the product."
        ),
        tipsRU = listOf(
            "Попросите итог таблицей: товар, цена, рейтинг, ссылка."
        ),
        tipsEN = listOf(
            "Ask for the result as a table: item, price, rating, link."
        ),
        related = listOf("tables", "web-search")
    )
}

private val articleSocial: HelpArticle get() {
    return HelpArticle(
        id = "social", symbol = "person.2", tint = HelpTint.blue,
        titleRU = "ВКонтакте и Telegram", titleEN = "VK and Telegram",
        summaryRU = "Публичные страницы VK и открытые каналы Telegram — без входа в аккаунт.",
        summaryEN = "Public VK pages and open Telegram channels — without logging in.",
        bodyRU = listOf(
            "Honer AI читает **публичные страницы ВКонтакте** и **открытые каналы Telegram**: последние посты, описание, новости сообщества.",
            "Все интеграции работают **через публичные страницы и открытые API, без входа** в ваши аккаунты. Поэтому **закрытые** профили, группы, личные сообщения и приватные каналы недоступны — и это касается любых соцсетей, включая Instagram и Facebook."
        ),
        bodyEN = listOf(
            "Honer AI reads **public VK pages** and **open Telegram channels**: latest posts, descriptions, community news.",
            "All integrations work **through public pages and open APIs, without logging in** to your accounts. So **private** profiles, groups, direct messages and closed channels are not available — and that applies to every social network, including Instagram and Facebook."
        ),
        stepsRU = listOf(
            "Вставьте ссылку на канал или страницу.",
            "Попросите: «что нового за неделю?» или «сделай дайджест»."
        ),
        stepsEN = listOf(
            "Paste a link to the channel or page.",
            "Ask: “what's new this week?” or “make a digest”."
        ),
        tipsRU = listOf(
            "Дайджест из трёх каналов: вставьте три ссылки и попросите «главное за сегодня одним списком»."
        ),
        tipsEN = listOf(
            "A digest from three channels: paste three links and ask for “today's highlights in one list”."
        ),
        related = listOf("youtube", "web-search")
    )
}

// MARK: - Содержание: таблицы

internal val tablesSection: HelpSection get() {
    val items: List<HelpArticle> = listOf(articleTables, articleTableExport)
    return HelpSection(id = "tables", symbol = "tablecells", tint = HelpTint.green,
                       titleRU = "Таблицы", titleEN = "Tables", articles = items)
}

private val articleTables: HelpArticle get() {
    return HelpArticle(
        id = "tables", symbol = "tablecells", tint = HelpTint.green,
        titleRU = "Таблицы в чате", titleEN = "Tables in the chat",
        summaryRU = "Редактируемые таблицы: ячейки, строки, столбцы — а нейросеть видит ваши правки.",
        summaryEN = "Editable tables: cells, rows, columns — and the assistant sees your edits.",
        bodyRU = listOf(
            "Honer AI создаёт таблицы прямо в чате: бюджет, расписание, список покупок, сравнение товаров. Таблицы бывают двух видов — **редактируемые** и **только для чтения**.",
            "Редактируемую таблицу можно **открыть на весь экран**: менять значения в ячейках, **добавлять и удалять строки и столбцы**, переименовывать заголовки.",
            "Нейросеть **видит ваши правки**. Попросите «пересчитай итог», «добавь столбец с процентами», «отсортируй по цене» — и она изменит таблицу."
        ),
        bodyEN = listOf(
            "Honer AI creates tables right in the chat: a budget, a schedule, a shopping list, a product comparison. Tables come in two kinds — **editable** and **read-only**.",
            "An editable table can be **opened full screen**: change cell values, **add and delete rows and columns**, rename headers.",
            "The assistant **sees your edits**. Ask “recalculate the total”, “add a percentage column”, “sort by price” — and it updates the table."
        ),
        stepsRU = listOf(
            "Попросите: «сделай таблицу бюджета на месяц».",
            "Нажмите на таблицу, чтобы открыть её на весь экран.",
            "Нажмите на ячейку и измените значение; кнопками добавьте строку или столбец.",
            "Вернитесь в чат и попросите нейросеть что-то пересчитать."
        ),
        stepsEN = listOf(
            "Ask: “make a monthly budget table”.",
            "Tap the table to open it full screen.",
            "Tap a cell and change the value; use the buttons to add a row or column.",
            "Go back to the chat and ask the assistant to recalculate something."
        ),
        tipsRU = listOf(
            "Скажите «сделай редактируемую таблицу», если хотите менять её сами.",
            "Прикрепите Excel или CSV и попросите «покажи как таблицу» — данные можно будет править."
        ),
        tipsEN = listOf(
            "Say “make an editable table” if you want to change it yourself.",
            "Attach an Excel or CSV file and ask to “show it as a table” — you can then edit the data."
        ),
        demo = HelpDemo.table,
        related = listOf("table-export", "documents", "formatting")
    )
}

private val articleTableExport: HelpArticle get() {
    return HelpArticle(
        id = "table-export", symbol = "square.and.arrow.up", tint = HelpTint.teal,
        titleRU = "Экспорт таблицы", titleEN = "Exporting a table",
        summaryRU = "Сохраните таблицу в CSV или скопируйте её.",
        summaryEN = "Save a table as CSV or copy it.",
        bodyRU = listOf(
            "Любую таблицу можно **экспортировать в CSV** — такой файл открывается в Excel, Numbers и Google Таблицах. Или просто **скопировать** её и вставить в заметку, письмо или документ.",
            "Кнопки экспорта и копирования есть в полноэкранном режиме таблицы."
        ),
        bodyEN = listOf(
            "Any table can be **exported to CSV** — the file opens in Excel, Numbers and Google Sheets. Or just **copy** it and paste into a note, email or document.",
            "Export and copy buttons are in the table's full-screen view."
        ),
        stepsRU = listOf(
            "Откройте таблицу на весь экран.",
            "Нажмите **«Поделиться»** → CSV или **«Копировать»**."
        ),
        stepsEN = listOf(
            "Open the table full screen.",
            "Tap **Share** → CSV, or **Copy**."
        ),
        tipsRU = listOf(
            "CSV удобно отправить в «Файлы» (Загрузки) и открыть на компьютере."
        ),
        tipsEN = listOf(
            "Save the CSV to Files and open it on your computer."
        ),
        related = listOf("tables")
    )
}

// MARK: - Содержание: память и инструкции

internal val memorySection: HelpSection get() {
    val items: List<HelpArticle> = listOf(articleMemory, articlePinnedInstructions, articleInstructionLibrary, articleMemoryVsInstructions)
    return HelpSection(id = "memory", symbol = "brain", tint = HelpTint.pink,
                       titleRU = "Память и инструкции", titleEN = "Memory and instructions", articles = items)
}

private val articleMemory: HelpArticle get() {
    return HelpArticle(
        id = "memory", symbol = "brain", tint = HelpTint.pink,
        titleRU = "Долговременная память", titleEN = "Long-term memory",
        summaryRU = "Нейросеть запоминает важное о вас и использует это во всех чатах.",
        summaryEN = "The assistant remembers what matters about you and uses it in every chat.",
        bodyRU = listOf(
            "Honer AI **сам сохраняет факты**, которые стоит помнить: «у меня кошка Муся», «я вегетарианец», «предпочитаю короткие ответы». Эти факты используются **во всех чатах**, а не только в текущем.",
            "Все факты видны в **Настройки → Память Honer AI**. Там их можно **добавить** вручную, **изменить** или **удалить**. Факт до 1200 символов.",
            "Чтобы сохранить что-то из переписки, долго нажмите на сообщение → **«Запомнить»**. Отключить использование памяти между чатами можно в тех же настройках."
        ),
        bodyEN = listOf(
            "Honer AI **saves facts on its own** when they're worth remembering: “I have a cat called Mia”, “I'm vegetarian”, “I prefer short answers”. These facts are used **in every chat**, not just the current one.",
            "All facts are in **Settings → Honer AI memory**. There you can **add** them manually, **edit** or **delete** them. A fact can be up to 1200 characters.",
            "To save something from a conversation, long-press the message → **Remember**. You can turn off cross-chat memory in the same settings."
        ),
        stepsRU = listOf(
            "Откройте **☰ → профиль → Настройки → Память Honer AI**.",
            "Нажмите **+**, чтобы добавить факт, или значок корзины, чтобы удалить.",
            "Нажмите на факт, чтобы изменить его."
        ),
        stepsEN = listOf(
            "Open **☰ → profile → Settings → Honer AI memory**.",
            "Tap **+** to add a fact, or the trash icon to delete it.",
            "Tap a fact to edit it."
        ),
        tipsRU = listOf(
            "Скажите «запомни: я живу в Казани» — факт сохранится сразу.",
            "Скажите «забудь про мою диету» — нейросеть удалит этот факт."
        ),
        tipsEN = listOf(
            "Say “remember: I live in Kazan” — the fact is saved immediately.",
            "Say “forget about my diet” — the assistant deletes that fact."
        ),
        screenshot = "guide-memory",
        related = listOf("memory-vs-instructions", "pinned-instructions", "what-ai-knows")
    )
}

private val articlePinnedInstructions: HelpArticle get() {
    return HelpArticle(
        id = "pinned-instructions", symbol = "pin", tint = HelpTint.orange,
        titleRU = "Закреплённые инструкции", titleEN = "Pinned instructions",
        summaryRU = "Превратите сообщение в правило, которому нейросеть следует в каждом ответе чата.",
        summaryEN = "Turn a message into a rule the assistant follows in every answer of the chat.",
        bodyRU = listOf(
            "Долго нажмите на любое сообщение (своё или ответ нейросети) и выберите **«Закрепить как инструкцию»**. Например: «Отвечай только по-английски», «Пиши как для ребёнка 10 лет», «Всегда давай код на Swift».",
            "Закреплённая инструкция показывается **вверху чата**. Honer AI видит её **в каждом ответе этого чата** и понимает, что её закрепили вы.",
            "Инструкцию можно **изменить**, **открепить** или **удалить**. Откреплённые инструкции сохраняются в **библиотеку** — их можно снова закрепить в любом чате."
        ),
        bodyEN = listOf(
            "Long-press any message (yours or the assistant's) and choose **Pin as instruction**. For example: “Reply only in English”, “Write as if for a 10-year-old”, “Always give code in Swift”.",
            "A pinned instruction appears **at the top of the chat**. Honer AI sees it **in every answer in this chat** and knows you pinned it.",
            "You can **edit**, **unpin** or **delete** an instruction. Unpinned instructions go to the **library** — you can pin them again in any chat."
        ),
        stepsRU = listOf(
            "Напишите правило обычным сообщением.",
            "Долго нажмите на него → **«Закрепить как инструкцию»**.",
            "Нажмите на плашку вверху чата, чтобы изменить или открепить."
        ),
        stepsEN = listOf(
            "Write the rule as a normal message.",
            "Long-press it → **Pin as instruction**.",
            "Tap the banner at the top of the chat to edit or unpin."
        ),
        tipsRU = listOf(
            "Сделайте отдельные чаты-помощники: «Переводчик» с инструкцией «переводи всё на английский», «Шеф-повар», «Репетитор по химии»."
        ),
        tipsEN = listOf(
            "Make dedicated helper chats: a “Translator” with the instruction “translate everything into Russian”, a “Chef”, a “Chemistry tutor”."
        ),
        screenshot = "guide-pinned-instruction",
        related = listOf("instruction-library", "memory-vs-instructions", "message-menu")
    )
}

private val articleInstructionLibrary: HelpArticle get() {
    return HelpArticle(
        id = "instruction-library", symbol = "text.book.closed", tint = HelpTint.indigo,
        titleRU = "Библиотека инструкций", titleEN = "Instruction library",
        summaryRU = "Все закреплённые и сохранённые инструкции в одном месте.",
        summaryEN = "All pinned and saved instructions in one place.",
        bodyRU = listOf(
            "Экран **«Инструкции чата»** показывает, что закреплено в этом чате (и кто закрепил — вы или это ответ Honer AI), а ниже — **сохранённые инструкции** из библиотеки.",
            "Отсюда инструкции можно менять, откреплять, удалять и закреплять снова. Открыть экран можно через **••• → Инструкции чата** или нажав на плашку инструкции вверху чата."
        ),
        bodyEN = listOf(
            "The **Chat instructions** screen shows what's pinned in this chat (and whether it came from you or from a Honer AI answer), and below it the **saved instructions** from the library.",
            "From here you can edit, unpin, delete and re-pin instructions. Open it via **••• → Instructions** or by tapping the instruction banner at the top of the chat."
        ),
        stepsRU = listOf(
            "Нажмите **•••** в чате → **Инструкции**.",
            "Выберите сохранённую инструкцию и нажмите **«Закрепить»**."
        ),
        stepsEN = listOf(
            "Tap **•••** in the chat → **Instructions**.",
            "Pick a saved instruction and tap **Pin**."
        ),
        tipsRU = listOf(
            "Держите в библиотеке готовые стили: «кратко», «подробно с примерами», «официальный тон»."
        ),
        tipsEN = listOf(
            "Keep ready-made styles in the library: “brief”, “detailed with examples”, “formal tone”."
        ),
        screenshot = "guide-instructions",
        related = listOf("pinned-instructions", "memory")
    )
}

private val articleMemoryVsInstructions: HelpArticle get() {
    return HelpArticle(
        id = "memory-vs-instructions", symbol = "arrow.left.arrow.right", tint = HelpTint.gray,
        titleRU = "Память ≠ инструкции", titleEN = "Memory ≠ instructions",
        summaryRU = "Память — факты о вас во всех чатах. Инструкции — правила ответа в одном чате.",
        summaryEN = "Memory is facts about you in every chat. Instructions are answer rules in one chat.",
        bodyRU = listOf(
            "**Память** — это факты о вас: имя питомца, город, профессия, вкусы. Она общая для всех чатов и хранится в настройках.",
            "**Инструкции** — это правила ответа: язык, стиль, формат, роль. Они действуют только в том чате, где закреплены.",
            "Пример: «я аллергик на орехи» — в память. «Отвечай списком из трёх пунктов» — в инструкцию."
        ),
        bodyEN = listOf(
            "**Memory** is facts about you: your pet's name, your city, job, tastes. It's shared by all chats and lives in settings.",
            "**Instructions** are answer rules: language, style, format, role. They apply only in the chat where they're pinned.",
            "Example: “I'm allergic to nuts” goes to memory. “Answer as a three-point list” goes to an instruction."
        ),
        related = listOf("memory", "pinned-instructions")
    )
}

// MARK: - Содержание: чаты

internal val chatsSection: HelpSection get() {
    val items: List<HelpArticle> = listOf(articleHistory, articlePinDrag, articleArchive, articleFind, articleBranches, articleNavigationLines, articleChatInfo)
    return HelpSection(id = "chats", symbol = "sidebar.left", tint = HelpTint.blue,
                       titleRU = "Чаты", titleEN = "Chats", articles = items)
}

private val articleHistory: HelpArticle get() {
    return HelpArticle(
        id = "history", symbol = "sidebar.left", tint = HelpTint.blue,
        titleRU = "История чатов", titleEN = "Chat history",
        summaryRU = "Все разговоры по датам, поиск по содержимому, переименование и удаление.",
        summaryEN = "All conversations by date, content search, renaming and deleting.",
        bodyRU = listOf(
            "Нажмите **☰** — откроется боковая панель. Чаты сгруппированы: **Закреплено**, **Сегодня**, **Вчера**, **7 дней** и ранее. Название чата придумывается автоматически по первому сообщению.",
            "Поле **«Поиск в содержимом…»** ищет не только по названиям, но и **по тексту всех сообщений** во всех чатах.",
            "Кнопка **•••** у чата: переименовать, закрепить, в архив, удалить. Кнопка со списком справа от «Закреплено» включает **выбор нескольких чатов** — чтобы удалить или архивировать сразу несколько."
        ),
        bodyEN = listOf(
            "Tap **☰** to open the sidebar. Chats are grouped: **Pinned**, **Today**, **Yesterday**, **7 days** and older. A chat's title is generated automatically from the first message.",
            "The **Search content…** field looks not only at titles but at **the text of every message** in every chat.",
            "The **•••** button next to a chat: rename, pin, archive, delete. The list button to the right of Pinned turns on **multi-select** — to delete or archive several chats at once."
        ),
        stepsRU = listOf(
            "Нажмите **☰**.",
            "Найдите чат в списке или через поиск.",
            "Нажмите **•••** рядом с чатом, чтобы переименовать или удалить."
        ),
        stepsEN = listOf(
            "Tap **☰**.",
            "Find a chat in the list or via search.",
            "Tap **•••** next to a chat to rename or delete it."
        ),
        tipsRU = listOf(
            "В **Настройки → Управление данными** можно включить автоудаление старых чатов; закреплённые чаты при этом сохраняются."
        ),
        tipsEN = listOf(
            "In **Settings → Data management** you can turn on auto-delete for old chats; pinned chats are kept."
        ),
        screenshot = "guide-history",
        related = listOf("pin-drag", "archive", "find")
    )
}

private val articlePinDrag: HelpArticle get() {
    return HelpArticle(
        id = "pin-drag", symbol = "pin.circle", tint = HelpTint.orange,
        titleRU = "Закрепление и перетаскивание", titleEN = "Pinning and drag & drop",
        summaryRU = "Закрепите важные чаты сверху и расставьте их в нужном порядке.",
        summaryEN = "Keep important chats on top and arrange them in any order.",
        bodyRU = listOf(
            "Закреплённые чаты всегда наверху боковой панели. Закрепить можно через **••• → Закрепить** — или просто **перетащить** чат в область **«Закреплено»**.",
            "Порядок закреплённых чатов меняется **перетаскиванием**: нажмите и удерживайте чат, затем двигайте вверх или вниз. Стрелки ▲▼ рядом с закреплённым чатом делают то же одним нажатием.",
            "Чтобы открепить, выберите **••• → Открепить** или перетащите чат из закреплённых обратно в список."
        ),
        bodyEN = listOf(
            "Pinned chats always stay at the top of the sidebar. Pin via **••• → Pin** — or simply **drag** a chat onto the **Pinned** area.",
            "Reorder pinned chats by **dragging**: press and hold a chat, then move it up or down. The ▲▼ arrows next to a pinned chat do the same with a tap.",
            "To unpin, choose **••• → Unpin** or drag the chat out of Pinned back into the list."
        ),
        stepsRU = listOf(
            "Откройте **☰**.",
            "Нажмите и удерживайте чат, пока он не «приподнимется».",
            "Перетащите его в раздел **«Закреплено»** и отпустите."
        ),
        stepsEN = listOf(
            "Open **☰**.",
            "Press and hold a chat until it lifts.",
            "Drag it into **Pinned** and let go."
        ),
        tipsRU = listOf(
            "Закреплённые чаты не удаляются автоудалением — закрепите всё ценное."
        ),
        tipsEN = listOf(
            "Pinned chats are never auto-deleted — pin anything valuable."
        ),
        demo = HelpDemo.dragChat,
        related = listOf("history", "archive")
    )
}

private val articleArchive: HelpArticle get() {
    return HelpArticle(
        id = "archive", symbol = "archivebox", tint = HelpTint.brown,
        titleRU = "Архив", titleEN = "Archive",
        summaryRU = "Уберите чат с глаз, не удаляя его, и верните в любой момент.",
        summaryEN = "Hide a chat without deleting it, and bring it back any time.",
        bodyRU = listOf(
            "**В архив** — это «убрать, но не удалять». Чат исчезает из боковой панели, но вся переписка сохраняется.",
            "Архив открывается в **Настройки → Архив чатов** (рядом видно, сколько там чатов). Нажмите **«Восстановить»**, чтобы вернуть чат в список, или удалите его навсегда."
        ),
        bodyEN = listOf(
            "**Archive** means “put away, don't delete”. The chat disappears from the sidebar but the whole conversation is kept.",
            "Open the archive in **Settings → Archived chats** (the count is shown next to it). Tap **Restore** to bring a chat back, or delete it for good."
        ),
        stepsRU = listOf(
            "Чтобы архивировать: **☰ → ••• у чата → В архив**.",
            "Чтобы вернуть: **Настройки → Архив чатов → Восстановить**."
        ),
        stepsEN = listOf(
            "To archive: **☰ → ••• next to the chat → Archive**.",
            "To restore: **Settings → Archived chats → Restore**."
        ),
        tipsRU = listOf(
            "Архивируйте завершённые проекты — список останется коротким, а история не потеряется."
        ),
        tipsEN = listOf(
            "Archive finished projects — your list stays short and nothing is lost."
        ),
        related = listOf("history", "pin-drag", "backups")
    )
}

private val articleFind: HelpArticle get() {
    return HelpArticle(
        id = "find", symbol = "magnifyingglass.circle", tint = HelpTint.teal,
        titleRU = "Поиск в чате и по всем чатам", titleEN = "Find in chat and across chats",
        summaryRU = "Найдите слово в текущем разговоре или во всей истории.",
        summaryEN = "Find a word in the current conversation or in your whole history.",
        bodyRU = listOf(
            "**Найти в чате**: **••• → Найти в чате**. Введите слово — совпадения подсветятся, счётчик покажет «2 / 5», стрелки ▲▼ переключают между ними.",
            "**Поиск по всем чатам**: откройте **☰** и введите запрос в **«Поиск в содержимом…»** — появятся все чаты, где встречаются эти слова."
        ),
        bodyEN = listOf(
            "**Find in chat**: **••• → Find in chat**. Type a word — matches are highlighted, a counter shows “2 / 5” and the ▲▼ arrows jump between them.",
            "**Search all chats**: open **☰** and type into **Search content…** — every chat containing those words appears."
        ),
        stepsRU = listOf(
            "Нажмите **•••** → **Найти в чате**.",
            "Введите слово.",
            "Переходите по совпадениям стрелками; **✕** закрывает поиск."
        ),
        stepsEN = listOf(
            "Tap **•••** → **Find in chat**.",
            "Type a word.",
            "Jump through matches with the arrows; **✕** closes the search."
        ),
        tipsRU = listOf(
            "Ищите по корню слова: «дел» найдёт и «дела», и «делать»."
        ),
        tipsEN = listOf(
            "Search by word stem: “cook” finds “cook”, “cooking” and “cookies”."
        ),
        screenshot = "guide-find",
        related = listOf("history", "navigation-lines")
    )
}

private val articleBranches: HelpArticle get() {
    return HelpArticle(
        id = "branches", symbol = "arrow.triangle.branch", tint = HelpTint.purple,
        titleRU = "Ветки: «Продолжить отсюда»", titleEN = "Branches: “Continue from here”",
        summaryRU = "Начните новую линию разговора с любого сообщения, не теряя старую.",
        summaryEN = "Start a new line of conversation from any message without losing the old one.",
        bodyRU = listOf(
            "Хотите попробовать другой вариант с середины разговора? Долго нажмите на сообщение → **«Продолжить в ветке»** (или **«Продолжить отсюда»** в предпросмотре линий навигации).",
            "Создастся новый чат **«Ветка · Название»** со всей перепиской до этого сообщения. Исходный чат не меняется — можно развивать обе версии параллельно."
        ),
        bodyEN = listOf(
            "Want to try a different direction from the middle of a conversation? Long-press a message → **Continue in a branch** (or **Continue from here** in the navigation-line preview).",
            "A new chat **“Branch · Title”** is created with the whole conversation up to that message. The original chat stays unchanged — you can develop both versions in parallel."
        ),
        stepsRU = listOf(
            "Долго нажмите на сообщение.",
            "Выберите **«Продолжить в ветке»**.",
            "Продолжайте разговор в новой ветке."
        ),
        stepsEN = listOf(
            "Long-press a message.",
            "Choose **Continue in a branch**.",
            "Carry on the conversation in the new branch."
        ),
        tipsRU = listOf(
            "Удобно для сравнения: в одной ветке «сделай формально», в другой — «сделай с юмором»."
        ),
        tipsEN = listOf(
            "Handy for comparisons: one branch “make it formal”, another “make it funny”."
        ),
        screenshot = "guide-branches",
        related = listOf("navigation-lines", "message-menu", "history")
    )
}

private val articleNavigationLines: HelpArticle get() {
    return HelpArticle(
        id = "navigation-lines", symbol = "line.3.horizontal", tint = HelpTint.indigo,
        titleRU = "Линии навигации", titleEN = "Navigation lines",
        summaryRU = "Полоски у правого края: предпросмотр сообщения и быстрый переход.",
        summaryEN = "Lines at the right edge: message preview and quick jumps.",
        bodyRU = listOf(
            "У правого края чата видны короткие **горизонтальные линии** — каждая соответствует сообщению. Длинная линия подсвечивает, где вы сейчас.",
            "**Нажмите и удерживайте** линию — появится карточка-предпросмотр: кто написал, время и начало текста. Ведите пальцем вверх-вниз, чтобы просматривать сообщения, и отпустите, чтобы **перейти** к нужному. В карточке также есть **«Продолжить отсюда»**."
        ),
        bodyEN = listOf(
            "At the right edge of the chat there are short **horizontal lines** — one per message. The longer highlighted line shows where you are.",
            "**Press and hold** a line to see a preview card: who wrote it, the time and the start of the text. Slide your finger up and down to browse messages and let go to **jump** to one. The card also has **Continue from here**."
        ),
        stepsRU = listOf(
            "Нажмите и удерживайте линию у правого края.",
            "Проведите пальцем к нужному сообщению.",
            "Отпустите — чат прокрутится к нему."
        ),
        stepsEN = listOf(
            "Press and hold a line at the right edge.",
            "Slide to the message you want.",
            "Let go — the chat scrolls to it."
        ),
        tipsRU = listOf(
            "В длинных чатах это быстрее, чем листать: сотни сообщений за одно движение."
        ),
        tipsEN = listOf(
            "In long chats this is faster than scrolling: hundreds of messages in one gesture."
        ),
        screenshot = "guide-navigation",
        related = listOf("branches", "find")
    )
}

private val articleChatInfo: HelpArticle get() {
    return HelpArticle(
        id = "chat-info", symbol = "info.circle", tint = HelpTint.cyan,
        titleRU = "Информация о чате", titleEN = "Chat info",
        summaryRU = "Все фото, видео, голосовые, музыка, файлы и ссылки чата, а также хронология.",
        summaryEN = "All photos, videos, voice notes, music, files and links in a chat, plus a timeline.",
        bodyRU = listOf(
            "**••• → Информация о чате** собирает в одном месте всё, что было в разговоре: **фото, видео, голосовые сообщения, музыку и файлы** — отправленные вами и нейросетью, а также **все посещённые ссылки**.",
            "Есть **фильтры** (только фото, только файлы, только от вас, только от Honer AI) и **поиск**.",
            "Вкладка **«Хронология»** показывает по шагам, что происходило: вопросы, поиски в интернете, прочитанные сайты, созданные таблицы, правки фото."
        ),
        bodyEN = listOf(
            "**••• → Chat info** gathers everything from the conversation in one place: **photos, videos, voice messages, music and files** — sent by you and by the assistant — plus **every visited link**.",
            "There are **filters** (photos only, files only, from you, from Honer AI) and **search**.",
            "The **Timeline** tab shows step by step what happened: questions, web searches, sites read, tables created, photo edits."
        ),
        stepsRU = listOf(
            "Нажмите **•••** в чате.",
            "Выберите **«Информация о чате»**.",
            "Переключайте вкладки и фильтры."
        ),
        stepsEN = listOf(
            "Tap **•••** in the chat.",
            "Choose **Chat info**.",
            "Switch tabs and filters."
        ),
        tipsRU = listOf(
            "Потеряли ссылку, которую нейросеть открывала неделю назад? Она есть в «Ссылках»."
        ),
        tipsEN = listOf(
            "Lost a link the assistant opened last week? It's in Links."
        ),
        related = listOf("chat-screen", "sources", "attachments")
    )
}
