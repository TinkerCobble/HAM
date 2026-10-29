package com.tinkercobble.ham

import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.DateRange
import com.tinkercobble.ham.data.mergeCachedCalendarRead
import com.tinkercobble.ham.data.retainUnverifiedPartialDav
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialDavRefreshTest {
    private fun event(uid: String, title: String, provider: CalendarProviderKind =
        CalendarProviderKind.CALDAV) = AgendaEvent("dav:synthetic", "Synthetic", title,
        "2026-11-12", "2026-11-13", true, "", "", uid, null, provider)

    @Test fun partialReadShowsValidNewEventWithoutDroppingUnverifiedCache() {
        val oldVersion = event("known", "Before")
        val oldUnverified = event("unreadable", "Retain until complete refresh")
        val unrelated = event("other", "Home Assistant", CalendarProviderKind.HOME_ASSISTANT)
        val newVersion = event("known", "After")
        val range = DateRange(LocalDate.parse("2026-11-12"), LocalDate.parse("2026-11-13"))
        val retained = retainUnverifiedPartialDav(listOf(oldVersion, oldUnverified, unrelated),
            listOf(newVersion))
        val shown = mergeCachedCalendarRead(retained, listOf(newVersion), emptySet(), range)
        assertEquals(setOf("After", "Retain until complete refresh", "Home Assistant"),
            shown.map { it.summary }.toSet())
        assertTrue(shown.none { it.summary == "Before" })
    }

    @Test fun noUidAndOtherProviderCannotReplaceCachedDav() {
        val prior = event("known", "Before")
        val noUid = event("", "Unknown").copy(uid = null)
        val other = event("known", "Other", CalendarProviderKind.HOME_ASSISTANT)
        assertEquals(listOf(prior), retainUnverifiedPartialDav(listOf(prior), listOf(noUid, other)))
    }

}
