package com.honerai.app.ui.help

// СГЕНЕРИРОВАНО из HelpCenter.swift (iOS): содержание руководства на двух языках.
// Каждая статья — отдельный геттер, чтобы не упереться в предел размера метода JVM.
// Упоминания iPhone заменены на Android-аналоги.

// MARK: - Содержание: голос

internal val voiceSection: HelpSection get() {
    val items: List<HelpArticle> = listOf(articleVoiceInput, articleVoices, articleVoiceClone)
    return HelpSection(id = "voice", symbol = "waveform", tint = HelpTint.red,
                       titleRU = "Голос", titleEN = "Voice", articles = items)
}

private val articleVoiceInput: HelpArticle get() {
    return HelpArticle(
        id = "voice-input", symbol = "mic", tint = HelpTint.red,
        titleRU = "Голосовой ввод", titleEN = "Voice input",
        summaryRU = "Нажмите микрофон, говорите, нажмите ещё раз — и сообщение отправлено.",
        summaryEN = "Tap the mic, speak, tap again — and the message is sent.",
        bodyRU = listOf(
            "Нажмите **микрофон** справа внизу. Появится волна и подсказка **«Говорите. Нажмите ■, чтобы отправить»**. Текст распознаётся прямо во время речи.",
            "Нажмите **■**, чтобы отправить, или **«Отмена»**, чтобы выйти без отправки. Если в поле уже был черновик, он сохранится.",
            "Распознавание работает для русского и английского; основной язык выбирается в **Настройки → Основной язык**."
        ),
        bodyEN = listOf(
            "Tap the **microphone** at the bottom right. A waveform appears with the hint **“Speak. Tap ■ to send”**. Text is recognised while you talk.",
            "Tap **■** to send, or **Cancel** to leave without sending. Any draft already in the field is kept.",
            "Recognition works for Russian and English; choose the main language in **Settings → Speech language**."
        ),
        stepsRU = listOf(
            "Нажмите микрофон.",
            "Говорите.",
            "Нажмите **■** — сообщение уйдёт."
        ),
        stepsEN = listOf(
            "Tap the microphone.",
            "Speak.",
            "Tap **■** — the message is sent."
        ),
        tipsRU = listOf(
            "Включите динамик вверху — получится разговор голосом: вы говорите, нейросеть отвечает вслух."
        ),
        tipsEN = listOf(
            "Turn on the speaker at the top for a voice conversation: you talk, the assistant answers aloud."
        ),
        screenshot = "guide-voice", demo = HelpDemo.voice,
        related = listOf("voices", "read-aloud", "composer")
    )
}

private val articleVoices: HelpArticle get() {
    return HelpArticle(
        id = "voices", symbol = "speaker.wave.3", tint = HelpTint.green,
        titleRU = "Голоса озвучки", titleEN = "Reading voices",
        summaryRU = "Мужской или женский, русский и английский; как сделать звучание живым.",
        summaryEN = "Male or female, Russian and English; how to make it sound natural.",
        bodyRU = listOf(
            "В **Настройки → Голос** выберите **Мужской** (по умолчанию) или **Женский** голос. Русский текст читает русский голос, английские слова — английский голос того же пола. Кнопка **«Послушать голос»** воспроизводит пример.",
            "Там же настраивается **скорость чтения**.",
            "Для живого, не «роботного» звучания скачайте голос улучшенного качества: настройки Android → **Система → Язык и ввод → Синтез речи** → шестерёнка у «Синтезатор речи Google» → **Установить голосовые данные** → Русский (и English) — выберите голос высокого качества. Кнопка **«Скачать голоса»** в **Настройки → Голос** открывает этот экран. После загрузки голос выберется сам."
        ),
        bodyEN = listOf(
            "In **Settings → Voice** choose a **Male** (default) or **Female** voice. Russian text is read by a Russian voice and English words by an English voice of the same gender. **Listen to voice** plays a sample.",
            "Reading **speed** is set there too.",
            "For a natural, non-robotic sound, download an enhanced voice: Android settings → **System → Languages & input → Text-to-speech** → the gear next to “Speech Services by Google” → **Install voice data** → Russian (and English) — pick a high-quality voice. The **Download voices** button in **Settings → Voice** opens that screen. Once downloaded, the voice is picked automatically."
        ),
        stepsRU = listOf(
            "Откройте **Настройки → Голос**.",
            "Выберите пол голоса и скорость.",
            "Нажмите **«Послушать голос»**."
        ),
        stepsEN = listOf(
            "Open **Settings → Voice**.",
            "Choose the voice gender and speed.",
            "Tap **Listen to voice**."
        ),
        tipsRU = listOf(
            "Голоса высокого качества весят десятки и сотни мегабайт — скачивайте по Wi-Fi."
        ),
        tipsEN = listOf(
            "High-quality voices take tens to hundreds of megabytes — download them over Wi-Fi."
        ),
        screenshot = "guide-voice-settings",
        related = listOf("voice-clone", "read-aloud")
    )
}

private val articleVoiceClone: HelpArticle get() {
    return HelpArticle(
        id = "voice-clone", symbol = "person.wave.2", tint = HelpTint.purple,
        titleRU = "Свой голос", titleEN = "Your own voice",
        summaryRU = "Клонирование голоса через Fish Audio.",
        summaryEN = "Voice cloning with Fish Audio.",
        bodyRU = listOf(
            "**«Мой голос»** клонирует ваш голос для русского и английского: вы записываете образец, и ответы читаются вашим голосом. Работает через сервис **Fish Audio**, для него **нужен ключ** этого сервиса.",
            "Голос создаётся по записи 30–60 секунд. Его можно послушать, выключить или удалить в любой момент на той же странице. Если родитель запретил клонирование голоса, страница недоступна."
        ),
        bodyEN = listOf(
            "**My voice** clones your voice for Russian and English: you record a sample and answers are read in your voice. It uses the **Fish Audio** service and **requires a key** for it.",
            "The voice is created from a 30–60 second recording. You can listen to it, turn it off or delete it any time on the same page. If a parent has disabled voice cloning, the page is unavailable."
        ),
        stepsRU = listOf(
            "Откройте **Настройки → Голос → Мой голос**.",
            "Введите ключ Fish Audio и запишите образец голоса.",
            "Включите чтение своим голосом."
        ),
        stepsEN = listOf(
            "Open **Settings → Voice → My voice**.",
            "Enter a Fish Audio key and record a voice sample.",
            "Turn on reading in your voice."
        ),
        tipsRU = listOf(
            "Записывайте образец в тишине и обычным темпом — клон будет звучать естественнее."
        ),
        tipsEN = listOf(
            "Record the sample in a quiet room at your normal pace — the clone sounds more natural."
        ),
        related = listOf("voices", "parental-limits")
    )
}

// MARK: - Содержание: игры

internal val gamesSection: HelpSection get() {
    val items: List<HelpArticle> = listOf(articleGames, articleChess)
    return HelpSection(id = "games", symbol = "gamecontroller", tint = HelpTint.orange,
                       titleRU = "Игры", titleEN = "Games", articles = items)
}

private val articleGames: HelpArticle get() {
    return HelpArticle(
        id = "games", symbol = "gamecontroller", tint = HelpTint.orange,
        titleRU = "Игры с Honer AI", titleEN = "Games with Honer AI",
        summaryRU = "Шахматы, русские шашки, «Дурак» и слот-машина «Удача» против нейросети.",
        summaryEN = "Chess, Russian checkers, Durak and the “Luck” slot machine against the assistant.",
        bodyRU = listOf(
            "В приложении четыре игры: **Шахматы** (партия против Honer AI), **Шашки** (русские правила), **Дурак** (подкидной, 36 карт) и **Удача** (крутите барабаны).",
            "Нейросеть может **открыть игру по просьбе**: напишите «давай сыграем в шахматы». После партии **результат появляется в чате**, и можно обсудить ход игры.",
            "Игры работают **без интернета**."
        ),
        bodyEN = listOf(
            "There are four games: **Chess** (a match against Honer AI), **Checkers** (Russian rules), **Durak** (the classic card game, 36 cards) and **Luck** (spin the reels).",
            "The assistant can **open a game on request**: write “let's play chess”. After the game **the result appears in the chat** and you can discuss it.",
            "Games work **offline**."
        ),
        stepsRU = listOf(
            "Напишите «давай сыграем в…» или откройте игры из меню.",
            "Выберите игру.",
            "После партии вернитесь в чат — результат уже там."
        ),
        stepsEN = listOf(
            "Write “let's play…” or open games from the menu.",
            "Choose a game.",
            "After the game go back to the chat — the result is already there."
        ),
        tipsRU = listOf(
            "Попросите «разбери мою партию» — нейросеть объяснит ошибки."
        ),
        tipsEN = listOf(
            "Ask “review my game” — the assistant explains your mistakes."
        ),
        screenshot = "guide-games",
        related = listOf("chess", "parental-limits")
    )
}

private val articleChess: HelpArticle get() {
    return HelpArticle(
        id = "chess", symbol = "crown", tint = HelpTint.indigo,
        titleRU = "Шахматы и шашки", titleEN = "Chess and checkers",
        summaryRU = "Партия с нейросетью, подсветка ходов, отмена хода.",
        summaryEN = "A match with the assistant, move highlights, undo.",
        bodyRU = listOf(
            "Нажмите на фигуру — подсветятся допустимые ходы. Нажмите на клетку, чтобы сходить. Honer AI отвечает своим ходом.",
            "В шашках действуют русские правила: обязательное взятие, дамка ходит на любое число клеток."
        ),
        bodyEN = listOf(
            "Tap a piece to highlight its legal moves. Tap a square to move. Honer AI replies with its move.",
            "Checkers follows Russian rules: capturing is mandatory, and a king moves any number of squares."
        ),
        stepsRU = listOf(
            "Откройте **Шахматы** или **Шашки**.",
            "Нажмите на фигуру, затем на клетку.",
            "Играйте до мата или сдачи."
        ),
        stepsEN = listOf(
            "Open **Chess** or **Checkers**.",
            "Tap a piece, then a square.",
            "Play until checkmate or resignation."
        ),
        tipsRU = listOf(
            "Застряли? Спросите в чате «какой ход лучше в этой позиции?»."
        ),
        tipsEN = listOf(
            "Stuck? Ask in the chat “what's the best move in this position?”."
        ),
        screenshot = "guide-chess",
        related = listOf("games")
    )
}

// MARK: - Содержание: уведомления и фон

internal val backgroundSection: HelpSection get() {
    val items: List<HelpArticle> = listOf(articleBackgroundAnswers)
    return HelpSection(id = "background", symbol = "bell.badge", tint = HelpTint.red,
                       titleRU = "Уведомления и фон", titleEN = "Notifications and background", articles = items)
}

private val articleBackgroundAnswers: HelpArticle get() {
    return HelpArticle(
        id = "background-answers", symbol = "bell.badge", tint = HelpTint.red,
        titleRU = "Ответы в фоне", titleEN = "Background answers",
        summaryRU = "Сверните приложение — ответ допишется, а уведомление подскажет, что он готов.",
        summaryEN = "Leave the app — the answer finishes and a notification tells you it's ready.",
        bodyRU = listOf(
            "Не нужно держать приложение открытым, пока нейросеть думает или исследует сайты. Сверните его — ответ **допишется в фоне**.",
            "Когда ответ готов, придёт **уведомление** с началом текста и картинкой, если она есть. **Нажмите на уведомление** — откроется именно этот чат.",
            "Уведомления включены по умолчанию. Если они не приходят, проверьте настройки Android: **Настройки → Honer AI → Уведомления**."
        ),
        bodyEN = listOf(
            "No need to keep the app open while the assistant thinks or researches sites. Leave it — the answer **finishes in the background**.",
            "When it's ready you get a **notification** with the start of the text and an image if there is one. **Tap the notification** to open that exact chat.",
            "Notifications are on by default. If they don't arrive, check Android settings: **Settings → Honer AI → Notifications**."
        ),
        stepsRU = listOf(
            "Отправьте вопрос.",
            "Сверните приложение.",
            "Нажмите на уведомление, когда оно придёт."
        ),
        stepsEN = listOf(
            "Send a question.",
            "Leave the app.",
            "Tap the notification when it arrives."
        ),
        tipsRU = listOf(
            "Для долгого исследования на сотни сайтов — идеальный вариант: запустили и занялись своими делами."
        ),
        tipsEN = listOf(
            "Perfect for long research across hundreds of sites: start it and get on with your day."
        ),
        related = listOf("deep-research", "permissions")
    )
}

// MARK: - Содержание: приватность и безопасность

internal val privacySection: HelpSection get() {
    val items: List<HelpArticle> = listOf(articleDataPrivacy, articleBackups, articlePermissions, articleWhatAIKnows)
    return HelpSection(id = "privacy", symbol = "lock.shield", tint = HelpTint.green,
                       titleRU = "Приватность и безопасность", titleEN = "Privacy and security", articles = items)
}

private val articleDataPrivacy: HelpArticle get() {
    return HelpArticle(
        id = "data-privacy", symbol = "lock.shield", tint = HelpTint.green,
        titleRU = "Где хранятся данные", titleEN = "Where your data lives",
        summaryRU = "Чаты, вложения и память хранятся на вашем телефоне.",
        summaryEN = "Chats, attachments and memory are stored on your phone.",
        bodyRU = listOf(
            "Вся история, вложения и память Honer AI **хранятся на устройстве**. Аккаунта на сервере нет, регистрироваться не нужно.",
            "Чтобы нейросеть ответила, текст запроса (и нужные вложения) отправляется модели DeepSeek через интернет. Удаление фона на фото и распознавание речи в видео выполняются **прямо на телефоне**.",
            "Если удалить приложение без резервной копии, данные пропадут — поэтому включите автокопирование."
        ),
        bodyEN = listOf(
            "Your history, attachments and Honer AI memory are **stored on the device**. There's no server account and no sign-up.",
            "To get an answer, your request (and any needed attachments) is sent to the DeepSeek model over the internet. Photo background removal and speech recognition for videos run **right on the phone**.",
            "If you delete the app without a backup, the data is gone — so turn on automatic backups."
        ),
        tipsRU = listOf(
            "Не отправляйте нейросети пароли, номера карт и коды из SMS — они ей не нужны."
        ),
        tipsEN = listOf(
            "Never send passwords, card numbers or SMS codes to the assistant — it doesn't need them."
        ),
        related = listOf("backups", "permissions", "what-ai-knows")
    )
}

private val articleBackups: HelpArticle get() {
    return HelpArticle(
        id = "backups", symbol = "externaldrive.badge.icloud", tint = HelpTint.blue,
        titleRU = "Резервные копии", titleEN = "Backups",
        summaryRU = "Автокопирование в папку или Google Drive, экспорт и импорт JSON, восстановление.",
        summaryEN = "Auto backup to a folder or Google Drive, JSON export/import, restore.",
        bodyRU = listOf(
            "В **Настройки → Управление данными** можно включить **автоматическое резервное копирование** в выбранную папку, например в Google Drive — копия будет обновляться сама.",
            "**Экспортировать историю** — сохраняет файл JSON с перепиской, вложениями и памятью (его можно сохранить в «Файлы» (Загрузки) или отправить себе). **Импортировать историю** добавляет чаты и факты из такого файла.",
            "После переустановки приложения на первом экране нажмите **«Восстановить из резервной копии»** и выберите файл."
        ),
        bodyEN = listOf(
            "In **Settings → Data management** you can turn on **automatic backup** to a folder of your choice, such as Google Drive — the copy updates by itself.",
            "**Export history** saves a JSON file with your conversations, attachments and memory (save it to Files or send it to yourself). **Import history** adds chats and facts from such a file.",
            "After reinstalling the app, tap **Restore from backup** on the first screen and choose the file."
        ),
        stepsRU = listOf(
            "Откройте **Настройки → Управление данными**.",
            "Включите автокопирование и выберите папку в Google Drive.",
            "Или нажмите **«Экспортировать историю»** → **Сохранить в Файлы**."
        ),
        stepsEN = listOf(
            "Open **Settings → Data management**.",
            "Turn on auto backup and choose a folder in Google Drive.",
            "Or tap **Export history** → **Save to device**."
        ),
        tipsRU = listOf(
            "Копия в Google Drive переживёт даже потерю телефона.",
            "Импорт не удаляет текущие чаты — он добавляет к ним сохранённые."
        ),
        tipsEN = listOf(
            "A copy in Google Drive survives even losing your phone.",
            "Import doesn't delete current chats — it adds the saved ones."
        ),
        screenshot = "guide-backup",
        related = listOf("data-privacy", "first-launch")
    )
}

private val articlePermissions: HelpArticle get() {
    return HelpArticle(
        id = "permissions", symbol = "hand.raised", tint = HelpTint.orange,
        titleRU = "Разрешения", titleEN = "Permissions",
        summaryRU = "Камера, микрофон, фото, контакты, геопозиция, уведомления — зачем они нужны.",
        summaryEN = "Camera, microphone, photos, contacts, location, notifications — what they're for.",
        bodyRU = listOf(
            "Страница **Настройки → Разрешения** показывает, к чему у приложения есть доступ, и позволяет выдать его:",
            "**Камера** — снимать фото и видео для чата. **Микрофон** — голосовой ввод и озвучка видео. **Фото** — выбирать снимки из альбома. **Контакты** — находить людей по имени. **Геопозиция** — знать ваш город и местное время (погода, «что рядом»). **Уведомления** — сообщать о готовом ответе.",
            "Разрешения нужны только для функций, которыми вы пользуетесь. Отключить доступ можно в настройках Android."
        ),
        bodyEN = listOf(
            "**Settings → Permissions** shows what the app can access and lets you grant it:",
            "**Camera** — shoot photos and videos for the chat. **Microphone** — voice input and video voice-over. **Photos** — pick pictures from your album. **Contacts** — find people by name. **Location** — know your city and local time (weather, “what's nearby”). **Notifications** — tell you when an answer is ready.",
            "Permissions are only needed for features you use. You can revoke access in Android settings."
        ),
        stepsRU = listOf(
            "Откройте **Настройки → Разрешения**.",
            "Нажмите на нужное разрешение и подтвердите системный запрос."
        ),
        stepsEN = listOf(
            "Open **Settings → Permissions**.",
            "Tap the permission and confirm the system prompt."
        ),
        related = listOf("what-ai-knows", "data-privacy")
    )
}

private val articleWhatAIKnows: HelpArticle get() {
    return HelpArticle(
        id = "what-ai-knows", symbol = "person.text.rectangle", tint = HelpTint.teal,
        titleRU = "Что нейросеть знает о вас", titleEN = "What the assistant knows about you",
        summaryRU = "Имя, возраст, дата создания аккаунта, город, часовой пояс, модель телефона.",
        summaryEN = "Name, age, account creation date, city, time zone, phone model.",
        bodyRU = listOf(
            "Чтобы отвечать точнее, Honer AI знает: ваше **имя**; **дату рождения и возраст**, если вы их указали; **дату создания аккаунта** (первого запуска); **город** — только если разрешена геопозиция; **часовой пояс** и местное время; **модель устройства** (например, Samsung Galaxy S24 Ultra) и **версию Android**.",
            "Плюс факты из **памяти**, которые вы видите и можете удалить. Больше ничего о вас нейросеть не знает — она не читает другие приложения, переписку или файлы без вашего явного вложения."
        ),
        bodyEN = listOf(
            "To answer better, Honer AI knows: your **name**; your **birthday and age** if you set them; your **account creation date** (first launch); your **city** — only if location is allowed; your **time zone** and local time; the **device model** (e.g. Samsung Galaxy S24 Ultra) and **Android version**.",
            "Plus facts from **memory**, which you can see and delete. It knows nothing else about you — it doesn't read other apps, your messages or files unless you attach them."
        ),
        tipsRU = listOf(
            "Спросите «что ты обо мне знаешь?» — нейросеть перечислит всё сама."
        ),
        tipsEN = listOf(
            "Ask “what do you know about me?” — the assistant lists it all."
        ),
        related = listOf("memory", "permissions", "first-launch")
    )
}

// MARK: - Содержание: родительский контроль

internal val parentalSection: HelpSection get() {
    val items: List<HelpArticle> = listOf(articleParentalSetup, articleParentalFilters, articleParentalLimits)
    return HelpSection(id = "parental", symbol = "figure.2.and.child.holdinghands", tint = HelpTint.green,
                       titleRU = "Родительский контроль", titleEN = "Parental controls", articles = items)
}

private val articleParentalSetup: HelpArticle get() {
    return HelpArticle(
        id = "parental-setup", symbol = "figure.2.and.child.holdinghands", tint = HelpTint.green,
        titleRU = "Включение родительского контроля", titleEN = "Turning on parental controls",
        summaryRU = "Выключен по умолчанию, включается только родителем и защищён PIN-кодом.",
        summaryEN = "Off by default, turned on only by a parent and protected by a PIN.",
        bodyRU = listOf(
            "Родительский контроль **выключен по умолчанию** и **никогда не включается автоматически**. Включить его может только взрослый в настройках приложения.",
            "При включении задаётся **PIN-код**. Без него ребёнок не сможет выключить контроль или изменить ограничения. После нескольких неверных попыток ввод PIN **временно блокируется**.",
            "Укажите **возраст ребёнка** — Honer AI будет отвечать проще и понятнее для этого возраста."
        ),
        bodyEN = listOf(
            "Parental controls are **off by default** and **never turn on automatically**. Only an adult can enable them in the app settings.",
            "Turning them on sets a **PIN**. Without it, a child can't switch the controls off or change the limits. After several wrong attempts, PIN entry is **temporarily locked**.",
            "Set the **child's age** — Honer AI will answer more simply and clearly for that age."
        ),
        stepsRU = listOf(
            "Откройте **Настройки → Родительский контроль**.",
            "Нажмите **«Включить»** и дважды введите PIN.",
            "Укажите возраст ребёнка и настройте фильтры и ограничения."
        ),
        stepsEN = listOf(
            "Open **Settings → Parental controls**.",
            "Tap **Turn on** and enter a PIN twice.",
            "Set the child's age and configure filters and limits."
        ),
        tipsRU = listOf(
            "Выберите PIN, который ребёнок не угадает: не дату рождения и не 1234.",
            "Запишите PIN в надёжном месте — без него настройки не изменить."
        ),
        tipsEN = listOf(
            "Pick a PIN the child won't guess: not a birthday and not 1234.",
            "Keep the PIN somewhere safe — you can't change settings without it."
        ),
        demo = HelpDemo.parental,
        related = listOf("parental-filters", "parental-limits")
    )
}

private val articleParentalFilters: HelpArticle get() {
    return HelpArticle(
        id = "parental-filters", symbol = "shield.lefthalf.filled", tint = HelpTint.red,
        titleRU = "Фильтры содержимого", titleEN = "Content filters",
        summaryRU = "18+, насилие, наркотики и алкоголь, азартные игры, мат, самоповреждение, ненависть, знакомства.",
        summaryEN = "18+, violence, drugs and alcohol, gambling, profanity, self-harm, hate, dating.",
        bodyRU = listOf(
            "Каждый фильтр включается отдельно: **18+**, **насилие**, **наркотики и алкоголь**, **азартные игры**, **ненормативная лексика**, **самоповреждение**, **ненависть и травля**, **знакомства**.",
            "При теме **самоповреждения** нейросеть не просто отказывает, а бережно поддерживает и показывает **телефон доверия**.",
            "Дополнительно можно задать **запрещённые слова** и **запрещённые сайты**, а также режим **«только разрешённые сайты»** — тогда нейросеть откроет лишь сайты из вашего списка."
        ),
        bodyEN = listOf(
            "Each filter is turned on separately: **18+**, **violence**, **drugs and alcohol**, **gambling**, **profanity**, **self-harm**, **hate and bullying**, **dating**.",
            "For **self-harm** topics the assistant doesn't just refuse — it responds with care and shows a **helpline**.",
            "You can also set **blocked words** and **blocked sites**, and an **allowed sites only** mode — then the assistant opens only sites from your list."
        ),
        stepsRU = listOf(
            "**Настройки → Родительский контроль**, раздел **«Фильтры контента»**.",
            "Включите нужные фильтры.",
            "Добавьте запрещённые слова и сайты."
        ),
        stepsEN = listOf(
            "**Settings → Parental control**, section **Content filters**.",
            "Turn on the filters you need.",
            "Add blocked words and sites."
        ),
        related = listOf("parental-setup", "parental-limits")
    )
}

private val articleParentalLimits: HelpArticle get() {
    return HelpArticle(
        id = "parental-limits", symbol = "hourglass", tint = HelpTint.orange,
        titleRU = "Ограничения функций и времени", titleEN = "Feature and time limits",
        summaryRU = "Поиск, ссылки, рисование, игры, клонирование голоса, контакты, геопозиция; лимит времени и тихие часы.",
        summaryEN = "Search, links, drawing, games, voice cloning, contacts, location; daily limit and quiet hours.",
        bodyRU = listOf(
            "Можно отключить отдельные функции: **поиск в интернете**, **открытие ссылок**, **рисование**, **игры** (каждую игру отдельно), **клонирование голоса**, доступ к **контактам** и **геопозиции**.",
            "**Дневной лимит времени** ограничивает, сколько минут в день можно пользоваться приложением. **Тихие часы** (например, с 22:00 до 7:00) закрывают доступ ночью."
        ),
        bodyEN = listOf(
            "You can turn off individual features: **web search**, **opening links**, **drawing**, **games** (each game separately), **voice cloning**, access to **contacts** and **location**.",
            "A **daily time limit** caps how many minutes a day the app can be used. **Quiet hours** (e.g. 22:00 to 07:00) block access at night."
        ),
        stepsRU = listOf(
            "**Настройки → Родительский контроль**, разделы **«Возможности»** и **«Разрешённые игры»**.",
            "Отключите лишнее.",
            "Задайте дневной лимит и тихие часы."
        ),
        stepsEN = listOf(
            "**Settings → Parental control**, sections **Features** and **Allowed games**.",
            "Turn off what isn't needed.",
            "Set a daily limit and quiet hours."
        ),
        tipsRU = listOf(
            "Для младшего школьника: поиск выключен, игры — только шахматы, лимит 1 час."
        ),
        tipsEN = listOf(
            "For a young pupil: search off, games — chess only, a one-hour limit."
        ),
        related = listOf("parental-setup", "parental-filters")
    )
}

// MARK: - Содержание: оформление

internal val appearanceSection: HelpSection get() {
    val items: List<HelpArticle> = listOf(articleAppearance, articleLanguage)
    return HelpSection(id = "appearance", symbol = "paintbrush", tint = HelpTint.purple,
                       titleRU = "Оформление", titleEN = "Appearance", articles = items)
}

private val articleAppearance: HelpArticle get() {
    return HelpArticle(
        id = "appearance", symbol = "paintbrush", tint = HelpTint.purple,
        titleRU = "Тема и размер шрифта", titleEN = "Theme and font size",
        summaryRU = "Тёмная, светлая или системная тема; крупнее или мельче текст.",
        summaryEN = "Dark, light or system theme; larger or smaller text.",
        bodyRU = listOf(
            "**Настройки → Внешний вид**: **Тёмный**, **Светлый** или **Система**.",
            "**Настройки → Размер шрифта** увеличивает или уменьшает текст сообщений — удобно, если мелко читать."
        ),
        bodyEN = listOf(
            "**Settings → Appearance**: **Dark**, **Light** or **System**.",
            "**Settings → Font size** makes message text larger or smaller — handy if it's hard to read."
        ),
        stepsRU = listOf(
            "Откройте **☰ → профиль → Настройки**.",
            "Выберите **Внешний вид** или **Размер шрифта**."
        ),
        stepsEN = listOf(
            "Open **☰ → profile → Settings**.",
            "Choose **Appearance** or **Font size**."
        ),
        screenshot = "guide-settings",
        related = listOf("language")
    )
}

private val articleLanguage: HelpArticle get() {
    return HelpArticle(
        id = "language", symbol = "character.bubble", tint = HelpTint.blue,
        titleRU = "Язык приложения", titleEN = "App language",
        summaryRU = "Русский или English — и язык ответов нейросети.",
        summaryEN = "Russian or English — and the assistant's reply language.",
        bodyRU = listOf(
            "**Настройки → Язык**: **Русский** или **English**. Меняется интерфейс, и нейросеть отвечает на выбранном языке.",
            "Язык ответа можно поменять и в самом чате: «отвечай по-английски» или закрепите это как инструкцию."
        ),
        bodyEN = listOf(
            "**Settings → Language**: **Русский** or **English**. The interface changes and the assistant answers in the chosen language.",
            "You can also change the reply language in a chat: “answer in Russian”, or pin that as an instruction."
        ),
        stepsRU = listOf(
            "**Настройки → Язык**.",
            "Выберите язык."
        ),
        stepsEN = listOf(
            "**Settings → Language**.",
            "Choose a language."
        ),
        related = listOf("appearance", "pinned-instructions", "first-launch")
    )
}
