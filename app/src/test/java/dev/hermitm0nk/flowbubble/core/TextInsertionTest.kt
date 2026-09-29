package dev.hermitm0nk.flowbubble.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TextInsertionTest {
    @Test fun hintIsNeverPrependedToEmptyMessage() {
        assertEquals("", editableText("Message", "Message", true, 0, 0))
        assertEquals("", editableText("Message", "Message", false, -1, -1))
        assertEquals("", editableText("Message", "Message", false, 0, 0))
        assertEquals(Insertion("Hello", 5), composeInsertion(editableText("Message", "Message", false, -1, -1), 0, 0, "Hello"))
        assertEquals("Message", editableText("Message", "Message", false, 7, 7))
    }
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
