package com.tinkercobble.ham

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkercobble.ham.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProtectedPreferenceSyncApprovalsInstrumentedTest {
    @Test fun exactReviewedIdentitySurvivesRestartButReusedRowCannotInheritIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "preference-sync-approvals-v1.bin")
        val connection = ProtectedPreferenceSyncConnection(context)
        assertFalse("Synthetic test requires empty approval storage", file.exists())
        assertNull("Synthetic test requires an unpaired installation", connection.load())
        val first = CalendarSource("android:7", "Appointments", 1,
            provider = CalendarProviderKind.ANDROID, identityFingerprint = "a".repeat(64),
            logicalIdentity = "android-provider:v1:${"b".repeat(64)}")
        val similar = first.copy(id = "android:8", identityFingerprint = "c".repeat(64),
            logicalIdentity = "android-provider:v1:${"d".repeat(64)}")
        try {
            connection.save(PreferenceSyncConnection(PreferenceSyncEndpoint(
                "https://sync.example.invalid/", "a".repeat(64)),
                UUID.randomUUID().toString(), UUID.randomUUID().toString()))
            val approvals = ProtectedPreferenceSyncApprovals(context)
            approvals.approve(first, listOf(first, similar))
            assertEquals(first, ProtectedPreferenceSyncApprovals(context)
                .resolved(listOf(first, similar))[first.logicalIdentity])
            assertEquals(1, ProtectedPreferenceSyncApprovals(context)
                .resolved(listOf(first, similar)).size)
            assertTrue(ProtectedPreferenceSyncApprovals(context)
                .resolved(listOf(first.copy(identityFingerprint = "e".repeat(64)), similar)).isEmpty())
            assertTrue(ProtectedPreferenceSyncApprovals(context)
                .resolved(listOf(first.copy(id = "android:9"), similar)).isEmpty())
            assertTrue(ProtectedPreferenceSyncApprovals(context)
                .resolved(listOf(first, first.copy(id = "android:9"))).isEmpty())
            assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("Appointments"))
        } finally {
            connection.clear()
            file.delete()
            File(context.filesDir, "preference-sync-approvals-v1.bin.bak").delete()
        }
    }
}
