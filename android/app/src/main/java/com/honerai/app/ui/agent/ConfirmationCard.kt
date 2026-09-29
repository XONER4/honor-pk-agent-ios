package com.honerai.app.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import com.honerai.app.core.agent.AgentPendingAction
import com.honerai.app.ui.theme.HonerTheme

/**
 * agent: карточка подтверждения важного действия агента (оплата, отправка, публикация, удаление).
 * Пользователь жмёт «Подтвердить»/«Отмена» — или пишет «да»/«отмена» в поле ввода.
 */
@Composable
fun ConfirmationCard(
    action: AgentPendingAction,
    english: Boolean,
    fontScale: Float,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HonerTheme.colors
    fun t(ru: String, en: String) = if (english) en else ru
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.accent.copy(alpha = 0.4f), shape)
            .padding(14.dp)
            .testTag("agent.confirm.card"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Shield, null, tint = colors.accent, modifier = Modifier.size(20.dp))
            Text(t("Нужно ваше подтверждение", "Your confirmation is needed"),
                color = colors.foreground, fontSize = (15 * fontScale).sp, fontWeight = FontWeight.SemiBold)
        }
        Text(action.description, color = colors.foreground, fontSize = (15 * fontScale).sp)
        action.amount?.let {
            Text(t("Сумма: ", "Amount: ") + it, color = colors.accent, fontSize = (15 * fontScale).sp, fontWeight = FontWeight.SemiBold)
        }
        Text(t("Подтвердите кнопкой или напишите «да» / «отмена».", "Confirm with a button or type \"yes\" / \"cancel\"."),
            color = colors.secondary, fontSize = (12 * fontScale).sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            ActionButton(t("Отмена", "Cancel"), Icons.Rounded.Close, filled = false, "agent.confirm.cancel", Modifier.weight(1f), onCancel)
            ActionButton(t("Подтвердить", "Confirm"), Icons.Rounded.Check, filled = true, "agent.confirm.ok", Modifier.weight(1f), onConfirm)
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    filled: Boolean,
    tag: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = HonerTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val bg = if (filled) colors.accent else Color.Transparent
    val fg = if (filled) Color.White else colors.foreground
    Row(
        modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(bg)
            .then(if (filled) Modifier else Modifier.border(1.dp, colors.divider, shape))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Text("  $text", color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
