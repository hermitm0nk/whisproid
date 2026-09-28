package dev.hermitm0nk.flowbubble.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveEndpointTest {
    @Test fun constructsAWebSocketUpgradableUrlAndEncodesTheKey() {
        val url = liveEndpoint("test key&value")
        assertEquals("https", url.scheme)
        assertEquals("generativelanguage.googleapis.com", url.host)
        assertEquals("/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent", url.encodedPath)
        assertEquals("test key&value", url.queryParameter("key"))
        assertEquals("test%20key%26value", url.encodedQuery?.substringAfter("key="))
    }
}
