package dev.hermitm0nk.flowbubble.core

/** Tracks Gemini's authoritative final transcription segments across button release. */
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
    // Gemini may finalize an utterance during a pause before activityEnd. Give
    // late frames a settling window, then accept that final even if no more
    // server event follows release.
    fun canCommitAfterSettling(): Boolean = endSent && segments.isNotEmpty()
    fun canCommitOnClose(code: Int): Boolean = code == 1000 && canCommitAfterSettling()
    fun text(): String = segments.joinToString(" ").trim()
}
