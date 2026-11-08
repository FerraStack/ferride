package dev.ferride.workbench

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explorer: new file/folder, rename, delete with confirm, copy path, reveal, context menu. */
@RunWith(AndroidJUnit4::class)
class ExplorerActionsTest {
    private fun state(): WorkbenchState =
        WorkbenchState(FakeWorkspaceBackend(), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined).also {
            it.start("src/main.rs")
        }

    private fun WorkbenchState.names(dir: String = backend.root) = tree[dir].orEmpty().map { it.name }
    private fun WorkbenchState.node(path: String) = tree[path.substringBeforeLast('/')]!!.first { it.path == path }

    @Test
    fun newFileGoesIntoTheActiveFilesFolderAndOpens() {
        val s = state()
        val src = "${s.backend.root}/src"
        assertTrue(s.commands.execute(CommandIds.EXPLORER_NEW_FILE))
        val edit = s.explorerEdit!!
        assertEquals("next to the active file", src, edit.parent)
        assertTrue(src in s.expanded)
        assertEquals("A file or folder name must be provided.", s.validateName(edit, " "))
        assertEquals(
            "A file or folder **lib.rs** already exists at this location. Please choose a different name.",
            s.validateName(edit, "lib.rs"),
        )
        assertNotNull(s.validateName(edit, "../escape.rs"))
        s.commitExplorerEdit("util.rs")
        assertNull(s.explorerEdit)
        assertTrue("util.rs" in s.names(src))
        assertEquals("$src/util.rs", s.activeTab!!.path)
        assertFalse("created files open pinned", s.activeTab!!.preview)
    }

    @Test
    fun nestedNamesCreateFolders() {
        val s = state()
        s.startNewFile(s.backend.root)
        s.commitExplorerEdit("docs/guide/intro.md")
        assertTrue("docs" in s.names())
        assertEquals("${s.backend.root}/docs/guide/intro.md", s.activeTab!!.path)
        s.startNewFolder(s.backend.root)
        s.commitExplorerEdit("assets")
        assertTrue("assets" in s.names())
    }

    @Test
    fun renameMovesOpenEditors() {
        val s = state()
        val main = "${s.backend.root}/src/main.rs"
        s.toggleDir("${s.backend.root}/src")
        s.startRename(s.node(main))
        assertEquals("main.rs", s.explorerEdit!!.initial)
        assertNull("keeping the same name is fine", s.validateName(s.explorerEdit!!, "main.rs"))
        s.commitExplorerEdit("app.rs")
        assertTrue("app.rs" in s.names("${s.backend.root}/src"))
        assertFalse("main.rs" in s.names("${s.backend.root}/src"))
        assertEquals("${s.backend.root}/src/app.rs", s.activeTab!!.path)
        assertEquals("app.rs", s.activeTab!!.name)
    }

    @Test
    fun deleteAsksFirstThenClosesEditorsInside() {
        val s = state()
        s.toggleDir("${s.backend.root}/src")
        val src = s.node("${s.backend.root}/src")
        s.requestDelete(src)
        assertEquals(1, s.tabs.size)
        assertEquals("nothing happens before confirming", src, s.deleteRequest)
        s.confirmDelete()
        assertNull(s.deleteRequest)
        assertFalse("src" in s.names())
        assertTrue("its open file was closed", s.tabs.isEmpty())
    }

    @Test
    fun contextMenuDiffersForFilesAndFolders() {
        val s = state()
        s.toggleDir("${s.backend.root}/src")
        s.openExplorerMenu(s.node("${s.backend.root}/src"), 10f, 20f)
        assertEquals(
            listOf("New File…", "New Folder…", "Copy Path", "Copy Relative Path", "Rename…", "Delete"),
            s.contextMenu!!.items.map { it.label },
        )
        s.openExplorerMenu(s.node("${s.backend.root}/src/main.rs"), 10f, 20f)
        val items = s.contextMenu!!.items
        assertEquals(listOf("Copy Path", "Copy Relative Path", "Rename…", "Delete"), items.map { it.label })
        var copied: String? = null
        s.setClipboard = { copied = it }
        items.first { it.label == "Copy Relative Path" }.onClick()
        assertEquals("src/main.rs", copied)
        items.first { it.label == "Copy Path" }.onClick()
        assertEquals("${s.backend.root}/src/main.rs", copied)
    }

    @Test
    fun revealActiveFileExpandsItsFolders() {
        val s = state()
        s.openFile("${s.backend.root}/web/app.ts", preview = false)
        s.collapseExplorer()
        assertTrue(s.commands.execute(CommandIds.REVEAL_IN_EXPLORER))
        assertTrue("${s.backend.root}/web" in s.expanded)
        assertEquals("${s.backend.root}/web/app.ts", s.revealRequest)
        assertEquals(SideView.Explorer, s.activeView)
        assertTrue(s.sideBarVisible)
    }
}
