package dev.hermitm0nk.flowbubble

import android.content.Intent
import android.app.UiAutomation
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermitm0nk.flowbubble.core.BubbleAccessibilityService
import dev.hermitm0nk.flowbubble.data.HistoryStore
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run again after enabling the service to check insertion into a separate package. */
@RunWith(AndroidJUnit4::class)
class OverlayInsertionTest {
    @Test fun transcriptInsertsIntoFocusedExternalEditorAndSavesHistory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // The standard connected test pass intentionally runs without accessibility.
        val enabled = Settings.Secure.getString(context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        assumeTrue("Accessibility service is not enabled by this test run",
            enabled.contains("dev.hermitm0nk.flowbubble.core.BubbleAccessibilityService"))
        // Instrumentation normally suppresses other accessibility services.
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        var service = BubbleAccessibilityService.instance
        context.startActivity(Intent().setClassName(
            "dev.hermitm0nk.flowbubble.test", "dev.hermitm0nk.flowbubble.HostActivity"
        ).putExtra("focus", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            service = BubbleAccessibilityService.instance
            val focused = automation.windows.any { window ->
                val node = window.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                node?.packageName?.toString() == "dev.hermitm0nk.flowbubble.test" && node.isEditable
            }
            if (service != null && focused) break
            Thread.sleep(200)
        }
        assertTrue("Accessibility service did not bind", service != null)
        assertTrue("Test editor never gained focus", automation.windows.any { window ->
            window.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.packageName?.toString() ==
                "dev.hermitm0nk.flowbubble.test"
        })
        service!!.onAccessibilityEvent(null)
        Thread.sleep(500)
        val phrase = "Inserted from accessibility test"
        service.onTranscript(phrase)
        var inserted = false
        val insertDeadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < insertDeadline) {
            inserted = automation.windows.any { window ->
                val editor = window.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                editor?.packageName?.toString() == "dev.hermitm0nk.flowbubble.test" &&
                    editor.text?.toString()?.contains(phrase) == true
            }
            if (inserted) break
            Thread.sleep(200)
        }
        assertTrue("Transcript was not inserted at the active cursor", inserted)
        assertTrue("Transcript was not logged locally", HistoryStore(context).use { store ->
            store.all().any { it.text == phrase }
        })
        HistoryStore(context).use { it.clear() }
    }
}
