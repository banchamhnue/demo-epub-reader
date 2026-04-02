package com.example.demo_epub_reader.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.demo_epub_reader.epub.models.EpubChapter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListScreen(
    chapters: List<EpubChapter>,
    currentChapterIndex: Int,
    resolvedChapterTitles: Map<Int, String> = emptyMap(),
    onChapterClick: (Int) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Table of Contents") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            contentPadding = paddingValues,
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(chapters) { index, chapter ->
                val isSelected = index == currentChapterIndex
                // Use resolved title (real HTML/TOC title) if available
                val displayTitle = resolvedChapterTitles[index]
                    ?.takeIf { it.isNotBlank() }
                    ?: chapter.title.takeIf { it.isNotBlank() }
                    ?: "Chapter ${index + 1}"
                ListItem(
                    headlineContent = {
                        Text(
                            text = displayTitle,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = if (isSelected)
                                MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.primary
                                )
                            else MaterialTheme.typography.bodyLarge
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onChapterClick(index) }
                        .padding(horizontal = 4.dp),
                    colors = ListItemDefaults.colors(
                        containerColor = if (isSelected)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else MaterialTheme.colorScheme.surface
                    )
                )
                HorizontalDivider()
            }
        }
    }
}
