package com.honerai.app.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.ui.theme.HonerTheme

/** Что сделать с выделенным фрагментом. */
enum class SelectionAction { ASK, EXPLAIN, SIMPLER }

/**
 * Своя панель действий с выделением: вместо системного меню «Копировать / Выбрать всё»
 * показывается полоса с пунктами Honer AI («Спросить Honer AI», «Подробнее об этом»,
 * «Объяснить проще») — как пункты в меню выделения на iPhone.
 */
private class HonerTextToolbar : TextToolbar {
    var shown by mutableStateOf(false)
        private set
    var onCopy: (() -> Unit)? = null
        private set
    var onSelectAll: (() -> Unit)? = null
        private set

    override val status: TextToolbarStatus
        get() = if (shown) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        onCopy = onCopyRequested
        onSelectAll = onSelectAllRequested
        shown = true
    }

    override fun hide() { shown = false }
}

/**
 * Окно «Выбрать текст»: текст ответа с выделением. Выделенный фрагмент можно
 * спросить у Honer AI (цитата над полем ввода) или сразу попросить подробнее/проще.
 */
@Composable
fun SelectableTextSheet(
    content: String,
    english: Boolean,
    fontScale: Float,
    onAction: (fragment: String, action: SelectionAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = HonerTheme.colors
    val context = LocalContext.current
    fun t(ru: String, en: String) = if (english) en else ru
    HonerSheet(
        title = t("Выбрать текст", "Select text"),
        onDismiss = onDismiss,
        doneLabel = t("Готово", "Done"),
        doneTag = "message.selection.done",
        leading = {
            TextButton(onClick = { copyToClipboard(context, content) }, modifier = Modifier.testTag("message.selection.copy")) {
                Icon(Icons.Rounded.ContentCopy, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                Text(" " + t("Копировать всё", "Copy all"), color = colors.accent, fontSize = 14.sp)
            }
        },
    ) { close ->
        var value by remember(content) { mutableStateOf(TextFieldValue(content)) }
        val toolbar = remember { HonerTextToolbar() }
        val selection = value.selection
        val fragment = if (selection.collapsed) "" else content.substring(
            selection.min.coerceIn(0, content.length), selection.max.coerceIn(0, content.length))
        Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it.copy(text = content) },
                    readOnly = true,
                    textStyle = TextStyle(color = colors.foreground, fontSize = (17 * fontScale).sp, lineHeight = (24 * fontScale).sp),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 17.dp, end = 17.dp, top = 12.dp, bottom = 25.dp)
                        .testTag("message.selection.text"),
                )
            }
        }
        AnimatedVisibility(visible = fragment.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SelectionChip(Icons.Rounded.ContentCopy, t("Копировать", "Copy"), "message.selection.menu.copy") {
                    copyToClipboard(context, fragment)
                    value = value.copy(selection = TextRange(selection.max))
                    toolbar.hide()
                }
                SelectionChip(Icons.Rounded.AutoAwesome, t("Спросить Honer AI", "Ask Honer AI"), "message.selection.ask") {
                    onAction(fragment, SelectionAction.ASK); close()
                }
                SelectionChip(Icons.AutoMirrored.Rounded.ManageSearch, t("Подробнее об этом", "Tell me more"), "message.selection.explain") {
                    onAction(fragment, SelectionAction.EXPLAIN); close()
                }
                SelectionChip(Icons.Rounded.Lightbulb, t("Объяснить проще", "Explain simpler"), "message.selection.simpler") {
                    onAction(fragment, SelectionAction.SIMPLER); close()
                }
                if (fragment.length < content.length) {
                    SelectionChip(Icons.Rounded.SelectAll, t("Выбрать всё", "Select all"), "message.selection.all") {
                        value = value.copy(selection = TextRange(0, content.length))
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionChip(icon: ImageVector, title: String, tag: String, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(colors.surface)
            .border(0.7.dp, colors.divider, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = colors.accent, modifier = Modifier.size(17.dp))
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.foreground)
    }
}
