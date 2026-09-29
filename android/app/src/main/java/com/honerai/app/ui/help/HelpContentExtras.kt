package com.honerai.app.ui.help

// MARK: - Содержание: блокировка, инструменты телефона и соглашение (дополнения)

internal val extrasSection: HelpSection get() {
    val items: List<HelpArticle> = listOf(articleAppLock, articleDeviceTools, articleLicense)
    return HelpSection(id = "device-security", symbol = "shield.lefthalf.filled", tint = HelpTint.indigo,
                       titleRU = "Блокировка и телефон", titleEN = "Lock and phone", articles = items)
}

private val articleAppLock: HelpArticle get() {
    return HelpArticle(
        id = "app-lock", symbol = "lock.shield", tint = HelpTint.indigo,
        titleRU = "Блокировка приложения", titleEN = "App lock",
        summaryRU = "PIN-код или отпечаток / лицо при входе в Honer AI.",
        summaryEN = "A PIN or fingerprint / face when you open Honer AI.",
        bodyRU = listOf(
            "В **Настройки → Блокировка приложения** выберите **PIN-код** (4–6 цифр) или биометрию — «Отпечаток пальца», «Распознавание лица» или «Биометрия», в зависимости от телефона. Биометрия появляется в списке, только если телефон её поддерживает; PIN всегда остаётся запасным способом.",
            "Honer AI заблокируется, когда вы уйдёте из приложения — **сразу** или через **1, 5 или 15 минут**. После 5 неверных PIN подряд ввод на 30 секунд замирает.",
            "**«Скрывать в недавних»** прячет содержимое чата в списке недавних приложений. PIN хранится только на телефоне в виде хеша; если вы его забыли, переустановите приложение (заранее сделайте резервную копию)."
        ),
        bodyEN = listOf(
            "In **Settings → App lock** choose **PIN** (4–6 digits) or biometrics — Fingerprint, Face unlock or Biometrics, depending on your phone. Biometrics are listed only if the phone supports them; the PIN always stays as a fallback.",
            "Honer AI locks when you leave it — **immediately** or after **1, 5 or 15 minutes**. After 5 wrong PINs in a row, input pauses for 30 seconds.",
            "**Hide in recents** hides the chat in the recent apps list. The PIN is stored on the phone only, as a hash; if you forget it, reinstall the app (make a backup first)."
        ),
        stepsRU = listOf(
            "Откройте **Настройки → Блокировка приложения**.",
            "Выберите **PIN-код**, придумайте и повторите его.",
            "По желанию включите биометрию и выберите, когда блокировать."
        ),
        stepsEN = listOf(
            "Open **Settings → App lock**.",
            "Choose **PIN**, then enter and repeat it.",
            "Optionally turn on biometrics and choose when to lock."
        ),
        related = listOf("device-tools", "data-privacy")
    )
}

private val articleDeviceTools: HelpArticle get() {
    return HelpArticle(
        id = "device-tools", symbol = "wand.and.stars", tint = HelpTint.orange,
        titleRU = "Будильник, экран и состояние телефона", titleEN = "Alarms, screen and phone status",
        summaryRU = "Попросите поставить будильник, сделать скриншот или проверить телефон.",
        summaryEN = "Ask for an alarm, a screenshot or a phone check-up.",
        bodyRU = listOf(
            "**Будильник и таймер**: «разбуди в 7:30 по будням», «таймер на 10 минут». Honer AI откроет приложение «Часы» — вы подтверждаете сами.",
            "**Скриншот и запись экрана**: «сделай скриншот через 5 секунд», «запиши экран 30 секунд». Android каждый раз спрашивает разрешение; при записи в уведомлении есть кнопка **«Стоп»**. Файлы сохраняются в галерею (папка «Honer AI») и появляются в чате — нейросеть видит, что на них.",
            "**Состояние телефона**: заряд и здоровье батареи, температура, память, сеть, нагрев — с понятными советами. Также можно узнать список приложений, число контактов и экранное время за сегодня.",
            "Скриншоты, запись, состояние и данные телефона работают только при включённом переключателе **«Доступ ИИ к данным и состоянию телефона»** в Настройки → Разрешения (по умолчанию выключен). Звонки и SMS Honer AI не читает."
        ),
        bodyEN = listOf(
            "**Alarms and timers**: \"wake me at 7:30 on weekdays\", \"timer for 10 minutes\". Honer AI opens the Clock app — you confirm it yourself.",
            "**Screenshots and screen recording**: \"take a screenshot in 5 seconds\", \"record the screen for 30 seconds\". Android asks for permission every time; while recording there's a **Stop** button in the notification. Files are saved to the gallery (Honer AI folder) and appear in the chat — the AI sees what's on them.",
            "**Phone status**: battery level and health, temperature, memory, network, heat — with clear advice. You can also ask for the app list, contact count and today's screen time.",
            "Screenshots, recording, status and phone data work only when **AI access to phone data and status** is on in Settings → Permissions (off by default). Honer AI never reads calls or SMS."
        ),
        tipsRU = listOf(
            "Для экранного времени разрешите «Статистику использования» в Настройки → Разрешения."
        ),
        tipsEN = listOf(
            "For screen time, allow Usage access in Settings → Permissions."
        ),
        related = listOf("app-lock", "permissions")
    )
}

private val articleLicense: HelpArticle get() {
    return HelpArticle(
        id = "license", symbol = "doc.richtext", tint = HelpTint.gray,
        titleRU = "Лицензионное соглашение", titleEN = "License agreement",
        summaryRU = "Какие данные передаются и на каких условиях работает приложение.",
        summaryEN = "What data is sent and the terms the app works under.",
        bodyRU = listOf(
            "При первом запуске Honer AI показывает лицензионное соглашение. В нём перечислено, что получают администраторы Honer AI: модель и имя устройства, версия и дата установки приложения, имя и день рождения из профиля, время в приложении, счётчики сообщений, статус «в сети / в фоне / печатает» и переписка в чате с администратором. Переписка с ИИ отправляется поставщику модели для ответа.",
            "Приложение предоставляется «как есть», доступ может быть ограничен администраторами. Перечитать соглашение можно в **Настройки → О программе → Лицензионное соглашение**; когда текст обновится, приложение попросит принять новую редакцию."
        ),
        bodyEN = listOf(
            "On first launch Honer AI shows the license agreement. It lists what Honer AI administrators receive: device model and name, app version and install date, profile name and birthday, time in the app, message counts, online / background / typing status and the in-app chat with the administrator. AI chats are sent to the AI model provider to get answers.",
            "The app is provided \"as is\" and administrators may restrict access. Reread the agreement in **Settings → About → License agreement**; when the text changes, the app asks you to accept the new version."
        ),
        related = listOf("data-privacy", "app-lock")
    )
}
