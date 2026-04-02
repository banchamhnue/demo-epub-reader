package com.example.demo_epub_reader.epub.models

data class EpubChapter(
    val chapterId: String,
    val absPath: String,
    val title: String,
    val body: List<ReaderItem> = emptyList(),
    val nextFragmentId: String? = null
)

