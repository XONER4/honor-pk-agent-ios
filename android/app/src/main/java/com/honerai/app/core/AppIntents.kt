package com.honerai.app.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// media: открытие приложений Google, Яндекса, кошельков и установленных программ.
// Только разрешённый список действий; платежей и переводов приложение не начинает никогда.

/** Интеграции с приложениями на телефоне (отдельно от интернет-интеграций). */
object AppIntegrations {
    data class Service(val id: String, val nameRU: String, val nameEN: String, val descriptionRU: String, val descriptionEN: String)

    const val GOOGLE = "google"
    const val YANDEX = "yandex"
    const val PAYMENTS = "payments"
    const val APPS = "apps"

    val services = listOf(
        Service(GOOGLE, "Google", "Google",
            "Карты (маршрут, место), письмо в Gmail, событие в Календаре, поиск на YouTube.",
            "Maps (route, place), Gmail compose, Calendar event, YouTube search."),
        Service(YANDEX, "Яндекс", "Yandex",
            "Яндекс Карты, Навигатор, Go (такси), Музыка и Погода; без приложения — сайт.",
            "Yandex Maps, Navigator, Go (taxi), Music and Weather; website if no app."),
        Service(PAYMENTS, "Google Pay · Кошелёк · Mir Pay · СБП", "Google Pay · Koshelek · Mir Pay · SBP",
            "Только открыть кошелёк или экран карт. Платить и переводить нейросеть не может.",
            "Only opens the wallet or its cards screen. The AI can never pay or transfer money."),
        Service(APPS, "Приложения на телефоне", "Apps on the phone",
            "Открыть установленное приложение по названию.",
            "Open an installed app by its name."),
    )

    private const val PREFS = "honer.integrations"
    private const val OPEN_NOW = "open_immediately"

    fun isEnabled(context: Context?, id: String): Boolean =
        context == null || runCatching { Integrations.isEnabled(context, id) }.getOrDefault(true)

    /** Открывать сразу, без нажатия на кнопку (по умолчанию выключено). */
    fun opensImmediately(context: Context?): Boolean = context != null && runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(OPEN_NOW, false)
    }.getOrDefault(false)

    fun setOpensImmediately(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(OPEN_NOW, value).apply()
    }
}

/** Просьба открыть приложение: то, что прислала модель (или что записано в блоке ```app). */
data class AppRequest(
    val app: String,
    val action: String = "",
    val query: String = "",
    val to: String = "",
    val subject: String = "",
    val body: String = "",
    val title: String = "",
    val start: String = "",
    val end: String = "",
    val location: String = "",
    /** Для установленных программ — пакет, найденный среди приложений лаунчера. */
    val packageName: String = "",
    val label: String = "",
) {
    /** Компактный JSON для блока ```app в ответе. */
    fun toJson(): JsonObject = buildJsonObject {
        put("app", app)
        fun opt(key: String, value: String) { if (value.isNotBlank()) put(key, value) }
        opt("action", action); opt("query", query); opt("to", to); opt("subject", subject); opt("body", body)
        opt("title", title); opt("start", start); opt("end", end); opt("location", location)
        opt("package", packageName); opt("label", label)
    }

    companion object {
        fun from(arguments: JsonObject): AppRequest {
            fun text(key: String) = ToolArgument.string(arguments[key])?.trim().orEmpty()
            return AppRequest(
                app = text("app"), action = text("action").lowercase(), query = text("query"),
                to = text("to"), subject = text("subject"), body = text("body"), title = text("title"),
                start = text("start"), end = text("end"), location = text("location"),
                packageName = text("package"), label = text("label"),
            )
        }

        fun parse(json: String): AppRequest? = (parseJson(json.trim()) as? JsonObject)?.let { from(it) }?.takeIf { it.app.isNotEmpty() }
    }
}

enum class LaunchKind { VIEW, SENDTO, CALENDAR_INSERT, PACKAGE }

/** Готовое действие: что открыть и чем подстраховаться, если приложения нет. */
data class AppLaunch(
    val appId: String,
    val label: String,
    val integration: String,
    val kind: LaunchKind,
    val uri: String?,
    val packages: List<String>,
    val webFallback: String?,
    val extras: Map<String, String> = emptyMap(),
    val payment: Boolean = false,
    val note: String = "",
)

/** Разрешённый список приложений и действий. Без Android — проверяется модульными тестами. */
object AppCatalog {
    private val aliases: Map<String, String> = buildMap {
        fun add(id: String, vararg names: String) { names.forEach { put(normalize(it), id) }; put(normalize(id), id) }
        add("google_maps", "google maps", "гугл карты", "карты google", "карты гугл", "googlemaps", "maps")
        add("gmail", "джимейл", "гмейл", "почта google", "google mail", "почта gmail", "email", "почта", "mail")
        add("google_calendar", "google calendar", "гугл календарь", "календарь google", "календарь", "calendar")
        add("youtube", "ютуб", "ютюб", "you tube")
        add("yandex_maps", "яндекс карты", "yandex maps", "яндекскарты", "карты яндекса", "карты")
        add("yandex_navigator", "яндекс навигатор", "навигатор", "yandex navigator", "navigator")
        add("yandex_taxi", "яндекс такси", "яндекс go", "yandex go", "такси", "taxi", "yandex taxi")
        add("yandex_music", "яндекс музыка", "yandex music", "музыка")
        add("yandex_weather", "яндекс погода", "yandex weather", "погода")
        add("google_wallet", "google pay", "google wallet", "гугл пэй", "гугл пей", "гугл кошелек", "gpay", "g pay")
        add("koshelek", "кошелёк", "кошелек", "koshelek", "кошелек карты")
        add("mir_pay", "mir pay", "мир пэй", "мир пей", "мирпэй", "mirpay")
        add("sbp", "сбп", "сбпэй", "sbpay", "sbp pay", "система быстрых платежей")
    }

    /** Приводит название к ключу: без регистра, «ё» = «е», без пробелов и знаков. */
    fun normalize(text: String): String = text.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }

    fun id(app: String): String? = aliases[normalize(app)]

    private fun enc(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private fun store(pkg: String) = "https://play.google.com/store/apps/details?id=$pkg"

    /** Пакеты, которые приложение должно видеть (они же перечислены в <queries> манифеста). */
    val knownPackages = listOf(
        "com.google.android.apps.maps", "com.google.android.gm", "com.google.android.calendar", "com.google.android.youtube",
        "ru.yandex.yandexmaps", "ru.yandex.yandexnavi", "ru.yandex.taxi", "ru.yandex.music", "ru.yandex.weatherplugin",
        "com.google.android.apps.walletnfcrel", "ru.cardsmobile.mw3", "ru.nspk.mirpay", "ru.nspk.sbpay",
    )

    val paymentPackages = setOf("com.google.android.apps.walletnfcrel", "ru.cardsmobile.mw3", "ru.nspk.mirpay", "ru.nspk.sbpay")

    fun resolve(request: AppRequest, zone: ZoneId = ZoneId.systemDefault()): AppLaunch? {
        val id = id(request.app) ?: return null
        val q = request.query.ifBlank { request.location }
        val route = request.action in setOf("route", "directions", "маршрут", "navigate", "проложить")
        return when (id) {
            "google_maps" -> {
                val uri = when {
                    q.isBlank() -> "https://www.google.com/maps"
                    route -> "https://www.google.com/maps/dir/?api=1&destination=${enc(q)}&travelmode=driving"
                    else -> "https://www.google.com/maps/search/?api=1&query=${enc(q)}"
                }
                AppLaunch(id, "Google Карты", AppIntegrations.GOOGLE, LaunchKind.VIEW, uri, listOf("com.google.android.apps.maps"), uri)
            }
            "gmail" -> {
                val to = request.to.ifBlank { request.query.takeIf { it.contains('@') }.orEmpty() }
                val params = listOfNotNull(
                    request.subject.takeIf { it.isNotBlank() }?.let { "subject=${enc(it)}" },
                    request.body.takeIf { it.isNotBlank() }?.let { "body=${enc(it)}" },
                ).joinToString("&")
                val mailto = "mailto:${enc(to).replace("%40", "@")}" + if (params.isEmpty()) "" else "?$params"
                val web = "https://mail.google.com/mail/?view=cm&fs=1&to=${enc(to)}&su=${enc(request.subject)}&body=${enc(request.body)}"
                AppLaunch(id, "Gmail", AppIntegrations.GOOGLE, LaunchKind.SENDTO, mailto, listOf("com.google.android.gm"), web,
                    note = "Письмо откроется черновиком: отправляет его сам пользователь.")
            }
            "google_calendar" -> {
                val title = request.title.ifBlank { request.query }
                val begin = time(request.start, zone)
                val end = time(request.end, zone) ?: begin?.plus(60 * 60 * 1000)
                val extras = buildMap {
                    if (title.isNotBlank()) put(CalendarExtras.TITLE, title)
                    if (request.body.isNotBlank()) put(CalendarExtras.DESCRIPTION, request.body)
                    if (request.location.isNotBlank()) put(CalendarExtras.LOCATION, request.location)
                    begin?.let { put(CalendarExtras.BEGIN, it.toString()) }
                    end?.let { put(CalendarExtras.END, it.toString()) }
                }
                val format = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                val dates = if (begin != null && end != null) "&dates=" + listOf(begin, end).joinToString("/") {
                    format.format(java.time.Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC))
                } else ""
                val web = "https://calendar.google.com/calendar/render?action=TEMPLATE&text=${enc(title)}&details=${enc(request.body)}&location=${enc(request.location)}$dates"
                AppLaunch(id, "Google Календарь", AppIntegrations.GOOGLE, LaunchKind.CALENDAR_INSERT, null,
                    listOf("com.google.android.calendar"), web, extras,
                    note = "Событие откроется для проверки: сохраняет его сам пользователь.")
            }
            "youtube" -> {
                val uri = if (q.isBlank()) "https://www.youtube.com/" else "https://www.youtube.com/results?search_query=${enc(q)}"
                AppLaunch(id, "YouTube", AppIntegrations.GOOGLE, LaunchKind.VIEW, uri, listOf("com.google.android.youtube"), uri)
            }
            "yandex_maps" -> {
                val uri = when {
                    q.isBlank() -> "https://yandex.ru/maps/"
                    route -> "https://yandex.ru/maps/?rtext=~${enc(q)}&rtt=auto"
                    else -> "https://yandex.ru/maps/?text=${enc(q)}"
                }
                AppLaunch(id, "Яндекс Карты", AppIntegrations.YANDEX, LaunchKind.VIEW, uri, listOf("ru.yandex.yandexmaps"), uri)
            }
            "yandex_navigator" -> {
                val uri = if (q.isBlank()) null else "yandexnavi://map_search?text=${enc(q)}"
                val web = if (q.isBlank()) "https://yandex.ru/maps/" else "https://yandex.ru/maps/?rtext=~${enc(q)}&rtt=auto"
                AppLaunch(id, "Яндекс Навигатор", AppIntegrations.YANDEX, if (uri == null) LaunchKind.PACKAGE else LaunchKind.VIEW,
                    uri, listOf("ru.yandex.yandexnavi"), web)
            }
            "yandex_taxi" -> AppLaunch(id, "Яндекс Go", AppIntegrations.YANDEX, LaunchKind.PACKAGE, null, listOf("ru.yandex.taxi"),
                "https://go.yandex/", note = "Адрес и тариф пользователь выбирает и подтверждает в приложении сам.")
            "yandex_music" -> {
                val uri = if (q.isBlank()) "https://music.yandex.ru/" else "https://music.yandex.ru/search?text=${enc(q)}"
                AppLaunch(id, "Яндекс Музыка", AppIntegrations.YANDEX, LaunchKind.VIEW, uri, listOf("ru.yandex.music"), uri)
            }
            "yandex_weather" -> AppLaunch(id, "Яндекс Погода", AppIntegrations.YANDEX, LaunchKind.PACKAGE, null,
                listOf("ru.yandex.weatherplugin"), if (q.isBlank()) "https://yandex.ru/pogoda/" else "https://yandex.ru/pogoda/search?request=${enc(q)}")
            "google_wallet" -> payment(id, "Google Кошелёк", "com.google.android.apps.walletnfcrel")
            "koshelek" -> payment(id, "Кошелёк", "ru.cardsmobile.mw3")
            "mir_pay" -> payment(id, "Mir Pay", "ru.nspk.mirpay")
            "sbp" -> payment(id, "СБПэй", "ru.nspk.sbpay")
            else -> null
        }
    }

    private fun payment(id: String, label: String, pkg: String) = AppLaunch(id, label, AppIntegrations.PAYMENTS, LaunchKind.PACKAGE, null,
        listOf(pkg), store(pkg), payment = true,
        note = "Платёжное приложение только открывается. Оплату и переводы пользователь делает сам; ты их не начинаешь и не подтверждаешь.")

    /** Установленная программа по пакету — только запуск с главного экрана. */
    fun launcherApp(pkg: String, label: String): AppLaunch = AppLaunch("package:$pkg", label.ifBlank { pkg }, AppIntegrations.APPS,
        LaunchKind.PACKAGE, null, listOf(pkg), store(pkg), payment = pkg in paymentPackages)

    /** Дата и время из ISO: 2026-10-01T15:00, с зоной или без, или просто дата (9:00). */
    fun time(raw: String, zone: ZoneId): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        runCatching { return OffsetDateTime.parse(text).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(text.replace(' ', 'T')).atZone(zone).toInstant().toEpochMilli() }
        runCatching { return LocalDate.parse(text).atTime(9, 0).atZone(zone).toInstant().toEpochMilli() }
        return null
    }

    /** Блок для ответа: приложение покажет кнопку «Открыть …». */
    fun block(request: AppRequest): String = "```app\n${request.toJson()}\n```"
}

/** Ключи CalendarContract (строки, чтобы каталог проверялся без Android). */
object CalendarExtras {
    const val TITLE = "title"
    const val DESCRIPTION = "description"
    const val LOCATION = "eventLocation"
    const val BEGIN = "beginTime"
    const val END = "endTime"
}

/** Запуск приложений: проверка установки, намерения, запасной сайт. */
object AppLauncher {
    fun isInstalled(context: Context, pkg: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(pkg, 0); true
    }.getOrDefault(false)

    fun installedPackage(context: Context, launch: AppLaunch): String? = launch.packages.firstOrNull { isInstalled(context, it) }

    /** Намерение для самого приложения (если оно установлено) или для любого подходящего. */
    fun appIntent(context: Context, launch: AppLaunch): Intent? {
        val pkg = installedPackage(context, launch)
        val intent = when (launch.kind) {
            LaunchKind.PACKAGE -> pkg?.let { context.packageManager.getLaunchIntentForPackage(it) } ?: return null
            LaunchKind.VIEW -> Intent(Intent.ACTION_VIEW, Uri.parse(launch.uri ?: return null)).apply { if (pkg != null) setPackage(pkg) }
            LaunchKind.SENDTO -> Intent(Intent.ACTION_SENDTO, Uri.parse(launch.uri ?: "mailto:")).apply { if (pkg != null) setPackage(pkg) }
            LaunchKind.CALENDAR_INSERT -> Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
                if (pkg != null) setPackage(pkg)
                launch.extras.forEach { (key, value) ->
                    if (key == CalendarExtras.BEGIN || key == CalendarExtras.END) value.toLongOrNull()?.let { putExtra(key, it) }
                    else putExtra(key, value)
                }
            }
        }
        // Для VIEW без установленного приложения намерение откроет сайт — это запасной путь, не приложение.
        if (pkg == null && launch.kind == LaunchKind.VIEW) return null
        return intent.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    /** Есть чем открыть в самом приложении (а не на сайте). */
    fun canOpenApp(context: Context, launch: AppLaunch): Boolean = appIntent(context, launch) != null

    /** Открыть: приложение, а если его нет — сайт. Вызывается только по нажатию пользователя (или по настройке «сразу»). */
    fun open(context: Context, launch: AppLaunch): Boolean {
        val candidates = listOfNotNull(appIntent(context, launch), launch.webFallback?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) })
        for (intent in candidates) {
            try {
                if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }

    /** Приложения главного экрана: название и пакет. */
    fun launcherApps(context: Context): List<Pair<String, String>> = runCatching {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(main, PackageManager.MATCH_ALL).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != context.packageName }.distinctBy { it.second }
    }.getOrDefault(emptyList())

    /** Установленная программа по названию: точное совпадение, затем начало, затем вхождение. */
    fun findLauncherApp(apps: List<Pair<String, String>>, name: String): Pair<String, String>? {
        val wanted = AppCatalog.normalize(name)
        if (wanted.length < 2) return null
        val normalized = apps.map { it to AppCatalog.normalize(it.first) }
        return normalized.firstOrNull { it.second == wanted }?.first
            ?: normalized.firstOrNull { it.second.startsWith(wanted) }?.first
            ?: normalized.filter { it.second.contains(wanted) }.minByOrNull { it.second.length }?.first
    }

    /** Действие из блока ```app (или из аргументов инструмента): каталог, затем программа по пакету. */
    fun launchFor(request: AppRequest): AppLaunch? =
        AppCatalog.resolve(request) ?: request.packageName.takeIf { it.isNotBlank() }?.let { AppCatalog.launcherApp(it, request.label) }

    /** Инструмент open_app. */
    suspend fun execute(context: Context?, call: ToolCallRequest): ToolCallResult {
        fun reply(text: String) = ToolCallResult(call.id, call.name, text)
        var request = AppRequest.from(call.parsedArguments)
        if (request.app.isBlank()) return reply("Не передано, какое приложение открыть.")
        var launch = AppCatalog.resolve(request)
        if (launch == null) {
            if (context == null) return reply("Открыть приложение сейчас нельзя.")
            if (!AppIntegrations.isEnabled(context, AppIntegrations.APPS)) return reply("Открытие приложений выключено в Настройки → Интеграции. Скажи об этом пользователю.")
            val found = withContext(Dispatchers.IO) { findLauncherApp(launcherApps(context), request.app) }
                ?: return reply("Приложение «${request.app}» на телефоне не найдено. Скажи об этом и предложи установить его из магазина приложений.")
            request = request.copy(packageName = found.second, label = found.first)
            launch = AppCatalog.launcherApp(found.second, found.first)
        }
        if (!AppIntegrations.isEnabled(context, launch.integration)) {
            val name = AppIntegrations.services.firstOrNull { it.id == launch.integration }?.nameRU ?: launch.integration
            return reply("Интеграция «$name» выключена в Настройки → Интеграции. Скажи об этом пользователю.")
        }
        if (launch.payment && ParentalGuard.enabled) {
            return reply("Родительский контроль не разрешает открывать платёжные приложения. Коротко скажи об этом.")
        }
        val installed = context?.let { canOpenAppSafely(it, launch) } ?: false
        val block = AppCatalog.block(request)
        MediaAnswerBlocks.register(call.id, listOf(MediaAnswerBlocks.Item(request.toJson().toString(), block)))
        var opened = false
        // Кошельки и банки сразу не открываются никогда — только по нажатию пользователя.
        if (context != null && !launch.payment && AppIntegrations.opensImmediately(context)) {
            opened = withContext(Dispatchers.Main) { open(context, launch) }
        }
        val parts = mutableListOf<String>()
        parts += if (opened) "«${launch.label}» уже открыто на телефоне (в настройках включено «Открывать сразу»)."
        else "Готова кнопка «Открыть ${launch.label}» — пользователь нажмёт её сам."
        if (!installed) parts += "Приложение не установлено: кнопка откроет ${if (launch.payment || launch.kind == LaunchKind.PACKAGE) "страницу приложения или сайт" else "сайт"}."
        if (launch.note.isNotEmpty()) parts += launch.note
        if (launch.payment) parts += "Никогда не начинай оплату или перевод и не проси реквизиты, коды и пароли."
        parts += "Вставь в ответ ровно этот блок, без изменений, и одной фразой скажи, что откроется:\n$block"
        return reply(parts.joinToString(" "))
    }

    private suspend fun canOpenAppSafely(context: Context, launch: AppLaunch): Boolean =
        withContext(Dispatchers.IO) { runCatching { canOpenApp(context, launch) }.getOrDefault(false) }
}
