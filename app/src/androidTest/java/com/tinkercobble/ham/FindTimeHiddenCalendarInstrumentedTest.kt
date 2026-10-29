package com.tinkercobble.ham

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkercobble.ham.data.AndroidCalendarProvider
import com.tinkercobble.ham.data.AvailabilitySelectionStore
import com.tinkercobble.ham.data.FindTime
import com.tinkercobble.ham.ui.HamViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class FindTimeHiddenCalendarInstrumentedTest {
    @Test fun hiddenAndroidCalendarStillBlocksAvailabilityWhenExplicitlyConsidered() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val provider = AndroidCalendarProvider(context)
        if (!provider.canRead()) instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName, Manifest.permission.READ_CALENDAR)
        if (!provider.canWrite()) instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName, Manifest.permission.WRITE_CALENDAR)
        val androidPrefs = context.getSharedPreferences("ham-android-calendars", 0)
        val beforeEnabled = androidPrefs.getBoolean("enabled", false)
        val beforeVisible = androidPrefs.getStringSet("visible", emptySet()).orEmpty().toSet()
        val availability = AvailabilitySelectionStore(context, "android-device")
        val beforeConfigured = availability.isConfigured()
        val beforeIds = availability.ids()
        val beforeFingerprints = availability.androidFingerprints()
        var createdId: Long? = null
        try {
            assumeTrue(provider.localCalendar() == null)
            val source = provider.createLocalCalendar()
            createdId = source.id.removePrefix("android:").toLong()
            val day = LocalDate.now().plusDays(1)
            val title = "Synthetic availability fixture"
            provider.createAllDay(source, day, title)
            assertTrue(androidPrefs.edit().putBoolean("enabled", true)
                .putStringSet("visible", emptySet()).commit())
            availability.save(setOf(source.id),
                mapOf(source.id to requireNotNull(source.identityFingerprint)))
            lateinit var model: HamViewModel
            instrumentation.runOnMainSync {
                model = HamViewModel(context.applicationContext as Application)
                model.selectedDate.value = day
                model.refresh()
            }
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline &&
                (model.loadedRange.value?.contains(day) != true || model.loading.value)) {
                Thread.sleep(100)
            }
            assertTrue("Selected busy source was not refreshed", model.loadedRange.value?.contains(day) == true)
            assertFalse("Hidden event leaked into agenda", model.visibleEvents.any { it.summary == title })
            assertTrue("Hidden event was omitted from Find Time",
                model.availabilityEvents.any { it.summary == title })
            assertTrue("Hidden all-day event did not block suggestions",
                FindTime.suggest(day, model.availabilityEvents, LocalTime.of(9, 0),
                    LocalTime.of(17, 0), 60, 0).isEmpty())
        } finally {
            createdId?.let { id ->
                val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME,
                        "com.tinkercobble.ham.local")
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE,
                        CalendarContract.ACCOUNT_TYPE_LOCAL).build()
                assertTrue(context.contentResolver.delete(ContentUris.withAppendedId(uri, id),
                    null, null) == 1)
                assertTrue(provider.localCalendar() == null)
                context.contentResolver.query(CalendarContract.Events.CONTENT_URI,
                    arrayOf(CalendarContract.Events._ID),
                    "${CalendarContract.Events.CALENDAR_ID}=?", arrayOf(id.toString()), null)?.use {
                    assertTrue("Synthetic event remained after calendar removal", it.count == 0)
                }
            }
            androidPrefs.edit().putBoolean("enabled", beforeEnabled)
                .putStringSet("visible", beforeVisible).commit()
            if (beforeConfigured) availability.save(beforeIds, beforeFingerprints)
            else availability.clear()
        }
    }
}
