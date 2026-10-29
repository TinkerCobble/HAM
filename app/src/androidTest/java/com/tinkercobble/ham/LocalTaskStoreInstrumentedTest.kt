package com.tinkercobble.ham

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tinkercobble.ham.data.LocalTask
import com.tinkercobble.ham.data.LocalTaskStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalTaskStoreInstrumentedTest {
    @Test fun undoChecksExactTaskStateAndKeepsUnrelatedTasks() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = LocalTaskStore(context)
        val first = LocalTask(UUID.randomUUID().toString(), "Synthetic undo first", "", false,
            System.currentTimeMillis())
        val other = LocalTask(UUID.randomUUID().toString(), "Synthetic undo other", "", false,
            System.currentTimeMillis())
        try {
            store.save(first)
            store.save(other)
            val edited = first.copy(title = "Synthetic undo edited", completed = true)
            store.save(edited)
            assertTrue(store.undo(first, edited))
            assertEquals(first, LocalTaskStore(context).list().single { it.id == first.id })
            assertFalse(store.undo(first, edited))
            store.remove(first.id)
            assertTrue(store.undo(first, null))
            assertEquals(first, LocalTaskStore(context).list().single { it.id == first.id })
            assertTrue(store.undo(null, first))
            assertTrue(LocalTaskStore(context).list().none { it.id == first.id })
            assertEquals(other, LocalTaskStore(context).list().single { it.id == other.id })
        } finally {
            store.remove(first.id)
            store.remove(other.id)
        }
    }

    @Test fun taskCreateEditCompletionAndRemovalSurviveReopenWithoutPlaintext() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = LocalTaskStore(context)
        val id = UUID.randomUUID().toString()
        val title = "Synthetic unscheduled task $id"
        val notes = "Invented local notes $id"
        val task = LocalTask(id, title, notes, false, System.currentTimeMillis())
        try {
            store.save(task)
            assertEquals(title, LocalTaskStore(context).list().single { it.id == id }.title)
            store.save(task.copy(title = "Edited $title", completed = true))
            val reopened = LocalTaskStore(context).list().single { it.id == id }
            assertTrue(reopened.completed)
            assertEquals("Edited $title", reopened.title)
            val raw = context.getSharedPreferences("ham-local-tasks", android.content.Context.MODE_PRIVATE)
                .getString("tasks", "").orEmpty()
            assertFalse(raw.contains(title))
            assertFalse(raw.contains(notes))
        } finally { store.remove(id) }
        assertTrue(LocalTaskStore(context).list().none { it.id == id })
    }
}
