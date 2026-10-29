package com.tinkercobble.ham

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkercobble.ham.data.AgendaCache
import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.CachedAgenda
import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.IcsSubscriptions
import com.tinkercobble.ham.data.WidgetAgenda
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class HamAgendaWidgetInstrumentedTest {
    private fun snapshot(context: android.content.Context): WidgetAgenda? {
        val method = HamAgendaWidget::class.java.getDeclaredMethod("snapshot", android.content.Context::class.java)
        method.isAccessible = true
        return method.invoke(HamAgendaWidget(), context) as WidgetAgenda?
    }

    @Test fun cachedSelectedFeedDisappearsWhenSubscriptionIsRemoved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true))
        assumeFalse(File(context.filesDir, "agenda-cache-v1.bin").exists())
        val cache = AgendaCache(context)
        val store = IcsSubscriptions(context)
        val prefs = context.getSharedPreferences("ham-ics-selection", 0)
        val oldVisible = prefs.getStringSet("visible", null)?.toSet()
        val id = store.save("Synthetic widget feed", "https://example.invalid/widget.ics", "", "")
        try {
            val source = CalendarSource("ics:$id", "Synthetic widget feed", 0,
                provider = CalendarProviderKind.ICS)
            val day = LocalDate.now()
            val event = AgendaEvent(source.id, source.name, "Synthetic widget event",
                day.toString(), day.plusDays(1).toString(), true, "", "", null, null,
                CalendarProviderKind.ICS)
            assertTrue(prefs.edit().putStringSet("visible", setOf(source.id)).commit())
            cache.save("android-device", CachedAgenda(listOf(source), listOf(event)), day)
            assertEquals(1, snapshot(context)?.total)
            store.remove(id)
            assertEquals(0, snapshot(context)?.total)
        } finally {
            store.remove(id)
            cache.clear()
            val edit = prefs.edit()
            if (oldVisible == null) edit.remove("visible") else edit.putStringSet("visible", oldVisible)
            assertTrue(edit.commit())
        }
    }
}
