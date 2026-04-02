package com.example.demo_epub_reader.reader

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.demo_epub_reader.epub.asAnnotatedString
import com.example.demo_epub_reader.epub.models.ReaderItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    state: ReaderUiState.Success,
    onUpdateVisibleChapter: (Int) -> Unit,
    onBack: () -> Unit,
    onGetImageData: (String, (ByteArray?) -> Unit) -> Unit
) {
    var showToc by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Track which chapter is currently visible based on scroll position
    LaunchedEffect(listState.firstVisibleItemIndex) {
        onUpdateVisibleChapter(listState.firstVisibleItemIndex)
    }

    val visibleChapter = state.book.chapters.getOrNull(state.visibleChapterIndex)
    // Use resolved title for TopAppBar subtitle
    val visibleChapterTitle = state.resolvedChapterTitles[state.visibleChapterIndex]
        ?.takeIf { it.isNotBlank() }
        ?: visibleChapter?.title?.takeIf { it.isNotBlank() }
        ?: ""

    if (showToc) {
        ChapterListScreen(
            chapters = state.book.chapters,
            currentChapterIndex = state.visibleChapterIndex,
            resolvedChapterTitles = state.resolvedChapterTitles,
            onChapterClick = { index ->
                showToc = false
                val targetItemIndex = state.chapterFirstItemIndex[index] ?: 0
                coroutineScope.launch {
                    listState.scrollToItem(targetItemIndex)
                }
            },
            onBack = { showToc = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.book.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                        visibleChapter?.let {
                            if (visibleChapterTitle.isNotEmpty()) {
                                Text(
                                    text = visibleChapterTitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showToc = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = "Table of Contents"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        when {
            state.allItems.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Loading book…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            else -> {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(
                            items = state.allItems,
                            key = { index, _ -> index }
                        ) { _, item ->
                            ReaderItemView(
                                item = item,
                                onGetImageData = onGetImageData
                            )
                        }

                        // Show loading indicator at bottom while still loading
                        if (!state.isFullyLoaded) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                        Text(
                                            text = "Loading chapters…",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReaderItemView(
    item: ReaderItem,
    onGetImageData: (String, (ByteArray?) -> Unit) -> Unit
) {
    when (item) {
        is ReaderItem.Title -> {
            // Chapter title header — rendered inline just like Myne
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp, bottom = 16.dp)
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        lineHeight = 32.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        is ReaderItem.Text -> {
            val annotatedString = remember(item.spans) {
                item.spans.asAnnotatedString()
            }
            Text(
                text = annotatedString,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp),
                modifier = Modifier.fillMaxWidth()
            )
        }

        is ReaderItem.Image -> {
            EpubImageView(
                imagePath = item.path,
                aspectRatio = item.yrel,
                onGetImageData = onGetImageData
            )
        }

        is ReaderItem.CodeBlock -> {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = item.code,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        is ReaderItem.Blockquote -> {
            val annotatedString = remember(item.spans) {
                item.spans.asAnnotatedString()
            }
            IntrinsicSize.Min.let {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .padding(vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = annotatedString,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 28.sp
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun EpubImageView(
    imagePath: String,
    aspectRatio: Float,
    onGetImageData: (String, (ByteArray?) -> Unit) -> Unit
) {
    var imageData by remember(imagePath) { mutableStateOf<ByteArray?>(null) }

    LaunchedEffect(imagePath) {
        onGetImageData(imagePath) { data ->
            imageData = data
        }
    }

    val clampedRatio = if (aspectRatio > 0f) aspectRatio else 1.45f

    imageData?.let { bytes ->
        val bitmap = remember(bytes) {
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f / clampedRatio)
            )
        }
    } ?: Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f / clampedRatio)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
    }
}

