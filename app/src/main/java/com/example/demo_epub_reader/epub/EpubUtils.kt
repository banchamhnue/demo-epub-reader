package com.example.demo_epub_reader.epub

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.invariantSeparatorsPathString
import org.jsoup.nodes.Node as JsoupNode

fun parseXMLFile(inputStream: InputStream): Document? =
    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(inputStream)

fun parseXMLFile(byteArray: ByteArray): Document? = parseXMLFile(byteArray.inputStream())

val String.decodedURL: String get() = URLDecoder.decode(this, "UTF-8")

fun String.asFileName(): String = this.replace("/", "_")

fun String.hrefAbsolutePath(hrefRootPath: File): String {
    return File(hrefRootPath, this).canonicalFile
        .toPath()
        .invariantSeparatorsPathString
        .removePrefix("/")
}

fun Document.selectFirstTag(tag: String): Node? = getElementsByTagName(tag).item(0)

fun Node.selectFirstChildTag(tag: String) = childElements.find { it.tagName == tag }

fun Node.selectChildTag(tag: String) = childElements.filter { it.tagName == tag }

fun Node.getAttributeValue(attribute: String): String? =
    attributes?.getNamedItem(attribute)?.textContent

val NodeList.elements
    get() = (0 until length).asSequence().mapNotNull { item(it) as? Element }.toList()

val Node.childElements get() = childNodes.elements

fun JsoupNode.nextSiblingNodes(): List<JsoupNode> {
    val siblings = mutableListOf<JsoupNode>()
    var nextSibling = nextSibling()
    while (nextSibling != null) {
        siblings.add(nextSibling)
        nextSibling = nextSibling.nextSibling()
    }
    return siblings
}

fun String.smartTrim(): String = this.trim { it <= ' ' || it == '\u00A0' }

