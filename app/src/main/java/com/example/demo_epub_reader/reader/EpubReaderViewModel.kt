package com.example.demo_epub_reader.reader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.demo_epub_reader.epub.EpubParser
import com.example.demo_epub_reader.epub.models.EpubBook
import com.example.demo_epub_reader.epub.models.EpubChapter
import com.example.demo_epub_reader.epub.models.ReaderItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class ReaderUiState {
    object Idle : ReaderUiState()
    object Loading : ReaderUiState()
    data class Success(
        val book: EpubBook,
        // All items from all chapters flattened into one list
        val allItems: List<ReaderItem> = emptyList(),
        // Maps chapterIndex -> firstItemIndex in allItems (for TOC jump)
        val chapterFirstItemIndex: Map<Int, Int> = emptyMap(),
        // Maps chapterIndex -> resolved display title (real HTML title or TOC title)
        val resolvedChapterTitles: Map<Int, String> = emptyMap(),
        // Whether all chapters have been loaded
        val isFullyLoaded: Boolean = false,
        // The chapter currently visible (updated externally by scroll state)
        val visibleChapterIndex: Int = 0
    ) : ReaderUiState()
    data class Error(val message: String) : ReaderUiState()
}

class EpubReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val parser = EpubParser()

    private val _uiState = MutableStateFlow<ReaderUiState>(ReaderUiState.Idle)
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    fun loadEpubFromUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = ReaderUiState.Loading
            try {
                val context = getApplication<Application>()
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("Cannot open file")
                val book = parser.createEpubBook(inputStream, shouldUseToc = true)

                // Start with an empty Success state so UI shows immediately
                _uiState.value = ReaderUiState.Success(book = book)

                // Now load all chapters progressively
                loadAllChaptersProgressive(book)
            } catch (e: Exception) {
                _uiState.value = ReaderUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun loadAllChaptersProgressive(book: EpubBook) {
        val allItems = mutableListOf<ReaderItem>()
        val chapterFirstItemIndex = mutableMapOf<Int, Int>()
        val resolvedChapterTitles = mutableMapOf<Int, String>()

        for ((index, chapter) in book.chapters.withIndex()) {
            chapterFirstItemIndex[index] = allItems.size

            // Load chapter body — also gives us the real title from HTML (h1/h2/...)
            val chapterContent = loadChapterContent(book.filePath, chapter)

            // Prefer: TOC/NCX title > HTML heading title > "Chapter N"
            val displayTitle = when {
                chapter.title.isNotBlank() -> chapter.title
                chapterContent.title.isNotBlank() -> chapterContent.title
                else -> "Chapter ${index + 1}"
            }
            resolvedChapterTitles[index] = displayTitle

            // Add chapter title as inline header then body items
            allItems.add(ReaderItem.Title(chapterIndex = index, title = displayTitle))
            allItems.addAll(chapterContent.body)

            // Emit progress after each chapter so UI updates incrementally
            val currentState = _uiState.value as? ReaderUiState.Success ?: return
            _uiState.value = currentState.copy(
                allItems = allItems.toList(),
                chapterFirstItemIndex = chapterFirstItemIndex.toMap(),
                resolvedChapterTitles = resolvedChapterTitles.toMap(),
                isFullyLoaded = index == book.chapters.size - 1
            )
        }
    }

    fun updateVisibleChapter(itemIndex: Int) {
        val state = _uiState.value as? ReaderUiState.Success ?: return
        // Find which chapter the current first-visible item belongs to
        val chapterIndex = state.chapterFirstItemIndex.entries
            .filter { it.value <= itemIndex }
            .maxByOrNull { it.value }
            ?.key ?: 0
        if (chapterIndex != state.visibleChapterIndex) {
            _uiState.value = state.copy(visibleChapterIndex = chapterIndex)
        }
    }

    fun resetToIdle() {
        _uiState.value = ReaderUiState.Idle
    }

    fun getImageData(imagePath: String, callback: (ByteArray?) -> Unit) {
        val state = _uiState.value as? ReaderUiState.Success ?: return
        viewModelScope.launch {
            val data = parser.getImageData(state.book.filePath, imagePath)
            callback(data)
        }
    }

    private suspend fun loadChapterContent(
        filePath: String,
        chapter: EpubChapter
    ): EpubParser.ChapterContent {
        return try {
            parser.getChapterBody(filePath, chapter)
        } catch (e: Exception) {
            EpubParser.ChapterContent(title = "", body = emptyList())
        }
    }
}
