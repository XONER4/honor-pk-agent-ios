package com.honerai.app.extras.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.view.Display
import com.honerai.app.device.DeviceInfo
import java.util.Locale
import kotlin.math.roundToInt

/** Тип подключения к сети. */
enum class NetworkKind { WIFI, CELLULAR, ETHERNET, VPN, OTHER, OFFLINE }

/** Снимок состояния телефона. null — значение недоступно на этой версии Android. */
data class HealthSnapshot(
    val model: String,
    val androidRelease: String,
    val sdk: Int,
    val securityPatch: String?,
    val batteryPercent: Int?,
    /** Константа BatteryManager.BATTERY_HEALTH_*. */
    val batteryHealth: Int?,
    val batteryTemperatureC: Double?,
    val charging: Boolean?,
    /** Константа BatteryManager.BATTERY_PLUGGED_*; 0 — от батареи. */
    val plugged: Int?,
    val batteryVoltageMv: Int?,
    val ramTotalBytes: Long,
    val ramAvailableBytes: Long,
    val lowMemory: Boolean,
    val storageTotalBytes: Long,
    val storageFreeBytes: Long,
    val cpuCores: Int,
    val abis: List<String>,
    val soc: String?,
    val uptimeMs: Long,
    val network: NetworkKind,
    val refreshRateHz: Float?,
    /** PowerManager.THERMAL_STATUS_* (Android 10+). */
    val thermalStatus: Int?,
    val batterySaver: Boolean?,
)

/** Текст для нейросети и короткая строка для карточки «Состояние телефона». Без Android. */
object SystemHealthFormat {
    private val russian = Locale("ru", "RU")

    /** «3,1 ГБ», «512 МБ». */
    fun bytes(value: Long): String {
        if (value <= 0) return "0 МБ"
        val gb = value / 1_073_741_824.0
        return if (gb >= 1) String.format(russian, "%.1f ГБ", gb) else "${(value / 1_048_576.0).roundToInt()} МБ"
    }

    /** «3 дн. 4 ч 12 мин». */
    fun uptime(ms: Long): String {
        val minutes = (ms / 60_000).coerceAtLeast(0)
        val days = minutes / (60 * 24)
        val hours = (minutes / 60) % 24
        val mins = minutes % 60
        return listOfNotNull(if (days > 0) "$days дн." else null, if (hours > 0) "$hours ч" else null, "$mins мин").joinToString(" ")
    }

    fun batteryHealth(code: Int?): String? = when (code) {
        2 -> "хорошее"          // BATTERY_HEALTH_GOOD
        3 -> "перегрев"         // OVERHEAT
        4 -> "изношена (dead)"  // DEAD
        5 -> "перенапряжение"   // OVER_VOLTAGE
        6 -> "сбой"             // UNSPECIFIED_FAILURE
        7 -> "переохлаждение"   // COLD
        1, null -> null         // UNKNOWN
        else -> null
    }

    fun plugged(code: Int?): String? = when (code) {
        1 -> "от сети"          // BATTERY_PLUGGED_AC
        2 -> "по USB"
        4 -> "беспроводная"
        8 -> "от док-станции"
        else -> null
    }

    fun thermal(status: Int?): String? = when (status) {
        0 -> "в норме"
        1 -> "лёгкий нагрев"
        2 -> "умеренный нагрев"
        3 -> "сильный нагрев — система снижает производительность"
        4 -> "критический нагрев"
        5 -> "аварийный перегрев"
        6 -> "отключение из-за перегрева"
        else -> null
    }

    fun network(kind: NetworkKind): String = when (kind) {
        NetworkKind.WIFI -> "Wi-Fi"
        NetworkKind.CELLULAR -> "мобильный интернет"
        NetworkKind.ETHERNET -> "Ethernet"
        NetworkKind.VPN -> "VPN"
        NetworkKind.OTHER -> "другое подключение"
        NetworkKind.OFFLINE -> "нет подключения"
    }

    private fun percent(part: Long, total: Long): Int = if (total <= 0) 0 else ((part * 100.0) / total).roundToInt()

    /** Что стоит показать пользователю в первую очередь. */
    fun warnings(s: HealthSnapshot): List<String> = buildList {
        s.batteryPercent?.let { if (it <= 15 && s.charging != true) add("Заряд низкий: $it%.") }
        s.batteryTemperatureC?.let { if (it >= 42) add("Батарея горячая: ${String.format(russian, "%.1f", it)} °C.") }
        if (s.batteryHealth in setOf(3, 4, 5, 6, 7)) add("Состояние батареи: ${batteryHealth(s.batteryHealth)}.")
        if (s.storageTotalBytes > 0 && percent(s.storageFreeBytes, s.storageTotalBytes) < 10) add("Свободной памяти меньше 10%: ${bytes(s.storageFreeBytes)}.")
        if (s.lowMemory || (s.ramTotalBytes > 0 && percent(s.ramAvailableBytes, s.ramTotalBytes) < 10)) add("Мало свободной оперативной памяти.")
        s.thermalStatus?.let { if (it >= 2) add("Телефон нагрет: ${thermal(it)}.") }
        if (s.network == NetworkKind.OFFLINE) add("Нет подключения к интернету.")
    }

    /** Подробный отчёт для нейросети. */
    fun report(s: HealthSnapshot): String {
        val lines = mutableListOf<String>()
        lines += "Устройство: ${s.model}"
        lines += "Android ${s.androidRelease} (API ${s.sdk})" + (s.securityPatch?.takeIf { it.isNotBlank() }?.let { ", патч безопасности от $it" } ?: "")
        val battery = mutableListOf<String>()
        s.batteryPercent?.let { battery += "$it%" }
        when (s.charging) {
            true -> battery += "заряжается" + (plugged(s.plugged)?.let { " ($it)" } ?: "")
            false -> battery += "не заряжается"
            null -> Unit
        }
        batteryHealth(s.batteryHealth)?.let { battery += "состояние: $it" }
        s.batteryTemperatureC?.let { battery += "температура ${String.format(russian, "%.1f", it)} °C" }
        s.batteryVoltageMv?.takeIf { it > 0 }?.let { battery += "напряжение ${String.format(russian, "%.2f", it / 1000.0)} В" }
        if (battery.isNotEmpty()) lines += "Батарея: " + battery.joinToString(", ")
        s.batterySaver?.let { lines += "Энергосбережение: ${if (it) "включено" else "выключено"}" }
        if (s.ramTotalBytes > 0) {
            lines += "Оперативная память: свободно ${bytes(s.ramAvailableBytes)} из ${bytes(s.ramTotalBytes)} (${percent(s.ramAvailableBytes, s.ramTotalBytes)}%)" +
                if (s.lowMemory) ", система сообщает о нехватке памяти" else ""
        }
        if (s.storageTotalBytes > 0) {
            lines += "Память телефона: свободно ${bytes(s.storageFreeBytes)} из ${bytes(s.storageTotalBytes)} (${percent(s.storageFreeBytes, s.storageTotalBytes)}%)"
        }
        lines += "Процессор: ${s.cpuCores} ядер" + (s.soc?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: "") +
            (if (s.abis.isNotEmpty()) ", архитектура ${s.abis.joinToString(", ")}" else "")
        lines += "Работает без перезагрузки: ${uptime(s.uptimeMs)}"
        lines += "Сеть: ${network(s.network)}"
        s.refreshRateHz?.let { lines += "Частота экрана: ${it.roundToInt()} Гц" }
        thermal(s.thermalStatus)?.let { lines += "Температурный режим: $it" }
        val warnings = warnings(s)
        return "Состояние телефона:\n" + lines.joinToString("\n") { "• $it" } +
            if (warnings.isEmpty()) "\nОтклонений не найдено." else "\nОбрати внимание:\n" + warnings.joinToString("\n") { "• $it" }
    }

    /** Короткая строка для карточки в чате. */
    fun summary(s: HealthSnapshot): String = listOfNotNull(
        s.batteryPercent?.let { "🔋 $it%" + if (s.charging == true) " ⚡" else "" },
        if (s.ramTotalBytes > 0) "ОЗУ ${bytes(s.ramAvailableBytes)} своб." else null,
        if (s.storageTotalBytes > 0) "память ${bytes(s.storageFreeBytes)} своб." else null,
        network(s.network),
    ).joinToString(" · ")
}

/** Сбор состояния на телефоне. Только открытые API — без особых разрешений. */
object SystemHealthProbe {
    @Suppress("DEPRECATION")
    fun snapshot(context: Context): HealthSnapshot {
        val app = context.applicationContext
        val battery = runCatching { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val memory = ActivityManager.MemoryInfo()
        runCatching { (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory) }
        val stat = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val display = runCatching { (app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY) }.getOrNull()
        runCatching { DeviceInfo.init(app) }
        return HealthSnapshot(
            model = runCatching { DeviceInfo.modelName }.getOrDefault("${Build.MANUFACTURER} ${Build.MODEL}"),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdk = Build.VERSION.SDK_INT,
            securityPatch = runCatching { Build.VERSION.SECURITY_PATCH }.getOrNull(),
            batteryPercent = if (level >= 0 && scale > 0) (level * 100 / scale) else null,
            batteryHealth = battery?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)?.takeIf { it > 0 },
            batteryTemperatureC = if (temperature != Int.MIN_VALUE && temperature > -500) temperature / 10.0 else null,
            charging = if (status < 0) null else status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.takeIf { it >= 0 },
            batteryVoltageMv = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 },
            ramTotalBytes = memory.totalMem,
            ramAvailableBytes = memory.availMem,
            lowMemory = memory.lowMemory,
            storageTotalBytes = stat?.totalBytes ?: 0L,
            storageFreeBytes = stat?.availableBytes ?: 0L,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            abis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
            soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL).filter { !it.isNullOrBlank() && it != Build.UNKNOWN }.joinToString(" ").ifBlank { null }
            } else Build.HARDWARE?.takeIf { it.isNotBlank() },
            uptimeMs = SystemClock.elapsedRealtime(),
            network = network(app),
            refreshRateHz = display?.refreshRate,
            thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching { power?.currentThermalStatus }.getOrNull() else null,
            batterySaver = runCatching { power?.isPowerSaveMode }.getOrNull(),
        )
    }

    fun network(context: Context): NetworkKind = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork ?: return@runCatching NetworkKind.OFFLINE)
            ?: return@runCatching NetworkKind.OFFLINE
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkKind.VPN
            else -> NetworkKind.OTHER
        }
    }.getOrDefault(NetworkKind.OTHER)
}
