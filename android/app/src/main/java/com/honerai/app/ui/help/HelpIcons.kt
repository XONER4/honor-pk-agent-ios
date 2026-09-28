package com.honerai.app.ui.help

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AccountBox
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.FamilyRestroom
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicExternalOn
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** Значки SF Symbols из iOS-версии → значки Material. */
fun helpIcon(symbol: String): ImageVector = when (symbol) {
    "archivebox" -> Icons.Outlined.Archive
    "arrow.left.arrow.right" -> Icons.Outlined.SwapHoriz
    "arrow.triangle.branch" -> Icons.AutoMirrored.Outlined.CallSplit
    "atom" -> Icons.Outlined.Science
    "bell.badge" -> Icons.Outlined.NotificationsActive
    "books.vertical" -> Icons.AutoMirrored.Outlined.LibraryBooks
    "brain" -> Icons.Outlined.Psychology
    "bubble.left.and.bubble.right" -> Icons.Outlined.Forum
    "cart" -> Icons.Outlined.ShoppingCart
    "character.bubble" -> Icons.Outlined.Translate
    "checklist" -> Icons.Outlined.Checklist
    "chevron.left.forwardslash.chevron.right" -> Icons.Outlined.Code
    "crown" -> Icons.Outlined.EmojiEvents
    "doc.richtext" -> Icons.AutoMirrored.Outlined.Article
    "doc.text.viewfinder" -> Icons.Outlined.DocumentScanner
    "doc.text.magnifyingglass" -> Icons.Outlined.FindInPage
    "ellipsis.bubble" -> Icons.Outlined.ChatBubbleOutline
    "externaldrive.badge.icloud" -> Icons.Outlined.CloudUpload
    "face.smiling" -> Icons.Outlined.EmojiEmotions
    "figure.2.and.child.holdinghands", "figure.and.child.holdinghands" -> Icons.Outlined.FamilyRestroom
    "film" -> Icons.Outlined.Movie
    "gamecontroller" -> Icons.Outlined.SportsEsports
    "globe" -> Icons.Outlined.Public
    "graduationcap" -> Icons.Outlined.School
    "hand.raised" -> Icons.Outlined.PanTool
    "hand.thumbsdown" -> Icons.Outlined.ThumbDown
    "hourglass" -> Icons.Outlined.HourglassEmpty
    "info.circle" -> Icons.Outlined.Info
    "line.3.horizontal" -> Icons.Outlined.Menu
    "lock.shield" -> Icons.Outlined.Security
    "magnifyingglass" -> Icons.Outlined.Search
    "magnifyingglass.circle" -> Icons.AutoMirrored.Outlined.ManageSearch
    "mic" -> Icons.Outlined.Mic
    "mic.badge.plus" -> Icons.Outlined.MicExternalOn
    "moon.zzz" -> Icons.Outlined.Bedtime
    "paintbrush" -> Icons.Outlined.Brush
    "paperclip" -> Icons.Outlined.AttachFile
    "person.2" -> Icons.Outlined.People
    "person.crop.circle.badge.plus" -> Icons.Outlined.PersonAdd
    "person.crop.square" -> Icons.Outlined.AccountBox
    "person.text.rectangle" -> Icons.Outlined.Badge
    "person.wave.2" -> Icons.Outlined.RecordVoiceOver
    "photo" -> Icons.Outlined.Image
    "pin", "pin.circle" -> Icons.Outlined.PushPin
    "play.rectangle" -> Icons.Outlined.SmartDisplay
    "play.tv" -> Icons.Outlined.LiveTv
    "point.3.connected.trianglepath.dotted" -> Icons.Outlined.Hub
    "questionmark.bubble" -> Icons.Outlined.QuestionAnswer
    "questionmark.circle" -> Icons.AutoMirrored.Outlined.HelpOutline
    "quote.bubble" -> Icons.Outlined.FormatQuote
    "scissors" -> Icons.Outlined.ContentCut
    "shield.lefthalf.filled" -> Icons.Outlined.Shield
    "sidebar.left" -> Icons.AutoMirrored.Outlined.ViewSidebar
    "sparkles" -> Icons.Outlined.AutoAwesome
    "speaker.wave.2", "speaker.wave.3" -> Icons.AutoMirrored.Outlined.VolumeUp
    "square.and.arrow.up" -> Icons.Outlined.Share
    "square.and.pencil" -> Icons.Outlined.EditNote
    "square.stack.3d.up" -> Icons.Outlined.Layers
    "square.grid.2x2" -> Icons.Outlined.GridView
    "tablecells" -> Icons.Outlined.TableChart
    "text.book.closed" -> Icons.AutoMirrored.Outlined.MenuBook
    "text.bubble" -> Icons.AutoMirrored.Outlined.Chat
    "text.cursor" -> Icons.Outlined.TextFields
    "textformat" -> Icons.Outlined.TextFormat
    "wand.and.stars" -> Icons.Outlined.AutoFixHigh
    "waveform" -> Icons.Outlined.GraphicEq
    "lightbulb" -> Icons.Outlined.Lightbulb
    "list.number" -> Icons.Outlined.FormatListNumbered
    "link" -> Icons.Outlined.Link
    else -> Icons.AutoMirrored.Outlined.HelpOutline
}

/** Системные цвета iOS для значков разделов (контентные акценты, как в оригинале). */
fun HelpTint.color(): Color = when (this) {
    HelpTint.blue -> Color(0xFF0A84FF)
    HelpTint.brown -> Color(0xFFAC8E68)
    HelpTint.cyan -> Color(0xFF32ADE6)
    HelpTint.gray -> Color(0xFF8E8E93)
    HelpTint.green -> Color(0xFF30D158)
    HelpTint.indigo -> Color(0xFF5E5CE6)
    HelpTint.orange -> Color(0xFFFF9F0A)
    HelpTint.pink -> Color(0xFFFF375F)
    HelpTint.purple -> Color(0xFFBF5AF2)
    HelpTint.red -> Color(0xFFFF453A)
    HelpTint.teal -> Color(0xFF40C8E0)
    HelpTint.yellow -> Color(0xFFFFD60A)
}

/** Строка с **жирным** и *курсивом* (inline Markdown). */
fun helpRichText(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = text.length
    while (i < n) {
        if (text.startsWith("**", i)) {
            val end = text.indexOf("**", i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendItalics(text.substring(i + 2, end)) }
                i = end + 2
                continue
            }
        }
        if (text[i] == '*' && i + 1 < n && text[i + 1] != ' ') {
            val end = text.indexOf('*', i + 1)
            if (end > i + 1 && text[end - 1] != ' ') {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }
        append(text[i])
        i++
    }
}

private fun AnnotatedString.Builder.appendItalics(text: String) {
    var i = 0
    while (i < text.length) {
        val start = text.indexOf('*', i)
        val end = if (start >= 0) text.indexOf('*', start + 1) else -1
        if (start < 0 || end < 0) { append(text.substring(i)); return }
        append(text.substring(i, start))
        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(start + 1, end)) }
        i = end + 1
    }
}
