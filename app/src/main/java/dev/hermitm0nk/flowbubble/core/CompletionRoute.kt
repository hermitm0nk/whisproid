package dev.hermitm0nk.flowbubble.core

/** A canceled UI destination must not discard a transcript already finalized by Gemini. */
internal enum class CompletionRoute { DELIVER, SAVE_ONLY, IGNORE }

internal fun completionRoute(activeSession: Boolean, text: String?): CompletionRoute = when {
    activeSession -> CompletionRoute.DELIVER
    !text.isNullOrBlank() -> CompletionRoute.SAVE_ONLY
    else -> CompletionRoute.IGNORE
}
