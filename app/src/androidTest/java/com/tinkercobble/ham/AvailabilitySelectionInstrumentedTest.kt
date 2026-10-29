package com.tinkercobble.ham

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkercobble.ham.data.AvailabilitySelectionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AvailabilitySelectionInstrumentedTest {
    @Test fun separateBusyCalendarChoicePersistsWithoutChangingDisplaySelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = "synthetic-${UUID.randomUUID()}"
        val existing = AvailabilitySelectionStore(context, scope)
        val displayPrefs = context.getSharedPreferences("ham-android-calendars", 0)
        val oldDisplayed = displayPrefs.getStringSet("visible", emptySet()).orEmpty().toSet()
        try {
            val ids = setOf("android:987654", "calendar.synthetic_busy")
            existing.save(ids, mapOf("android:987654" to "a".repeat(64)))
            val reopened = AvailabilitySelectionStore(context, scope)
            assertTrue(reopened.isConfigured())
            assertEquals(ids, reopened.ids())
            assertEquals("a".repeat(64), reopened.androidFingerprints()["android:987654"])
            assertTrue(AvailabilitySelectionStore(context, scope + "-other").ids().isEmpty())
            assertEquals(oldDisplayed, displayPrefs.getStringSet("visible", emptySet()).orEmpty().toSet())
        } finally {
            existing.clear()
        }
    }
}
