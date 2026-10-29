package com.tinkercobble.ham

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkercobble.ham.data.IcsSubscriptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class IcsSubscriptionStoreInstrumentedTest {
    @Test fun encryptsPrivateUrlAndCredentialsAcrossReopenThenRemovesOnlyChosenFeed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = IcsSubscriptions(context)
        val unique = UUID.randomUUID().toString()
        val firstUrl = "https://example.test/$unique.ics?access=synthetic-private"
        val secondUrl = "https://other.example.test/$unique.ics"
        val first = store.save("Synthetic private feed", firstUrl, "synthetic-reader", "synthetic-password")
        val second = store.save("Synthetic public feed", secondUrl, "", "")
        try {
            val raw = context.getSharedPreferences("ham-ics-subscriptions", 0)
                .getString("items", null)
            assertNotNull(raw)
            assertFalse(raw!!.contains(firstUrl))
            assertFalse(raw.contains("synthetic-password"))
            val reopened = IcsSubscriptions(context)
            assertEquals(firstUrl, reopened.all().single { it.id == first }.url)
            assertEquals("synthetic-password", reopened.all().single { it.id == first }.password)
            assertEquals(2, reopened.summaries().count { it.id == first || it.id == second })
            reopened.remove(first)
            assertTrue(reopened.all().none { it.id == first })
            assertTrue(reopened.all().any { it.id == second })
        } finally {
            store.remove(first)
            store.remove(second)
        }
    }
}
