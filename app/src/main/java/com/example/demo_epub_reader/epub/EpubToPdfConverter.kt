package com.example.demo_epub_reader.epub

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.demo_epub_reader.epub.models.EpubBook
import com.example.demo_epub_reader.epub.models.EpubChapter
import com.example.demo_epub_reader.epub.models.HtmlSpan
import com.example.demo_epub_reader.epub.models.ReaderItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipFile

class EpubToPdfConverter(private val context: Context) {

    companion object {
        private const val PAGE_WIDTH = 595      // A4 width in points (72dpi)
        private const val PAGE_HEIGHT = 842     // A4 height in points
        private const val MARGIN_H = 48f
        private const val MARGIN_V = 56f
        private const val CONTENT_WIDTH = PAGE_WIDTH - MARGIN_H * 2
        private const val BODY_TEXT_SIZE = 12f
        private const val TITLE_TEXT_SIZE = 18f
        private const val LINE_SPACING = 6f
    }

    data class ConversionProgress(val current: Int, val total: Int, val chapterTitle: String)

    // Paint objects
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = BODY_TEXT_SIZE
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }

    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = BODY_TEXT_SIZE
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }

    private val italicPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = BODY_TEXT_SIZE
        typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = TITLE_TEXT_SIZE
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }

    private val monospacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = BODY_TEXT_SIZE
        typeface = Typeface.MONOSPACE
    }

    suspend fun convert(
        epubUri: Uri,
        outputFileName: String,
        onProgress: (ConversionProgress) -> Unit
    ): Uri = withContext(Dispatchers.IO) {
        val parser = EpubParser()
        val inputStream = context.contentResolver.openInputStream(epubUri)
            ?: throw Exception("Cannot open EPUB file")
        val book = parser.createEpubBook(inputStream, shouldUseToc = true)

        val pdfDocument = PdfDocument()
        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
        var page = pdfDocument.startPage(pageInfo)
        var canvas = page.canvas
        var yOffset = MARGIN_V

        // Draw book title on first page
        yOffset = drawBookTitle(canvas, book.title, book.author, yOffset)

        for ((index, chapter) in book.chapters.withIndex()) {
            val content = try {
                parser.getChapterBody(book.filePath, chapter)
            } catch (e: Exception) {
                EpubParser.ChapterContent("", emptyList())
            }

            val displayTitle = when {
                chapter.title.isNotBlank() -> chapter.title
                content.title.isNotBlank() -> content.title
                else -> "Chapter ${index + 1}"
            }

            onProgress(ConversionProgress(index + 1, book.chapters.size, displayTitle))

            // Start new page for each chapter
            pdfDocument.finishPage(page)
            pageNumber++
            pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            page = pdfDocument.startPage(pageInfo)
            canvas = page.canvas
            yOffset = MARGIN_V

            // Draw chapter title
            yOffset = drawChapterTitle(canvas, displayTitle, yOffset)

            // Draw chapter body items
            for (item in content.body) {
                val result = drawItem(
                    item = item,
                    canvas = canvas,
                    yOffset = yOffset,
                    pdfDocument = pdfDocument,
                    currentPage = page,
                    pageNumberRef = pageNumber,
                    filePath = book.filePath
                )
                page = result.page
                canvas = result.canvas
                yOffset = result.yOffset
                pageNumber = result.pageNumber
            }
        }

        pdfDocument.finishPage(page)

        // Save PDF to Downloads
        val savedUri = savePdf(pdfDocument, outputFileName)
        pdfDocument.close()
        savedUri
    }

    private fun drawBookTitle(canvas: Canvas, title: String, author: String, startY: Float): Float {
        var y = startY + 40f
        val bigTitlePaint = Paint(titlePaint).apply { textSize = 22f }
        val authorPaint = Paint(bodyPaint).apply {
            textSize = 13f
            color = Color.DKGRAY
        }

        // Centered title
        val titleLines = wrapText(title, CONTENT_WIDTH, bigTitlePaint)
        for (line in titleLines) {
            val x = MARGIN_H + (CONTENT_WIDTH - bigTitlePaint.measureText(line)) / 2f
            canvas.drawText(line, x, y, bigTitlePaint)
            y += bigTitlePaint.textSize + LINE_SPACING
        }
        y += 8f
        val authorLines = wrapText("by $author", CONTENT_WIDTH, authorPaint)
        for (line in authorLines) {
            val x = MARGIN_H + (CONTENT_WIDTH - authorPaint.measureText(line)) / 2f
            canvas.drawText(line, x, y, authorPaint)
            y += authorPaint.textSize + LINE_SPACING
        }
        return y + 32f
    }

    private fun drawChapterTitle(canvas: Canvas, title: String, startY: Float): Float {
        var y = startY
        val lines = wrapText(title, CONTENT_WIDTH, titlePaint)
        for (line in lines) {
            if (y + titlePaint.textSize > PAGE_HEIGHT - MARGIN_V) break
            canvas.drawText(line, MARGIN_H, y, titlePaint)
            y += titlePaint.textSize + LINE_SPACING
        }
        // Underline
        val dividerPaint = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }
        canvas.drawLine(MARGIN_H, y + 4f, MARGIN_H + CONTENT_WIDTH, y + 4f, dividerPaint)
        return y + 20f
    }

    data class DrawResult(
        val page: PdfDocument.Page,
        val canvas: Canvas,
        val yOffset: Float,
        val pageNumber: Int
    )

    private fun drawItem(
        item: ReaderItem,
        canvas: Canvas,
        yOffset: Float,
        pdfDocument: PdfDocument,
        currentPage: PdfDocument.Page,
        pageNumberRef: Int,
        filePath: String
    ): DrawResult {
        var page = currentPage
        var cv = canvas
        var y = yOffset
        var pageNum = pageNumberRef

        fun newPage(): Triple<PdfDocument.Page, Canvas, Float> {
            pdfDocument.finishPage(page)
            pageNum++
            val pi = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create()
            page = pdfDocument.startPage(pi)
            cv = page.canvas
            return Triple(page, cv, MARGIN_V)
        }

        when (item) {
            is ReaderItem.Title -> {
                // Should not appear here (handled per chapter), skip
            }

            is ReaderItem.Text -> {
                val text = item.spans.spanToPlainText()
                if (text.isBlank()) return DrawResult(page, cv, y, pageNum)
                val paint = if (item.spans.isBold()) boldPaint else bodyPaint
                val lines = wrapText(text, CONTENT_WIDTH, paint)
                for (line in lines) {
                    if (y + paint.textSize > PAGE_HEIGHT - MARGIN_V) {
                        val (np, nc, ny) = newPage(); page = np; cv = nc; y = ny
                    }
                    cv.drawText(line, MARGIN_H, y, paint)
                    y += paint.textSize + LINE_SPACING
                }
                y += 4f
            }

            is ReaderItem.Blockquote -> {
                val text = item.spans.spanToPlainText()
                if (text.isBlank()) return DrawResult(page, cv, y, pageNum)
                val quotePaint = Paint(italicPaint).apply { color = Color.DKGRAY }
                val lines = wrapText(text, CONTENT_WIDTH - 24f, quotePaint)
                val barPaint = Paint().apply { color = Color.GRAY; strokeWidth = 3f }
                for (line in lines) {
                    if (y + quotePaint.textSize > PAGE_HEIGHT - MARGIN_V) {
                        val (np, nc, ny) = newPage(); page = np; cv = nc; y = ny
                    }
                    cv.drawLine(MARGIN_H + 4f, y - quotePaint.textSize, MARGIN_H + 4f, y + 4f, barPaint)
                    cv.drawText(line, MARGIN_H + 20f, y, quotePaint)
                    y += quotePaint.textSize + LINE_SPACING
                }
                y += 4f
            }

            is ReaderItem.CodeBlock -> {
                val lines = item.code.lines()
                val bgPaint = Paint().apply { color = Color.parseColor("#F5F5F5") }
                val startY = y
                val totalHeight = lines.size * (monospacePaint.textSize + LINE_SPACING) + 16f
                if (y + totalHeight > PAGE_HEIGHT - MARGIN_V) {
                    val (np, nc, ny) = newPage(); page = np; cv = nc; y = ny
                }
                cv.drawRect(MARGIN_H - 4f, y - monospacePaint.textSize, MARGIN_H + CONTENT_WIDTH + 4f, y + totalHeight, bgPaint)
                for (line in lines) {
                    cv.drawText(line.take(80), MARGIN_H + 4f, y, monospacePaint)
                    y += monospacePaint.textSize + LINE_SPACING
                }
                y += 8f
            }

            is ReaderItem.Image -> {
                try {
                    val imageBytes = ZipFile(filePath).use { zip ->
                        zip.getEntry(item.path)?.let { zip.getInputStream(it).readBytes() }
                    } ?: return DrawResult(page, cv, y, pageNum)

                    val bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        ?: return DrawResult(page, cv, y, pageNum)

                    val maxW = CONTENT_WIDTH
                    val maxH = PAGE_HEIGHT * 0.5f
                    val scale = minOf(maxW / bmp.width, maxH / bmp.height, 1f)
                    val dstW = (bmp.width * scale).toInt()
                    val dstH = (bmp.height * scale).toInt()

                    if (y + dstH > PAGE_HEIGHT - MARGIN_V) {
                        val (np, nc, ny) = newPage(); page = np; cv = nc; y = ny
                    }
                    val scaled = Bitmap.createScaledBitmap(bmp, dstW, dstH, true)
                    cv.drawBitmap(scaled, MARGIN_H, y, null)
                    y += dstH + 12f
                } catch (_: Exception) {}
            }
        }

        return DrawResult(page, cv, y, pageNum)
    }

    private fun wrapText(text: String, maxWidth: Float, paint: Paint): List<String> {
        val result = mutableListOf<String>()
        for (paragraph in text.split("\n")) {
            val words = paragraph.split(" ")
            var currentLine = StringBuilder()
            for (word in words) {
                val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                if (paint.measureText(testLine) <= maxWidth) {
                    currentLine = StringBuilder(testLine)
                } else {
                    if (currentLine.isNotEmpty()) result.add(currentLine.toString())
                    // Handle very long single words
                    currentLine = StringBuilder(word)
                }
            }
            if (currentLine.isNotEmpty()) result.add(currentLine.toString())
        }
        return result.ifEmpty { listOf("") }
    }

    private fun List<HtmlSpan>.spanToPlainText(): String {
        val sb = StringBuilder()
        fun visit(span: HtmlSpan) {
            when (span) {
                is HtmlSpan.Text -> sb.append(span.text)
                is HtmlSpan.Tag -> {
                    if (span.name == "br") sb.append("\n")
                    else span.children.forEach { visit(it) }
                }
            }
        }
        forEach { visit(it) }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun List<HtmlSpan>.isBold(): Boolean = any { span ->
        span is HtmlSpan.Tag && (span.name == "b" || span.name == "strong")
    }

    private fun savePdf(pdfDocument: PdfDocument, fileName: String): Uri {
        val safeFileName = fileName.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_") + ".pdf"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeFileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues
            ) ?: throw Exception("Cannot create PDF file in Downloads")
            context.contentResolver.openOutputStream(uri)?.use { output ->
                pdfDocument.writeTo(output)
            }
            uri
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()
            val file = File(downloadsDir, safeFileName)
            file.outputStream().use { pdfDocument.writeTo(it) }
            Uri.fromFile(file)
        }
    }
}

