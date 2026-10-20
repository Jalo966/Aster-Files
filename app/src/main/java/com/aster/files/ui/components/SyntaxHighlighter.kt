package com.aster.files.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

data class SyntaxColors(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val tag: Color,
    val attribute: Color
)

/** 轻量语法高亮，避免引入 sora-editor 增大 APK；仅改变显示样式，不改变文本。 */
object SyntaxHighlighter {
    private val codeKeywords = setOf(
        "abstract", "actual", "as", "break", "by", "catch", "class", "companion", "const",
        "continue", "data", "def", "do", "else", "enum", "false", "final", "finally", "for",
        "fun", "if", "implements", "import", "in", "interface", "internal", "is", "lambda",
        "let", "new", "null", "object", "open", "override", "package", "private", "protected",
        "public", "return", "sealed", "static", "super", "switch", "this", "throw", "true",
        "try", "typealias", "val", "var", "when", "while", "with", "yield", "async", "await",
        "from", "None", "True", "False", "elif", "except", "pass", "raise", "self"
    )

    fun isCodeFile(extension: String): Boolean = extension.lowercase() in setOf(
        "json", "xml", "html", "htm", "js", "ts", "java", "kt", "kts", "py", "md", "gradle"
    )

    fun highlight(text: String, extension: String, colors: SyntaxColors): AnnotatedString {
        val builder = AnnotatedString.Builder(text)
        val ext = extension.lowercase()
        when (ext) {
            "json" -> {
                applyQuotedStrings(builder, text, colors.string)
                applyRegex(builder, text, Regex("\\b(true|false|null)\\b"), colors.keyword)
                applyRegex(builder, text, Regex("\\b-?\\d+(\\.\\d+)?\\b"), colors.number)
            }
            "xml", "html", "htm" -> {
                applyRegex(builder, text, Regex("<!--[\\s\\S]*?-->"), colors.comment)
                applyRegex(builder, text, Regex("</?[A-Za-z][\\w:-]*"), colors.tag)
                applyRegex(builder, text, Regex("\\s[\\w:-]+="), colors.attribute)
                applyQuotedStrings(builder, text, colors.string)
            }
            "md" -> {
                applyRegex(builder, text, Regex("(?m)^#{1,6} .*$"), colors.keyword)
                applyRegex(builder, text, Regex("(?m)^> .*$"), colors.comment)
            }
            else -> {
                applyRegex(builder, text, Regex("//[^\\n]*"), colors.comment)
                applyRegex(builder, text, Regex("/\\*[\\s\\S]*?\\*/"), colors.comment)
                applyQuotedStrings(builder, text, colors.string)
                applyRegex(builder, text, Regex("\\b\\d+(\\.\\d+)?\\b"), colors.number)
                val keywordPattern = "\\b(" + codeKeywords.joinToString("|") { Regex.escape(it) } + ")\\b"
                applyRegex(builder, text, Regex(keywordPattern), colors.keyword)
            }
        }
        return builder.toAnnotatedString()
    }

    private fun applyQuotedStrings(
        builder: androidx.compose.ui.text.AnnotatedString.Builder,
        text: String,
        color: Color
    ) {
        Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'").findAll(text).forEach { match ->
            builder.addStyle(SpanStyle(color = color), match.range.first, match.range.last + 1)
        }
    }

    private fun applyRegex(
        builder: androidx.compose.ui.text.AnnotatedString.Builder,
        text: String,
        regex: Regex,
        color: Color
    ) {
        regex.findAll(text).forEach { match ->
            builder.addStyle(SpanStyle(color = color), match.range.first, match.range.last + 1)
        }
    }
}

class SyntaxHighlightTransformation(
    private val extension: String,
    private val colors: SyntaxColors
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        return TransformedText(
            SyntaxHighlighter.highlight(text.text, extension, colors),
            OffsetMapping.Identity
        )
    }
}
