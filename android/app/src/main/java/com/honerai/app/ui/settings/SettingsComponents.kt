package com.honerai.app.ui.settings

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.AppContainer
import com.honerai.app.core.AppSettings
import com.honerai.app.ui.theme.HonerTheme

// Общие элементы страниц настроек в стиле iOS: сгруппированные скруглённые карточки,
// значки слева, значения и шевроны справа.

/** Красный для опасных действий (как .destructive на iOS). */
internal val DestructiveRed = Color(0xFFFF453A)
internal val SuccessGreen = Color(0xFF30D158)
internal val WarningOrange = Color(0xFFFF9F0A)

@Composable
internal fun appContainer(): AppContainer = AppContainer.get(LocalContext.current)

@Composable
internal fun appSettings(): AppSettings = appContainer().settings

/** Меньше анимаций: слабый телефон, настройка приложения или системное отключение анимаций. */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val container = appContainer()
    val reduce by container.settings.reduceMotion.collectAsState()
    val context = LocalContext.current
    val systemOff = remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
    return reduce || systemOff
}

/** Фон страницы и карточки (как Color(white: 0.115/0.155) на iOS). */
internal val pageBackground: Color @Composable get() = HonerTheme.colors.let { if (it.isDark) it.surface else it.sidebar }
internal val cardBackground: Color @Composable get() = HonerTheme.colors.let { if (it.isDark) it.raised else it.background }

/** Верхняя панель: «назад» слева, заголовок по центру, крестик справа. */
@Composable
internal fun SettingsTopBar(
    title: String,
    onBack: (() -> Unit)?,
    onClose: (() -> Unit)? = null,
    backLabel: String = "",
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = HonerTheme.colors
    Box(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .height(56.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp).testTag("settings.back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backLabel, tint = colors.foreground)
            }
        }
        Text(
            title, color = colors.foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 64.dp),
        )
        Row(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            trailing?.invoke(this)
            if (onClose != null) {
                Box(
                    Modifier.size(35.dp).clip(CircleShape).background(colors.foreground.copy(alpha = 0.06f))
                        .clickable(role = Role.Button, onClick = onClose).testTag("settings.close"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = backLabel, tint = colors.foreground, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/**
 * Страница настроек: панель сверху и прокручиваемый список. На широких экранах
 * содержимое ограничено по ширине и стоит по центру.
 */
@Composable
internal fun SettingsPageScaffold(
    title: String,
    tag: String,
    onBack: (() -> Unit)?,
    onClose: (() -> Unit)? = null,
    state: LazyListState = rememberLazyListState(),
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val settings = appSettings()
    Column(Modifier.fillMaxSize().background(pageBackground)) {
        SettingsTopBar(title, onBack, onClose, settings.text("Назад", "Back"), trailing)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = state,
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize().imePadding().testTag(tag),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                content()
                item(key = "__bottom_inset") {
                    Spacer(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)))
                }
            }
        }
    }
}

/** Группа строк: заголовок сверху, карточка, пояснение снизу. */
@Composable
internal fun SettingsGroup(
    title: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = HonerTheme.colors
    Column(modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp)) {
        if (!title.isNullOrEmpty()) {
            Text(
                title, color = colors.secondary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 14.dp, bottom = 8.dp),
            )
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(cardBackground), content = content)
        if (!footer.isNullOrEmpty()) SettingsFootnote(footer)
    }
}

@Composable
internal fun SettingsFootnote(text: String, color: Color = HonerTheme.colors.secondary) {
    Text(text, color = color, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp))
}

@Composable
internal fun SettingsDivider(inset: Int = 56) {
    HorizontalDivider(Modifier.padding(start = inset.dp, end = 16.dp), thickness = 0.6.dp, color = HonerTheme.colors.divider)
}

/** Строка-переход: значок, заголовок, значение, шеврон. */
@Composable
internal fun SettingsRow(
    icon: ImageVector?,
    title: String,
    value: String = "",
    tag: String? = null,
    chevron: Boolean = true,
    trailingIcon: ImageVector? = null,
    titleColor: Color = HonerTheme.colors.foreground,
    enabled: Boolean = true,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = HonerTheme.colors
    val fontScale by appSettings().fontScale.collectAsState()
    var modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp)
    if (onClick != null) modifier = modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    if (tag != null) modifier = modifier.testTag(tag)
    Row(
        modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = if (enabled) titleColor else colors.secondary, modifier = Modifier.padding(horizontal = 2.dp).size(23.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) titleColor else colors.secondary, fontSize = (17 * fontScale).sp)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, color = colors.secondary, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        if (value.isNotEmpty()) {
            Text(
                value, color = colors.secondary, fontSize = (16 * fontScale).sp, textAlign = TextAlign.End,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp),
            )
        }
        when {
            trailingIcon != null -> Icon(trailingIcon, contentDescription = null, tint = colors.secondary, modifier = Modifier.size(18.dp))
            chevron && onClick != null -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.secondary, modifier = Modifier.size(20.dp))
        }
    }
}

/** Строка «подпись — значение» без действия (LabeledContent). */
@Composable
internal fun SettingsValueRow(label: String, value: String, tag: String? = null) {
    val colors = HonerTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).let { if (tag != null) it.testTag(tag) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.foreground, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, color = colors.secondary, fontSize = 16.sp, textAlign = TextAlign.End)
    }
}

/** Переключатель со значком и подсказкой. */
@Composable
internal fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
    tag: String? = null,
) {
    val colors = HonerTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .let { if (tag != null) it.testTag(tag) else it }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            if (iconTint != null) IconBadge(icon, iconTint) else Icon(icon, null, tint = colors.foreground, modifier = Modifier.padding(horizontal = 2.dp).size(23.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) colors.foreground else colors.secondary, fontSize = 16.sp)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, color = colors.secondary, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Switch(
            checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = Color.White),
        )
    }
}

/** Квадратный цветной значок (как иконки в настройках iPhone). */
@Composable
internal fun IconBadge(icon: ImageVector, tint: Color, size: Int = 30) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.27f).dp)).background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size((size * 0.55f).dp))
    }
}

/** Кнопка-строка (в том числе красная для удаления). */
@Composable
internal fun SettingsButtonRow(
    text: String,
    icon: ImageVector? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    tag: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = HonerTheme.colors
    val tint = when {
        !enabled -> colors.secondary
        destructive -> DestructiveRed
        else -> colors.accent
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .let { if (tag != null) it.testTag(tag) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Text(text, color = tint, fontSize = 16.sp, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Выбор одного варианта в меню (Picker в стиле .menu). */
@Composable
internal fun <T> SettingsMenuRow(
    icon: ImageVector?,
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    tag: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingsRow(
            icon = icon, title = title, value = options.firstOrNull { it.first == selected }?.second.orEmpty(),
            tag = tag, trailingIcon = Icons.Filled.UnfoldMore, onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(cardBackground)) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label, color = HonerTheme.colors.foreground) },
                    trailingIcon = { if (value == selected) Icon(Icons.Filled.Check, null, tint = HonerTheme.colors.accent) },
                    onClick = { expanded = false; onSelect(value) },
                    modifier = if (tag != null) Modifier.testTag("$tag.$value") else Modifier,
                )
            }
        }
    }
}

/** Строка выбора с галочкой (список языков, голосов). */
@Composable
internal fun SettingsCheckRow(title: String, checked: Boolean, tag: String? = null, subtitle: String? = null, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.RadioButton, onClick = onClick)
            .let { if (tag != null) it.testTag(tag) else it }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.foreground, fontSize = 16.sp)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, color = colors.secondary, fontSize = 12.sp)
        }
        if (checked) Icon(Icons.Filled.Check, contentDescription = null, tint = colors.accent)
    }
}

/** Сегментированный выбор (Picker .segmented). */
@Composable
internal fun <T> SegmentedPicker(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, tag: String? = null) {
    val colors = HonerTheme.colors
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).let { if (tag != null) it.testTag(tag) else it }) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = colors.accent.copy(alpha = 0.22f), activeContentColor = colors.foreground,
                    inactiveContainerColor = Color.Transparent, inactiveContentColor = colors.foreground,
                    activeBorderColor = colors.divider, inactiveBorderColor = colors.divider,
                ),
                icon = {},
                modifier = if (tag != null) Modifier.testTag("$tag.$value") else Modifier,
            ) { Text(label, fontSize = 14.sp, maxLines = 1) }
        }
    }
}

/** Подтверждение действия (alert на iOS). */
@Composable
internal fun ConfirmDialog(
    title: String,
    message: String?,
    confirm: String,
    cancel: String,
    destructive: Boolean = true,
    confirmTag: String? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = HonerTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cardBackground,
        title = { Text(title, color = colors.foreground) },
        text = message?.let { { Text(it, color = colors.secondary) } },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }, modifier = if (confirmTag != null) Modifier.testTag(confirmTag) else Modifier) {
                Text(confirm, color = if (destructive) DestructiveRed else colors.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(cancel, color = colors.accent) } },
    )
}

/** Пустое место между группами. */
internal fun LazyListScope.spacer(key: String, height: Int = 8) {
    item(key = key) { Spacer(Modifier.height(height.dp)) }
}
