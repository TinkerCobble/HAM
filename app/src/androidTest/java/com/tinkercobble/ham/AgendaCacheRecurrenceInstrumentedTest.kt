package com.tinkercobble.ham

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkercobble.ham.data.AgendaCache
import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.CachedAgenda
import com.tinkercobble.ham.data.CalendarSource
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgendaCacheRecurrenceInstrumentedTest {
    @Test fun recurrenceMetadataSurvivesEncryptedCacheRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = AgendaCache(context)
        val source = CalendarSource("calendar.synthetic", "Synthetic", 1 or 2 or 4)
        val recurring = AgendaEvent(source.id, source.name, "Synthetic series", "2026-11-12",
            "2026-11-13", true, "", "", "synthetic-series", null,
            rrule = "FREQ=DAILY", recurrenceKnown = true)
        val ordinary = recurring.copy(summary = "Synthetic one-off", uid = "synthetic-one-off", rrule = null)
        val server = "https://synthetic.example.test"
        try {
            cache.save(server, CachedAgenda(listOf(source), listOf(recurring, ordinary)),
                LocalDate.parse("2026-11-12"))
            val raw = File(context.filesDir, "agenda-cache-v1.bin").readBytes()
            assertFalse(raw.toString(Charsets.UTF_8).contains("Synthetic series"))
            val reopened = AgendaCache(context).load(server)!!.events
            assertEquals("FREQ=DAILY", reopened.single { it.uid == "synthetic-series" }.rrule)
            assertTrue(reopened.single { it.uid == "synthetic-series" }.recurrenceKnown)
            assertEquals(null, reopened.single { it.uid == "synthetic-one-off" }.rrule)
            assertTrue(reopened.single { it.uid == "synthetic-one-off" }.recurrenceKnown)
        } finally { cache.clear() }
    }
}
