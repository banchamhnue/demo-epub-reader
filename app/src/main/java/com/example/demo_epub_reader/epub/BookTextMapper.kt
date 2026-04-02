package com.example.demo_epub_reader.epub

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import com.example.demo_epub_reader.epub.models.HtmlSpan

fun List<HtmlSpan>.asAnnotatedString(collapseWhitespace: Boolean = true): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var lastWasSpace = true

    fun parseSpan(span: HtmlSpan) {
        when (span) {
            is HtmlSpan.Text -> {
                if (collapseWhitespace) {
                    val normalized = span.text
                        .replace("\u00A0", " ")
                        .replace(Regex("\\s+"), " ")
                    if (normalized.isEmpty()) return
                    var toAppend = normalized
                    if (lastWasSpace) toAppend = toAppend.trimStart()
                    if (toAppend.isNotEmpty()) {
                        builder.append(toAppend)
                        lastWasSpace = toAppend.endsWith(" ")
                    }
                } else {
                    builder.append(span.text)
                }
            }
            is HtmlSpan.Tag -> {
                val style = when (span.name) {
                    "b", "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
                    "i", "em" -> SpanStyle(fontStyle = FontStyle.Italic)
                    "u" -> SpanStyle(textDecoration = TextDecoration.Underline)
                    "strike", "del", "s" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    "code" -> SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color.LightGray.copy(alpha = 0.3f)
                    )
                    "sub" -> SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.75.em)
                    "sup" -> SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.75.em)
                    else -> null
                }
                when {
                    span.name == "br" -> {
                        builder.append("\n")
                        lastWasSpace = true
                    }
                    span.name == "a" -> {
                        val url = span.attributes["href"] ?: ""
                        builder.pushStringAnnotation("URL", url)
                        span.children.forEach { parseSpan(it) }
                        builder.pop()
                    }
                    style != null -> {
                        builder.pushStyle(style)
                        span.children.forEach { parseSpan(it) }
                        builder.pop()
                    }
                    else -> span.children.forEach { parseSpan(it) }
                }
            }
        }
    }

    this.forEach { parseSpan(it) }
    return builder.toAnnotatedString().trimEnd()
}

private fun AnnotatedString.trimEnd(): AnnotatedString {
    var lastIndex = text.length - 1
    while (lastIndex >= 0 && (text[lastIndex].isWhitespace() || text[lastIndex] == '\u00A0')) {
        lastIndex--
    }
    if (lastIndex == -1) return AnnotatedString("")
    if (lastIndex == text.length - 1) return this
    return subSequence(0, lastIndex + 1)
}

