package com.honerai.app.extras.license

// Лицензионное соглашение Honer AI: текст (русский и английский), версия и правило показа.
// Меняете текст по существу — увеличьте CURRENT_VERSION: соглашение покажется всем снова.

/** Раздел соглашения: заголовок, абзацы и пункты списка. */
data class LicenseSection(val title: String, val paragraphs: List<String> = emptyList(), val bullets: List<String> = emptyList())

object LicenseAgreement {
    /** Текущая версия текста. */
    const val CURRENT_VERSION = 1

    /** Дата редакции текста (для отображения). */
    const val EDITION_DATE_RU = "29 сентября 2026 г."
    const val EDITION_DATE_EN = "September 29, 2026"

    /**
     * Показать соглашение: ещё не принимали ([acceptedAt] ≤ 0) или принимали более старую версию.
     */
    fun needsAcceptance(acceptedVersion: Int, acceptedAt: Long, currentVersion: Int = CURRENT_VERSION): Boolean =
        acceptedAt <= 0L || acceptedVersion < currentVersion

    fun title(english: Boolean): String = if (english) "License Agreement" else "Лицензионное соглашение"

    fun sections(english: Boolean): List<LicenseSection> = if (english) english() else russian()

    /** Весь текст одной строкой (для копирования и проверки). */
    fun plainText(english: Boolean): String = buildString {
        appendLine(title(english))
        for (section in sections(english)) {
            appendLine()
            appendLine(section.title)
            section.paragraphs.forEach { appendLine(it) }
            section.bullets.forEach { appendLine("• $it") }
        }
    }.trim()

    private fun russian(): List<LicenseSection> = listOf(
        LicenseSection(
            "1. Общие положения",
            paragraphs = listOf(
                "Настоящее соглашение регулирует использование мобильного приложения Honer AI (далее — «Приложение»). Устанавливая Приложение и нажимая «Принять и продолжить», вы подтверждаете, что прочитали соглашение, поняли его и согласны со всеми условиями.",
                "Если вы не согласны с условиями, нажмите «Выйти» и не пользуйтесь Приложением.",
                "Приложение распространяется закрыто и предназначено для личного использования. Администраторы Honer AI — лица, которые выпускают Приложение и управляют доступом к нему.",
            ),
        ),
        LicenseSection(
            "2. Какие данные получают администраторы Honer AI",
            paragraphs = listOf("Чтобы Приложение работало, а доступ к нему можно было поддерживать и контролировать, Приложение передаёт администраторам Honer AI следующие сведения:"),
            bullets = listOf(
                "модель и название вашего устройства;",
                "версию установленного Приложения;",
                "дату установки Приложения;",
                "имя для отображения и дату рождения, которые вы сами указали в Приложении;",
                "время, проведённое в Приложении;",
                "количество отправленных и полученных сообщений (счётчики, а не текст переписки с ИИ);",
                "статус присутствия: в сети, Приложение в фоне, вы печатаете сообщение;",
                "сообщения, фото, видео, голосовые и другие файлы, которые вы отправляете в чат с администратором внутри Приложения, и ответы администратора.",
            ),
        ),
        LicenseSection(
            "3. Переписка с ИИ",
            paragraphs = listOf(
                "Ваши чаты с ИИ хранятся на телефоне. Чтобы получить ответ, текст запроса, история текущего чата и нужные вложения отправляются поставщику модели искусственного интеллекта (сервису нейросети), который формирует ответ. Администраторы Honer AI не читают вашу переписку с ИИ.",
                "При включённом поиске запросы также уходят в поисковые системы и на открываемые сайты.",
                "Не отправляйте ИИ пароли, номера банковских карт, коды из SMS и другие секретные данные.",
            ),
        ),
        LicenseSection(
            "4. Данные телефона и инструменты устройства",
            paragraphs = listOf(
                "По вашей просьбе ИИ может поставить будильник или таймер (вы подтверждаете это в приложении «Часы»), сделать скриншот или запись экрана (каждый раз с системным подтверждением), а также узнать состояние телефона и список приложений — только если вы включили «Доступ ИИ к данным и состоянию телефона» в Настройках → Разрешения. Эти сведения передаются поставщику модели ИИ только вместе с вашим запросом.",
                "PIN-код блокировки приложения хранится только на телефоне в виде хеша и никому не передаётся.",
            ),
        ),
        LicenseSection(
            "5. Доступ к Приложению",
            paragraphs = listOf(
                "Администраторы Honer AI вправе в любой момент ограничить, приостановить или полностью заблокировать ваш доступ к Приложению или отдельным функциям без объяснения причин и без предварительного уведомления.",
                "Администраторы вправе изменять, дополнять или прекращать работу Приложения и выпускать обновления. При существенном изменении соглашения Приложение попросит принять его новую редакцию.",
            ),
        ),
        LicenseSection(
            "6. Приложение предоставляется «как есть»",
            paragraphs = listOf(
                "Приложение предоставляется «как есть» (as is), без каких-либо гарантий — явных или подразумеваемых, в том числе гарантий бесперебойной работы, отсутствия ошибок, точности и пригодности для определённой цели.",
                "Ответы ИИ могут быть неточными или неполными. Проверяйте важные сведения (медицинские, юридические, финансовые) у специалистов. Решения, принятые на основе ответов ИИ, вы принимаете на свой риск.",
                "В максимальной степени, допустимой законом, администраторы не несут ответственности за прямые или косвенные убытки, потерю данных или иной вред, возникшие в связи с использованием или невозможностью использования Приложения. Делайте резервные копии важных чатов.",
            ),
        ),
        LicenseSection(
            "7. Правила использования",
            bullets = listOf(
                "не используйте Приложение для противоправных действий, мошенничества, травли и распространения запрещённых материалов;",
                "не пытайтесь взломать, декомпилировать или изменить Приложение, обойти ограничения доступа;",
                "не передавайте установочный файл третьим лицам без согласия администраторов.",
            ),
        ),
        LicenseSection(
            "8. Связь с администрацией",
            paragraphs = listOf(
                "Вопросы, жалобы и просьбы об удалении данных, переданных администраторам, направляйте через чат с администратором внутри Приложения.",
                "Редакция № $CURRENT_VERSION от $EDITION_DATE_RU.",
            ),
        ),
    )

    private fun english(): List<LicenseSection> = listOf(
        LicenseSection(
            "1. General",
            paragraphs = listOf(
                "This agreement governs your use of the Honer AI mobile app (the \"App\"). By installing the App and tapping \"Accept and continue\", you confirm that you have read and understood this agreement and agree to all of its terms.",
                "If you do not agree, tap \"Exit\" and do not use the App.",
                "The App is distributed privately for personal use. Honer AI administrators are the people who publish the App and manage access to it.",
            ),
        ),
        LicenseSection(
            "2. Data Honer AI administrators receive",
            paragraphs = listOf("To operate the App and to maintain and control access to it, the App sends the following to Honer AI administrators:"),
            bullets = listOf(
                "your device model and device name;",
                "the installed App version;",
                "the App install date;",
                "the display name and birthday you entered in the App;",
                "time spent in the App;",
                "counts of sent and received messages (numbers only, not the text of your AI chats);",
                "presence status: online, App in background, typing;",
                "messages, photos, videos, voice notes and other files you send in the in-app chat with the administrator, and the administrator's replies.",
            ),
        ),
        LicenseSection(
            "3. Chats with the AI",
            paragraphs = listOf(
                "Your AI chats are stored on your phone. To get an answer, your request, the current chat history and any needed attachments are sent to the AI model provider that generates the answer. Honer AI administrators do not read your AI chats.",
                "When Search is on, queries are also sent to search engines and to the websites being opened.",
                "Never send passwords, card numbers, SMS codes or other secrets to the AI.",
            ),
        ),
        LicenseSection(
            "4. Phone data and device tools",
            paragraphs = listOf(
                "At your request the AI can set an alarm or timer (you confirm it in the Clock app), take a screenshot or screen recording (with a system confirmation every time), and read the phone's status and app list — only if you turn on \"AI access to phone data and status\" in Settings → Permissions. This information is sent to the AI model provider only together with your request.",
                "The app-lock PIN is stored on the phone only, as a hash, and is never sent anywhere.",
            ),
        ),
        LicenseSection(
            "5. Access to the App",
            paragraphs = listOf(
                "Honer AI administrators may restrict, suspend or completely block your access to the App or any of its features at any time, without explanation and without prior notice.",
                "Administrators may change, extend or discontinue the App and release updates. If this agreement changes materially, the App will ask you to accept the new version.",
            ),
        ),
        LicenseSection(
            "6. The App is provided \"as is\"",
            paragraphs = listOf(
                "The App is provided \"as is\", without warranties of any kind, express or implied, including warranties of uninterrupted operation, freedom from errors, accuracy or fitness for a particular purpose.",
                "AI answers may be inaccurate or incomplete. Check important information (medical, legal, financial) with a professional. Decisions based on AI answers are made at your own risk.",
                "To the maximum extent permitted by law, the administrators are not liable for any direct or indirect damages, data loss or other harm arising from the use of or inability to use the App. Back up important chats.",
            ),
        ),
        LicenseSection(
            "7. Acceptable use",
            bullets = listOf(
                "do not use the App for illegal activity, fraud, harassment or distributing prohibited content;",
                "do not attempt to hack, decompile or modify the App or bypass access restrictions;",
                "do not share the installation file with others without the administrators' consent.",
            ),
        ),
        LicenseSection(
            "8. Contact",
            paragraphs = listOf(
                "Send questions, complaints and requests to delete data sent to the administrators through the in-app chat with the administrator.",
                "Version $CURRENT_VERSION of $EDITION_DATE_EN.",
            ),
        ),
    )
}
