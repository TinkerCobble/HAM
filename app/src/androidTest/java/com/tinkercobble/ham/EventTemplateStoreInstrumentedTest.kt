package com.tinkercobble.ham

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tinkercobble.ham.data.EventTemplate
import com.tinkercobble.ham.data.EventTemplateStore
import com.tinkercobble.ham.data.RepeatFrequency
import com.tinkercobble.ham.data.RepeatSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EventTemplateStoreInstrumentedTest {
    @Test fun protectedTemplateSurvivesReopenAndRemovalWithoutPlaintext() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = EventTemplateStore(context)
        val id = UUID.randomUUID().toString()
        val title = "Synthetic template $id"
        try {
            store.save(EventTemplate(id, "Synthetic", title, "Invented notes", "Test room",
                false, "09:15", "10:00", RepeatSpec(RepeatFrequency.WEEKLY, 3)))
            val reopened = EventTemplateStore(context).list().single { it.id == id }
            assertEquals(title, reopened.title)
            assertEquals("09:15", reopened.startTime)
            assertEquals(3, reopened.repeat?.count)
            val raw = context.getSharedPreferences("ham-event-templates", android.content.Context.MODE_PRIVATE)
                .getString("templates", "").orEmpty()
            assertFalse(raw.contains(title))
            assertFalse(raw.contains("Invented notes"))
            assertFalse(raw.contains("Test room"))
        } finally { store.remove(id) }
        assertTrue(EventTemplateStore(context).list().none { it.id == id })
    }
}
