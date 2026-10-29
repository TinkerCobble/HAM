package com.tinkercobble.ham

import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.CalendarSourceOverview
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarSourceOverviewTest {
    @Test fun countsAndHintsNeverCollapseSources() {
        val ha = CalendarSource("calendar.synthetic", "Family events", 0)
        val android = CalendarSource("android:4", "  FAMILY  events ", 1,
            provider = CalendarProviderKind.ANDROID)
        val distinct = CalendarSource("android:5", "Work", 0,
            provider = CalendarProviderKind.ANDROID)
        val sources = listOf(ha, android, distinct)
        assertEquals(3, CalendarSourceOverview.counts(sources).total)
        assertEquals(1, CalendarSourceOverview.counts(sources).homeAssistant)
        assertEquals(2, CalendarSourceOverview.counts(sources).android)
        assertEquals(listOf(listOf(ha, android)), CalendarSourceOverview.possibleNameGroups(sources))
        assertEquals(3, sources.size)
    }

    @Test fun ambiguousAndSameProviderNamesRemainSeparateReviewGroups() {
        val ha = CalendarSource("calendar.synthetic", "Shared", 0)
        val android = CalendarSource("android:4", "Shared", 0,
            provider = CalendarProviderKind.ANDROID)
        val otherAndroid = CalendarSource("android:5", "Shared", 0,
            provider = CalendarProviderKind.ANDROID)
        assertEquals(listOf(listOf(ha, android, otherAndroid)),
            CalendarSourceOverview.possibleNameGroups(listOf(ha, android, otherAndroid)))
        assertEquals(listOf(listOf(android, otherAndroid)),
            CalendarSourceOverview.possibleNameGroups(listOf(android, otherAndroid)))
        assertEquals(emptyList<List<CalendarSource>>(),
            CalendarSourceOverview.possibleNameGroups(listOf(ha, ha.copy(id = ha.id))))
    }
}
