package com.example.demo_epub_reader.epub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.demo_epub_reader.epub.models.EpubBook
import com.example.demo_epub_reader.epub.models.EpubChapter
import com.example.demo_epub_reader.epub.models.EpubImage
import com.example.demo_epub_reader.epub.models.ReaderItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.InputStream
import java.util.concurrent.ThreadLocalRandom
import java.util.zip.ZipFile

class EpubParser {

    companion object {
        const val TAG = "EpubParser"
    }

    data class EpubDocument(
        val metadata: Node,
        val manifest: Node,
        val spine: Node,
        val opfFilePath: String
    )

    data class EpubManifestItem(
        val id: String,
        val absPath: String,
        val mediaType: String,
        val properties: String
    )

    data class TempEpubChapter(
        val url: String,
        val title: String?,
        val body: List<ReaderItem>,
        val chapterIndex: Int
    )

    data class EpubFile(val absPath: String, val data: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as EpubFile
            if (absPath != other.absPath) return false
            return data.contentEquals(other.data)
        }
        override fun hashCode(): Int {
            var result = absPath.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    data class ChapterContent(val title: String, val body: List<ReaderItem>)

    suspend fun createEpubBook(filePath: String, shouldUseToc: Boolean): EpubBook {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Parsing EPUB file: $filePath")
            val files = getZipFilesFromFile(filePath)
            val document = createEpubDocument(files)
            parseAndCreateEbook(files, document, shouldUseToc, filePath)
        }
    }

    suspend fun createEpubBook(inputStream: InputStream, shouldUseToc: Boolean): EpubBook {
        return withContext(Dispatchers.IO) {
            val tempFile = createTempEpubFile(inputStream)
            val files = getZipFilesFromFile(tempFile.absolutePath)
            val document = createEpubDocument(files)
            parseAndCreateEbook(files, document, shouldUseToc, tempFile.absolutePath)
        }
    }

    suspend fun getChapterBody(filePath: String, chapter: EpubChapter): ChapterContent {
        return withContext(Dispatchers.IO) {
            ZipFile(filePath).use { zipFile ->
                val entries = zipFile.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .associateBy { it.name }

                val fragmentPath: String
                val fragmentId: String?
                if ('#' in chapter.absPath) {
                    val parts = chapter.absPath.split("#", limit = 2)
                    fragmentPath = parts[0]
                    fragmentId = parts[1]
                } else {
                    fragmentPath = chapter.absPath
                    fragmentId = null
                }

                val entry = entries[fragmentPath]
                    ?: return@withContext ChapterContent("", emptyList())

                val data = zipFile.getInputStream(entry).readBytes()
                val epubFiles = entries.mapValues { (path, _) ->
                    EpubFile(absPath = path, data = byteArrayOf())
                }

                val parser = EpubXMLFileParser(
                    fileAbsolutePath = fragmentPath,
                    data = data,
                    zipFile = epubFiles,
                    fragmentId = fragmentId,
                    nextFragmentId = chapter.nextFragmentId
                )
                val res = parser.parseAsDocument()
                ChapterContent(res.title ?: "", res.body)
            }
        }
    }

    suspend fun getImageData(filePath: String, imagePath: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            ZipFile(filePath).use { zipFile ->
                val entry = zipFile.getEntry(imagePath) ?: return@withContext null
                zipFile.getInputStream(entry).readBytes()
            }
        }
    }

    fun peekLanguage(filePath: String): String {
        val files = getZipFilesFromFile(filePath)
        val document = createEpubDocument(files)
        return document.metadata.selectFirstChildTag("dc:language")?.textContent ?: "en"
    }

    private fun getZipFilesFromFile(filePath: String): Map<String, EpubFile> {
        return ZipFile(filePath).use { zipFile ->
            zipFile.entries().asSequence()
                .filterNot { it.isDirectory }
                .map { entry ->
                    val content = if (isMetadataFile(entry.name)) {
                        zipFile.getInputStream(entry).readBytes()
                    } else {
                        byteArrayOf()
                    }
                    EpubFile(absPath = entry.name, data = content)
                }
                .associateBy { it.absPath }
        }
    }

    private suspend fun createTempEpubFile(inputStream: InputStream): File =
        withContext(Dispatchers.IO) {
            val file = File.createTempFile("epub_" + System.currentTimeMillis(), ".epub")
            file.deleteOnExit()
            file.outputStream().use { output -> inputStream.copyTo(output) }
            file
        }

    private suspend fun parseAndCreateEbook(
        files: Map<String, EpubFile>,
        document: EpubDocument,
        shouldUseToc: Boolean,
        filePath: String
    ): EpubBook = withContext(Dispatchers.IO) {
        val metadataTitle =
            document.metadata.selectFirstChildTag("dc:title")?.textContent ?: "Unknown Title"
        val metadataAuthor =
            document.metadata.selectFirstChildTag("dc:creator")?.textContent ?: "Unknown Author"
        val metadataLanguage =
            document.metadata.selectFirstChildTag("dc:language")?.textContent ?: "en"

        val metadataCoverId = getMetadataCoverId(document.metadata)
        val hrefRootPath = File(document.opfFilePath).parentFile ?: File("")
        val manifestItems = getManifestItems(document.manifest, hrefRootPath)

        val tocFileItem = manifestItems.values.firstOrNull {
            it.absPath.endsWith(".ncx", ignoreCase = true)
        }
        val tocNavPoints = tocFileItem?.let { navItem ->
            val tocFile = files[navItem.absPath]
            val tocDocument = tocFile?.let { parseXMLFile(it.data) }
            findNestedNavPoints((tocDocument?.selectFirstTag("navMap") as Element?))
        }

        val isTocReliable = tocNavPoints != null && tocNavPoints.size > 3 &&
                tocNavPoints.map {
                    it.selectFirstChildTag("content")?.getAttributeValue("src")
                }.distinct().size > 1

        val spineItemCount = document.spine.childElements
            .filter { it.tagName.contains("itemref") }.size
        val isTocIncomplete = tocNavPoints != null &&
                tocNavPoints.size < (spineItemCount / 2) && spineItemCount > 10

        val chapters = if (shouldUseToc && isTocReliable && !isTocIncomplete) {
            Log.d(TAG, "Parsing based on ToC file")
            parseUsingTocFile(tocNavPoints!!, hrefRootPath)
        } else {
            Log.d(TAG, "Parsing based on spine")
            parseUsingSpine(document.spine, manifestItems, files)
        }

        val images = parseImages(manifestItems, files)
        val coverImage = parseCoverImage(metadataCoverId, manifestItems, files, filePath)

        EpubBook(
            fileName = metadataTitle.asFileName(),
            title = metadataTitle,
            author = metadataAuthor,
            language = metadataLanguage,
            coverImage = coverImage,
            chapters = chapters,
            images = images,
            filePath = filePath
        )
    }

    private fun isMetadataFile(path: String): Boolean {
        return path == "META-INF/container.xml" || path.endsWith(".opf") || path.endsWith(".ncx")
    }

    @Throws(EpubParserException::class)
    private fun createEpubDocument(files: Map<String, EpubFile>): EpubDocument {
        val container = files["META-INF/container.xml"]
            ?: throw EpubParserException("META-INF/container.xml file missing")

        val opfFilePathAttr = parseXMLFile(container.data)?.selectFirstTag("rootfile")
            ?.getAttributeValue("full-path")
            ?: throw EpubParserException("Invalid container.xml file")

        val opfFilePath = opfFilePathAttr.decodedURL
        val opfFile = files[opfFilePath] ?: throw EpubParserException(".opf file missing")

        val document = parseXMLFile(opfFile.data)
            ?: throw EpubParserException(".opf file failed to parse data")
        val metadata = document.selectFirstTag("metadata")
            ?: document.selectFirstTag("opf:metadata")
            ?: throw EpubParserException(".opf file metadata section missing")
        val manifest = document.selectFirstTag("manifest")
            ?: document.selectFirstTag("opf:manifest")
            ?: throw EpubParserException(".opf file manifest section missing")
        val spine = document.selectFirstTag("spine")
            ?: document.selectFirstTag("opf:spine")
            ?: throw EpubParserException(".opf file spine section missing")

        return EpubDocument(metadata, manifest, spine, opfFilePath)
    }

    private fun getMetadataCoverId(metadata: Node): String? {
        return metadata.selectChildTag("meta")
            .ifEmpty { metadata.selectChildTag("opf:meta") }
            .find { it.getAttributeValue("name") == "cover" }?.getAttributeValue("content")
    }

    private fun getManifestItems(
        manifest: Node,
        hrefRootPath: File
    ): Map<String, EpubManifestItem> {
        return manifest.selectChildTag("item")
            .ifEmpty { manifest.selectChildTag("opf:item") }
            .map {
                EpubManifestItem(
                    id = it.getAttribute("id"),
                    absPath = it.getAttribute("href").decodedURL.hrefAbsolutePath(hrefRootPath),
                    mediaType = it.getAttribute("media-type"),
                    properties = it.getAttribute("properties")
                )
            }.associateBy { it.id }
    }

    private fun findNestedNavPoints(element: Element?): List<Element> {
        val navPoints = mutableListOf<Element>()
        if (element == null) return navPoints
        if (element.tagName == "navPoint") navPoints.add(element)
        for (child in element.childElements) navPoints.addAll(findNestedNavPoints(child))
        return navPoints
    }

    private fun generateId(): String {
        val timestamp = System.currentTimeMillis()
        val randomSuffix = ThreadLocalRandom.current().nextInt(1000, 9999)
        return timestamp.toString() + "-" + randomSuffix
    }

    private fun parseUsingTocFile(
        tocNavPoints: List<Element>,
        hrefRootPath: File
    ): List<EpubChapter> {
        return tocNavPoints.mapIndexedNotNull { index, navPoint ->
            val title = navPoint.selectFirstChildTag("navLabel")
                ?.selectFirstChildTag("text")?.textContent
            val chapterSrc = navPoint.selectFirstChildTag("content")
                ?.getAttributeValue("src")?.decodedURL?.hrefAbsolutePath(hrefRootPath)

            if (chapterSrc != null) {
                val fragmentPath = chapterSrc.substringBefore('#')
                var nextFragmentId: String? = null
                if (index < tocNavPoints.size - 1) {
                    val nextSrc = tocNavPoints[index + 1].selectFirstChildTag("content")
                        ?.getAttributeValue("src")?.decodedURL?.hrefAbsolutePath(hrefRootPath)
                    if (nextSrc != null && '#' in nextSrc) {
                        val parts = nextSrc.split("#", limit = 2)
                        if (parts[0] == fragmentPath) nextFragmentId = parts[1]
                    }
                }
                EpubChapter(
                    chapterId = generateId(),
                    absPath = chapterSrc,
                    title = title?.takeIf { it.isNotEmpty() } ?: "Chapter " + (index + 1),
                    body = emptyList(),
                    nextFragmentId = nextFragmentId
                )
            } else null
        }.toList()
    }

    private fun parseUsingSpine(
        spine: Node,
        manifestItems: Map<String, EpubManifestItem>,
        files: Map<String, EpubFile>
    ): List<EpubChapter> {
        var chapterIndex = 0
        val chapterExtensions = listOf("xhtml", "xml", "html", "htm").map { "." + it }

        return spine.selectChildTag("itemref")
            .ifEmpty { spine.selectChildTag("opf:itemref") }
            .asSequence()
            .mapNotNull { manifestItems[it.getAttribute("idref")] }
            .filter { item ->
                chapterExtensions.any {
                    item.absPath.endsWith(it, ignoreCase = true)
                } || item.mediaType.startsWith("image/")
            }
            .mapNotNull { files[it.absPath]?.let { file -> it to file } }
            .map { (item, file) ->
                if (item.mediaType.startsWith("image/")) {
                    TempEpubChapter("image_" + file.absPath, null, emptyList(), chapterIndex)
                } else {
                    val currentIndex = chapterIndex
                    chapterIndex += 1
                    // title = null means "read from HTML content at load time"
                    TempEpubChapter(file.absPath, null, emptyList(), currentIndex)
                }
            }
            .groupBy { it.chapterIndex }
            .map { (index, list) ->
                EpubChapter(
                    chapterId = generateId(),
                    absPath = list.first().url,
                    title = list.first().title ?: "",   // "" = will be replaced by HTML title
                    body = list.flatMap { it.body }
                )
            }
            .toList()
    }

    private fun parseImages(
        manifestItems: Map<String, EpubManifestItem>,
        files: Map<String, EpubFile>
    ): List<EpubImage> {
        val imageExtensions =
            listOf("gif", "raw", "png", "jpg", "jpeg", "webp", "svg").map { "." + it }
        val unlistedImages = files.asSequence().filter { (_, file) ->
            imageExtensions.any { file.absPath.endsWith(it, ignoreCase = true) }
        }.map { (_, file) -> EpubImage(absPath = file.absPath, image = null) }

        val listedImages = manifestItems.asSequence()
            .map { it.value }.filter { it.mediaType.startsWith("image") }
            .mapNotNull { files[it.absPath] }
            .map { EpubImage(absPath = it.absPath, image = null) }

        return (listedImages + unlistedImages).distinctBy { it.absPath }.toList()
    }

    private suspend fun parseCoverImage(
        metadataCoverId: String?,
        manifestItems: Map<String, EpubManifestItem>,
        files: Map<String, EpubFile>,
        filePath: String
    ): Bitmap? = withContext(Dispatchers.IO) {
        val coverImageItem = manifestItems[metadataCoverId]?.let { files[it.absPath] }
        val coverImageData = coverImageItem?.let {
            if (it.data.isNotEmpty()) {
                it.data
            } else {
                ZipFile(filePath).use { zipFile ->
                    val entry = zipFile.getEntry(it.absPath)
                    zipFile.getInputStream(entry).readBytes()
                }
            }
        }
        if (coverImageData != null) {
            BitmapFactory.decodeByteArray(coverImageData, 0, coverImageData.size)
        } else {
            Log.e(TAG, "Cover image not found")
            null
        }
    }
}

