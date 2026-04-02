package com.example.demo_epub_reader.epub.models

import android.graphics.Bitmap

data class EpubBook(
    val fileName: String,
    val title: String,
    val author: String,
    val language: String,
    val coverImage: Bitmap?,
    val chapters: List<EpubChapter> = emptyList(),
    val images: List<EpubImage> = emptyList(),
    val filePath: String = ""
)

