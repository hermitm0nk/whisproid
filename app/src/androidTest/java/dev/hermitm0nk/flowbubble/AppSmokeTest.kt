package dev.hermitm0nk.flowbubble

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
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
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("Whisproid")).check(matches(isDisplayed()))
            onView(withText("API key and bubble appearance")).perform(click())
            onView(withText("Google AI Studio API key")).check(matches(isDisplayed()))
            onView(withText("View transcript history")).perform(click())
            onView(withText("Transcript history")).check(matches(isDisplayed()))
        }
    }
}
