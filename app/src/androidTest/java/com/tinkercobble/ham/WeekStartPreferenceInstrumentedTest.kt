package com.tinkercobble.ham

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkercobble.ham.data.WeekStart
import com.tinkercobble.ham.data.WeekStartPreferenceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WeekStartPreferenceInstrumentedTest {
    @Test fun choiceSurvivesStoreRecreationAndOldInstallKeepsMonday() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("ham-ui-preferences", 0)
        val old = prefs.getString("start_week_on", null)
        try {
            assertTrue(prefs.edit().remove("start_week_on").commit())
            assertEquals(WeekStart.MONDAY, WeekStartPreferenceStore(context).load())
            WeekStartPreferenceStore(context).save(WeekStart.SUNDAY)
            assertEquals(WeekStart.SUNDAY, WeekStartPreferenceStore(context).load())
            WeekStartPreferenceStore(context).save(WeekStart.SYSTEM_DEFAULT)
            assertEquals(WeekStart.SYSTEM_DEFAULT, WeekStartPreferenceStore(context).load())
        } finally {
            val edit = prefs.edit()
            if (old == null) edit.remove("start_week_on") else edit.putString("start_week_on", old)
            assertTrue(edit.commit())
        }
    }
}
