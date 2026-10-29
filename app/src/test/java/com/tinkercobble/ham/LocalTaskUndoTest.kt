package com.tinkercobble.ham

import com.tinkercobble.ham.data.LocalTask
import com.tinkercobble.ham.data.LocalTaskUndo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalTaskUndoTest {
    private val first = LocalTask("00000000-0000-0000-0000-000000000001", "Synthetic one", "", false, 1)
    private val other = LocalTask("00000000-0000-0000-0000-000000000002", "Synthetic two", "", false, 2)

    @Test fun createCanBeUndoneWithoutRemovingAnotherTask() {
        assertEquals(listOf(other), LocalTaskUndo.restore(listOf(other, first), null, first))
        assertNull(LocalTaskUndo.restore(listOf(other, first.copy(title = "Changed")), null, first))
    }

    @Test fun editAndCompletionUndoRequireExactCurrentState() {
        val edited = first.copy(title = "Edited", completed = true)
        assertEquals(listOf(other, first), LocalTaskUndo.restore(listOf(other, edited), first, edited))
        assertNull(LocalTaskUndo.restore(listOf(other, edited.copy(notes = "Later")), first, edited))
    }

    @Test fun removalCanBeRestoredOnlyWhileIdentityIsStillAbsent() {
        assertEquals(listOf(other, first), LocalTaskUndo.restore(listOf(other), first, null))
        assertNull(LocalTaskUndo.restore(listOf(other, first.copy(title = "Replacement")), first, null))
        assertNull(LocalTaskUndo.restore(listOf(other), first, other))
    }
}
