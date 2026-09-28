package com.honerai.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.honerai.app.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Автоматическая резервная копия в выбранную папку (порт BackupService из BackupSync.swift).
 * Папка выбирается через системный выбор папки (Google Drive, «Загрузки», карта памяти);
 * доступ к ней сохраняется навсегда (persistable permission). Копия делается при сворачивании
 * приложения, не чаще раза в 5 минут.
 */
object BackupService {
    const val FILE_NAME = "Honer AI — резервная копия.json"
    private const val PREFS = "honer.backup"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var appContext: Context? = null

    private val _autoBackup = MutableStateFlow(true)
    private val _folderName = MutableStateFlow<String?>(null)
    private val _lastBackupAt = MutableStateFlow<Long?>(null)
    private val _lastError = MutableStateFlow<String?>(null)
    private val _isWorking = MutableStateFlow(false)
    val autoBackup: StateFlow<Boolean> = _autoBackup.asStateFlow()
    val folderName: StateFlow<String?> = _folderName.asStateFlow()
    val lastBackupAt: StateFlow<Long?> = _lastBackupAt.asStateFlow()
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    val isWorking: StateFlow<Boolean> = _isWorking.asStateFlow()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Подключить хранилище и копию при сворачивании. Повторные вызовы ничего не делают. */
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val p = prefs(app)
        _autoBackup.value = p.getBoolean("autoBackup", true)
        _folderName.value = p.getString("folderName", null)
        _lastBackupAt.value = p.getLong("lastBackupAt", 0L).takeIf { it > 0 }
        val attach = Runnable {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) = backupIfNeeded()
            })
        }
        if (Looper.myLooper() == Looper.getMainLooper()) attach.run() else Handler(Looper.getMainLooper()).post(attach)
    }

    private fun text(ru: String, en: String): String =
        appContext?.let { AppContainer.get(it).settings.text(ru, en) } ?: ru

    fun setAutoBackup(value: Boolean) {
        _autoBackup.value = value
        appContext?.let { prefs(it).edit().putBoolean("autoBackup", value).apply() }
    }

    private fun folderUri(context: Context): Uri? = prefs(context).getString("folderUri", null)?.let(Uri::parse)

    /** Запомнить папку для копий (доступ сохраняется после перезапуска). */
    fun setFolder(context: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            // Старую папку отпускаем, чтобы не копить разрешения.
            folderUri(context)?.takeIf { it != uri }?.let { old -> runCatching { context.contentResolver.releasePersistableUriPermission(old, flags) } }
            context.contentResolver.takePersistableUriPermission(uri, flags)
            val name = DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment?.substringAfterLast(':') ?: "…"
            prefs(context).edit().putString("folderUri", uri.toString()).putString("folderName", name).apply()
            _folderName.value = name
            _lastError.value = null
        }.onFailure {
            _lastError.value = text("Не удалось запомнить папку: ", "Could not remember the folder: ") + (it.localizedMessage ?: it.toString())
        }
    }

    /** Сделать копию сейчас. */
    suspend fun backupNow(context: Context) {
        if (_isWorking.value) return
        val folder = folderUri(context)
        if (folder == null) {
            _lastError.value = text("Выберите папку для резервных копий.", "Choose a folder for backups.")
            return
        }
        _isWorking.value = true
        try {
            val store = AppContainer.get(context).store
            val exported = store.exportData().getOrThrow()
            withContext(Dispatchers.IO) {
                val tree = DocumentFile.fromTreeUri(context, folder) ?: error(text("Папка недоступна", "Folder unavailable"))
                if (!tree.canWrite()) error(text("Нет доступа к папке — выберите её заново.", "No access to the folder — choose it again."))
                val target = tree.findFile(FILE_NAME) ?: tree.createFile("application/json", FILE_NAME)
                    ?: error(text("Не удалось создать файл", "Could not create the file"))
                // "wt" — перезаписать целиком (без «хвоста» от прошлой, более длинной копии).
                context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                    exported.inputStream().use { it.copyTo(out) }
                } ?: error(text("Не удалось открыть файл", "Could not open the file"))
                runCatching { exported.delete() }
            }
            val now = System.currentTimeMillis()
            _lastBackupAt.value = now
            prefs(context).edit().putLong("lastBackupAt", now).apply()
            _lastError.value = null
        } catch (e: Exception) {
            _lastError.value = text("Копия не сохранилась: ", "Backup failed: ") + (e.localizedMessage ?: e.toString())
        } finally {
            _isWorking.value = false
        }
    }

    /** Копия при сворачивании приложения — не чаще раза в 5 минут. */
    fun backupIfNeeded() {
        val context = appContext ?: return
        if (!_autoBackup.value || folderUri(context) == null) return
        val last = _lastBackupAt.value
        if (last != null && System.currentTimeMillis() - last < 300_000) return
        scope.launch { backupNow(context) }
    }
}
