package com.honerai.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.honerai.app.data.GenerationStep
import com.honerai.app.ui.common.HonerImages
import com.honerai.app.ui.common.LocalReduceMotion
import com.honerai.app.ui.common.MediaLinks
import com.honerai.app.ui.common.StatusText
import com.honerai.app.ui.theme.HonerTheme

/** Значок шага работы по его виду. */
fun stepIcon(kind: String): ImageVector = when (kind) {
    "search" -> Icons.Rounded.Search
    "read" -> Icons.AutoMirrored.Rounded.Article
    "images" -> Icons.Rounded.Image
    "videos" -> Icons.Rounded.SmartDisplay
    "screenshot" -> Icons.Rounded.CenterFocusStrong
    "weather" -> Icons.Rounded.WbSunny
    "draw" -> Icons.Rounded.Brush
    "chats" -> Icons.Rounded.Forum
    "memory" -> Icons.Rounded.Psychology
    "contact" -> Icons.Rounded.Person
    "table" -> Icons.Rounded.TableChart
    else -> Icons.Rounded.Settings
}

/**
 * Лента «что делает Honer AI»: поиск, чтение страниц, рисование и другие шаги.
 * Текущий шаг с пульсирующим значком и бликом по названию, готовые — спокойные;
 * у чтения много страниц — полоса «Прочитано N из M»; сайты показаны значками.
 */
@Composable
fun ActivityTimeline(steps: List<GenerationStep>, english: Boolean, fontSize: Float, modifier: Modifier = Modifier, live: Boolean = false) {
    val colors = HonerTheme.colors
    Column(modifier.animateContentSize().testTag("activity.timeline")) {
        steps.forEachIndexed { index, step ->
            key(step.id) {
                Row(Modifier.height(IntrinsicSize.Min).testTag("activity.step." + step.kind),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.width(22.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        StepMarker(step, live)
                        if (index < steps.size - 1) {
                            Box(Modifier.width(1.5.dp).weight(1f).background(colors.divider))
                        }
                    }
                    Column(
                        Modifier.weight(1f).padding(bottom = if (index < steps.size - 1) 12.dp else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            StatusText.localized(step.title, english),
                            fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold, color = colors.foreground,
                            modifier = Modifier.workingShimmer(live && !step.done),
                        )
                        if (step.detail.isNotEmpty()) {
                            Text(StatusText.localized(step.detail, english), fontSize = (fontSize * 0.9f).sp,
                                color = colors.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        val fraction = ActivityInfo.progress(step.detail)
                        if (fraction != null && (!step.done || fraction < 1f)) {
                            val animated by animateFloatAsState(fraction, tween(300), label = "progress")
                            LinearProgressIndicator(
                                progress = { animated },
                                color = colors.accent,
                                trackColor = colors.divider,
                                modifier = Modifier.widthIn(max = 220.dp).fillMaxWidth().testTag("activity.progress"),
                            )
                        }
                        if (step.sites.isNotEmpty()) SiteChips(step.sites)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepMarker(step: GenerationStep, live: Boolean) {
    val colors = HonerTheme.colors
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        if (!step.done && live) PulsingRing()
        Box(
            Modifier.size(22.dp).clip(CircleShape)
                .background(if (step.done) colors.accent.copy(alpha = 0.18f) else colors.surface)
                .border(1.dp, if (step.done) colors.accent.copy(alpha = 0.5f) else colors.divider, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(stepIcon(step.kind), null, tint = colors.accent, modifier = Modifier.size(12.dp))
        }
    }
}

/** Мягко пульсирующее кольцо вокруг значка текущего шага. */
@Composable
private fun PulsingRing() {
    if (LocalReduceMotion.current) return
    val accent = HonerTheme.colors.accent
    val transition = rememberInfiniteTransition(label = "ring")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Box(
        Modifier.size(22.dp).graphicsLayer {
            scaleX = 1f + 0.6f * phase
            scaleY = 1f + 0.6f * phase
            alpha = 0.7f * (1f - phase)
        }.border(1.5.dp, accent, CircleShape),
    )
}

/** Блик, пробегающий по названию текущего шага: видно, что работа идёт. */
@Composable
fun Modifier.workingShimmer(active: Boolean): Modifier {
    if (!active || LocalReduceMotion.current) return this
    val accent = HonerTheme.colors.accent
    val transition = rememberInfiniteTransition(label = "shimmer")
    val cycle by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "cycle")
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val width = size.width
            val band = maxOf(40.dp.toPx(), width * 0.35f)
            val x = cycle * (width * 1.6f) - width * 0.35f
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.55f), Color.Transparent),
                    startX = x, endX = x + band),
                blendMode = BlendMode.SrcAtop,
            )
        }
}

/** Значки сайтов: первая буква и адрес в капсуле; новые сайты появляются справа. */
@Composable
fun SiteChips(sites: List<String>) {
    val colors = HonerTheme.colors
    val context = LocalContext.current
    val unique = remember(sites) { ActivityInfo.uniqueSites(sites) }
    val palette = remember {
        listOf(Color(0xFF0A84FF), Color(0xFFBF5AF2), Color(0xFF30B0C7), Color(0xFFFF9F0A),
            Color(0xFFFF375F), Color(0xFF5E5CE6), Color(0xFF30D158), Color(0xFFFF453A))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        unique.take(4).forEach { host ->
            Row(
                Modifier.clip(CircleShape).background(colors.surface).border(0.6.dp, colors.divider, CircleShape)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.size(15.dp).clip(CircleShape)
                    .background(palette[ActivityInfo.colorIndex(host, palette.size)]), contentAlignment = Alignment.Center) {
                    Text(host.take(1).uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    AsyncImage(model = MediaLinks.favicon(host, 32), imageLoader = HonerImages.loader(context),
                        contentDescription = null, modifier = Modifier.size(15.dp).clip(CircleShape))
                }
                Text(host, fontSize = 11.sp, color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 110.dp))
            }
        }
        if (unique.size > 4) {
            Text("+${unique.size - 4}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.secondary)
        }
    }
}
