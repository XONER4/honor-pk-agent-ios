package com.honerai.app.extras.device

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.ContactsContract
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.honerai.app.BuildConfig
import com.honerai.app.device.DeviceInfo
import java.text.Collator
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Оформление данных телефона для нейросети. Без Android. */
object PhoneDataFormat {
    const val MAX_APPS = 400

    fun apps(labels: List<String>): String {
        if (labels.isEmpty()) return "Не удалось получить список приложений."
        val shown = labels.take(MAX_APPS)
        val more = if (labels.size > shown.size) "\n…и ещё ${labels.size - shown.size}." else ""
        return "Приложений с иконкой на рабочем столе: ${labels.size}.\n" + shown.joinToString(", ") + more
    }

    /** [entries] — (название, миллисекунды на экране). */
    fun usage(entries: List<Pair<String, Long>>, top: Int = 15): String {
        val used = entries.filter { it.second >= 60_000 }.sortedByDescending { it.second }
        if (used.isEmpty()) return "Сегодня приложения почти не использовались (меньше минуты каждое)."
        val total = used.sumOf { it.second }
        val lines = used.take(top).map { (name, ms) -> "• $name — ${minutes(ms)}" }
        return "Время в приложениях сегодня (с полуночи), всего ${minutes(total)}:\n" + lines.joinToString("\n")
    }

    /** «1 ч 25 мин», «7 мин». */
    fun minutes(ms: Long): String {
        val total = (ms / 60_000).toInt()
        val h = total / 60
        val m = total % 60
        return if (h > 0) "$h ч $m мин" else "$m мин"
    }
}

/** Сбор данных для phone_data. Звонки и SMS не читаются вовсе (правила Google Play). */
object PhoneDataProbe {
    fun hasContactsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun contactsCount(context: Context): Int? {
        if (!hasContactsPermission(context)) return null
        return runCatching {
            context.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID), null, null, null)
                ?.use { it.count }
        }.getOrNull()
    }

    /** Приложения с иконкой в меню (нужен <queries> MAIN/LAUNCHER в манифесте). */
    fun launcherApps(context: Context): List<String> = runCatching {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val activities = pm.queryIntentActivities(intent, 0)
        val collator = Collator.getInstance(Locale("ru", "RU"))
        activities.distinctBy { it.activityInfo.packageName }
            .map { it.loadLabel(pm).toString().trim().ifEmpty { it.activityInfo.packageName } }
            .distinct()
            .sortedWith(collator)
    }.getOrDefault(emptyList())

    @Suppress("DEPRECATION")
    fun deviceInfo(context: Context): String {
        runCatching { DeviceInfo.init(context) }
        val metrics = DisplayMetrics()
        runCatching { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics) }
        val lines = mutableListOf<String>()
        lines += "Модель: ${runCatching { DeviceInfo.modelName }.getOrDefault(Build.MODEL)} (${Build.MANUFACTURER} ${Build.MODEL})"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { lines += "Имя устройства: $it" }
        }
        lines += "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        if (metrics.widthPixels > 0) {
            val inches = if (metrics.xdpi > 0 && metrics.ydpi > 0) {
                val w = metrics.widthPixels / metrics.xdpi
                val h = metrics.heightPixels / metrics.ydpi
                sqrt(w * w + h * h)
            } else 0f
            lines += "Экран: ${metrics.widthPixels}×${metrics.heightPixels}, ${metrics.densityDpi} dpi" +
                if (inches > 2f) String.format(Locale("ru", "RU"), ", около %.1f″", inches) else ""
        }
        lines += "Язык системы: ${Locale.getDefault().getDisplayName(Locale("ru", "RU"))}"
        lines += "Часовой пояс: ${TimeZone.getDefault().id}"
        lines += "Honer AI: ${BuildConfig.VERSION_NAME}"
        return "Сведения об устройстве:\n" + lines.joinToString("\n") { "• $it" }
    }

    /** Разрешён ли доступ к статистике использования (Настройки → Специальный доступ). */
    fun hasUsageAccess(context: Context): Boolean = runCatching {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** Экран системных настроек «Доступ к истории использования» (сразу на Honer AI, если система умеет). */
    fun usageAccessIntent(context: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) data = Uri.fromParts("package", context.packageName, null)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** (название, мс на экране) с полуночи. */
    fun usageToday(context: Context): List<Pair<String, Long>>? {
        if (!hasUsageAccess(context)) return null
        return runCatching {
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val stats = manager.queryAndAggregateUsageStats(start, System.currentTimeMillis())
            val pm = context.packageManager
            stats.values.filter { it.totalTimeInForeground > 0 }.map { stat ->
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(stat.packageName, 0)).toString() }
                    .getOrDefault(stat.packageName)
                label to stat.totalTimeInForeground
            }.groupBy({ it.first }, { it.second }).map { (name, times) -> name to times.sum() }
        }.getOrNull()
    }

    fun percent(part: Long, total: Long): Int = if (total <= 0) 0 else (part * 100.0 / total).roundToInt()
}
