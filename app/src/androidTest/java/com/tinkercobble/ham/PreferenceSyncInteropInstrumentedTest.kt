package com.tinkercobble.ham

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkercobble.ham.data.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID
import java.io.File

/** Opt-in test against a temporary synthetic loopback service; never uses a personal profile. */
@RunWith(AndroidJUnit4::class)
class PreferenceSyncInteropInstrumentedTest {
    @Test fun pinnedTlsPairingSignedOperationsIdempotenceAndConflict() {
        val arguments = InstrumentationRegistry.getArguments()
        val url = arguments.getString("sync_test_url").orEmpty()
        val fingerprint = arguments.getString("sync_test_fingerprint").orEmpty()
        val code = arguments.getString("sync_test_code").orEmpty()
        assumeTrue("Synthetic local server arguments are required",
            url.isNotBlank() && fingerprint.isNotBlank() && code.isNotBlank())
        val alias = "ham-preference-sync-interop-test-v1"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connection = ProtectedPreferenceSyncConnection(context)
        assertNull("Synthetic test requires an unpaired app", connection.load())
        val journalFile = File(context.filesDir, "preference-sync-v1.bin")
        assertFalse("Synthetic test requires an empty journal", journalFile.exists())
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse("Synthetic identity should start empty", keyStore.containsAlias(alias))
        try {
            val client = PreferenceSyncHttps(PreferenceSyncEndpoint(url, fingerprint),
                KeystorePreferenceSyncIdentity(alias))
            val request = client.requestPairing(code, "Synthetic test device")
            val enrolled = client.completePairing(request)
            val initial = client.exchange(enrolled.profileId, enrolled.deviceId, 0, emptyList())
            assertEquals(0, initial.headRevision)
            val op = PreferenceChange(UUID.randomUUID().toString(), "global:week_start", 0,
                SyncSettingValue.Text("SUNDAY"))
            val first = client.exchange(enrolled.profileId, enrolled.deviceId, 0, listOf(op))
            assertEquals(SyncOperationResult.Status.APPLIED, first.results.single().status)
            assertEquals(SyncSettingValue.Text("SUNDAY"), first.changes.single().value)
            val wrongFingerprint = if (fingerprint.startsWith("a")) "b".repeat(64) else "a".repeat(64)
            try {
                PreferenceSyncHttps(PreferenceSyncEndpoint(url, wrongFingerprint),
                    KeystorePreferenceSyncIdentity(alias)).exchange(enrolled.profileId,
                    enrolled.deviceId, 0, emptyList())
                fail("A changed server certificate fingerprint must block sync")
            } catch (_: javax.net.ssl.SSLException) { }
            assertEquals(first.results.single(), client.exchange(enrolled.profileId,
                enrolled.deviceId, 0, listOf(op)).results.single())
            val conflict = client.exchange(enrolled.profileId, enrolled.deviceId, first.headRevision,
                listOf(PreferenceChange(UUID.randomUUID().toString(), "global:week_start", 0,
                    SyncSettingValue.Text("MONDAY"))))
            assertEquals(SyncOperationResult.Status.CONFLICT, conflict.results.single().status)
            // The actual settings adapter must pull an existing choice, seed a
            // missing setting, then transmit a real local preference change.
            connection.save(PreferenceSyncConnection(PreferenceSyncEndpoint(url, fingerprint),
                enrolled.profileId, enrolled.deviceId))
            val adapter = PreferenceSyncGlobalSettings(context, KeystorePreferenceSyncIdentity(alias))
            val pulled = adapter.exchange(WeekStart.MONDAY, CalendarView.WEEK)
            assertEquals(WeekStart.SUNDAY, pulled.weekStart)
            assertEquals(CalendarView.WEEK, pulled.view)
            assertTrue(adapter.recordWeekStart(WeekStart.MONDAY))
            val updated = adapter.exchange(WeekStart.MONDAY, CalendarView.WEEK)
            assertEquals(WeekStart.MONDAY, updated.weekStart)
            assertEquals(0, updated.pending)
            assertEquals(0, updated.conflicts)
            val readBack = client.exchange(enrolled.profileId, enrolled.deviceId, 0, emptyList())
            assertTrue(readBack.changes.any { it.key == "global:week_start" &&
                it.value == SyncSettingValue.Text("MONDAY") })
        } finally {
            connection.clear()
            journalFile.delete()
            File(context.filesDir, "preference-sync-v1.bin.bak").delete()
            keyStore.deleteEntry(alias)
        }
    }
}
