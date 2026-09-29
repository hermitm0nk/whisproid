package dev.hermitm0nk.flowbubble.core

internal data class Insertion(val text: String, val cursor: Int)

/** These chat editors expose their empty placeholder as accessibility text. */
internal fun useNativePaste(packageName: String?): Boolean = packageName in setOf(
    "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web"
)

/** Some editors expose the placeholder as node text without setting isShowingHintText. */
internal fun editableText(text: String, hint: String?, showingHint: Boolean, start: Int, end: Int): String =
    if (showingHint || (hint != null && text == hint && start <= 0 && end <= 0)) "" else text

/** Pure text edit, preserving surrounding content and replacing the active selection. */
internal fun composeInsertion(current: String, start: Int, end: Int, words: String): Insertion {
    val first = minOf(start.coerceIn(0, current.length), end.coerceIn(0, current.length))
    val last = maxOf(start.coerceIn(0, current.length), end.coerceIn(0, current.length))
    val prefix = current.substring(0, first)
    val suffix = current.substring(last)
    val spacer = if (prefix.isNotEmpty() && !prefix.last().isWhitespace() && words.isNotEmpty() && !words.first().isWhitespace()) " " else ""
    return Insertion(prefix + spacer + words + suffix, prefix.length + spacer.length + words.length)
}
