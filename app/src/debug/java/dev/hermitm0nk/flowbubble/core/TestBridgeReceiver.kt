package dev.hermitm0nk.flowbubble.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.hermitm0nk.flowbubble.data.HistoryStore

/** Shell-only test hook in debug builds; normal apps do not hold DUMP permission. */
class TestBridgeReceiver : BroadcastReceiver() {
    companion object {
        const val INSERT = "dev.hermitm0nk.flowbubble.TEST_INSERT"
        const val HISTORY = "dev.hermitm0nk.flowbubble.TEST_HISTORY"
        const val AUDIO = "dev.hermitm0nk.flowbubble.TEST_AUDIO"
        const val PHRASE = "Inserted from accessibility test"
    }

    override fun onReceive(context: Context, intent: Intent) {
        resultCode = when (intent.action) {
            INSERT -> {
                val service = BubbleAccessibilityService.instance
                if (service == null) 2 else { service.onTranscript(PHRASE); 1 }
            }
            HISTORY -> if (HistoryStore(context).use { store -> store.all().any { it.text == PHRASE } }) 1 else 2
            AUDIO -> try {
                val pcm = context.assets.open("synthetic.pcm").use { it.readBytes() }
                if (pcm.isEmpty() || pcm.size % 2 != 0) 2 else {
                    MicrophoneService.syntheticPcmForNextSession = pcm
                    1
                }
            } catch (_: Exception) { 2 }
            else -> 0
        }
    }
}
