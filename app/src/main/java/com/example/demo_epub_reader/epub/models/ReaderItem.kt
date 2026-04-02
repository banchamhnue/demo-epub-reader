package com.example.demo_epub_reader.epub.models

sealed class ReaderItem {
    data class Title(val chapterIndex: Int, val title: String) : ReaderItem()
    data class Text(val spans: List<HtmlSpan>) : ReaderItem()
    data class Image(val path: String, val yrel: Float) : ReaderItem()
    data class CodeBlock(val code: String) : ReaderItem()
    data class Blockquote(val spans: List<HtmlSpan>) : ReaderItem()
}

sealed class HtmlSpan {
    data class Text(val text: String) : HtmlSpan()
    data class Tag(
        val name: String,
        val attributes: Map<String, String>,
        val children: List<HtmlSpan>
    ) : HtmlSpan()
}

