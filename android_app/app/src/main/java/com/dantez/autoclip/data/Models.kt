package com.dantez.autoclip.data

data class WordTimestamp(
    val word: String,
    val start: Double,
    val end: Double
)

data class TranscriptSegment(
    val id: Int,
    val start: Double,
    val end: Double,
    val text: String,
    val words: List<WordTimestamp> = emptyList()
)

data class PlannedClip(
    val clipId: Int,
    val start: Double,
    val end: Double,
    val duration: Double,
    val reason: String = "highlight",
    val text: String = "",
    val visualStatus: String = "Tuntas",
    val visualScore: Int = 8,
    var thumbPath: String? = null,
    val words: List<WordTimestamp> = emptyList()
)

data class KeyItem(
    val id: String,
    val key: String,
    var status: String = "active", // "active", "exhausted"
    val addedAt: Long = System.currentTimeMillis(),
    var failCount: Int = 0
)

data class ProcessLog(
    val timestamp: Long = System.currentTimeMillis(),
    val stage: String,
    val percent: Int,
    val message: String
)

data class AutoCaptionResult(
    val hookHeadline: String = "",
    val caption: String = "",
    val hashtags: List<String> = emptyList(),
    val formattedText: String = ""
)

