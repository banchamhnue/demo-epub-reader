package com.example.demo_epub_reader.epub.models

data class EpubImage(
    val absPath: String,
    val image: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EpubImage
        if (absPath != other.absPath) return false
        if (image != null) {
            if (other.image == null) return false
            if (!image.contentEquals(other.image)) return false
        } else if (other.image != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = absPath.hashCode()
        result = 31 * result + (image?.contentHashCode() ?: 0)
        return result
    }
}

