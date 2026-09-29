package dev.hermitm0nk.flowbubble

import android.graphics.Bitmap
import android.content.ContentValues
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.SeekBar
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermitm0nk.flowbubble.data.HistoryStore
import dev.hermitm0nk.flowbubble.data.SettingsStore
import dev.hermitm0nk.flowbubble.ui.MainActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun reset() {
        SettingsStore(context).apiKey = ""
        HistoryStore(context).use { it.clear() }
    }

    @Test fun keyIsRecoverableButNotSavedAsPlaintext() {
        val settings = SettingsStore(context)
        settings.apiKey = "test-key-not-a-real-key"
        assertEquals("test-key-not-a-real-key", SettingsStore(context).apiKey)
        val prefs = context.getSharedPreferences("settings", 0)
        assertFalse(prefs.all.values.any { it.toString().contains("test-key-not-a-real-key") })
    }

    @Test fun historyPersistsAndCanDeleteIndividualRecords() {
        HistoryStore(context).use { store ->
            store.clear()
            val first = store.add("first transcript")
            val second = store.add("second transcript")
            assertEquals(listOf("second transcript", "first transcript"), HistoryStore(context).use { it.all().map { e -> e.text } })
            store.delete(first)
            assertEquals(listOf(second), store.all().map { it.id })
        }
    }

    @Test fun mainScreenLaunchesSettingsAndHistory() {
        HistoryStore(context).use { it.add("Screenshot sample transcript") }
        ActivityScenario.launch(MainActivity::class.java).use {
            it.onActivity { activity -> assertNotNull(findText(activity.window.decorView, "Whisproid")) }
            captureScreenshot("home")
            it.onActivity { activity ->
                findText(activity.window.decorView, "API key and bubble appearance")!!.performClick()
                assertNotNull(findText(activity.window.decorView, "Google AI Studio API key"))
            }
            captureScreenshot("settings")
            it.onActivity { activity ->
                findText(activity.window.decorView, "View transcript history")!!.performClick()
                assertNotNull(findText(activity.window.decorView, "Transcript history"))
                assertNotNull(findText(activity.window.decorView, "Screenshot sample transcript"))
            }
            captureScreenshot("history")
        }
    }

    @Test fun bubbleSlidersUpdateLabelsAndPersistValues() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                findText(activity.window.decorView, "API key and bubble appearance")!!.performClick()
                val sliders = findSeekBars(activity.window.decorView)
                assertEquals(2, sliders.size)
                sliders[0].progress = 28
                sliders[1].progress = 45
                assertNotNull(findText(activity.window.decorView, "Size: 76 dp"))
                assertNotNull(findText(activity.window.decorView, "Opacity: 70%"))
                assertEquals(76, SettingsStore(context).bubbleSizeDp)
                assertEquals(.70f, SettingsStore(context).bubbleAlpha, .001f)
            }
        }
        SettingsStore(context).bubbleSizeDp = 58
        SettingsStore(context).bubbleAlpha = .72f
    }

    private fun findSeekBars(view: View): List<SeekBar> =
        (if (view is SeekBar) listOf(view) else emptyList()) +
            (if (view is ViewGroup) (0 until view.childCount).flatMap { findSeekBars(view.getChildAt(it)) } else emptyList())

    private fun findText(view: View, value: String): TextView? {
        if (view is TextView && view.text.toString() == value) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findText(view.getChildAt(i), value)?.let { return it }
        }
        return null
    }

    private fun captureScreenshot(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: throw AssertionError("Could not capture $name screenshot")
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "whisproid-$name.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Whisproid")
            }) ?: throw AssertionError("Could not create $name screenshot")
        context.contentResolver.openOutputStream(uri)!!.use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Could not save $name screenshot" }
        }
        bitmap.recycle()
    }
}
