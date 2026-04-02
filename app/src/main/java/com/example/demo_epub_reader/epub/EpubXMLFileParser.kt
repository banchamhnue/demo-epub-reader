package com.example.demo_epub_reader.epub

import android.graphics.BitmapFactory
import android.util.Log
import com.example.demo_epub_reader.epub.models.HtmlSpan
import com.example.demo_epub_reader.epub.models.ReaderItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.File
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Parses an XML file from an EPUB archive and extracts the title and body content.
 */
class EpubXMLFileParser(
    val fileAbsolutePath: String,
    val data: ByteArray,
    private val zipFile: Map<String, EpubParser.EpubFile>,
    private val fragmentId: String? = null,
    private val nextFragmentId: String? = null
) {
    data class Output(val title: String?, val body: List<ReaderItem>)

    private val fileParentFolder: File = File(fileAbsolutePath).parentFile ?: File("")

    companion object {
        const val TAG = "EpubXMLFileParser"

        private const val HEADER_SELECTORS =
            "h1, h2, h3, h4, h5, h6, .title, .chapter-title, .header"

        private val INLINE_TAGS = setOf(
            "a", "abbr", "b", "bdi", "bdo", "big", "br", "cite", "code", "del", "dfn",
            "em", "font", "i", "ins", "kbd", "mark", "q", "rp", "rt", "ruby", "s",
            "samp", "small", "span", "strike", "strong", "sub", "sup", "time", "u",
            "var", "wbr"
        )
    }

    fun parseAsDocument(): Output {
        val document = Jsoup.parse(data.inputStream(), "UTF-8", "")

        val title: String
        val bodyContent: List<ReaderItem>
        val bodyElement: Element?

        if (fragmentId != null) {
            bodyElement = document.selectFirst("div#$fragmentId")
            if (bodyElement != null) {
                Log.d(TAG, "Fragment ID: $fragmentId represents a <div> tag.")
                val header = bodyElement.selectFirst(HEADER_SELECTORS) ?: document.selectFirst(HEADER_SELECTORS)
                title = header?.text() ?: ""
                header?.remove()
                bodyContent = processNodes(bodyElement.childNodes())
            } else {
                Log.d(TAG, "Fragment ID: $fragmentId doesn't represent a <div> tag.")
                val fragmentElement = document.selectFirst("#$fragmentId")
                val header = if (fragmentElement != null && fragmentElement.isHeader) {
                    fragmentElement
                } else {
                    fragmentElement?.selectFirst(HEADER_SELECTORS)
                }
                title = header?.text() ?: ""
                val fragmentNodes = mutableListOf<Node>()
                var currentNode: Node? = if (header != null && header == fragmentElement) {
                    fragmentElement.nextSibling()
                } else {
                    fragmentElement
                }
                val nextFragmentIdElement = if (nextFragmentId != null) {
                    document.selectFirst("#$nextFragmentId")
                } else null
                header?.remove()
                while (currentNode != null && currentNode != nextFragmentIdElement) {
                    fragmentNodes.add(currentNode)
                    currentNode = getNextSibling(currentNode)
                }
                bodyContent = processNodes(fragmentNodes)
            }
        } else {
            Log.d(TAG, "No fragment ID provided. Fetching the entire body content.")
            bodyElement = document.body()
            val header = document.selectFirst(HEADER_SELECTORS)
            title = header?.text() ?: ""
            header?.remove()
            bodyContent = processNodes(bodyElement.childNodes())
        }

        val finalBody = if (bodyContent.isEmpty() && fragmentId != null) {
            Log.w(TAG, "Empty body content for fragment $fragmentId. Falling back to full file.")
            processNodes(document.body().childNodes())
        } else {
            bodyContent
        }

        return Output(
            title = title.smartTrim(),
            body = finalBody
        )
    }

    private val Element.isHeader: Boolean
        get() = tagName() in listOf("h1", "h2", "h3", "h4", "h5", "h6") ||
                hasClass("title") || hasClass("chapter-title") || hasClass("header")

    private fun parseAsImage(absolutePathImage: String): ReaderItem.Image {
        val bitmap = zipFile[absolutePathImage]?.data?.runCatching {
            BitmapFactory.decodeByteArray(this, 0, this.size)
        }?.getOrNull()
        return ReaderItem.Image(
            path = absolutePathImage,
            yrel = bitmap?.let { it.height.toFloat() / it.width.toFloat() } ?: 1.45f
        )
    }

    private fun getNextSibling(currentNode: Node?): Node? {
        var nextSibling: Node? = currentNode?.nextSibling()
        if (nextSibling == null) {
            var parentNode = currentNode?.parent()
            while (parentNode != null) {
                nextSibling = parentNode.nextSibling()
                if (nextSibling != null) {
                    return traverseDescendants(nextSibling)
                }
                parentNode = parentNode.parent()
            }
        }
        return nextSibling
    }

    private fun traverseDescendants(node: Node): Node? {
        val children = node.childNodes()
        if (children.isNotEmpty()) return children.first()
        val siblings = node.nextSiblingNodes()
        if (siblings.isNotEmpty()) return traverseDescendants(siblings.first())
        return null
    }

    private fun declareImgEntry(node: Node): ReaderItem.Image {
        val attrs = node.attributes().associate { it.key to it.value }
        val relPathEncoded = attrs["src"] ?: attrs["xlink:href"] ?: ""
        val absolutePathImage = File(fileParentFolder, relPathEncoded.decodedURL)
            .canonicalFile
            .toPath()
            .invariantSeparatorsPathString
            .removePrefix("/")
        return parseAsImage(absolutePathImage)
    }

    private fun processNodes(nodes: List<Node>): List<ReaderItem> {
        val items = mutableListOf<ReaderItem>()
        val inlineBuffer = mutableListOf<Node>()

        fun flushInlineBuffer() {
            if (inlineBuffer.isNotEmpty()) {
                val spans = inlineBuffer.map { it.toHtmlSpan() }
                if (spans.any { it.isNotBlank() }) {
                    items.add(ReaderItem.Text(spans))
                }
                inlineBuffer.clear()
            }
        }

        for (node in nodes) {
            when (node) {
                is TextNode -> inlineBuffer.add(node)
                is Element -> {
                    val tagName = node.tagName()
                    when (tagName) {
                        in INLINE_TAGS -> inlineBuffer.add(node)
                        "p" -> {
                            flushInlineBuffer()
                            val spans = node.childNodes().map { it.toHtmlSpan() }
                            if (spans.any { it.isNotBlank() }) {
                                items.add(ReaderItem.Text(spans))
                            }
                        }
                        in listOf("img", "image") -> {
                            flushInlineBuffer()
                            items.add(declareImgEntry(node))
                        }
                        "pre" -> {
                            flushInlineBuffer()
                            items.add(ReaderItem.CodeBlock(node.wholeText()))
                        }
                        "blockquote" -> {
                            flushInlineBuffer()
                            val spans = node.childNodes().map { it.toHtmlSpan() }
                            if (spans.any { it.isNotBlank() }) {
                                items.add(ReaderItem.Blockquote(spans))
                            }
                        }
                        else -> {
                            flushInlineBuffer()
                            items.addAll(processNodes(node.childNodes()))
                        }
                    }
                }
            }
        }
        flushInlineBuffer()

        val finalItems = items.toMutableList()
        while (finalItems.isNotEmpty() && finalItems.first().isTrulyEmpty()) finalItems.removeAt(0)
        while (finalItems.isNotEmpty() && finalItems.last().isTrulyEmpty()) finalItems.removeAt(finalItems.size - 1)

        return finalItems
    }

    private fun ReaderItem.isTrulyEmpty(): Boolean = when (this) {
        is ReaderItem.Title -> false
        is ReaderItem.Text -> spans.all { !it.isNotBlank() }
        is ReaderItem.Blockquote -> spans.all { !it.isNotBlank() }
        is ReaderItem.CodeBlock -> code.isEmpty()
        is ReaderItem.Image -> false
    }

    private fun HtmlSpan.isNotBlank(): Boolean = when (this) {
        is HtmlSpan.Text -> text.any { !it.isWhitespace() && it != '\u00A0' && it != '\u200B' }
        is HtmlSpan.Tag -> name == "br" || children.any { it.isNotBlank() }
    }

    private fun Node.toHtmlSpan(): HtmlSpan = when (this) {
        is TextNode -> HtmlSpan.Text(this.wholeText)
        is Element -> HtmlSpan.Tag(
            name = this.tagName(),
            attributes = this.attributes().associate { it.key to it.value },
            children = this.childNodes().map { it.toHtmlSpan() }
        )
        else -> HtmlSpan.Text("")
    }
}

