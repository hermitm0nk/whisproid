package dev.hermitm0nk.flowbubble.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CompletionRouteTest {
    @Test fun finalizedTextSurvivesDestinationCancellationBeforeDelivery() {
        assertEquals(CompletionRoute.SAVE_ONLY, completionRoute(false, "Final words."))
        assertEquals(CompletionRoute.IGNORE, completionRoute(false, ""))
        assertEquals(CompletionRoute.IGNORE, completionRoute(false, null))
    }

    @Test fun activeSessionStillDeliversToItsAccessibilityListener() {
        assertEquals(CompletionRoute.DELIVER, completionRoute(true, "Final words."))
    }
}
