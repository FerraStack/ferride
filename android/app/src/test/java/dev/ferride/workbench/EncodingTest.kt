package dev.ferride.workbench

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** #15: files keep the encoding they were read in; Reopen/Save with Encoding. */
@RunWith(AndroidJUnit4::class)
class EncodingTest {
    private fun state() = WorkbenchState(
        FakeWorkspaceBackend(encodingOf = mapOf("src/main.rs" to "windows1252")),
        CoroutineScope(Dispatchers.Unconfined),
        Dispatchers.Unconfined,
    ).also { it.start("src/main.rs") }

    @Test
    fun theDetectedEncodingIsKept() {
        val s = state()
        val tab = s.activeTab!!
        assertEquals("windows1252", tab.encoding)
        assertEquals("Western (Windows 1252)", tab.encodingLabel)
        s.openFile("${s.backend.root}/README.md", preview = false)
        assertEquals("utf8", s.activeTab!!.encoding)
    }

    @Test
    fun saveWithEncodingGoesThroughTheQuickPicks() {
        val s = state()
        assertTrue(s.commands.execute(CommandIds.CHANGE_ENCODING))
        val actions = s.paletteItems(emptyList())
        assertEquals(listOf("Reopen with Encoding", "Save with Encoding"), actions.map { it.label })
        actions[1].run()
        val encodings = s.paletteItems(emptyList())
        assertEquals("Western (Windows 1252)", encodings.first { it.description?.endsWith("Current") == true }.label)
        encodings.first { it.label == "Japanese (Shift JIS)" }.run()
        assertEquals("shiftjis", s.activeTab!!.encoding)
        assertEquals(false, s.paletteOpen)
    }

    @Test
    fun reopenReplacesTheModelButNotWithUnsavedChanges() {
        val s = state()
        val tab = s.activeTab!!
        tab.model!!.insert("x", true)
        s.onEditorChanged(tab, contentChanged = true)
        s.reopenWithEncoding(tab, "utf8")
        assertTrue(s.message!!.contains("before reopening"))
        assertEquals("windows1252", s.activeTab!!.encoding)
        tab.model!!.markSaved()
        s.onEditorChanged(tab, contentChanged = false)
        s.reopenWithEncoding(tab, "utf8")
        assertEquals("utf8", s.activeTab!!.encoding)
        assertNotSame(tab.model, s.activeTab!!.model)
    }
}
