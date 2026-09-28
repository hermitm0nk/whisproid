package dev.hermitm0nk.flowbubble.core

import org.junit.Assert.*
import org.junit.Test

class TranscriptionCompletionTest {
    @Test fun earlierFinalIsPartialUntilServerConfirmsReleasedSpeech() {
        val completion = TranscriptionCompletion()
        completion.addFinal("First sentence.")
        completion.markTurnComplete()
        completion.markEndSent()
        assertFalse(completion.canCommit())
        assertEquals("First sentence.", completion.text())
    }

    @Test fun postReleaseFinalMayCompleteWithoutSeparateTurnMarker() {
        val completion = TranscriptionCompletion()
        completion.markEndSent()
        assertTrue(completion.addFinal("Sky is blue."))
        assertTrue(completion.canCommit())
        assertFalse(completion.canCommitOnClose(1008))
        assertTrue(completion.canCommitOnClose(1000))
    }

    @Test fun turnMarkerAfterReleaseConfirmsEarlierFinal() {
        val completion = TranscriptionCompletion()
        completion.addFinal("Complete text.")
        completion.markEndSent()
        assertTrue(completion.markTurnComplete())
        assertTrue(completion.canCommit())
    }
}
