package com.honerai.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.ZoneId

// «Управление данными» (порт DataSettingsPage + BackupSection) и «Архив чатов».

/** Уведомления разрешены системой (на Android 13+ ещё и разрешение POST_NOTIFICATIONS). */
internal fun notificationsAllowed(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

internal fun openNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
    }
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Пересчитывать значение при каждом возвращении в приложение (после системных настроек). */
@Composable
internal fun <T> rememberOnResume(compute: () -> T): T {
    var value by remember { mutableStateOf(compute()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) value = compute() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return value
}

@Composable
internal fun DataSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = appContainer()
    val settings = container.settings
    val store = container.store
    val colors = HonerTheme.colors
    val scope = rememberCoroutineScope()
    val conversations by store.conversations.collectAsState()
    val autoDeleteDays by settings.autoDeleteDays.collectAsState()
    val notificationsEnabled by settings.notificationsEnabled.collectAsState()
    val autoBackup by BackupService.autoBackup.collectAsState()
    val folderName by BackupService.folderName.collectAsState()
    val lastBackupAt by BackupService.lastBackupAt.collectAsState()
    val backupError by BackupService.lastError.collectAsState()
    val backupWorking by BackupService.isWorking.collectAsState()
    var status by remember { mutableStateOf<String?>(null) }
    var transferring by remember { mutableStateOf(false) }
    var transferJob by remember { mutableStateOf<Job?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    // После системного окна или настроек приложение возвращается — состояние пересчитывается.
    val notificationsBlocked = rememberOnResume { !notificationsAllowed(context) }
    fun t(ru: String, en: String) = settings.text(ru, en)

    DisposableEffect(Unit) { onDispose { transferJob?.cancel() } }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            BackupService.setFolder(context, uri)
            scope.launch { BackupService.backupNow(context) }
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        transferring = true
        status = null
        transferJob = scope.launch {
            val result = store.importData(uri)
            transferring = false
            status = result.fold({ t("История импортирована.", "History imported.") }, { it.localizedMessage ?: it.toString() })
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    SettingsPageScaffold(t("Управление данными", "Data management"), "settings.page.data", onBack) {
        item(key = "backup") {
            SettingsGroup(
                t("Синхронизация и резервная копия", "Sync and backup"),
                footer = t(
                    "Выберите папку, например в Google Drive или «Загрузках», — туда будут сохраняться все чаты, память, инструкции и настройки. После переустановки приложения нажмите на первом экране «Восстановить из резервной копии» и выберите этот файл.",
                    "Pick a folder (for example in Google Drive or Downloads) for chats, memory, instructions and settings. After reinstalling, tap “Restore from backup” on the first screen and choose that file.",
                ),
            ) {
                SettingsToggleRow(t("Автоматическая резервная копия", "Automatic backup"), autoBackup, { BackupService.setAutoBackup(it) }, tag = "backup.auto")
                SettingsDivider(16)
                SettingsRow(Icons.Outlined.Folder, t("Папка для копий", "Backup folder"), folderName ?: t("не выбрана", "not chosen"), tag = "backup.folder") {
                    runCatching { folderPicker.launch(null) }
                }
                SettingsDivider()
                SettingsButtonRow(
                    t("Сделать копию сейчас", "Back up now"), Icons.Outlined.CloudUpload, enabled = folderName != null && !backupWorking, tag = "backup.now",
                    trailing = if (backupWorking) ({ CircularProgressIndicator(Modifier.size(18.dp), color = colors.accent, strokeWidth = 2.dp) }) else null,
                ) { scope.launch { BackupService.backupNow(context) } }
                lastBackupAt?.let {
                    SettingsDivider(16)
                    SettingsValueRow(t("Последняя копия", "Last backup"), dateTimeText(settings.isEnglish, it), tag = "backup.last")
                }
                backupError?.let {
                    Text(it, color = WarningOrange, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
                }
            }
        }
        item(key = "transfer") {
            SettingsGroup(
                footer = t(
                    "Резервная копия в JSON содержит переписку, вложения и память Honer AI. Импорт добавляет сохранённые чаты и факты в историю.",
                    "The JSON backup contains conversations, attachments and Honer AI memory. Import adds saved conversations and memories.",
                ),
            ) {
                SettingsValueRow(t("Чатов", "Conversations"), "${conversations.size}", tag = "data.count")
                SettingsDivider(16)
                SettingsButtonRow(t("Экспортировать историю", "Export history"), Icons.Outlined.Upload, enabled = !transferring, tag = "data.export") {
                    transferring = true
                    status = null
                    transferJob = scope.launch {
                        val result = store.exportData()
                        transferring = false
                        result.onSuccess { file ->
                            runCatching {
                                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                                val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                context.startActivity(Intent.createChooser(send, t("Экспорт истории", "Export history")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }.onFailure { status = it.localizedMessage ?: it.toString() }
                        }.onFailure { status = it.localizedMessage ?: it.toString() }
                    }
                }
                SettingsDivider()
                SettingsButtonRow(t("Импортировать историю", "Import history"), Icons.Outlined.Download, enabled = !transferring, tag = "data.import") {
                    runCatching { importPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                }
            }
        }
        // Итог действия — рядом с кнопками, а не внизу длинной страницы.
        if (transferring) {
            item(key = "busy") {
                SettingsGroup {
                    Row(Modifier.fillMaxWidth().padding(16.dp).testTag("data.busy"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = colors.accent, strokeWidth = 2.dp)
                        Text(t("Обрабатываю архив…", "Processing backup…"), color = colors.foreground, fontSize = 16.sp)
                    }
                }
            }
        }
        status?.let { text ->
            item(key = "status") {
                SettingsGroup { Text(text, color = colors.secondary, fontSize = 15.sp, modifier = Modifier.padding(16.dp).testTag("data.status")) }
            }
        }
        item(key = "delete") {
            SettingsGroup {
                SettingsButtonRow(t("Удалить всю историю", "Delete all history"), Icons.Outlined.Delete, destructive = true, enabled = !transferring, tag = "data.delete") {
                    confirmDelete = true
                }
            }
        }
        item(key = "automation") {
            SettingsGroup(
                t("Автоматизация", "Automation"),
                footer = t(
                    "Автоудаление убирает чаты, в которых не было сообщений дольше выбранного срока. Закреплённые чаты сохраняются.",
                    "Auto-delete removes chats with no messages for longer than the selected period. Pinned chats are kept.",
                ),
            ) {
                SettingsMenuRow(
                    null, t("Автоудаление чатов", "Auto-delete chats"),
                    listOf(0 to t("Никогда", "Never"), 1 to t("Через 1 день", "After 1 day"), 7 to t("Через 7 дней", "After 7 days"),
                        30 to t("Через 30 дней", "After 30 days"), 90 to t("Через 90 дней", "After 90 days")),
                    autoDeleteDays, { days -> settings.setAutoDeleteDays(days); if (days > 0) store.purgeOldChats(days) }, tag = "data.autodelete",
                )
                SettingsDivider(16)
                SettingsToggleRow(
                    t("Уведомлять о готовом ответе", "Notify when an answer is ready"), notificationsEnabled,
                    { on ->
                        settings.setNotificationsEnabled(on)
                        // Включили — сразу спрашиваем системное разрешение (Android 13+).
                        if (on && Build.VERSION.SDK_INT >= 33 && !notificationsAllowed(context)) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    tag = "data.notifications",
                )
                if (notificationsEnabled && notificationsBlocked) {
                    // Разрешение выключено в системе: иначе переключатель включён, а уведомления молча не приходят.
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).testTag("data.notifications.blocked")) {
                        Text(
                            t("Уведомления запрещены в настройках Android. Откройте Настройки → Приложения → Honer AI → Уведомления и разрешите их.",
                                "Notifications are blocked in Android settings. Open Settings → Apps → Honer AI → Notifications and allow them."),
                            color = WarningOrange, fontSize = 13.sp,
                        )
                        Text(
                            t("Открыть настройки уведомлений", "Open notification settings"), color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp).clip(CircleShape).clickableRow { openNotificationSettings(context) },
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            t("Удалить всю историю?", "Delete all history?"),
            t("Все чаты и их вложения будут удалены с этого телефона. Память Honer AI останется. Сначала можно сохранить экспорт.",
                "All conversations and attachments will be removed from this phone. Honer AI memory will be kept. You can export a backup first."),
            t("Удалить", "Delete"), t("Отмена", "Cancel"), confirmTag = "data.delete.confirm",
            onConfirm = { store.clearAllChats(); status = t("История удалена.", "History deleted.") },
            onDismiss = { confirmDelete = false },
        )
    }
}

internal fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

@Composable
internal fun ArchivedChatsPage(onBack: () -> Unit) {
    val container = appContainer()
    val settings = container.settings
    val store = container.store
    val colors = HonerTheme.colors
    val conversations by store.conversations.collectAsState()
    val fontScale by settings.fontScale.collectAsState()
    val archived = remember(conversations) { store.archivedConversations() }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    fun t(ru: String, en: String) = settings.text(ru, en)

    SettingsPageScaffold(t("Архив чатов", "Archived chats"), "settings.page.archive", onBack) {
        if (archived.isEmpty()) {
            item(key = "empty") {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 30.dp).testTag("archive.empty"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(15.dp),
                ) {
                    Icon(Icons.Outlined.Archive, null, tint = colors.secondary, modifier = Modifier.size(42.dp))
                    Text(t("Архив пуст", "No archived chats"), color = colors.foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        t("Архивированные чаты скрыты из боковой панели. Здесь их можно восстановить.", "Archived conversations are hidden from the sidebar. Restore them here."),
                        color = colors.secondary, fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
        items(archived, key = { it.id }) { chat ->
            SettingsGroup {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(chat.title, color = colors.foreground, fontSize = (17 * fontScale).sp, fontWeight = FontWeight.SemiBold, maxLines = 3, modifier = Modifier.testTag("archive.row.${chat.id}"))
                    val date = (chat.archivedAt ?: chat.updatedAt).atZone(ZoneId.systemDefault()).toLocalDate()
                    Text(dateText(settings.isEnglish, date), color = colors.secondary, fontSize = (12 * fontScale).sp, modifier = Modifier.testTag("archive.date.${chat.id}"))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.heightIn(min = 44.dp).clip(CircleShape).clickableRow { store.restoreChat(chat.id) }
                                .padding(end = 8.dp).testTag("archive.restore.${chat.id}"),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.Undo, null, tint = colors.accent, modifier = Modifier.size(20.dp))
                            Text(t("Восстановить", "Restore"), color = colors.accent, fontSize = (17 * fontScale).sp, maxLines = 1)
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        IconButton(onClick = { pendingDelete = chat.id }, modifier = Modifier.testTag("archive.delete.${chat.id}")) {
                            Icon(Icons.Outlined.Delete, contentDescription = t("Удалить", "Delete"), tint = DestructiveRed)
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let { id ->
        ConfirmDialog(
            t("Удалить чат?", "Delete chat?"), null, t("Удалить", "Delete"), t("Отмена", "Cancel"), confirmTag = "archive.delete.confirm",
            onConfirm = { store.deleteChats(setOf(id)) }, onDismiss = { pendingDelete = null },
        )
    }
}
