package com.honerai.app.ui.help

// СГЕНЕРИРОВАНО из HelpCenter.swift (iOS): содержание руководства на двух языках.
// Каждая статья — отдельный геттер, чтобы не упереться в предел размера метода JVM.
// Упоминания iPhone заменены на Android-аналоги.

// MARK: - Содержание: начало работы

internal val startSection: HelpSection get() {
    val items: List<HelpArticle> = listOf(articleFirstLaunch, articleChatScreen, articleComposer, articleThinking, articleSearchButton)
    return HelpSection(id = "start", symbol = "sparkles", tint = HelpTint.blue,
                       titleRU = "Начало работы", titleEN = "Getting started", articles = items)
}

private val articleFirstLaunch: HelpArticle get() {
    return HelpArticle(
        id = "first-launch", symbol = "person.crop.circle.badge.plus", tint = HelpTint.blue,
        titleRU = "Первый запуск", titleEN = "First launch",
        summaryRU = "Имя, дата рождения и язык: что спрашивает Honer AI при знакомстве и зачем.",
        summaryEN = "Name, birthday and language: what Honer AI asks when you first meet, and why.",
        bodyRU = listOf(
            "При первом запуске Honer AI предлагает познакомиться. Введите имя, ник или позывной — так нейросеть будет к вам обращаться. Имя появится в профиле, его можно поменять в любой момент в **Настройки → Настройки аккаунта**.",
            "Дата рождения необязательна. Если указать её, Honer AI будет знать ваш возраст и сможет, например, подбирать примеры и объяснения по возрасту или поздравить с днём рождения. Если не хотите — просто пропустите этот шаг.",
            "Там же выбирается язык приложения: **Русский** (по умолчанию) или **English**. Нейросеть отвечает на выбранном языке приложения. Если написать ей на другом языке или прямо попросить — она ответит так, как вы просите.",
            "Если вы уже пользовались приложением и сохраняли резервную копию, на первом экране есть кнопка **«Восстановить из резервной копии»** — чаты, вложения и память вернутся."
        ),
        bodyEN = listOf(
            "On first launch Honer AI asks to get acquainted. Enter your name, nickname or callsign — this is how the assistant will address you. The name shows up in your profile and can be changed any time in **Settings → Account settings**.",
            "Your birthday is optional. If you add it, Honer AI knows your age and can, for example, tailor examples and explanations or wish you a happy birthday. If you'd rather not, just skip it.",
            "The same screen lets you pick the app language: **Русский** (the default) or **English**. The assistant replies in the app language. If you write in another language or explicitly ask for one, it will answer the way you ask.",
            "If you used the app before and saved a backup, the first screen has a **Restore from backup** button — your chats, attachments and memory come back."
        ),
        stepsRU = listOf(
            "Откройте Honer AI — появится экран «Давайте познакомимся».",
            "Выберите язык: Русский или English.",
            "Введите имя (до 60 символов) и при желании включите дату рождения.",
            "Нажмите **«Начать общение»** — откроется новый чат."
        ),
        stepsEN = listOf(
            "Open Honer AI — the “Let's get acquainted” screen appears.",
            "Choose a language: Русский or English.",
            "Enter your name (up to 60 characters) and optionally turn on your birthday.",
            "Tap **Start chatting** — a new chat opens."
        ),
        tipsRU = listOf(
            "Язык можно сменить позже: **Настройки → Язык**. Интерфейс и язык ответов переключатся сразу.",
            "Имя можно писать как угодно — «Капитан», «Аня», «Док». Нейросеть будет использовать именно его."
        ),
        tipsEN = listOf(
            "You can switch the language later in **Settings → Language**. Both the interface and the reply language change instantly.",
            "Any name works — “Captain”, “Anna”, “Doc”. The assistant will use exactly that."
        ),
        screenshot = "guide-onboarding",
        related = listOf("language", "chat-screen", "what-ai-knows")
    )
}

private val articleChatScreen: HelpArticle get() {
    return HelpArticle(
        id = "chat-screen", symbol = "bubble.left.and.bubble.right", tint = HelpTint.indigo,
        titleRU = "Экран чата", titleEN = "The chat screen",
        summaryRU = "Где история чатов, как включить чтение вслух, начать новый чат и открыть меню.",
        summaryEN = "Where chat history lives, how to turn on read-aloud, start a new chat and open the menu.",
        bodyRU = listOf(
            "Вверху слева — кнопка **☰**. Она открывает боковую панель: там история всех чатов, поиск по содержимому, закреплённые чаты и ваш профиль с настройками. Панель также открывается свайпом от левого края.",
            "Справа вверху — две круглые кнопки. **Динамик** включает автоматическое чтение ответов вслух: пока он включён, Honer AI начинает читать ответ сразу, как только тот начинает появляться. Перечёркнутый динамик — озвучка выключена. Кнопка **с плюсом в облачке** создаёт новый чат.",
            "Кнопка **•••** — меню текущего чата: переименовать, закрепить, найти в чате, инструкции чата, информация о чате (все фото, видео, файлы и ссылки), архив и удаление.",
            "Под каждым ответом есть панель действий: реакция, копировать, нравится/не нравится, читать вслух, поделиться и повторить. Внизу — поле ввода сообщения."
        ),
        bodyEN = listOf(
            "Top left is the **☰** button. It opens the sidebar with your whole chat history, content search, pinned chats and your profile with settings. You can also open it by swiping from the left edge.",
            "Top right are two round buttons. The **speaker** turns on automatic read-aloud: while it's on, Honer AI starts reading an answer as soon as it begins to appear. A crossed-out speaker means read-aloud is off. The **bubble with a plus** starts a new chat.",
            "The **•••** button is the menu of the current chat: rename, pin, find in chat, chat instructions, chat info (all photos, videos, files and links), archive and delete.",
            "Under every answer there's an action bar: reaction, copy, like/dislike, read aloud, share and regenerate. The message field is at the bottom."
        ),
        stepsRU = listOf(
            "Нажмите **☰**, чтобы увидеть все чаты.",
            "Нажмите на динамик, чтобы ответы читались вслух автоматически.",
            "Нажмите на облачко с плюсом, чтобы начать новый разговор.",
            "Нажмите **•••**, чтобы управлять текущим чатом."
        ),
        stepsEN = listOf(
            "Tap **☰** to see all chats.",
            "Tap the speaker so answers are read aloud automatically.",
            "Tap the bubble with a plus to start a new conversation.",
            "Tap **•••** to manage the current chat."
        ),
        tipsRU = listOf(
            "Надпись «Сгенерированный ИИ ответ, только для справки» напоминает: важные факты (медицина, деньги, право) стоит перепроверять.",
            "Полоски у правого края экрана — навигация по сообщениям. Подробнее в статье «Линии навигации»."
        ),
        tipsEN = listOf(
            "The “AI-generated answer, for reference only” note is a reminder: double-check important facts (health, money, law).",
            "The short lines at the right edge are message navigation. See “Navigation lines”."
        ),
        screenshot = "guide-conversation",
        related = listOf("composer", "history", "navigation-lines", "chat-info")
    )
}

private val articleComposer: HelpArticle get() {
    return HelpArticle(
        id = "composer", symbol = "square.and.pencil", tint = HelpTint.teal,
        titleRU = "Поле ввода и кнопки", titleEN = "Message field and buttons",
        summaryRU = "Текст, вложения через «+», голосовой ввод, кнопки «Рассуждение» и «Поиск».",
        summaryEN = "Text, attachments via “+”, voice input, the Reasoning and Search buttons.",
        bodyRU = listOf(
            "Напишите сообщение в поле **«Напишите сообщение…»** и отправьте. Поле растёт вместе с текстом, черновик сохраняется, даже если вы переключитесь на другой чат.",
            "Кнопка **+** открывает вложения: недавние фото и видео, **Камера**, **Альбом** и **Файл**. Можно прикрепить сразу несколько файлов — они появятся над полем ввода миниатюрами.",
            "Кнопка **микрофона** включает голосовой ввод: нажмите один раз и говорите — текст распознаётся на лету. Нажмите ещё раз (кнопка станет квадратом ■), чтобы отправить. **«Отмена»** — выйти без отправки.",
            "Кнопки **«Рассуждение»** и **«Поиск»** под полем включают режим размышления и доступ в интернет. Включённая кнопка подсвечивается синим. Смайлик справа открывает стикеры и эмодзи."
        ),
        bodyEN = listOf(
            "Type into the **Message** field and send. The field grows with your text and the draft is kept even if you switch to another chat.",
            "The **+** button opens attachments: recent photos and videos, **Camera**, **Album** and **File**. You can attach several files at once — they appear above the field as thumbnails.",
            "The **microphone** button starts voice input: tap once and speak — the text is recognised as you talk. Tap again (the button turns into a square ■) to send. **Cancel** leaves without sending.",
            "The **Reasoning** and **Search** buttons below the field turn on thinking mode and internet access. An active button is highlighted in blue. The smiley on the right opens stickers and emoji."
        ),
        stepsRU = listOf(
            "Нажмите на поле и введите вопрос.",
            "При необходимости нажмите **+** и выберите фото или файл.",
            "Включите **«Рассуждение»** для сложной задачи или **«Поиск»** для свежих данных.",
            "Нажмите кнопку отправки."
        ),
        stepsEN = listOf(
            "Tap the field and type your question.",
            "If needed, tap **+** and pick a photo or a file.",
            "Turn on **Reasoning** for a hard task or **Search** for fresh data.",
            "Tap send."
        ),
        tipsRU = listOf(
            "Видео прикрепляются длиной до 2 минут и размером до 40 МБ.",
            "Можно отправить только вложение без текста — Honer AI сам поймёт, что с ним сделать, или спросит."
        ),
        tipsEN = listOf(
            "Videos can be attached up to 2 minutes long and 40 MB in size.",
            "You can send just an attachment with no text — Honer AI will work out what to do with it or ask."
        ),
        screenshot = "guide-welcome",
        related = listOf("attachments", "voice-input", "thinking-mode", "search-button")
    )
}

private val articleThinking: HelpArticle get() {
    return HelpArticle(
        id = "thinking-mode", symbol = "atom", tint = HelpTint.purple,
        titleRU = "Режим «Рассуждение»", titleEN = "Reasoning mode",
        summaryRU = "Нейросеть сначала думает, а вы видите ход её мыслей в реальном времени.",
        summaryEN = "The assistant thinks first, and you watch its reasoning live.",
        bodyRU = listOf(
            "Когда кнопка **«Рассуждение»** включена, Honer AI перед ответом обдумывает задачу: разбивает её на части, проверяет варианты, ищет ошибки. Это заметно улучшает ответы на задачи по математике, логике, программированию и на сложные вопросы.",
            "Рассуждение показывается вживую над ответом с заголовком **«Размышляю…»** и таймером. Когда ответ готов, заголовок превращается в **«Размышлял N секунд»**, а само рассуждение сворачивается. Нажмите на заголовок, чтобы развернуть или свернуть его снова.",
            "Даже очень длинное рассуждение выводится плавно и не подвешивает приложение. Если рассуждение было на английском, приложение показывает перевод на язык интерфейса."
        ),
        bodyEN = listOf(
            "When **Reasoning** is on, Honer AI thinks the task through before answering: it breaks it down, checks options and looks for mistakes. This noticeably improves answers for maths, logic, programming and tricky questions.",
            "The reasoning appears live above the answer with a **Thinking…** header and a timer. When the answer is ready, the header becomes **Thought for N seconds** and the reasoning collapses. Tap the header to expand or collapse it again.",
            "Even very long reasoning streams smoothly without freezing the app. If the reasoning was written in another language, the app shows a translation into the interface language."
        ),
        stepsRU = listOf(
            "Нажмите **«Рассуждение»** под полем ввода — кнопка станет синей.",
            "Задайте вопрос и наблюдайте за рассуждением.",
            "Нажмите на **«Размышлял…»**, чтобы свернуть или развернуть ход мыслей."
        ),
        stepsEN = listOf(
            "Tap **Reasoning** below the message field — it turns blue.",
            "Ask your question and watch the reasoning.",
            "Tap **Thought for…** to collapse or expand it."
        ),
        tipsRU = listOf(
            "Для простых вопросов («переведи слово», «сколько времени в Токио») рассуждение можно выключить — ответ придёт быстрее.",
            "Режим запоминается: если он включён, он останется включённым и в следующих сообщениях."
        ),
        tipsEN = listOf(
            "For simple questions (“translate a word”, “what time is it in Tokyo”) turn reasoning off — the answer arrives faster.",
            "The mode is remembered: once on, it stays on for the next messages."
        ),
        screenshot = "guide-thinking", demo = HelpDemo.typing,
        related = listOf("live-answers", "search-button", "composer")
    )
}

private val articleSearchButton: HelpArticle get() {
    return HelpArticle(
        id = "search-button", symbol = "globe", tint = HelpTint.cyan,
        titleRU = "Кнопка «Поиск»", titleEN = "The Search button",
        summaryRU = "Разрешает нейросети выходить в интернет, когда это действительно нужно.",
        summaryEN = "Lets the assistant go online when it's actually needed.",
        bodyRU = listOf(
            "Кнопка **«Поиск»** даёт Honer AI **возможность** искать в интернете — но не обязанность. Нейросеть сама решает, нужен ли поиск: для вопроса «сколько будет 2+2» она ответит сразу, а для «какая погода завтра» или «что нового у Apple» пойдёт в сеть.",
            "Когда поиск идёт, над ответом видна живая лента: какие сайты открываются и читаются. После ответа появляется карточка **«Источники ответа»** со списком прочитанных страниц и цитатами.",
            "Если поиск выключен, нейросеть отвечает по своим знаниям и не открывает сайты. Сведения о событиях после даты обучения модели в этом случае могут быть неполными."
        ),
        bodyEN = listOf(
            "The **Search** button gives Honer AI the **ability** to search the web — not an obligation. The assistant decides whether it's needed: for “what's 2+2” it answers right away, for “what's the weather tomorrow” or “what's new at Apple” it goes online.",
            "While searching, a live feed above the answer shows which sites are being opened and read. After the answer you get an **Answer sources** card with the pages it read and quotes.",
            "With Search off, the assistant answers from its own knowledge and opens no sites. Information about events after the model's training date may then be incomplete."
        ),
        stepsRU = listOf(
            "Нажмите **«Поиск»** — кнопка станет синей.",
            "Спросите о чём-то свежем: новости, цены, погода, расписание.",
            "Нажмите на карточку источников, чтобы открыть страницы и цитаты."
        ),
        stepsEN = listOf(
            "Tap **Search** — it turns blue.",
            "Ask about something current: news, prices, weather, timetables.",
            "Tap the sources card to open the pages and quotes."
        ),
        tipsRU = listOf(
            "Хотите, чтобы поиск был точно? Напишите прямо: «найди в интернете…» или «проверь в сети».",
            "Для большого исследования попросите: «прочитай 300 сайтов и сделай сводку» — см. статью «Глубокое исследование»."
        ),
        tipsEN = listOf(
            "Want to be sure it searches? Say it directly: “search the web for…” or “check online”.",
            "For big research ask: “read 300 sites and summarise” — see “Deep research”."
        ),
        screenshot = "guide-search",
        related = listOf("web-search", "deep-research", "sources")
    )
}

// MARK: - Содержание: общение и ответы

internal val chatSection: HelpSection get() {
    val items: List<HelpArticle> = listOf(articleLiveAnswers, articleFormatting, articleReactions, articleMessageMenu, articleSelectAsk, articleReadAloud)
    return HelpSection(id = "chat", symbol = "text.bubble", tint = HelpTint.indigo,
                       titleRU = "Общение и ответы", titleEN = "Conversation and answers", articles = items)
}

private val articleLiveAnswers: HelpArticle get() {
    return HelpArticle(
        id = "live-answers", symbol = "text.bubble", tint = HelpTint.blue,
        titleRU = "Живые ответы", titleEN = "Live answers",
        summaryRU = "Ответ печатается плавно, даже если он очень длинный.",
        summaryEN = "Answers type out smoothly, even very long ones.",
        bodyRU = listOf(
            "Honer AI выводит ответ по мере того, как он пишется, — плавно, как будто человек печатает. Не нужно ждать конца: можно читать с первых слов.",
            "Очень длинные ответы (статьи, код на сотни строк, большие таблицы) и длинные рассуждения выводятся без подвисаний: приложение дозирует текст и рисует его кусками.",
            "Ответ можно остановить кнопкой ■ во время генерации. Если ответ оборвался из-за связи, нажмите **«Повторить»** под ним."
        ),
        bodyEN = listOf(
            "Honer AI shows the answer as it's being written — smoothly, like a person typing. No need to wait for the end: start reading from the first words.",
            "Very long answers (articles, hundreds of lines of code, large tables) and long reasoning stream without freezing: the app paces the text and renders it in chunks.",
            "You can stop an answer with the ■ button while it's being generated. If an answer broke off because of the connection, tap **Regenerate** under it."
        ),
        stepsRU = listOf(
            "Отправьте вопрос.",
            "Читайте ответ, пока он печатается.",
            "Чтобы остановить генерацию, нажмите ■."
        ),
        stepsEN = listOf(
            "Send a question.",
            "Read the answer while it types.",
            "To stop generating, tap ■."
        ),
        tipsRU = listOf(
            "Если свернуть приложение, ответ допишется в фоне и придёт уведомление."
        ),
        tipsEN = listOf(
            "If you leave the app, the answer finishes in the background and you get a notification."
        ),
        demo = HelpDemo.typing,
        related = listOf("thinking-mode", "background-answers", "formatting")
    )
}

private val articleFormatting: HelpArticle get() {
    return HelpArticle(
        id = "formatting", symbol = "textformat", tint = HelpTint.orange,
        titleRU = "Оформление ответов", titleEN = "Rich answers",
        summaryRU = "Заголовки, списки, таблицы, формулы, код с копированием, цветной текст, карточки и диаграммы.",
        summaryEN = "Headings, lists, tables, formulas, copyable code, coloured text, cards and diagrams.",
        bodyRU = listOf(
            "Ответы оформлены как в хорошем документе: **заголовки**, *курсив*, списки, цитаты, ссылки. Таблицы рисуются настоящими таблицами, а математические формулы — формулами, а не набором символов.",
            "Код показывается в отдельных блоках с подсветкой синтаксиса и кнопкой **«Копировать»** — один тап, и весь блок в буфере обмена.",
            "Нейросеть умеет выделять важное **цветным текстом**, собирать информацию в **карточки** (например, карточка товара, фильма или места) и рисовать **диаграммы Mermaid**: схемы процессов, деревья, диаграммы последовательностей.",
            "Если оформление не нужно, так и скажите: «ответь простым текстом без таблиц»."
        ),
        bodyEN = listOf(
            "Answers look like a well-made document: **headings**, *italics*, lists, quotes, links. Tables are real tables, and maths formulas are rendered as formulas, not as a jumble of symbols.",
            "Code appears in separate blocks with syntax highlighting and a **Copy** button — one tap and the whole block is on your clipboard.",
            "The assistant can highlight key points with **coloured text**, pack information into **cards** (a product, a film or a place, for example) and draw **Mermaid diagrams**: flowcharts, trees, sequence diagrams.",
            "If you don't want formatting, just say so: “answer in plain text, no tables”."
        ),
        stepsRU = listOf(
            "Попросите: «сделай таблицу сравнения», «нарисуй схему процесса», «покажи формулу».",
            "Чтобы скопировать код, нажмите **«Копировать»** в углу блока кода."
        ),
        stepsEN = listOf(
            "Ask: “make a comparison table”, “draw a process diagram”, “show the formula”.",
            "To copy code, tap **Copy** in the corner of the code block."
        ),
        tipsRU = listOf(
            "Для схемы скажите: «нарисуй диаграмму mermaid» — получится наглядная картинка прямо в чате.",
            "Попросите «выдели главное цветом» — важные слова станут заметнее."
        ),
        tipsEN = listOf(
            "For a diagram say “draw a mermaid diagram” — you get a clear picture right in the chat.",
            "Ask to “highlight the key points in colour” to make important words stand out."
        ),
        related = listOf("tables", "live-answers", "message-menu")
    )
}

private val articleReactions: HelpArticle get() {
    return HelpArticle(
        id = "reactions", symbol = "face.smiling", tint = HelpTint.yellow,
        titleRU = "Реакции", titleEN = "Reactions",
        summaryRU = "Ставьте эмодзи на ответы — нейросеть их видит и тоже может реагировать.",
        summaryEN = "Put emoji on answers — the assistant sees them and can react too.",
        bodyRU = listOf(
            "Под ответом есть кнопка со смайликом. Нажмите её или **долго нажмите на ответ** и выберите реакцию: 👍, ❤️, 😂, 😮, 🔥 и другие.",
            "Honer AI **видит ваши реакции** на свои ответы и учитывает их: например, после 👎 постарается объяснить иначе, а 😂 поймёт как «смешно получилось».",
            "Нейросеть тоже может поставить реакцию эмодзи на **ваше** сообщение — когда это уместно, например на хорошую новость."
        ),
        bodyEN = listOf(
            "Under an answer there's a smiley button. Tap it, or **long-press the answer** and choose a reaction: 👍, ❤️, 😂, 😮, 🔥 and more.",
            "Honer AI **sees your reactions** to its answers and takes them into account: after 👎 it will try a different explanation, and 😂 reads as “that was funny”.",
            "The assistant can also react with an emoji to **your** message when it fits — to good news, for example."
        ),
        stepsRU = listOf(
            "Долго нажмите на ответ Honer AI.",
            "Выберите эмодзи в строке реакций.",
            "Чтобы убрать реакцию, нажмите на неё ещё раз."
        ),
        stepsEN = listOf(
            "Long-press an answer from Honer AI.",
            "Pick an emoji in the reactions row.",
            "To remove a reaction, tap it again."
        ),
        tipsRU = listOf(
            "Кнопки 👍/👎 тоже работают как сигнал: нейросеть поймёт, что понравилось, а что нет."
        ),
        tipsEN = listOf(
            "The 👍/👎 buttons work as a signal too: the assistant learns what you liked and what you didn't."
        ),
        screenshot = "guide-assistant-menu",
        related = listOf("message-menu", "select-ask")
    )
}

private val articleMessageMenu: HelpArticle get() {
    return HelpArticle(
        id = "message-menu", symbol = "ellipsis.bubble", tint = HelpTint.indigo,
        titleRU = "Меню сообщения", titleEN = "Message menu",
        summaryRU = "Копировать, выделить, цитировать, редактировать, повторить, закрепить, ветка, поделиться.",
        summaryEN = "Copy, select, quote, edit, regenerate, pin, branch, share.",
        bodyRU = listOf(
            "Долгое нажатие на любое сообщение открывает меню. Для **вашего сообщения**: Копировать, Закрепить как инструкцию, Выбрать текст, **Редактировать** (исправить и отправить заново), Продолжить в ветке, Запомнить, Поделиться.",
            "Для **ответа Honer AI**: Копировать, Закрепить как инструкцию, Выбрать текст, **Повторить** (сгенерировать ответ заново), Продолжить в ветке, Запомнить, Нравится / Не нравится, **Читать вслух**, Поделиться.",
            "**Цитата**: выберите «Цитировать» в меню сообщения или «Выбрать текст и спросить», выделите фрагмент и нажмите «Спросить Honer AI» — цитата появится над полем ввода, и нейросеть поймёт, о каком именно фрагменте вы спрашиваете. «Подробнее об этом» и «Объяснить проще» отправляют вопрос сразу.",
            "**Запомнить** сохраняет факт из сообщения в долговременную память, а **Закрепить как инструкцию** делает сообщение правилом для всех ответов этого чата."
        ),
        bodyEN = listOf(
            "Long-press any message to open its menu. For **your message**: Copy, Pin as instruction, Select text, **Edit** (fix and resend), Continue in a branch, Remember, Share.",
            "For **an answer from Honer AI**: Copy, Pin as instruction, Select text, **Regenerate**, Continue in a branch, Remember, Like / Dislike, **Read aloud**, Share.",
            "**Quote**: choose Quote in the message menu, or Select text and ask, highlight a fragment and tap Ask Honer AI — the quote appears above the message field and the assistant knows exactly which fragment you mean. Tell me more and Explain simpler send the question right away.",
            "**Remember** saves a fact from the message into long-term memory, and **Pin as instruction** turns the message into a rule for every answer in this chat."
        ),
        stepsRU = listOf(
            "Долго нажмите на сообщение.",
            "Выберите действие в меню.",
            "Для редактирования исправьте текст и нажмите отправить — ответ сгенерируется заново."
        ),
        stepsEN = listOf(
            "Long-press a message.",
            "Choose an action from the menu.",
            "To edit, fix the text and tap send — the answer is generated again."
        ),
        tipsRU = listOf(
            "**«Выбрать текст»** позволяет выделить и скопировать любой кусочек ответа, а не весь ответ целиком.",
            "Предыдущий вариант ответа после **«Повторить»** не теряется, если вы продолжаете в ветке."
        ),
        tipsEN = listOf(
            "**Select text** lets you highlight and copy any part of an answer instead of the whole thing.",
            "The previous answer isn't lost after **Regenerate** if you continue in a branch."
        ),
        screenshot = "guide-message-menu",
        related = listOf("select-ask", "pinned-instructions", "branches", "memory")
    )
}

private val articleSelectAsk: HelpArticle get() {
    return HelpArticle(
        id = "select-ask", symbol = "text.cursor", tint = HelpTint.pink,
        titleRU = "«Спросить Honer AI» о фрагменте", titleEN = "“Ask Honer AI” about a fragment",
        summaryRU = "Выделите часть ответа и задайте вопрос именно о ней.",
        summaryEN = "Select part of an answer and ask about exactly that part.",
        bodyRU = listOf(
            "Иногда непонятно одно слово или одна фраза в длинном ответе. Выделите её — в меню выделения появится пункт **«Спросить Honer AI»**.",
            "Выделенный фрагмент попадёт в поле ввода как цитата. Допишите вопрос — «объясни проще», «приведи пример», «а почему так?» — и отправьте. Нейросеть ответит именно про этот кусочек.",
            "В том же меню есть **«Копировать»**, если нужно просто взять часть текста."
        ),
        bodyEN = listOf(
            "Sometimes one word or phrase in a long answer is unclear. Select it — the selection menu gets an **Ask Honer AI** item.",
            "The selected fragment goes into the message field as a quote. Add your question — “explain it more simply”, “give an example”, “why is that?” — and send. The assistant answers about that exact piece.",
            "The same menu has **Copy** if you just need part of the text."
        ),
        stepsRU = listOf(
            "Долго нажмите на ответ и выберите **«Выбрать текст»**.",
            "Выделите нужный фрагмент, двигая маркеры.",
            "Нажмите **«Спросить Honer AI»**.",
            "Допишите вопрос и отправьте."
        ),
        stepsEN = listOf(
            "Long-press the answer and choose **Select text**.",
            "Drag the handles to select the fragment.",
            "Tap **Ask Honer AI**.",
            "Add your question and send."
        ),
        tipsRU = listOf(
            "Так удобно разбирать сложные тексты: выделяйте термин за термином и спрашивайте по очереди."
        ),
        tipsEN = listOf(
            "Great for complex texts: select one term at a time and ask about each."
        ),
        demo = HelpDemo.selectAsk,
        related = listOf("message-menu", "reactions")
    )
}

private val articleReadAloud: HelpArticle get() {
    return HelpArticle(
        id = "read-aloud", symbol = "speaker.wave.2", tint = HelpTint.green,
        titleRU = "Чтение вслух", titleEN = "Read aloud",
        summaryRU = "Нейросеть читает ответы естественным голосом — даже очень длинные.",
        summaryEN = "The assistant reads answers in a natural voice — even very long ones.",
        bodyRU = listOf(
            "Чтобы прослушать один ответ, нажмите кнопку динамика под ним или выберите **«Читать вслух»** в меню сообщения.",
            "Чтобы слушать все ответы автоматически, включите **динамик вверху экрана**. Тогда чтение начинается сразу, как только ответ начинает печататься, — не нужно ждать, пока он допишется.",
            "Длинные тексты читаются целиком: код, таблицы и служебные символы пропускаются или проговариваются понятно. Русский текст читает русский голос, английские слова — английский голос того же пола."
        ),
        bodyEN = listOf(
            "To hear a single answer, tap the speaker button under it or choose **Read aloud** in the message menu.",
            "To hear every answer automatically, turn on the **speaker at the top of the screen**. Reading then starts as soon as the answer begins to type — no need to wait for it to finish.",
            "Long texts are read in full: code, tables and technical symbols are skipped or spoken sensibly. Russian text is read by a Russian voice and English words by an English voice of the same gender."
        ),
        stepsRU = listOf(
            "Нажмите динамик вверху справа — он перестанет быть перечёркнутым.",
            "Задайте вопрос — ответ начнёт читаться сразу.",
            "Чтобы остановить, нажмите динамик ещё раз."
        ),
        stepsEN = listOf(
            "Tap the speaker at the top right — it's no longer crossed out.",
            "Ask something — the answer starts being read right away.",
            "To stop, tap the speaker again."
        ),
        tipsRU = listOf(
            "Скорость чтения и голос (мужской по умолчанию или женский) меняются в **Настройки → Голос**."
        ),
        tipsEN = listOf(
            "Reading speed and voice (male by default, or female) are in **Settings → Voice**."
        ),
        related = listOf("voices", "voice-input", "chat-screen")
    )
}

// MARK: - Содержание: вопросы и тесты

internal val questionsSection: HelpSection get() {
    val items: List<HelpArticle> = listOf(articleClarifyingQuestions, articleQuizzes)
    return HelpSection(id = "questions", symbol = "questionmark.bubble", tint = HelpTint.orange,
                       titleRU = "Вопросы и тесты", titleEN = "Questions and quizzes", articles = items)
}

private val articleClarifyingQuestions: HelpArticle get() {
    return HelpArticle(
        id = "clarifying-questions", symbol = "questionmark.bubble", tint = HelpTint.orange,
        titleRU = "Уточняющие вопросы", titleEN = "Clarifying questions",
        summaryRU = "Карточки с вариантами А/Б/В, своим ответом и таймером на 10 секунд.",
        summaryEN = "Cards with A/B/C options, your own answer and a 10-second timer.",
        bodyRU = listOf(
            "Если задача неоднозначна, Honer AI может сначала спросить вас — не текстом, а удобной **карточкой с вариантами** А, Б, В. Нажмите на подходящий вариант или напишите **свой ответ** в поле под ними.",
            "Вопросов может быть несколько — до **30** подряд (например, чтобы составить план тренировок или подобрать подарок). Вверху карточки видно, какой это вопрос по счёту.",
            "На каждый вопрос даётся **10 секунд** — это видно по кольцу-таймеру. Если не ответить, вопрос закроется и нейросеть **решит сама**, выбрав самый разумный вариант, и продолжит работу. Так ответ не зависнет, если вы отвлеклись."
        ),
        bodyEN = listOf(
            "If a task is ambiguous, Honer AI may ask you first — not in plain text but with a handy **card of options** A, B, C. Tap the right option or type **your own answer** in the field below.",
            "There can be several questions — up to **30** in a row (to build a workout plan or choose a gift, for example). The card shows which question you're on.",
            "Each question has **10 seconds** — shown by the countdown ring. If you don't answer, the question closes and the assistant **decides by itself**, picking the most sensible option, and carries on. So the answer never gets stuck if you're distracted."
        ),
        stepsRU = listOf(
            "Когда появилась карточка, прочитайте вопрос.",
            "Нажмите вариант А, Б или В — или впишите свой ответ.",
            "Не успели? Ничего страшного: нейросеть выберет сама."
        ),
        stepsEN = listOf(
            "When a card appears, read the question.",
            "Tap option A, B or C — or type your own.",
            "Missed it? No problem: the assistant chooses by itself."
        ),
        tipsRU = listOf(
            "Попросите «задай мне вопросы, чтобы понять, что мне нужно» — нейросеть проведёт короткое интервью.",
            "Не хотите вопросов? Напишите «не задавай уточнений, решай сам»."
        ),
        tipsEN = listOf(
            "Ask “ask me questions to figure out what I need” — the assistant runs a short interview.",
            "Don't want questions? Write “don't ask, just decide yourself”."
        ),
        demo = HelpDemo.questionTimer,
        related = listOf("quizzes", "message-menu")
    )
}

private val articleQuizzes: HelpArticle get() {
    return HelpArticle(
        id = "quizzes", symbol = "checklist", tint = HelpTint.green,
        titleRU = "Тесты и викторины", titleEN = "Tests and quizzes",
        summaryRU = "Викторины, IQ-тесты и контрольные с картинками, звуком и мгновенной проверкой.",
        summaryEN = "Quizzes, IQ tests and practice tests with pictures, audio and instant scoring.",
        bodyRU = listOf(
            "Попросите Honer AI устроить тест: «проверь мои знания по истории», «IQ-тест на 15 вопросов», «викторина по фильмам Marvel». Вопросы приходят теми же карточками с вариантами.",
            "Внутри вопроса могут быть **картинки** (например, задачи на логику с фигурами), **аудио** (угадай мелодию или произношение) или **файлы**.",
            "Проверка — **мгновенная**: правильный ответ подсвечивается зелёным, неправильный — красным, а в конце показывается итог, например **2 из 3**. Затем нейросеть комментирует результат: объясняет ошибки или продолжает задание."
        ),
        bodyEN = listOf(
            "Ask Honer AI for a test: “check my history knowledge”, “a 15-question IQ test”, “a Marvel movie quiz”. Questions arrive as the same option cards.",
            "A question can include **images** (logic puzzles with shapes, for example), **audio** (guess the tune or the pronunciation) or **files**.",
            "Scoring is **instant**: a correct answer lights up green, a wrong one red, and at the end you see the total, e.g. **2 of 3**. Then the assistant comments on the result: explains mistakes or continues the task."
        ),
        stepsRU = listOf(
            "Напишите: «сделай тест из 10 вопросов по …».",
            "Отвечайте на карточки по одной.",
            "Посмотрите итог и попросите разобрать ошибки."
        ),
        stepsEN = listOf(
            "Write: “make a 10-question test on …”.",
            "Answer the cards one by one.",
            "See your score and ask to go through the mistakes."
        ),
        tipsRU = listOf(
            "Прикрепите конспект или учебник (PDF, Word) и попросите тест по нему — получится подготовка к экзамену.",
            "Скажите «сложнее» или «проще» — следующий тест подстроится."
        ),
        tipsEN = listOf(
            "Attach your notes or a textbook (PDF, Word) and ask for a test on it — instant exam prep.",
            "Say “harder” or “easier” — the next test adapts."
        ),
        demo = HelpDemo.quiz,
        related = listOf("clarifying-questions", "documents")
    )
}

// MARK: - Содержание: файлы, фото, видео, голос

internal val filesSection: HelpSection get() {
    val items: List<HelpArticle> = listOf(articleAttachments, articlePhotos, articleVideoAudio, articleDocuments)
    return HelpSection(id = "files", symbol = "paperclip", tint = HelpTint.teal,
                       titleRU = "Файлы, фото, видео, голос", titleEN = "Files, photos, video, voice", articles = items)
}

private val articleAttachments: HelpArticle get() {
    return HelpArticle(
        id = "attachments", symbol = "paperclip", tint = HelpTint.blue,
        titleRU = "Как прикрепить файл", titleEN = "Attaching files",
        summaryRU = "Камера, альбом, файлы — и как вложения выглядят в чате.",
        summaryEN = "Camera, album, files — and how attachments look in the chat.",
        bodyRU = listOf(
            "Нажмите **+** слева от микрофона. Откроется панель: **недавние фото и видео**, **Камера** (снять прямо сейчас), **Альбом** (выбрать из галереи) и **Файл** (из приложения «Файлы» (Загрузки), Google Drive, почты и т. д.).",
            "Вложения появляются над полем ввода. Лишнее можно убрать крестиком до отправки. В чате фото и видео показываются миниатюрами, документы — карточками с названием и типом.",
            "Нажмите на вложение в чате, чтобы открыть **просмотр**: фото увеличиваются жестами, видео проигрываются, документы открываются в просмотрщике."
        ),
        bodyEN = listOf(
            "Tap **+** to the left of the microphone. A panel opens: **recent photos and videos**, **Camera** (shoot now), **Album** (pick from the gallery) and **File** (from the Files app, Google Drive, mail, etc.).",
            "Attachments appear above the message field. Remove extras with the cross before sending. In the chat, photos and videos show as thumbnails and documents as cards with name and type.",
            "Tap an attachment in the chat to open a **preview**: photos zoom with gestures, videos play, documents open in a viewer."
        ),
        stepsRU = listOf(
            "Нажмите **+**.",
            "Выберите Камера, Альбом или Файл.",
            "Добавьте вопрос («что на фото?», «сделай выжимку») и отправьте."
        ),
        stepsEN = listOf(
            "Tap **+**.",
            "Choose Camera, Album or File.",
            "Add a question (“what's in the photo?”, “summarise this”) and send."
        ),
        tipsRU = listOf(
            "Чтобы видеть недавние фото прямо в панели, разрешите доступ к «Фото» (**Настройки → Разрешения**).",
            "Видео — до 2 минут и 40 МБ."
        ),
        tipsEN = listOf(
            "To see recent photos right in the panel, allow photo access (**Settings → Permissions**).",
            "Videos — up to 2 minutes and 40 MB."
        ),
        screenshot = "guide-attachments",
        related = listOf("photos-vision", "video-audio", "documents", "photo-editor")
    )
}

private val articlePhotos: HelpArticle get() {
    return HelpArticle(
        id = "photos-vision", symbol = "photo", tint = HelpTint.pink,
        titleRU = "Фото: нейросеть видит", titleEN = "Photos: the assistant sees",
        summaryRU = "Honer AI действительно понимает изображения: текст, предметы, графики, задачи.",
        summaryEN = "Honer AI truly understands images: text, objects, charts, problems.",
        bodyRU = listOf(
            "Отправьте фото — и спросите что угодно: «что это за растение?», «переведи надпись», «реши задачу с доски», «что не так с этим графиком?», «сколько калорий в этой тарелке?».",
            "Нейросеть распознаёт текст на снимках (в том числе рукописный), предметы, людей в общем виде, скриншоты интерфейсов, чеки, таблицы и схемы.",
            "Можно отправить несколько фото сразу и попросить сравнить их."
        ),
        bodyEN = listOf(
            "Send a photo and ask anything: “what plant is this?”, “translate the sign”, “solve the problem on the board”, “what's wrong with this chart?”, “how many calories are on this plate?”.",
            "The assistant recognises text in pictures (including handwriting), objects, people in general terms, app screenshots, receipts, tables and diagrams.",
            "You can send several photos at once and ask to compare them."
        ),
        stepsRU = listOf(
            "Нажмите **+** → Камера или Альбом.",
            "Выберите фото и задайте вопрос.",
            "Нажмите отправить."
        ),
        stepsEN = listOf(
            "Tap **+** → Camera or Album.",
            "Pick a photo and ask your question.",
            "Tap send."
        ),
        tipsRU = listOf(
            "Чем чётче снимок, тем точнее ответ: снимайте документы ровно и при хорошем свете.",
            "Хотите изменить фото? Откройте его и нажмите **«Редактировать»** — или попросите нейросеть."
        ),
        tipsEN = listOf(
            "The sharper the shot, the better the answer: photograph documents straight and in good light.",
            "Want to change the photo? Open it and tap **Edit** — or ask the assistant."
        ),
        related = listOf("attachments", "photo-editor", "ai-edit")
    )
}

private val articleVideoAudio: HelpArticle get() {
    return HelpArticle(
        id = "video-audio", symbol = "film", tint = HelpTint.purple,
        titleRU = "Видео, голосовые и аудио", titleEN = "Video, voice notes and audio",
        summaryRU = "Нейросеть смотрит ключевые кадры и слушает звук; аудио расшифровывается.",
        summaryEN = "The assistant watches key frames and listens to the sound; audio is transcribed.",
        bodyRU = listOf(
            "Для **видео** Honer AI берёт ключевые кадры и смотрит их, а звуковую дорожку расшифровывает с помощью распознавания речи **прямо на телефоне**. Поэтому можно спросить: «о чём это видео?», «что сказал спикер на 1:20?», «что происходит в кадре?».",
            "**Голосовые сообщения и аудиофайлы** (m4a, mp3, wav и другие) расшифровываются в текст, и нейросеть отвечает по содержанию: делает конспект, переводит, выделяет задачи.",
            "Честно о пределах: видео анализируется по отдельным кадрам, а не каждое мгновение, поэтому очень быстрые детали могут быть пропущены. Музыку без слов нейросеть не «слышит» как мелодию — только речь."
        ),
        bodyEN = listOf(
            "For **video**, Honer AI takes key frames and looks at them, and transcribes the audio track with speech recognition **right on the phone**. So you can ask “what's this video about?”, “what did the speaker say at 1:20?”, “what's happening in the shot?”.",
            "**Voice messages and audio files** (m4a, mp3, wav and more) are transcribed to text and the assistant works with the content: makes notes, translates, pulls out tasks.",
            "Honest limits: video is analysed frame by frame, not every instant, so very fast details can be missed. Music without words isn't “heard” as a melody — only speech is."
        ),
        stepsRU = listOf(
            "Нажмите **+** и выберите видео или аудиофайл.",
            "Напишите, что нужно: конспект, перевод, ответ на вопрос.",
            "Отправьте и дождитесь расшифровки."
        ),
        stepsEN = listOf(
            "Tap **+** and choose a video or an audio file.",
            "Say what you need: notes, translation, an answer.",
            "Send and wait for the transcription."
        ),
        tipsRU = listOf(
            "Запись лекции на диктофон → «сделай конспект по пунктам» — экономит часы.",
            "Для лучшего распознавания укажите язык записи в **Настройки → Основной язык**."
        ),
        tipsEN = listOf(
            "Lecture recording → “make bullet-point notes” — saves hours.",
            "For better recognition set the recording language in **Settings → Speech language**."
        ),
        related = listOf("attachments", "video-editor", "voice-input")
    )
}

private val articleDocuments: HelpArticle get() {
    return HelpArticle(
        id = "documents", symbol = "doc.richtext", tint = HelpTint.teal,
        titleRU = "Документы и таблицы-файлы", titleEN = "Documents and spreadsheets",
        summaryRU = "PDF, Word, Excel, CSV, PowerPoint, EPUB, код и многое другое.",
        summaryEN = "PDF, Word, Excel, CSV, PowerPoint, EPUB, code and much more.",
        bodyRU = listOf(
            "Honer AI читает: **PDF**, **Word** (docx), **Excel** (xlsx) и **CSV** — как настоящие таблицы со столбцами и строками, **PowerPoint** (pptx), **OpenDocument** (odt, ods, odp), **RTF**, **HTML**, **EPUB**, блокноты **Jupyter** (ipynb) и **любые файлы с кодом** (swift, py, js, json, yaml и т. д.).",
            "Можно попросить выжимку, перевод, найти ошибки, ответить по тексту, сравнить два договора, посчитать итоги в таблице или построить по ней выводы.",
            "Прикреплённые документы показываются в чате карточками. Нажмите на карточку, чтобы открыть файл."
        ),
        bodyEN = listOf(
            "Honer AI reads **PDF**, **Word** (docx), **Excel** (xlsx) and **CSV** — as real tables with columns and rows, **PowerPoint** (pptx), **OpenDocument** (odt, ods, odp), **RTF**, **HTML**, **EPUB**, **Jupyter** notebooks (ipynb) and **any code file** (swift, py, js, json, yaml and more).",
            "Ask for a summary, a translation, error checks, answers from the text, a comparison of two contracts, totals for a spreadsheet or conclusions from it.",
            "Attached documents show up in the chat as cards. Tap a card to open the file."
        ),
        stepsRU = listOf(
            "Нажмите **+** → **Файл**.",
            "Выберите документ в «Файлах» или Google Drive.",
            "Напишите задачу и отправьте."
        ),
        stepsEN = listOf(
            "Tap **+** → **File**.",
            "Pick a document in Files or Google Drive.",
            "Describe the task and send."
        ),
        tipsRU = listOf(
            "Сканы без текстового слоя лучше отправлять как фото — нейросеть распознает текст на изображении.",
            "Для Excel с несколькими листами уточните, какой лист смотреть."
        ),
        tipsEN = listOf(
            "Scans without a text layer work better as photos — the assistant reads the text from the image.",
            "For Excel files with several sheets, say which sheet to look at."
        ),
        related = listOf("attachments", "tables", "quizzes")
    )
}

// MARK: - Содержание: редактор фото и видео

internal val editorSection: HelpSection get() {
    val items: List<HelpArticle> = listOf(articlePhotoEditor, articleVideoEditor, articleAIEdit)
    return HelpSection(id = "editor", symbol = "wand.and.stars", tint = HelpTint.pink,
                       titleRU = "Редактор фото и видео", titleEN = "Photo and video editor", articles = items)
}

private val articlePhotoEditor: HelpArticle get() {
    return HelpArticle(
        id = "photo-editor", symbol = "wand.and.stars", tint = HelpTint.pink,
        titleRU = "Редактор фото", titleEN = "Photo editor",
        summaryRU = "Фильтры, коррекция, обрезка, удаление и замена фона, текст, стикеры, рисование.",
        summaryEN = "Filters, adjustments, crop, background removal and replacement, text, stickers, drawing.",
        bodyRU = listOf(
            "Откройте фото-вложение в чате и нажмите **«Редактировать»**. Внизу — инструменты: **Фильтры**, **Коррекция** (яркость, контраст, насыщенность, тепло, резкость), **Обрезка и поворот**, **Фон**, **Текст**, **Стикеры**, **Рисование**.",
            "**Удалить фон** — одно нажатие: объект вырезается прямо на телефоне, без отправки фото в интернет. Затем фон можно **заменить**: сплошной цвет, размытие исходного фона, градиент или другое фото.",
            "Любое действие можно **отменить**, а кнопкой **«Сравнить»** (удерживайте) — посмотреть оригинал. Готовое фото сохраняется в чат как новое вложение, его можно отправить нейросети или сохранить в «Фото»."
        ),
        bodyEN = listOf(
            "Open a photo attachment in the chat and tap **Edit**. The tools are at the bottom: **Filters**, **Adjust** (brightness, contrast, saturation, warmth, sharpness), **Crop & rotate**, **Background**, **Text**, **Stickers**, **Draw**.",
            "**Remove background** takes one tap: the subject is cut out right on the phone, without sending the photo anywhere. Then you can **replace** the background: a solid colour, a blur of the original, a gradient or another photo.",
            "Every action can be **undone**, and holding **Compare** shows the original. The finished photo is saved to the chat as a new attachment — send it to the assistant or save it to the gallery."
        ),
        stepsRU = listOf(
            "Нажмите на фото в чате, чтобы открыть его.",
            "Нажмите **«Редактировать»**.",
            "Выберите **Фон → Удалить фон**, затем цвет, размытие или фото для нового фона.",
            "Добавьте фильтр, текст или стикер и нажмите **«Готово»**."
        ),
        stepsEN = listOf(
            "Tap a photo in the chat to open it.",
            "Tap **Edit**.",
            "Choose **Background → Remove background**, then a colour, blur or photo for the new background.",
            "Add a filter, text or sticker and tap **Done**."
        ),
        tipsRU = listOf(
            "Удаление фона лучше всего работает, когда объект чётко отделён от фона: человек, животное, предмет на столе.",
            "Для аватарки: удалите фон → градиент → обрезка «квадрат»."
        ),
        tipsEN = listOf(
            "Background removal works best when the subject stands out clearly: a person, a pet, an object on a table.",
            "For an avatar: remove background → gradient → square crop."
        ),
        demo = HelpDemo.photoEditor,
        related = listOf("ai-edit", "video-editor", "photos-vision")
    )
}

private val articleVideoEditor: HelpArticle get() {
    return HelpArticle(
        id = "video-editor", symbol = "scissors", tint = HelpTint.orange,
        titleRU = "Редактор видео", titleEN = "Video editor",
        summaryRU = "Обрезка, звук, скорость, фильтры, музыка и озвучка голосом.",
        summaryEN = "Trim, sound, speed, filters, music and voice-over.",
        bodyRU = listOf(
            "Откройте видео-вложение и нажмите **«Редактировать»**. Можно **обрезать** начало и конец, **выключить звук**, изменить **скорость** (замедлить или ускорить), наложить **фильтр**.",
            "Кнопка **«Музыка»** добавляет звуковую дорожку из файла, а **«Озвучка»** позволяет записать свой голос поверх видео прямо в редакторе.",
            "Готовое видео сохраняется в чат новым вложением."
        ),
        bodyEN = listOf(
            "Open a video attachment and tap **Edit**. You can **trim** the start and end, **mute** it, change the **speed** (slow down or speed up) and apply a **filter**.",
            "**Music** adds a soundtrack from a file, and **Voice-over** records your voice on top of the video right in the editor.",
            "The finished video is saved to the chat as a new attachment."
        ),
        stepsRU = listOf(
            "Откройте видео в чате → **«Редактировать»**.",
            "Потяните края полосы кадров, чтобы обрезать.",
            "Выберите скорость, фильтр, музыку или озвучку.",
            "Нажмите **«Готово»**."
        ),
        stepsEN = listOf(
            "Open the video in the chat → **Edit**.",
            "Drag the edges of the frame strip to trim.",
            "Choose speed, filter, music or voice-over.",
            "Tap **Done**."
        ),
        tipsRU = listOf(
            "Сначала обрежьте видео до нужного куска — потом нейросети будет проще его разобрать."
        ),
        tipsEN = listOf(
            "Trim the video to the part you need first — it's easier for the assistant to analyse."
        ),
        related = listOf("photo-editor", "video-audio")
    )
}

private val articleAIEdit: HelpArticle get() {
    return HelpArticle(
        id = "ai-edit", symbol = "sparkles", tint = HelpTint.purple,
        titleRU = "Нейросеть редактирует фото", titleEN = "The assistant edits photos",
        summaryRU = "Просто попросите: «убери фон», «сделай чёрно-белым», «добавь надпись».",
        summaryEN = "Just ask: “remove the background”, “make it black and white”, “add a caption”.",
        bodyRU = listOf(
            "Не обязательно открывать редактор самому. Прикрепите фото и напишите, что сделать: **«убери фон»**, **«сделай чёрно-белым»**, **«добавь надпись С днём рождения»**, **«обрежь квадратом»**, **«сделай теплее»**.",
            "Honer AI применит те же инструменты редактора и пришлёт готовый результат в чат. Если что-то не так — попросите поправить: «надпись крупнее», «фон синий»."
        ),
        bodyEN = listOf(
            "You don't have to open the editor yourself. Attach a photo and write what to do: **“remove the background”**, **“make it black and white”**, **“add the caption Happy Birthday”**, **“crop it square”**, **“make it warmer”**.",
            "Honer AI applies the same editor tools and sends the result to the chat. If something's off, ask for a fix: “bigger caption”, “blue background”."
        ),
        stepsRU = listOf(
            "Прикрепите фото.",
            "Напишите, что изменить.",
            "Получите результат и при необходимости уточните."
        ),
        stepsEN = listOf(
            "Attach a photo.",
            "Say what to change.",
            "Get the result and refine if needed."
        ),
        tipsRU = listOf(
            "Можно просить несколько правок сразу: «убери фон, поставь белый и добавь подпись снизу»."
        ),
        tipsEN = listOf(
            "You can ask for several edits at once: “remove the background, make it white and add a caption at the bottom”."
        ),
        related = listOf("photo-editor", "photos-vision")
    )
}
