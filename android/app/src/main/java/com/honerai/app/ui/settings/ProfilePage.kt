package com.honerai.app.ui.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// «Настройки аккаунта» (порт ProfileSettingsPage): фото, имя, дата рождения, дата создания аккаунта.

private const val PROFILE_PHOTO = "profile-photo.jpg"
private val birthdayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

internal fun dateText(english: Boolean, date: LocalDate): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(if (english) Locale.ENGLISH else Locale("ru")))

internal fun dateTimeText(english: Boolean, millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(if (english) Locale.ENGLISH else Locale("ru")))

/** Уменьшенное фото с учётом поворота из EXIF (максимум [side] точек по большей стороне). */
internal fun decodeScaled(context: android.content.Context, uri: Uri, side: Int): Bitmap? = runCatching {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
    val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
    val rotation = resolver.openInputStream(uri)?.use {
        when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } ?: 0f
    val scale = minOf(1f, side.toFloat() / maxOf(raw.width, raw.height))
    val matrix = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
    if (scale == 1f && rotation == 0f) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
}.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = appContainer()
    val settings = container.settings
    val colors = HonerTheme.colors
    val scope = rememberCoroutineScope()
    val displayName by settings.displayName.collectAsState()
    val birthday by settings.birthday.collectAsState()
    val photoPath by settings.profilePhotoPath.collectAsState()
    var avatar by remember { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var photoError by remember { mutableStateOf<String?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    fun t(ru: String, en: String) = settings.text(ru, en)

    // Фото ищем и по сохранённому пути, и по стандартному имени в папке приложения.
    LaunchedEffect(photoPath) {
        avatar = if (photoPath.isEmpty()) null else withContext(Dispatchers.IO) {
            listOf(File(photoPath), File(context.filesDir, PROFILE_PHOTO)).firstOrNull { it.isFile }?.let { f ->
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap()
            }
        }
    }
    // Имя и день рождения передаются нейросети, когда пользователь уходит со страницы.
    DisposableEffect(Unit) {
        onDispose { container.store.setProfile(settings.displayName.value, settings.birthday.value) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                val bitmap = decodeScaled(context, uri, 512) ?: return@withContext null
                runCatching {
                    val file = File(context.filesDir, PROFILE_PHOTO)
                    val tmp = File(context.filesDir, "$PROFILE_PHOTO.tmp")
                    tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
                    file to bitmap
                }.getOrNull()
            }
            busy = false
            if (saved == null) {
                photoError = t("Не удалось прочитать изображение.", "Could not read the image.")
            } else {
                avatar = saved.second.asImageBitmap()
                settings.setProfilePhotoPath(saved.first.path)
                photoError = null
            }
        }
    }

    SettingsPageScaffold(t("Настройки аккаунта", "Account settings"), "settings.page.profile", onBack) {
        item(key = "photo") {
            SettingsGroup {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier.size(64.dp).clip(CircleShape).border(0.7.dp, colors.divider, CircleShape).testTag("profile.photo.view"),
                        contentAlignment = Alignment.Center,
                    ) {
                        val image = avatar
                        when {
                            busy -> CircularProgressIndicator(Modifier.size(24.dp), color = colors.accent, strokeWidth = 2.dp)
                            image != null -> Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp))
                            else -> Icon(Icons.Filled.AccountCircle, null, tint = colors.secondary, modifier = Modifier.size(64.dp))
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(displayName.ifEmpty { t("Ваш профиль", "Your profile") }, color = colors.foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                Modifier.clip(CircleShape).clickable {
                                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }.padding(vertical = 4.dp).testTag("profile.photo.pick"),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Icons.Outlined.Image, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                                Text(t("Загрузить фото", "Upload photo"), color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                            if (avatar != null) {
                                Text(
                                    t("Удалить", "Remove"), color = DestructiveRed, fontSize = 14.sp,
                                    modifier = Modifier.clip(CircleShape).clickable {
                                        settings.setProfilePhotoPath("")
                                        avatar = null
                                        scope.launch(Dispatchers.IO) { File(context.filesDir, PROFILE_PHOTO).delete() }
                                    }.padding(vertical = 4.dp).testTag("profile.photo.remove"),
                                )
                            }
                        }
                    }
                }
            }
        }
        item(key = "name") {
            SettingsGroup(t("Имя", "Name")) {
                Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                    if (displayName.isEmpty()) Text(t("Ваше имя", "Your name"), color = colors.secondary, fontSize = 17.sp)
                    BasicTextField(
                        value = displayName, onValueChange = { settings.setDisplayName(it.take(60)) }, singleLine = true,
                        textStyle = TextStyle(color = colors.foreground, fontSize = 17.sp), cursorBrush = SolidColor(colors.accent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        modifier = Modifier.fillMaxWidth().testTag("profile.name"),
                    )
                }
            }
        }
        item(key = "birthday") {
            val date = runCatching { LocalDate.parse(birthday, birthdayFormat) }.getOrNull()
            SettingsGroup(
                t("Дата рождения", "Birthday"),
                footer = t("Необязательно. Honer AI учитывает возраст в объяснениях и может поздравить с днём рождения.",
                    "Optional. Honer AI takes your age into account and can wish you a happy birthday."),
            ) {
                SettingsRow(Icons.Outlined.Cake, t("Когда родились", "Date of birth"), date?.let { dateText(settings.isEnglish, it) } ?: t("не указана", "not set"), tag = "profile.birthday") { pickingDate = true }
                if (date != null) {
                    SettingsDivider()
                    SettingsButtonRow(t("Убрать дату рождения", "Remove birthday"), destructive = true, tag = "profile.birthday.remove") {
                        settings.setBirthday("")
                        container.store.setProfile(settings.displayName.value, "")
                    }
                }
            }
        }
        item(key = "created") {
            SettingsGroup {
                val created = settings.accountCreatedAt.atZone(ZoneId.systemDefault()).toLocalDate()
                SettingsRow(Icons.Outlined.Event, t("Аккаунт создан", "Account created"), dateText(settings.isEnglish, created), chevron = false, tag = "profile.created")
            }
        }
        photoError?.let { error -> item(key = "error") { SettingsFootnote(error) } }
        item(key = "note") {
            SettingsFootnote(t("Профиль и история чатов хранятся на этом телефоне. Вход в аккаунт не требуется.",
                "Your profile and chat history are stored on this phone. No account sign-in is required."))
        }
    }

    if (pickingDate) {
        val initial = runCatching { LocalDate.parse(birthday, birthdayFormat) }.getOrNull() ?: LocalDate.now().minusYears(20)
        val today = LocalDate.now()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = (today.year - 110)..today.year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val value = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().format(birthdayFormat)
                        settings.setBirthday(value)
                        container.store.setProfile(settings.displayName.value, value)
                    }
                    pickingDate = false
                }) { Text(t("Готово", "Done"), color = colors.accent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(t("Отмена", "Cancel"), color = colors.accent) } },
            colors = DatePickerDefaults.colors(containerColor = cardBackground),
        ) {
            DatePicker(state, colors = DatePickerDefaults.colors(containerColor = cardBackground, selectedDayContainerColor = colors.accent, todayDateBorderColor = colors.accent))
        }
    }
}
