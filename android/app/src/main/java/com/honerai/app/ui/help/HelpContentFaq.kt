package com.honerai.app.ui.help

// СГЕНЕРИРОВАНО из HelpCenter.swift (iOS): содержание руководства на двух языках.
// Каждая статья — отдельный геттер, чтобы не упереться в предел размера метода JVM.
// Упоминания iPhone заменены на Android-аналоги.

// MARK: - Содержание: вопросы и ответы

internal val faqBasics: List<HelpFAQ> get() {
    val items: List<HelpFAQ> = listOf(
        HelpFAQ(questionRU = "Почему ответ пришёл на английском?",
                questionEN = "Why did the answer come in Russian?",
                answerRU = "Honer AI отвечает на языке приложения. Проверьте **Настройки → Язык** — там должно быть «Русский». Также нейросеть может перейти на английский, если вы написали по-английски, вставили английский текст или в чате закреплена инструкция «отвечай по-английски». Напишите «отвечай по-русски» — и она переключится.",
                answerEN = "Honer AI answers in the app language. Check **Settings → Language** — it should say English. The assistant may also switch to Russian if you wrote in Russian, pasted Russian text or pinned an instruction like “answer in Russian”. Write “answer in English” and it will switch.",
                screenshot = "guide-settings"),
        HelpFAQ(questionRU = "Как заставить нейросеть искать в интернете?",
                questionEN = "How do I make the assistant search the web?",
                answerRU = "Включите кнопку **«Поиск»** под полем ввода. Это даёт нейросети возможность выходить в сеть, но решение она принимает сама. Чтобы поиск был наверняка, скажите прямо: «найди в интернете», «проверь в сети свежие данные».",
                answerEN = "Turn on the **Search** button below the message field. It gives the assistant the ability to go online, but it decides by itself. To make sure it searches, say so: “search the web”, “check the latest data online”.",
                screenshot = "guide-search"),
        HelpFAQ(questionRU = "Что будет, если не ответить на вопрос за 10 секунд?",
                questionEN = "What happens if I don't answer a question within 10 seconds?",
                answerRU = "Карточка вопроса закроется, и Honer AI **решит сам** — выберет самый разумный вариант и продолжит работу. Ничего не сломается. Если выбор не понравился, просто напишите, что хотели другое.",
                answerEN = "The question card closes and Honer AI **decides by itself** — it picks the most sensible option and carries on. Nothing breaks. If you don't like its choice, just say what you wanted instead.",
                demo = HelpDemo.questionTimer),
        HelpFAQ(questionRU = "Работает ли приложение без интернета?",
                questionEN = "Does the app work offline?",
                answerRU = "Для ответов нейросети **нужен интернет** — модель работает на сервере. Без сети доступны: история чатов и поиск по ней, просмотр вложений, **редактор фото и видео**, **игры**, настройки и память.",
                answerEN = "The assistant's answers **need the internet** — the model runs on a server. Offline you still have chat history and search, attachment viewing, the **photo and video editor**, **games**, settings and memory."),
        HelpFAQ(questionRU = "Сколько сайтов может прочитать нейросеть?",
                questionEN = "How many sites can the assistant read?",
                answerRU = "Для обычного вопроса — несколько страниц. Для исследования — **сотни и даже тысячи**: попросите, например, «изучи 500 сайтов». Во время работы виден счётчик «Прочитано N из M сайтов».",
                answerEN = "For a normal question, a few pages. For research — **hundreds or even thousands**: ask, for example, “study 500 sites”. A “Read N of M sites” counter shows progress.",
                demo = HelpDemo.webResearch),
        HelpFAQ(questionRU = "Можно ли читать закрытые страницы Instagram или Facebook?",
                questionEN = "Can it read private Instagram or Facebook pages?",
                answerRU = "**Нет.** Honer AI работает только с **публичными** страницами и открытыми API и никогда не входит в ваши аккаунты. Закрытые профили, личные сообщения, приватные группы и каналы недоступны в любой соцсети.",
                answerEN = "**No.** Honer AI only works with **public** pages and open APIs and never signs in to your accounts. Private profiles, direct messages, closed groups and channels are unavailable on every social network."),
        HelpFAQ(questionRU = "Как установить приложение другу?",
                questionEN = "How can I install the app for a friend?",
                answerRU = "Honer AI не распространяется через Google Play: приложение устанавливается через рассылку владельца приложения. Чтобы установить его другу, обратитесь к владельцу — он добавит новое устройство.",
                answerEN = "Honer AI isn't distributed through Google Play: it's installed through the app owner's distribution. To install it for a friend, contact the owner — they'll add the new device."),
        HelpFAQ(questionRU = "Нейросеть ошиблась. Что делать?",
                questionEN = "The assistant made a mistake. What should I do?",
                answerRU = "Скажите, в чём ошибка, — она исправится. Можно нажать **«Повторить»** под ответом, включить **«Рассуждение»** для сложной задачи или **«Поиск»** для свежих фактов. Важные сведения (здоровье, деньги, право) всегда перепроверяйте.",
                answerEN = "Say what's wrong and it will correct itself. You can tap **Regenerate** under the answer, turn on **Reasoning** for a hard task or **Search** for current facts. Always double-check important information (health, money, law).")
    )
    return items
}

internal val faqChats: List<HelpFAQ> get() {
    val items: List<HelpFAQ> = listOf(
        HelpFAQ(questionRU = "Как перетащить чат?",
                questionEN = "How do I drag a chat?",
                answerRU = "Откройте **☰**, нажмите на чат и удерживайте, пока он не приподнимется, затем перетащите в раздел **«Закреплено»** — чат закрепится. Закреплённые чаты так же перетаскиваются вверх-вниз, чтобы поменять порядок.",
                answerEN = "Open **☰**, press and hold a chat until it lifts, then drag it into **Pinned** — the chat gets pinned. Pinned chats can be dragged up and down the same way to reorder them.",
                demo = HelpDemo.dragChat),
        HelpFAQ(questionRU = "Как вернуть чат из архива?",
                questionEN = "How do I restore a chat from the archive?",
                answerRU = "Откройте **Настройки → Архив чатов**, найдите чат и нажмите **«Восстановить»**. Он снова появится в боковой панели со всей перепиской.",
                answerEN = "Open **Settings → Archived chats**, find the chat and tap **Restore**. It reappears in the sidebar with the whole conversation."),
        HelpFAQ(questionRU = "Как скопировать часть ответа?",
                questionEN = "How do I copy part of an answer?",
                answerRU = "Долго нажмите на ответ → **«Выбрать текст»**, выделите нужный кусок маркерами и нажмите **«Копировать»**. Там же есть **«Спросить Honer AI»**, чтобы задать вопрос именно об этом фрагменте.",
                answerEN = "Long-press the answer → **Select text**, drag the handles over the part you need and tap **Copy**. The same menu has **Ask Honer AI** to ask about that exact fragment.",
                demo = HelpDemo.selectAsk),
        HelpFAQ(questionRU = "Как найти старый разговор?",
                questionEN = "How do I find an old conversation?",
                answerRU = "Откройте **☰** и введите слово в **«Поиск в содержимом…»** — поиск идёт по тексту всех сообщений, а не только по названиям.",
                answerEN = "Open **☰** and type a word into **Search content…** — it searches the text of every message, not just titles.",
                screenshot = "guide-history"),
        HelpFAQ(questionRU = "Как начать разговор заново с середины?",
                questionEN = "How do I restart a conversation from the middle?",
                answerRU = "Долго нажмите на сообщение → **«Продолжить в ветке»**. Появится новый чат «Ветка · …» с перепиской до этого места, а исходный чат останется как был.",
                answerEN = "Long-press a message → **Continue in a branch**. A new “Branch · …” chat appears with the conversation up to that point, and the original stays as it was.",
                screenshot = "guide-branches"),
        HelpFAQ(questionRU = "Как удалить сразу несколько чатов?",
                questionEN = "How do I delete several chats at once?",
                answerRU = "В боковой панели нажмите кнопку выбора (значок со списком справа от «Закреплено»), отметьте чаты и выберите **«Удалить»** или **«В архив»**.",
                answerEN = "In the sidebar tap the select button (the list icon to the right of Pinned), tick the chats and choose **Delete** or **Archive**."),
        HelpFAQ(questionRU = "Где найти все фото и ссылки из чата?",
                questionEN = "Where can I find all photos and links from a chat?",
                answerRU = "**••• → Информация о чате**: там все фото, видео, голосовые, музыка, файлы и посещённые ссылки — ваши и нейросети, с фильтрами, поиском и хронологией.",
                answerEN = "**••• → Chat info**: every photo, video, voice note, music track, file and visited link — yours and the assistant's — with filters, search and a timeline."),
        HelpFAQ(questionRU = "Что за полоски у правого края экрана?",
                questionEN = "What are the lines at the right edge of the screen?",
                answerRU = "Это линии навигации: каждая — одно сообщение. Нажмите и удерживайте, чтобы увидеть предпросмотр, ведите пальцем и отпустите, чтобы перейти к сообщению.",
                answerEN = "Those are navigation lines: one per message. Press and hold to preview, slide your finger and let go to jump to that message.",
                screenshot = "guide-navigation")
    )
    return items
}

internal val faqFiles: List<HelpFAQ> get() {
    val items: List<HelpFAQ> = listOf(
        HelpFAQ(questionRU = "Нейросеть не видит мои файлы Excel?",
                questionEN = "The assistant can't see my Excel files?",
                answerRU = "Excel (xlsx) и CSV поддерживаются и читаются как таблицы. Проверьте: файл прикреплён через **+ → Файл** и видна карточка над полем ввода; файл не защищён паролем; для книги с несколькими листами укажите нужный лист. Старый формат .xls лучше пересохранить в .xlsx.",
                answerEN = "Excel (xlsx) and CSV are supported and read as tables. Check that: the file was attached via **+ → File** and its card shows above the message field; it isn't password-protected; for a multi-sheet workbook you named the sheet. Re-save old .xls files as .xlsx.",
                screenshot = "guide-attachments"),
        HelpFAQ(questionRU = "Какие файлы можно отправить?",
                questionEN = "Which files can I send?",
                answerRU = "Фото, видео (до 2 минут и 40 МБ), голосовые и аудио, PDF, Word, Excel, CSV, PowerPoint, OpenDocument, RTF, HTML, EPUB, Jupyter и любые файлы с кодом.",
                answerEN = "Photos, videos (up to 2 minutes and 40 MB), voice notes and audio, PDF, Word, Excel, CSV, PowerPoint, OpenDocument, RTF, HTML, EPUB, Jupyter and any code files."),
        HelpFAQ(questionRU = "Нейросеть правда смотрит видео?",
                questionEN = "Does the assistant really watch videos?",
                answerRU = "Да: она просматривает ключевые кадры и слушает звуковую дорожку — речь распознаётся прямо на телефоне. Очень быстрые детали между кадрами могут быть пропущены.",
                answerEN = "Yes: it looks at key frames and listens to the soundtrack — speech is recognised right on the phone. Very quick details between frames can be missed."),
        HelpFAQ(questionRU = "Как убрать фон с фото?",
                questionEN = "How do I remove a photo's background?",
                answerRU = "Откройте фото → **«Редактировать» → Фон → Удалить фон**. Или просто напишите нейросети «убери фон» вместе с фото. Затем можно поставить цвет, размытие, градиент или другое фото.",
                answerEN = "Open the photo → **Edit → Background → Remove background**. Or just send the photo with “remove the background”. Then add a colour, blur, gradient or another photo.",
                demo = HelpDemo.photoEditor),
        HelpFAQ(questionRU = "Как отредактировать таблицу, которую сделала нейросеть?",
                questionEN = "How do I edit a table the assistant made?",
                answerRU = "Нажмите на таблицу — она откроется на весь экран. Нажмите на ячейку, чтобы изменить значение, используйте кнопки добавления строк и столбцов. Нейросеть увидит ваши правки. Если таблица только для чтения, попросите «сделай её редактируемой».",
                answerEN = "Tap the table to open it full screen. Tap a cell to change it and use the buttons to add rows and columns. The assistant sees your edits. If the table is read-only, ask it to “make it editable”.",
                demo = HelpDemo.table),
        HelpFAQ(questionRU = "Голосовой ввод плохо распознаёт речь. Что делать?",
                questionEN = "Voice input doesn't recognise me well. What can I do?",
                answerRU = "Выберите правильный язык в **Настройки → Основной язык**, говорите ближе к микрофону и без сильного шума. Проверьте, что у приложения есть доступ к микрофону (**Настройки → Разрешения**).",
                answerEN = "Choose the right language in **Settings → Speech language**, speak closer to the mic and avoid loud noise. Make sure the app has microphone access (**Settings → Permissions**).",
                demo = HelpDemo.voice),
        HelpFAQ(questionRU = "Голос озвучки звучит как робот.",
                questionEN = "The reading voice sounds robotic.",
                answerRU = "Скачайте голос высокого качества: **Настройки → Голос → Скачать голоса** (или настройки Android → Система → Язык и ввод → Синтез речи → Установить голосовые данные) → Русский и English. Приложение выберет его само.",
                answerEN = "Download a high-quality voice: **Settings → Voice → Download voices** (or Android settings → System → Languages & input → Text-to-speech → Install voice data) → English and Russian. The app picks it automatically.",
                screenshot = "guide-voice-settings"),
        HelpFAQ(questionRU = "Почему ответ начинает читаться вслух сам?",
                questionEN = "Why does the answer start reading aloud by itself?",
                answerRU = "Включён динамик вверху экрана — автоматическое чтение. Нажмите на него, чтобы он стал перечёркнутым, и озвучка выключится.",
                answerEN = "The speaker at the top of the screen — auto read-aloud — is on. Tap it so it's crossed out and reading stops.")
    )
    return items
}

internal val faqMore: List<HelpFAQ> get() {
    val items: List<HelpFAQ> = listOf(
        HelpFAQ(questionRU = "Как удалить факт из памяти?",
                questionEN = "How do I delete a fact from memory?",
                answerRU = "Откройте **Настройки → Память Honer AI**, нажмите на значок корзины рядом с фактом. Или напишите в чате «забудь, что …».",
                answerEN = "Open **Settings → Honer AI memory**, tap the trash icon next to the fact. Or write in the chat “forget that …”.",
                screenshot = "guide-memory"),
        HelpFAQ(questionRU = "Чем память отличается от инструкций?",
                questionEN = "How is memory different from instructions?",
                answerRU = "Память — факты о вас, общие для всех чатов. Инструкции — правила ответа (язык, стиль, роль), которые действуют только в том чате, где закреплены.",
                answerEN = "Memory is facts about you, shared by all chats. Instructions are answer rules (language, style, role) that apply only in the chat where they're pinned.",
                screenshot = "guide-pinned-instruction"),
        HelpFAQ(questionRU = "Как перенести чаты на новый телефон?",
                questionEN = "How do I move chats to a new phone?",
                answerRU = "На старом телефоне: **Настройки → Управление данными → Экспортировать историю** и сохраните файл в Google Drive (или включите автокопирование). На новом: на первом экране **«Восстановить из резервной копии»** и выберите файл.",
                answerEN = "On the old phone: **Settings → Data management → Export history** and save the file to Google Drive (or turn on auto backup). On the new one: tap **Restore from backup** on the first screen and pick the file.",
                screenshot = "guide-backup"),
        HelpFAQ(questionRU = "Ребёнок может сам выключить родительский контроль?",
                questionEN = "Can a child turn parental controls off?",
                answerRU = "Нет: для выключения и изменения настроек нужен PIN. После нескольких неверных попыток ввод временно блокируется. Контроль выключен по умолчанию и сам никогда не включается.",
                answerEN = "No: switching off or changing settings requires the PIN. After several wrong attempts, entry is temporarily locked. Controls are off by default and never turn on by themselves.",
                demo = HelpDemo.parental),
        HelpFAQ(questionRU = "Не приходят уведомления о готовом ответе.",
                questionEN = "I don't get notifications when an answer is ready.",
                answerRU = "Разрешите уведомления: настройки Android → Приложения → Honer AI → Уведомления. Также проверьте режим «Фокус» и что в **Настройки → Разрешения** уведомления включены.",
                answerEN = "Allow notifications: Android settings → Apps → Honer AI → Notifications. Also check Focus mode and that notifications are enabled in **Settings → Permissions**."),
        HelpFAQ(questionRU = "Откуда нейросеть знает мой город и модель телефона?",
                questionEN = "How does the assistant know my city and phone model?",
                answerRU = "Город — из геопозиции, только если вы её разрешили. Модель телефона, версию Android и часовой пояс приложение берёт из системы, чтобы давать точные советы. Больше о вас нейросеть ничего не знает, кроме имени, даты рождения (если указана) и фактов из памяти.",
                answerEN = "Your city comes from location, only if you allowed it. The phone model, Android version and time zone come from the system so advice is accurate. Beyond that it only knows your name, birthday (if set) and memory facts."),
        HelpFAQ(questionRU = "Может ли нейросеть что-то купить или заказать?",
                questionEN = "Can the assistant buy or order things?",
                answerRU = "Нет. Она ищет товары на Wildberries, Ozon и Avito, сравнивает цены и даёт ссылки, но покупку вы делаете сами на сайте магазина.",
                answerEN = "No. It searches Wildberries, Ozon and Avito, compares prices and gives links, but you make the purchase yourself on the store's site."),
        HelpFAQ(questionRU = "Как сыграть с нейросетью в шахматы?",
                questionEN = "How do I play chess with the assistant?",
                answerRU = "Напишите «давай сыграем в шахматы» — игра откроется. Также доступны шашки, «Дурак» и «Удача». Результат партии появится в чате.",
                answerEN = "Write “let's play chess” — the game opens. Checkers, Durak and Luck are available too. The result appears in the chat.",
                screenshot = "guide-games")
    )
    return items
}

// MARK: - Содержание: лайфхаки

internal val lifehacksPartOne: List<HelpLifehack> get() {
    val items: List<HelpLifehack> = listOf(
        HelpLifehack(id = "hack-voice-chat", symbol = "waveform", tint = HelpTint.red,
                     titleRU = "Разговор голосом", titleEN = "Voice conversation",
                     textRU = "Включите динамик вверху и отвечайте микрофоном — получится живой диалог без рук: вы говорите, Honer AI отвечает вслух.",
                     textEN = "Turn on the speaker at the top and reply with the microphone — a hands-free dialogue: you talk, Honer AI answers aloud."),
        HelpLifehack(id = "hack-helper-chats", symbol = "pin", tint = HelpTint.orange,
                     titleRU = "Чаты-помощники", titleEN = "Helper chats",
                     textRU = "Создайте чат «Переводчик» и закрепите инструкцию «переводи всё, что я пишу, на английский». Закрепите сам чат — и он всегда под рукой.",
                     textEN = "Create a “Translator” chat and pin the instruction “translate everything I write into Russian”. Pin the chat itself so it's always at hand."),
        HelpLifehack(id = "hack-lecture", symbol = "mic.badge.plus", tint = HelpTint.purple,
                     titleRU = "Конспект лекции", titleEN = "Lecture notes",
                     textRU = "Запишите лекцию на диктофон, отправьте файл и попросите «конспект по пунктам + 5 вопросов для самопроверки».",
                     textEN = "Record a lecture, send the file and ask for “bullet-point notes + 5 self-test questions”."),
        HelpLifehack(id = "hack-exam", symbol = "graduationcap", tint = HelpTint.green,
                     titleRU = "Подготовка к экзамену", titleEN = "Exam prep",
                     textRU = "Прикрепите учебник в PDF и попросите тест из 20 вопросов по главе — ответы проверятся мгновенно, а ошибки нейросеть разберёт.",
                     textEN = "Attach a PDF textbook and ask for a 20-question test on a chapter — answers are scored instantly and mistakes explained."),
        HelpLifehack(id = "hack-receipt", symbol = "doc.text.viewfinder", tint = HelpTint.teal,
                     titleRU = "Чек → таблица", titleEN = "Receipt → table",
                     textRU = "Сфотографируйте чек и попросите «сделай редактируемую таблицу расходов» — потом её можно поправить и выгрузить в CSV.",
                     textEN = "Photograph a receipt and ask for “an editable expenses table” — then tweak it and export to CSV."),
        HelpLifehack(id = "hack-compare-branches", symbol = "arrow.triangle.branch", tint = HelpTint.indigo,
                     titleRU = "Два варианта сразу", titleEN = "Two versions at once",
                     textRU = "Не уверены, какой стиль письма лучше? Создайте ветку: в одной попросите официальный тон, в другой — дружеский, и сравните.",
                     textEN = "Not sure which writing style is better? Make a branch: ask for a formal tone in one and a friendly one in the other, then compare."),
        HelpLifehack(id = "hack-ask-fragment", symbol = "text.cursor", tint = HelpTint.pink,
                     titleRU = "Разбор по кусочкам", titleEN = "Piece by piece",
                     textRU = "В сложном тексте выделяйте непонятные термины и жмите «Спросить Honer AI» — объяснение придёт именно по выделенному.",
                     textEN = "In a complex text, select unclear terms and tap “Ask Honer AI” — you get an explanation of exactly that part.")
    )
    return items
}

internal val lifehacksPartTwo: List<HelpLifehack> get() {
    val items: List<HelpLifehack> = listOf(
        HelpLifehack(id = "hack-research-background", symbol = "moon.zzz", tint = HelpTint.blue,
                     titleRU = "Исследование в фоне", titleEN = "Research in the background",
                     textRU = "Запустите «изучи 500 сайтов о …» и сверните приложение. Когда сводка будет готова, придёт уведомление.",
                     textEN = "Start “study 500 sites about …” and leave the app. A notification arrives when the summary is ready."),
        HelpLifehack(id = "hack-avatar", symbol = "person.crop.square", tint = HelpTint.pink,
                     titleRU = "Аватарка за минуту", titleEN = "An avatar in a minute",
                     textRU = "Селфи → «убери фон, поставь градиент и обрежь квадратом» — готовая аватарка без сторонних приложений.",
                     textEN = "Selfie → “remove the background, add a gradient and crop square” — a ready avatar without other apps."),
        HelpLifehack(id = "hack-shopping", symbol = "cart", tint = HelpTint.purple,
                     titleRU = "Выгодная покупка", titleEN = "Smart shopping",
                     textRU = "«Сравни цены на Wildberries, Ozon и Avito на … и сделай таблицу: цена, рейтинг, ссылка» — сразу видно, где дешевле.",
                     textEN = "“Compare prices on Wildberries, Ozon and Avito for … as a table: price, rating, link” — you instantly see where it's cheaper."),
        HelpLifehack(id = "hack-youtube", symbol = "play.tv", tint = HelpTint.red,
                     titleRU = "Час видео за минуту", titleEN = "An hour of video in a minute",
                     textRU = "Вставьте ссылку на длинное видео YouTube и попросите «главные мысли с таймкодами».",
                     textEN = "Paste a long YouTube link and ask for “key ideas with timestamps”."),
        HelpLifehack(id = "hack-memory", symbol = "brain", tint = HelpTint.pink,
                     titleRU = "Скажите один раз", titleEN = "Say it once",
                     textRU = "«Запомни: я вегетарианец и живу в Самаре» — и все рецепты и советы дальше будут это учитывать, в любом чате.",
                     textEN = "“Remember: I'm vegetarian and live in Samara” — every recipe and tip from then on takes it into account, in any chat."),
        HelpLifehack(id = "hack-reactions", symbol = "hand.thumbsdown", tint = HelpTint.yellow,
                     titleRU = "Реакция вместо слов", titleEN = "React instead of typing",
                     textRU = "Поставьте 👎 на неудачный ответ — нейросеть это увидит и в следующий раз объяснит иначе.",
                     textEN = "Put 👎 on a weak answer — the assistant sees it and explains differently next time."),
        HelpLifehack(id = "hack-diagram", symbol = "point.3.connected.trianglepath.dotted", tint = HelpTint.orange,
                     titleRU = "Схема вместо текста", titleEN = "A diagram instead of text",
                     textRU = "Попросите «нарисуй диаграмму mermaid процесса» — сложная последовательность шагов станет наглядной картинкой.",
                     textEN = "Ask to “draw a mermaid diagram of the process” — a complex sequence of steps becomes a clear picture."),
        HelpLifehack(id = "hack-quick-jump", symbol = "line.3.horizontal", tint = HelpTint.indigo,
                     titleRU = "Быстрый переход", titleEN = "Quick jump",
                     textRU = "В длинном чате нажмите и удерживайте линии справа — пролистайте сотни сообщений одним движением пальца.",
                     textEN = "In a long chat, press and hold the lines on the right — scroll through hundreds of messages with one finger movement.")
    )
    return items
}
