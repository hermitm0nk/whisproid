package dev.hermitm0nk.flowbubble.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** OkHttp upgrades an HTTPS request to WebSocket; HttpUrl itself rejects wss. */
internal fun liveEndpoint(apiKey: String): HttpUrl =
    "https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        .toHttpUrl().newBuilder().addQueryParameter("key", apiKey).build()
