package com.tinkercobble.ham

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkercobble.ham.data.CalendarPreferenceStore
import com.tinkercobble.ham.data.CalendarPreferences
import com.tinkercobble.ham.data.CalendarSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeAssistantSelectionMigrationInstrumentedTest {
    @Test fun existingSessionMigrationSurvivesRestartAndCompletesOnlyAfterDiscovery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("ham-calendar-preferences", 0)
        val initializedKey = "ha_selection_migration_initialized"
        val pendingKey = "ha_legacy_session_pending"
        val initializedWasSet = prefs.contains(initializedKey)
        val pendingWasSet = prefs.contains(pendingKey)
        val initialized = prefs.getBoolean(initializedKey, false)
        val pending = prefs.getBoolean(pendingKey, false)
        try {
            assertTrue(prefs.edit().remove(initializedKey).remove(pendingKey).commit())
            val first = CalendarPreferenceStore(context)
            assertTrue(first.prepareHomeAssistantSelectionMigration(existingSession = true))
            val restarted = CalendarPreferenceStore(context)
            assertTrue(restarted.prepareHomeAssistantSelectionMigration(existingSession = false))
            val priorChoice = CalendarPreferences(hidden = setOf("ha:previously-hidden"))
            val migrated = CalendarSelection.hideNewHomeAssistantCalendars(priorChoice,
                setOf("ha:previously-hidden", "ha:previously-visible"), null, emptySet(),
                preserveLegacySession = true)
            assertEquals(priorChoice.hidden, migrated.hidden)
            restarted.completeHomeAssistantSelectionMigration()
            assertFalse(CalendarPreferenceStore(context).prepareHomeAssistantSelectionMigration(false))
            val later = CalendarSelection.hideNewHomeAssistantCalendars(migrated,
                setOf("ha:previously-hidden", "ha:previously-visible", "ha:new"),
                setOf("ha:previously-hidden", "ha:previously-visible"), emptySet())
            assertEquals(setOf("ha:previously-hidden", "ha:new"), later.hidden)
        } finally {
            val edit = prefs.edit()
            if (initializedWasSet) edit.putBoolean(initializedKey, initialized)
            else edit.remove(initializedKey)
            if (pendingWasSet) edit.putBoolean(pendingKey, pending)
            else edit.remove(pendingKey)
            assertTrue(edit.commit())
        }
    }
}
