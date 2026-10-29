package com.tinkercobble.ham

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.provider.CalendarContract
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkercobble.ham.data.AndroidCalendarProvider
import com.tinkercobble.ham.data.DateRange
import com.tinkercobble.ham.ui.HamViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class AndroidProviderForegroundRefreshInstrumentedTest {
    private fun await(timeoutMs: Long = 45_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(200)
        }
        assertTrue("Provider change was not reflected while HAM stayed foreground", condition())
    }

    @Test fun externalProviderCreationAndRemovalRefreshForegroundAgenda() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CALENDAR)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.WRITE_CALENDAR)
        val provider = AndroidCalendarProvider(context)
        assertTrue(provider.localCalendar() == null)
        val source = provider.createLocalCalendar()
        val day = LocalDate.now()
        val title = "Synthetic foreground provider change"
        var activity: MainActivity? = null
        try {
            val started = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                as MainActivity
            activity = started
            instrumentation.waitForIdleSync()
            val model = ViewModelProvider(started)[HamViewModel::class.java]
            instrumentation.runOnMainSync { model.enableAndroidCalendars() }
            await { model.calendars.value.any { it.id == source.id } && !model.loading.value }
            instrumentation.runOnMainSync { model.setCalendarVisible(source, true) }
            await { !model.loading.value && model.events.value.none { it.summary == title } }

            // This write bypasses HAM's editor, as an account sync adapter would.
            provider.createAllDay(source, day, title)
            await { model.events.value.any { it.calendarId == source.id && it.summary == title } }

            val event = provider.events(listOf(source), DateRange(day, day.plusDays(1)))
                .single { it.summary == title }
            val eventId = event.uid!!.removePrefix("android-event:").toLong()
            assertTrue(context.contentResolver.delete(ContentUris.withAppendedId(
                CalendarContract.Events.CONTENT_URI, eventId), null, null) == 1)
            await { model.events.value.none { it.calendarId == source.id && it.summary == title } }
            assertFalse(provider.events(listOf(source), DateRange(day, day.plusDays(1)))
                .any { it.summary == title })
            instrumentation.runOnMainSync {
                model.setCalendarVisible(source, false)
                model.disableAndroidCalendars()
            }
        } finally {
            activity?.finish()
            val fixtureRows = provider.events(listOf(source), DateRange(day, day.plusDays(1)))
                .filter { it.summary == title }
            fixtureRows.forEach { event ->
                val id = event.uid!!.removePrefix("android-event:").toLong()
                context.contentResolver.delete(ContentUris.withAppendedId(
                    CalendarContract.Events.CONTENT_URI, id), null, null)
            }
            provider.removeEmptyLocalCalendar(source)
            assertTrue(provider.localCalendar() == null)
        }
    }
}
