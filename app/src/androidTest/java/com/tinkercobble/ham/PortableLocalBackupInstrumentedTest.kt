package com.tinkercobble.ham

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tinkercobble.ham.data.EventTemplate
import com.tinkercobble.ham.data.EventTemplateStore
import com.tinkercobble.ham.data.LocalBackupContent
import com.tinkercobble.ham.data.LocalTask
import com.tinkercobble.ham.data.LocalTaskStore
import com.tinkercobble.ham.data.PortableLocalBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PortableLocalBackupInstrumentedTest {
    @Test fun encryptedBackupRoundTripRejectsWrongPassphraseAndTampering() {
        val id = UUID.randomUUID().toString()
        val title = "Synthetic backup task $id"
        val task = LocalTask(id, title, "Invented task notes", false, System.currentTimeMillis())
        val template = EventTemplate(UUID.randomUUID().toString(), "Synthetic", "Invented event",
            "Invented description", "Test room", true, "", "", null)
        val password = "Synthetic passphrase for test".toCharArray()
        val file = PortableLocalBackup.encrypt(LocalBackupContent(listOf(task), listOf(template)), password)
        assertFalse(String(file, Charsets.ISO_8859_1).contains(title))
        val restored = PortableLocalBackup.decrypt(file, password)
        assertEquals(task, restored.tasks.single())
        assertEquals(template, restored.templates.single())
        assertTrue(runCatching { PortableLocalBackup.decrypt(file, "Incorrect passphrase".toCharArray()) }.isFailure)
        val tampered = file.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertTrue(runCatching { PortableLocalBackup.decrypt(tampered, password) }.isFailure)
        password.fill('\u0000')
    }

    @Test fun importMergeKeepsExistingContentAndRejectsChangedIdentity() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val taskStore = LocalTaskStore(context)
        val templateStore = EventTemplateStore(context)
        val id = UUID.randomUUID().toString()
        val templateId = UUID.randomUUID().toString()
        val task = LocalTask(id, "Synthetic import task", "", false, System.currentTimeMillis())
        val template = EventTemplate(templateId, "Synthetic", "Synthetic event", "", "", true, "", "", null)
        try {
            val beforeTasks = taskStore.list()
            val beforeTemplates = templateStore.list()
            assertEquals(1, taskStore.additions(listOf(task)))
            assertEquals(1, templateStore.additions(listOf(template)))
            assertEquals(1, taskStore.merge(listOf(task)))
            assertEquals(1, templateStore.merge(listOf(template)))
            assertEquals(0, taskStore.merge(listOf(task)))
            assertEquals(0, templateStore.merge(listOf(template)))
            assertTrue(runCatching { taskStore.merge(listOf(task.copy(title = "Changed"))) }.isFailure)
            assertTrue(runCatching { templateStore.merge(listOf(template.copy(title = "Changed"))) }.isFailure)
            assertEquals(beforeTasks.size + 1, taskStore.list().size)
            assertEquals(beforeTemplates.size + 1, templateStore.list().size)
        } finally {
            taskStore.remove(id)
            templateStore.remove(templateId)
        }
    }
}
