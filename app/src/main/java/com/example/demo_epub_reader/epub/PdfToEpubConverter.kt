package com.example.demo_epub_reader.epub

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PdfToEpubConverter(private val context: Context) {

    data class ConversionProgress(val current: Int, val total: Int, val info: String)

    suspend fun convert(
        pdfUri: Uri,
        outputFileName: String,
        onProgress: (ConversionProgress) -> Unit
    ): Uri = withContext(Dispatchers.IO) {

        // Open the PDF
        val pfd = context.contentResolver.openFileDescriptor(pdfUri, "r")
            ?: throw Exception("Cannot open PDF file")

        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val pageCount = renderer.pageCount
                if (pageCount == 0) throw Exception("PDF has no pages")

                onProgress(ConversionProgress(0, pageCount, "Reading PDF…"))

                // Extract each page as text via bitmap → OCR is not available without ML Kit,
                // so we render each page as a PNG image embedded in the EPUB HTML.
                // This faithfully preserves layout — same approach many converters use.
                val pages = mutableListOf<PageData>()

                for (i in 0 until pageCount) {
                    onProgress(
                        ConversionProgress(
                            i + 1,
                            pageCount,
                            "Rendering page ${i + 1}/$pageCount"
                        )
                    )
                    val page = renderer.openPage(i)
                    val scale = 2f   // 2x for readable resolution (~150 dpi on A4)
                    val bitmapW = (page.width * scale).toInt()
                    val bitmapH = (page.height * scale).toInt()
                    val bitmap = Bitmap.createBitmap(bitmapW, bitmapH, Bitmap.Config.ARGB_8888)

                    // Fill white background first
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)

                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()

                    // Compress to JPEG for smaller file size
                    val bos = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bos)
                    bitmap.recycle()

                    pages.add(PageData(index = i, imageBytes = bos.toByteArray()))
                }

                onProgress(ConversionProgress(pageCount, pageCount, "Building EPUB…"))

                val epubUri = buildEpub(outputFileName, pages)
                epubUri
            }
        }
    }

    data class PageData(val index: Int, val imageBytes: ByteArray)

    private fun buildEpub(bookTitle: String, pages: List<PageData>): Uri {
        val safeTitle = bookTitle.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
        val epubFileName = "$safeTitle.epub"

        val outputStream: java.io.OutputStream
        val savedUri: Uri

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, epubFileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/epub+zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            savedUri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv
            ) ?: throw Exception("Cannot create EPUB file in Downloads")
            outputStream = context.contentResolver.openOutputStream(savedUri)
                ?: throw Exception("Cannot open output stream")
        } else {
            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()
            val file = File(downloadsDir, epubFileName)
            savedUri = Uri.fromFile(file)
            outputStream = file.outputStream()
        }

        ZipOutputStream(outputStream.buffered()).use { zip ->

            // ── mimetype (must be first, uncompressed) ──────────────────────
            val mimeBytes = "application/epub+zip".toByteArray()
            writeStoredEntry(zip, "mimetype", mimeBytes)

            // ── META-INF/container.xml ──────────────────────────────────────
            writeEntry(zip, "META-INF/container.xml", containerXml())

            // ── OEBPS/content.opf ──────────────────────────────────────────
            writeEntry(zip, "OEBPS/content.opf", contentOpf(bookTitle, pages.size))

            // ── OEBPS/toc.ncx ─────────────────────────────────────────────
            writeEntry(zip, "OEBPS/toc.ncx", tocNcx(bookTitle, pages.size))

            // ── OEBPS/css/style.css ────────────────────────────────────────
            writeEntry(zip, "OEBPS/css/style.css", stylesheet())

            // ── One HTML + one image per page ─────────────────────────────
            for (page in pages) {
                val num = page.index + 1
                // Image
                writeEntryBytes(zip, "OEBPS/images/page_$num.jpg", page.imageBytes)
                // HTML wrapper
                writeEntry(zip, "OEBPS/page_$num.xhtml", pageHtml(num))
            }
        }

        return savedUri
    }

    // ─── ZipOutputStream helpers ────────────────────────────────────────────

    private fun writeStoredEntry(zip: ZipOutputStream, name: String, data: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = data.size.toLong()
            compressedSize = data.size.toLong()
            val crc = java.util.zip.CRC32().also { it.update(data) }
            this.crc = crc.value
        }
        zip.putNextEntry(entry)
        zip.write(data)
        zip.closeEntry()
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        val data = content.toByteArray(Charsets.UTF_8)
        zip.putNextEntry(ZipEntry(name))
        zip.write(data)
        zip.closeEntry()
    }

    private fun writeEntryBytes(zip: ZipOutputStream, name: String, data: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(data)
        zip.closeEntry()
    }

    // ─── EPUB XML content ────────────────────────────────────────────────────

    private fun containerXml() = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

    private fun contentOpf(title: String, pageCount: Int): String {
        val safeTitle = title.replace("&", "&amp;").replace("<", "&lt;")
        val manifestItems = buildString {
            // CSS
            appendLine("""    <item id="css" href="css/style.css" media-type="text/css"/>""")
            for (i in 1..pageCount) {
                appendLine("""    <item id="page_$i" href="page_$i.xhtml" media-type="application/xhtml+xml"/>""")
                appendLine("""    <item id="img_$i" href="images/page_$i.jpg" media-type="image/jpeg"/>""")
            }
        }
        val spineItems = buildString {
            for (i in 1..pageCount) {
                appendLine("""    <itemref idref="page_$i"/>""")
            }
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="book-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:title>$safeTitle</dc:title>
    <dc:language>en</dc:language>
    <dc:identifier id="book-id">urn:uuid:${java.util.UUID.randomUUID()}</dc:identifier>
  </metadata>
  <manifest>
$manifestItems  </manifest>
  <spine toc="ncx">
$spineItems  </spine>
</package>"""
    }

    private fun tocNcx(title: String, pageCount: Int): String {
        val safeTitle = title.replace("&", "&amp;").replace("<", "&lt;")
        val navPoints = buildString {
            for (i in 1..pageCount) {
                appendLine(
                    """    <navPoint id="page_$i" playOrder="$i">
      <navLabel><text>Page $i</text></navLabel>
      <content src="page_$i.xhtml"/>
    </navPoint>"""
                )
            }
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="urn:uuid:${java.util.UUID.randomUUID()}"/>
    <meta name="dtb:depth" content="1"/>
    <meta name="dtb:totalPageCount" content="0"/>
    <meta name="dtb:maxPageNumber" content="0"/>
  </head>
  <docTitle><text>$safeTitle</text></docTitle>
  <navMap>
$navPoints  </navMap>
</ncx>"""
    }

    private fun stylesheet() = """
body { margin: 0; padding: 0; background: #fff; }
.page { width: 100%; text-align: center; page-break-after: always; }
.page img { max-width: 100%; height: auto; display: block; margin: 0 auto; }
"""

    private fun pageHtml(pageNum: Int) = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
  <title>Page $pageNum</title>
  <link rel="stylesheet" type="text/css" href="css/style.css"/>
</head>
<body>
  <div class="page">
    <img src="images/page_$pageNum.jpg" alt="Page $pageNum"/>
  </div>
</body>
</html>"""
}

