package dev.hermitm0nk.flowbubble.core

/** Tracks final segments and whether the server confirmed the released utterance. */
internal class TranscriptionCompletion {
    private val segments = mutableListOf<String>()
    private var endSent = false
    private var confirmedAfterEnd = false

    fun markEndSent() { endSent = true }
    fun addFinal(text: String): Boolean {
        if (text.isBlank()) return false
        segments.add(text.trim())
        if (endSent) confirmedAfterEnd = true
        return endSent
    }
    fun markTurnComplete(): Boolean {
        if (endSent) confirmedAfterEnd = true
        return endSent
    }
    fun canCommit(): Boolean = endSent && confirmedAfterEnd && segments.isNotEmpty()
    fun canCommitOnClose(code: Int): Boolean = code == 1000 && canCommit()
    fun text(): String = segments.joinToString(" ").trim()
}
