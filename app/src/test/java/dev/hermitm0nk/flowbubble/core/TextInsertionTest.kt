package dev.hermitm0nk.flowbubble.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TextInsertionTest {
    @Test fun replacesSelectedTextWithoutDiscardingSurroundingContent() {
        assertEquals(Insertion("Draft revised today", 13), composeInsertion("Draft old today", 6, 9, "revised"))
    }
    @Test fun insertsAtCursorAndLeavesItAfterDictation() {
        assertEquals(Insertion("Hello world!", 11), composeInsertion("Hello!", 5, 5, "world"))
    }
    @Test fun supportsUnicodeTextWithoutTrimmingUserWords() {
        assertEquals(Insertion("Привет мир", 10), composeInsertion("Привет", 6, 6, "мир"))
    }
}
