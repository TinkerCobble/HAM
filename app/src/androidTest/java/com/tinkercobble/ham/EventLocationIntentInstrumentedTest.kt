package com.tinkercobble.ham

import android.content.Intent
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventLocationIntentInstrumentedTest {
    @Test
    fun createsChooserAroundGeoViewIntent() {
        val chooser = EventLocationIntents.chooser("Northstar Community Hall")

        assertNotNull(chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)
        assertEquals("Open location with", chooser.getStringExtra(Intent.EXTRA_TITLE))
        val target = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)
        assertNotNull(target)
        assertEquals(Intent.ACTION_VIEW, target!!.action)
        assertEquals("geo:0,0?q=Northstar%20Community%20Hall", target.dataString)
        assertEquals(null, target.`package`)
    }
}
