package com.tinkercobble.ham

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkercobble.ham.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class ProtectedPreferenceSyncJournalInstrumentedTest {
    @Test fun repeatedOfflineChoiceWaitsForStableAcknowledgementAndSurvivesRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "preference-sync-v1.bin")
        assertFalse("Test storage should begin empty", file.exists())
        try {
            val journal = ProtectedPreferenceSyncJournal(context)
            val first = PreferenceChange(UUID.randomUUID().toString(), "global:week_start", 0,
                SyncSettingValue.Text("SUNDAY"))
            val latest = PreferenceChange(UUID.randomUUID().toString(), "global:week_start", 0,
                SyncSettingValue.Text("MONDAY"))
            journal.stage(first)
            journal.stage(latest)
            assertEquals(listOf(first), ProtectedPreferenceSyncJournal(context).pending())
            journal.apply(SyncExchange(listOf(SyncOperationResult(first.id,
                SyncOperationResult.Status.APPLIED, 3)),
                listOf(RemotePreference(first.key, 3, first.value)), 3))
            assertEquals(listOf(latest.copy(baseRevision = 3)),
                ProtectedPreferenceSyncJournal(context).pending())
            journal.apply(SyncExchange(listOf(SyncOperationResult(latest.id,
                SyncOperationResult.Status.APPLIED, 4)),
                listOf(RemotePreference(latest.key, 4, latest.value)), 4))
            assertTrue(ProtectedPreferenceSyncJournal(context).pending().isEmpty())
            assertEquals(latest.value, ProtectedPreferenceSyncJournal(context).current(latest.key)?.value)
        } finally {
            file.delete()
            File(context.filesDir, "preference-sync-v1.bin.bak").delete()
        }
    }

    @Test fun conflictingFirstChoiceRetainsLatestChoiceForReview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "preference-sync-v1.bin")
        assertFalse("Test storage should begin empty", file.exists())
        try {
            val journal = ProtectedPreferenceSyncJournal(context)
            val first = PreferenceChange(UUID.randomUUID().toString(), "global:week_start", 0,
                SyncSettingValue.Text("SUNDAY"))
            val latest = PreferenceChange(UUID.randomUUID().toString(), "global:week_start", 0,
                SyncSettingValue.Text("MONDAY"))
            journal.stage(first)
            journal.stage(latest)
            journal.apply(SyncExchange(listOf(SyncOperationResult(first.id,
                SyncOperationResult.Status.CONFLICT, 5)),
                listOf(RemotePreference(first.key, 5, SyncSettingValue.Text("SYSTEM_DEFAULT"))), 5))
            val restarted = ProtectedPreferenceSyncJournal(context)
            assertTrue(restarted.pending().isEmpty())
            assertEquals(latest.value, restarted.conflicts().single().local.value)
            assertEquals(SyncSettingValue.Text("SYSTEM_DEFAULT"), restarted.conflicts().single().remote?.value)
            restarted.resolveConflict(latest.id, keepLocal = false)
            assertTrue(ProtectedPreferenceSyncJournal(context).conflicts().isEmpty())
        } finally {
            file.delete()
            File(context.filesDir, "preference-sync-v1.bin.bak").delete()
        }
    }

    @Test fun reviewedLocalConflictUsesFreshIdAndRemoteRevision() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "preference-sync-v1.bin")
        assertFalse("Test storage should begin empty", file.exists())
        try {
            val journal = ProtectedPreferenceSyncJournal(context)
            val local = PreferenceChange(UUID.randomUUID().toString(), "global:week_start", 0,
                SyncSettingValue.Text("SUNDAY"))
            journal.stage(local)
            journal.apply(SyncExchange(listOf(SyncOperationResult(local.id,
                SyncOperationResult.Status.CONFLICT, 7)),
                listOf(RemotePreference(local.key, 7, SyncSettingValue.Text("MONDAY"))), 7))
            journal.resolveConflict(local.id, keepLocal = true)
            val retry = ProtectedPreferenceSyncJournal(context).pending().single()
            assertNotEquals(local.id, retry.id)
            assertEquals(7, retry.baseRevision)
            assertEquals(local.value, retry.value)
            assertTrue(ProtectedPreferenceSyncJournal(context).conflicts().isEmpty())
        } finally {
            file.delete()
            File(context.filesDir, "preference-sync-v1.bin.bak").delete()
        }
    }

    @Test fun androidKeystoreSignatureVerifiesWithExportedPublicKey() {
        val alias = "ham-preference-sync-crypto-test-v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse("Test signing identity should begin empty", store.containsAlias(alias))
        try {
            val identity = KeystorePreferenceSyncIdentity(alias)
            val pem = identity.publicKeyPem()
            val encoded = pem.substringAfter("-----BEGIN PUBLIC KEY-----")
                .substringBefore("-----END PUBLIC KEY-----").filterNot(Char::isWhitespace)
            val publicKey = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encoded)))
            val message = PreferenceSyncRequest.canonical("POST", "/v1/sync", 1790000000,
                "0123456789abcdef", "{}".toByteArray())
            val signature = Base64.getUrlDecoder().decode(identity.sign(message))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(message)
            assertTrue(verifier.verify(signature))
        } finally { store.deleteEntry(alias) }
    }

    @Test fun queuePersistsAcrossInstancesAndConflictSurvivesRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "preference-sync-v1.bin")
        assertFalse("Test storage should begin empty", file.exists())
        try {
            val first = ProtectedPreferenceSyncJournal(context)
            val change = PreferenceChange(UUID.randomUUID().toString(), "global:week_start", 0,
                SyncSettingValue.Text("SUNDAY"))
            first.stage(change)
            assertEquals(change, ProtectedPreferenceSyncJournal(context).pending().single())
            first.apply(SyncExchange(listOf(SyncOperationResult(change.id,
                SyncOperationResult.Status.CONFLICT, 2)),
                listOf(RemotePreference("global:week_start", 2,
                    SyncSettingValue.Text("MONDAY"))), 2))
            val restarted = ProtectedPreferenceSyncJournal(context)
            assertTrue(restarted.pending().isEmpty())
            assertEquals(2, restarted.remoteRevision())
            assertEquals(change, restarted.conflicts().single().local)
            assertEquals(SyncSettingValue.Text("MONDAY"), restarted.current("global:week_start")?.value)
            assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("SUNDAY"))
        } finally {
            file.delete()
            File(context.filesDir, "preference-sync-v1.bin.bak").delete()
        }
    }
}
