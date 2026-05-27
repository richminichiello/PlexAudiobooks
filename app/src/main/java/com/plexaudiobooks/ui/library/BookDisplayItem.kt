package com.plexaudiobooks.ui.library

data class BookDisplayItem(
    val ratingKey: String,
    val title: String,
    val author: String?,
    val thumbPath: String?,
    val durationMs: Long,
    val progressPercent: Float,
    val isDownloaded: Boolean = false
)
