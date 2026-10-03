package com.honerai.admin.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.admin.AdminContainer
import com.honerai.admin.ui.Navigator
import com.honerai.admin.ui.common.SectionCard
import com.honerai.admin.ui.common.TopBar
import com.honerai.admin.ui.theme.HonerTheme
import com.honerai.admin.ui.theme.LocalEnglish
import com.honerai.admin.ui.theme.tr

private data class GuideSection(val emoji: String, val title: String, val lines: List<String>)

/** «Инструкция и лицензия» (план п.14): как пользоваться админкой + условия. Текст, без внешних ресурсов. */
@Composable
fun GuideScreen(container: AdminContainer, navigator: Navigator) {
    val colors = HonerTheme.colors
    val english = LocalEnglish.current
    val sections = remember(english) { guideSections(english) }
    Column(Modifier.fillMaxSize().background(colors.background)) {
        TopBar(tr("Инструкция и лицензия", "Guide & license"), onBack = { navigator.pop() })
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(sections) { sec ->
                SectionCard {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Text(sec.emoji, fontSize = 18.sp)
                            Spacer(Modifier.width(10.dp))
                            androidx.compose.material3.Text(sec.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.foreground)
                        }
                        sec.lines.forEach { line ->
                            Row(verticalAlignment = Alignment.Top) {
                                androidx.compose.material3.Text("•", fontSize = 14.sp, color = colors.accent, modifier = Modifier.width(16.dp))
                                androidx.compose.material3.Text(line, fontSize = 14.sp, color = colors.secondary, lineHeight = 20.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun guideSections(english: Boolean): List<GuideSection> = if (english) listOf(
    GuideSection("💬", "Support", listOf(
        "The Support tab lists conversations with a preview of the last message.",
        "Non-Russian messages are auto-translated — see “🌐 Translation” under the text.",
        "Long-press a conversation to pin it to the top (favorites).",
        "The pinned “Team chat” at the top is for admins and the developer.",
    )),
    GuideSection("🎯", "Taking a ticket", listOf(
        "Open a chat and tap “Take” to mark it as yours — others see it and the timer.",
        "“Release” frees it; “Take over” reassigns it to you.",
    )),
    GuideSection("🚫", "User limits", listOf(
        "In a user card you can mute AI or block Support with a reason and a time limit.",
        "The user sees the reason; the limit lifts automatically when it expires.",
    )),
    GuideSection("🤖", "AI", listOf(
        "Toggle AI globally on the Home screen, or add/remove AI inside a specific chat.",
        "A schedule can turn AI on only during chosen hours.",
    )),
    GuideSection("🛡️", "Admins & rights", listOf(
        "The developer manages roles and per-admin rights in “Administrators”.",
        "New admins start with no rights until the developer grants them.",
    )),
    GuideSection("📄", "License", listOf(
        "Honer AI admin panel. Operator: XONER.",
        "For internal use by the team only. Do not share access or user data.",
        "User-facing policy and terms are available at /legal/privacy and /legal/terms.",
    )),
) else listOf(
    GuideSection("💬", "Поддержка", listOf(
        "Вкладка «Поддержка» — список переписок с превью последнего сообщения.",
        "Сообщения не на русском переводятся автоматически — см. «🌐 Перевод» под текстом.",
        "Долгое нажатие на переписку закрепляет её вверху (избранное).",
        "Закреплённый «Чат команды» вверху — для админов и разработчика.",
    )),
    GuideSection("🎯", "Взять обращение", listOf(
        "Откройте чат и нажмите «Взять в работу» — остальные видят это и таймер.",
        "«Освободить» снимает, «Перехватить» — забирает обращение себе.",
    )),
    GuideSection("🚫", "Ограничения пользователя", listOf(
        "В карточке пользователя можно замутить ИИ или запретить поддержку — с причиной и сроком.",
        "Пользователь видит причину; ограничение снимается само по истечении срока.",
    )),
    GuideSection("🤖", "Нейросеть", listOf(
        "Общий переключатель ИИ — на «Главной», или добавить/убрать ИИ в конкретном чате.",
        "Расписание включает ИИ только в выбранные часы.",
    )),
    GuideSection("🛡️", "Админы и права", listOf(
        "Разработчик управляет ролями и правами каждого админа в «Администраторах».",
        "Новый админ без прав, пока разработчик их не выдаст.",
    )),
    GuideSection("📄", "Лицензия", listOf(
        "Админ-панель Honer AI. Оператор: XONER.",
        "Только для внутреннего использования командой. Не передавайте доступ и данные пользователей.",
        "Политика и соглашение для пользователей — по адресам /legal/privacy и /legal/terms.",
    )),
)
