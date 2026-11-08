package dev.ferride.workbench

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** View: Split Editor — two groups sharing one document, model lifetime across groups. */
@RunWith(AndroidJUnit4::class)
class SplitEditorTest {
    private fun state(wide: Boolean = true) =
        WorkbenchState(FakeWorkspaceBackend(), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined).also {
            it.compactWindow = !wide
            it.start("src/main.rs")
        }

    @Test
    fun splitOpensTheSameDocumentInASecondGroup() {
        val s = state()
        assertTrue(s.commands.execute(CommandIds.SPLIT_EDITOR))
        assertEquals(2, s.groups.size)
        assertEquals("the new group is focused", 1, s.activeGroupIndex)
        val left = s.groups[0].activeTab!!
        val right = s.groups[1].activeTab!!
        assertSame(left.model, right.model)
        assertFalse("at most two groups", s.commands.execute(CommandIds.SPLIT_EDITOR))
        assertTrue(s.commands.execute(CommandIds.FOCUS_FIRST_GROUP))
        assertEquals(0, s.activeGroupIndex)
    }

    @Test
    fun phonesDontSplit() {
        val s = state(wide = false)
        assertFalse(s.commands.execute(CommandIds.SPLIT_EDITOR))
        assertEquals(1, s.groups.size)
    }

    @Test
    fun editsMarkBothTabsDirtyAndClosingOneKeepsTheDocument() {
        val s = state()
        s.splitEditor()
        val left = s.groups[0].activeTab!!
        val right = s.groups[1].activeTab!!
        right.model!!.insert("x", true)
        s.onEditorChanged(right, contentChanged = true)
        assertTrue(left.dirty && right.dirty)
        // Still open on the left: no "Save changes?" prompt, nothing freed.
        s.requestCloseTab(0, s.groups[1])
        assertNull(s.closeRequest)
        assertEquals("the empty group closes", 1, s.groups.size)
        assertEquals(0, s.activeGroupIndex)
        s.disposeRetired(left.model, s.groups[0])
        assertFalse(left.model!!.isClosed)
        assertEquals(0, s.retiredCount)
        // The last tab of a dirty document asks again.
        s.requestCloseTab(0)
        assertSame(left, s.closeRequest)
    }

    @Test
    fun aModelShownByTheOtherGroupIsNeverFreedUnderIt() {
        val s = state()
        val left = s.groups[0]
        left.activeTab!!.model!!.let { s.disposeRetired(it, left) } // the left view shows main.rs
        s.splitEditor()
        val right = s.groups[1]
        s.openFile("${s.backend.root}/web/app.ts", preview = false)
        val appModel = right.activeTab!!.model!!
        s.closeTab(0, right) // the right copy of main.rs
        s.focusGroup(0)
        val mainModel = left.activeTab!!.model!!
        s.closeTab(0, left) // main.rs is now unreferenced, but the left view still shows it
        // The right group recomposes first: it must not free what the left view shows.
        s.disposeRetired(appModel, right)
        assertFalse(mainModel.isClosed)
        // The left view switched away (its group closed): now it's freed.
        s.disposeRetired(null, left)
        assertTrue(mainModel.isClosed)
        assertFalse(appModel.isClosed)
    }

    @Test
    fun savingClearsTheDirtyDotInBothGroups() {
        val s = state()
        s.splitEditor()
        val left = s.groups[0].activeTab!!
        val right = s.groups[1].activeTab!!
        left.model!!.insert("y", true)
        s.onEditorChanged(left, contentChanged = true)
        s.save(right)
        assertFalse(left.dirty)
        assertFalse(right.dirty)
    }
}
