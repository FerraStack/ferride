package dev.ferride.workbench

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Markdown: Open Preview — only for Markdown, one preview per file, reads the open editor's text. */
@RunWith(AndroidJUnit4::class)
class MarkdownPreviewTest {
    private fun state() = WorkbenchState(FakeWorkspaceBackend(), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        .also { it.start("README.md") }

    @Test
    fun previewOpensForMarkdownOnly() {
        val s = state()
        assertTrue(s.commands.execute(CommandIds.MARKDOWN_PREVIEW))
        val preview = s.activeTab!!
        assertEquals(TabKind.MarkdownPreview, preview.kind)
        assertEquals("Preview README.md", preview.name)
        // The preview reads the open editor's (unsaved) text.
        val source = s.tabs.first { it.kind == TabKind.File }
        assertSame(source.model, s.previewSourceModel(preview))
        // Opening it again focuses the same tab.
        s.activeIndex = 0
        s.commands.execute(CommandIds.MARKDOWN_PREVIEW)
        assertEquals(2, s.tabs.size)
        // Not for other languages.
        s.openFile("${s.backend.root}/src/main.rs", preview = false)
        assertFalse(s.commands.execute(CommandIds.MARKDOWN_PREVIEW))
    }

    @Test
    fun previewOfAClosedFileReadsItFromDisk() {
        val s = state()
        s.commands.execute(CommandIds.MARKDOWN_PREVIEW)
        val preview = s.activeTab!!
        s.closeTab(0)
        assertEquals(null, s.previewSourceModel(preview))
        assertTrue(s.readPreviewSource(preview).startsWith("# hello-ferride"))
    }
}
