package com.honerai.app.device

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.honerai.app.AppContainer
import com.honerai.app.BuildConfig
import com.honerai.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

/** Этап обновления — для экрана настроек. */
sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object UpToDate : UpdateState()
    data object Downloading : UpdateState()
    data object Installing : UpdateState()
    /** Система ждёт подтверждения установки от пользователя. */
    data object WaitingForUser : UpdateState()
    data object Installed : UpdateState()
    data class Failed(val message: String) : UpdateState()
}

/**
 * Автообновление из выпусков GitHub (BuildConfig.UPDATE_REPOSITORY, теги «android-v…» с .apk).
 * Установка — PackageInstaller-сессией. Первое обновление система всегда показывает пользователю;
 * если приложение само стало «установщиком» (после первой своей установки), на Android 12+
 * следующие обновления ставятся без вопросов (USER_ACTION_NOT_REQUIRED).
 */
object UpdateManager {
    const val ACTION_INSTALL_RESULT = "com.honerai.app.action.UPDATE_INSTALL_RESULT"
    internal const val NOTIFICATION_AVAILABLE = 7_001
    internal const val NOTIFICATION_INSTALLED = 7_002
    internal const val NOTIFICATION_CONFIRM = 7_003
    private const val PREFS = "honer.update"
    private const val KEY_LAST_CHECK = "lastCheck"
    private const val KEY_NOTIFIED = "notifiedVersion"

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    /** Найденное обновление (или null). */
    val available: StateFlow<UpdateInfo?> = _available.asStateFlow()
    private val _progress = MutableStateFlow<Float?>(null)
    /** Ход загрузки 0…1 или null. */
    val progress: StateFlow<Float?> = _progress.asStateFlow()
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val _needsInstallPermission = MutableStateFlow(false)
    /** Нужно разрешение «Установка неизвестных приложений» — экран открывает [installPermissionIntent]. */
    val needsInstallPermission: StateFlow<Boolean> = _needsInstallPermission.asStateFlow()

    private val mutex = Mutex()
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE
    val currentVersionName: String get() = BuildConfig.VERSION_NAME.removeSuffix("-debug")

    /** Проверить выпуски GitHub; null — обновления нет или сеть недоступна. */
    suspend fun checkNow(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val busy = _state.value == UpdateState.Downloading || _state.value == UpdateState.Installing
        if (!busy) _state.value = UpdateState.Checking
        val url = "https://api.github.com/repos/${BuildConfig.UPDATE_REPOSITORY}/releases?per_page=15"
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "HonerAI-Android/${BuildConfig.VERSION_NAME}")
            .build()
        val body = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        } catch (_: Exception) {
            null
        }
        prefs(app).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        if (body == null) {
            if (!busy) _state.value = UpdateState.Failed(text(app, "Не удалось проверить обновления. Проверьте интернет.", "Could not check for updates. Check your connection."))
            return@withContext _available.value
        }
        val latest = UpdateReleases.pickLatest(body)
        val newer = latest?.takeIf { UpdateReleases.isNewer(it, currentVersionCode, currentVersionName) }
        _available.value = newer
        if (!busy) _state.value = if (newer == null) UpdateState.UpToDate else UpdateState.Idle
        newer
    }

    /** Скачать и установить; приложение перезапустится после обновления. */
    suspend fun installNow(context: Context) {
        installInternal(context.applicationContext)
    }

    /** Время последней проверки (мс) — чтобы не проверять при каждом запуске. */
    internal fun lastCheck(context: Context): Long = prefs(context).getLong(KEY_LAST_CHECK, 0L)

    /** true — установка пошла (дальше решает система и [onInstallResult]). */
    internal suspend fun installInternal(app: Context): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val info = _available.value ?: checkNow(app) ?: run {
                _state.value = UpdateState.UpToDate
                return@withContext false
            }
            if (!refreshInstallPermission(app)) {
                _state.value = UpdateState.Failed(text(app,
                    "Разрешите Honer AI устанавливать обновления: Настройки → Приложения → Honer AI → Установка неизвестных приложений.",
                    "Allow Honer AI to install updates: Settings → Apps → Honer AI → Install unknown apps."))
                return@withContext false
            }
            val apk = try {
                download(app, info)
            } catch (e: Exception) {
                _progress.value = null
                if (e is kotlinx.coroutines.CancellationException) { _state.value = UpdateState.Idle; throw e }
                _state.value = UpdateState.Failed(e.message ?: text(app, "Не удалось скачать обновление.", "Could not download the update."))
                return@withContext false
            }
            try {
                _state.value = UpdateState.Installing
                commit(app, apk)
                true
            } catch (e: Exception) {
                _progress.value = null
                _state.value = UpdateState.Failed(text(app, "Не удалось начать установку: ", "Could not start the installation: ") + (e.message ?: e.javaClass.simpleName))
                false
            }
        }
    }

    // MARK: Загрузка

    private suspend fun download(app: Context, info: UpdateInfo): File {
        val directory = File(app.cacheDir, "updates").apply { mkdirs() }
        val name = "honer-" + (if (info.versionCode > 0) info.versionCode.toString() else info.versionName.replace(Regex("[^0-9.]"), "")) + ".apk"
        val target = File(directory, name)
        directory.listFiles()?.filter { it.name != name }?.forEach { it.delete() }
        if (target.exists() && info.sizeBytes > 0 && target.length() == info.sizeBytes && verify(app, target) == null) {
            _progress.value = 1f
            return target
        }
        _state.value = UpdateState.Downloading
        _progress.value = 0f
        val partial = File(directory, "$name.part")
        val request = Request.Builder().url(info.apkUrl).header("User-Agent", "HonerAI-Android/${BuildConfig.VERSION_NAME}").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException(text(app, "Сервер обновлений ответил ошибкой ${response.code}.", "The update server returned error ${response.code}."))
            val body = response.body ?: throw IllegalStateException(text(app, "Пустой ответ сервера обновлений.", "Empty response from the update server."))
            val total = if (info.sizeBytes > 0) info.sizeBytes else body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var written = 0L
                    var lastReported = 0f
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val fraction = (written.toFloat() / total).coerceIn(0f, 1f)
                            if (fraction - lastReported >= 0.01f) { _progress.value = fraction; lastReported = fraction }
                        }
                    }
                    output.fd.sync()
                }
            }
        }
        if (info.sizeBytes > 0 && partial.length() != info.sizeBytes) {
            partial.delete()
            throw IllegalStateException(text(app, "Обновление скачалось не полностью. Попробуйте ещё раз.", "The update was not fully downloaded. Try again."))
        }
        target.delete()
        if (!partial.renameTo(target)) throw IllegalStateException(text(app, "Не удалось сохранить обновление.", "Could not save the update."))
        verify(app, target)?.let { problem ->
            target.delete()
            throw IllegalStateException(problem)
        }
        _progress.value = 1f
        return target
    }

    /** Проверка APK до установки: тот же пакет и более новая сборка. null — всё в порядке. */
    private fun verify(app: Context, apk: File): String? {
        val archive: PackageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                app.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            }
        } catch (_: Exception) {
            null
        } ?: return text(app, "Файл обновления повреждён.", "The update file is damaged.")
        if (archive.packageName != app.packageName) {
            return text(app, "Это обновление предназначено для другой сборки приложения (${archive.packageName}).",
                "This update is for a different build of the app (${archive.packageName}).")
        }
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode else {
            @Suppress("DEPRECATION")
            archive.versionCode.toLong()
        }
        if (code <= currentVersionCode) {
            return text(app, "Скачанная версия не новее установленной.", "The downloaded version is not newer than the installed one.")
        }
        return null
    }

    // MARK: Установка

    /** Разрешение «Установка неизвестных приложений» (Android 8+). */
    fun refreshInstallPermission(context: Context): Boolean {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()
        _needsInstallPermission.value = !allowed
        return allowed
    }

    /** Экран системы, где пользователь разрешает Honer AI ставить обновления. */
    fun installPermissionIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Можно ли поставить обновление без вопроса пользователю: Android 12+, есть разрешение на установку,
     * и установщик приложения — оно само (так бывает после первого обновления изнутри приложения).
     */
    fun canInstallSilently(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (!context.packageManager.canRequestPackageInstalls()) return false
        val installer = try {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } catch (_: Exception) {
            null
        }
        return installer == context.packageName
    }

    private fun commit(app: Context, apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER)
        }
        val sessionId = installer.createSession(params)
        var committed = false
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("honer-update.apk", 0, apk.length()).use { output ->
                        input.copyTo(output, 256 * 1024)
                        session.fsync(output)
                    }
                }
                val intent = Intent(app, UpdateInstallReceiver::class.java).setAction(ACTION_INSTALL_RESULT).setPackage(app.packageName)
                // PackageInstaller дописывает в Intent итог — поэтому PendingIntent изменяемый (Intent явный).
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(app, sessionId, intent, flags)
                session.commit(pending.intentSender)
                committed = true
            }
        } finally {
            if (!committed) runCatching { installer.abandonSession(sessionId) }
        }
    }

    /** Итог от PackageInstaller (вызывает [UpdateInstallReceiver]). */
    internal fun onInstallResult(context: Context, intent: Intent) {
        val app = context.applicationContext
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                _state.value = UpdateState.WaitingForUser
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                var shown = false
                if (HonerNotifications.isAppInForeground()) {
                    shown = runCatching { app.startActivity(confirm); true }.getOrDefault(false)
                }
                // Из фона Android 10+ не даёт открыть окно — просим подтвердить через уведомление.
                if (!shown) postConfirmNotification(app, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                _progress.value = null
                _state.value = UpdateState.Installed
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                _progress.value = null
                _state.value = UpdateState.Idle
            }
            else -> {
                _progress.value = null
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                val reason = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> text(app, "Установленная версия подписана другим ключом. Удалите её и установите заново.",
                        "The installed version is signed with a different key. Uninstall it and install again.")
                    PackageInstaller.STATUS_FAILURE_STORAGE -> text(app, "Недостаточно места для обновления.", "Not enough storage for the update.")
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> text(app, "Обновление несовместимо с этим телефоном.", "The update is incompatible with this phone.")
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> text(app, "Установку заблокировала система.", "The system blocked the installation.")
                    else -> text(app, "Не удалось установить обновление.", "Could not install the update.")
                }
                _state.value = UpdateState.Failed(if (detail.isNotBlank()) "$reason ($detail)" else reason)
            }
        }
    }

    private fun postConfirmNotification(app: Context, confirm: Intent) {
        val pending = PendingIntent.getActivity(app, NOTIFICATION_CONFIRM, confirm,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val version = _available.value?.versionName.orEmpty()
        val builder = NotificationCompat.Builder(app, HonerNotifications.CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(HonerNotifications.ACCENT_COLOR)
            .setContentTitle(text(app, "Обновление Honer AI $version готово", "Honer AI $version update is ready").replace("  ", " "))
            .setContentText(text(app, "Нажмите, чтобы установить.", "Tap to install."))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(pending)
        HonerNotifications.post(app, NOTIFICATION_CONFIRM, builder)
    }

    /** Уведомление «доступно обновление» — один раз на версию; нажатие открывает приложение. */
    internal fun notifyAvailable(context: Context, info: UpdateInfo) {
        val app = context.applicationContext
        val key = info.versionCode.takeIf { it > 0 }?.toString() ?: info.versionName
        val prefs = prefs(app)
        if (prefs.getString(KEY_NOTIFIED, null) == key) return
        val notes = info.notes.lineSequence().map { it.trim().trimStart('-', '*', '•', '#', ' ') }.firstOrNull { it.isNotBlank() }.orEmpty()
        val builder = NotificationCompat.Builder(app, HonerNotifications.CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(HonerNotifications.ACCENT_COLOR)
            .setContentTitle(text(app, "Доступно обновление Honer AI ${info.versionName}", "Honer AI ${info.versionName} is available"))
            .setContentText(notes.ifEmpty { text(app, "Откройте приложение, чтобы обновить.", "Open the app to update.") })
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                (if (notes.isNotEmpty()) info.notes.take(600) + "\n\n" else "") + text(app, "Откройте приложение, чтобы обновить.", "Open the app to update.")))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(HonerNotifications.openAppIntent(app))
        HonerNotifications.post(app, NOTIFICATION_AVAILABLE, builder)
        prefs.edit().putString(KEY_NOTIFIED, key).apply()
    }

    /** После обновления: сбросить найденное обновление и удалить скачанный APK. */
    internal fun onPackageReplaced(context: Context) {
        _available.value = null
        _progress.value = null
        _state.value = UpdateState.Idle
        File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun text(context: Context, russian: String, english: String): String =
        runCatching { AppContainer.get(context).settings.text(russian, english) }.getOrDefault(russian)
}
