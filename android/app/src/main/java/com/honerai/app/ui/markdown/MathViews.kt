package com.honerai.app.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.ui.theme.HonerTheme

/**
 * Формула отдельным блоком $$ … $$ — нативно, без веб-вью (порт MathExpressionView):
 * дроби, корни, степени, индексы, греческие буквы и крупные операторы.
 */
@Composable
internal fun MathBlockView(latex: String, fontSize: Float) {
    val colors = HonerTheme.colors
    val tokens = remember(latex) { MathTokenizer.tokenize(latex) }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .border(0.6.dp, colors.divider, RoundedCornerShape(10.dp))
            .testTag("message.math.block")
    ) {
        val minWidth = maxWidth
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Row(
                Modifier.widthIn(min = minWidth).padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tokens.forEach { MathTokenView(it, fontSize) }
            }
        }
    }
}

@Composable
private fun MathTokenView(token: MathToken, fontSize: Float) {
    val colors = HonerTheme.colors
    val density = LocalDensity.current
    when (token) {
        is MathToken.Text -> Text(
            token.value,
            fontSize = fontSize.sp,
            fontFamily = FontFamily.Serif,
            fontStyle = if (token.value.length == 1 && token.value[0].isLetter()) FontStyle.Italic else FontStyle.Normal,
            color = colors.foreground,
        )
        is MathToken.Sup -> Text(
            token.value,
            fontSize = (fontSize * 0.72f).sp,
            fontFamily = FontFamily.Serif,
            color = colors.foreground,
            modifier = Modifier.offset(y = with(density) { -(fontSize * 0.45f).sp.toDp() }),
        )
        is MathToken.Sub -> Text(
            token.value,
            fontSize = (fontSize * 0.72f).sp,
            fontFamily = FontFamily.Serif,
            color = colors.foreground,
            modifier = Modifier.offset(y = with(density) { (fontSize * 0.25f).sp.toDp() }),
        )
        is MathToken.Frac -> Column(
            Modifier.width(IntrinsicSize.Max),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(token.numerator, fontSize = (fontSize * 0.78f).sp, fontFamily = FontFamily.Serif, color = colors.foreground)
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.foreground))
            Text(token.denominator, fontSize = (fontSize * 0.78f).sp, fontFamily = FontFamily.Serif, color = colors.foreground)
        }
        is MathToken.Sqrt -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("√", fontSize = fontSize.sp, color = colors.foreground)
            Text(
                token.value,
                fontSize = (fontSize * 0.9f).sp,
                fontFamily = FontFamily.Serif,
                color = colors.foreground,
                modifier = Modifier
                    .drawBehind {
                        drawLine(colors.foreground, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                    }
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            )
        }
    }
}
