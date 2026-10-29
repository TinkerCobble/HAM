package com.tinkercobble.ham

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.SavedCalendarGroup
import com.tinkercobble.ham.data.SavedCalendarGroupStore
import com.tinkercobble.ham.data.SavedGroupMember
import com.tinkercobble.ham.data.SavedGroupState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SavedCalendarGroupInstrumentedTest {
    @Test fun encryptedGroupsRoundTripAndRemainScoped() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = "https://synthetic.example.invalid/${UUID.randomUUID()}"
        val name = "Synthetic family ${UUID.randomUUID()}"
        val memberId = "ics:${UUID.randomUUID()}"
        val group = SavedCalendarGroup(UUID.randomUUID().toString(), name,
            listOf(SavedGroupMember(memberId, CalendarProviderKind.ICS)))
        val state = SavedGroupState(listOf(group), group.id)
        val store = SavedCalendarGroupStore(context)
        try {
            store.save(scope, state)
            assertEquals(state, SavedCalendarGroupStore(context).load(scope))
            assertEquals(SavedGroupState(), SavedCalendarGroupStore(context).load(scope + "-other"))
            val raw = context.getSharedPreferences("ham-saved-calendar-groups", 0).all.toString()
            assertFalse(raw.contains(name))
            assertFalse(raw.contains(memberId))
            assertTrue(raw.isNotEmpty())
        } finally { store.clear(scope) }
    }
}
