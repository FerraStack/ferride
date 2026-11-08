package dev.ferride.workbench

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ferride.core.WorkspaceKind
import dev.ferride.storage.SafDocumentTree
import dev.ferride.storage.StoragePaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Open Folder, SAF folders without search, and copying them into Ferride projects. */
@RunWith(AndroidJUnit4::class)
class WorkspaceSwitchTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeHost(override val projectsDir: String) : WorkspaceHost {
        var picks = 0
        val opened = mutableListOf<String>()
        override fun pickFolder() {
            picks++
        }

        override fun openLocal(path: String): WorkspaceBackend {
            opened += path
            return FakeWorkspaceBackend(name = path.substringAfterLast('/'))
        }
    }

    private fun state(backend: WorkspaceBackend = FakeWorkspaceBackend(), host: WorkspaceHost? = null) =
        WorkbenchState(backend, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, host = host)

    @Test
    fun openFolderUsesTheSystemPicker() {
        val host = FakeHost(tmp.root.path)
        val s = state(host = host)
        assertTrue(s.commands.execute(CommandIds.OPEN_FOLDER))
        assertEquals(1, host.picks)
        // Without a host (previews) the command is disabled.
        assertFalse(state().commands.execute(CommandIds.OPEN_FOLDER))
    }

    @Test
    fun openWorkspaceResetsTheWorkbench() {
        val s = state()
        s.start("src/main.rs")
        s.toggleDir("${s.backend.root}/src")
        assertEquals(1, s.tabs.size)
        val saf = FakeWorkspaceBackend(name = "Shared", kind = WorkspaceKind.SAF)
        var remembered: WorkspaceBackend? = null
        s.onWorkspaceOpened = { remembered = it }
        assertTrue(s.openWorkspace(saf))
        assertTrue(s.tabs.isEmpty())
        assertTrue(s.expanded.isEmpty())
        assertEquals(saf, s.backend)
        assertEquals(saf, remembered)
        assertTrue("the new root is listed", s.tree.containsKey(saf.root))
    }

    @Test
    fun openWorkspaceRefusesWithUnsavedEditors() {
        val s = state()
        s.start("src/main.rs")
        val tab = s.activeTab!!
        tab.model!!.insert("x", true)
        s.onEditorChanged(tab, contentChanged = true)
        assertFalse(s.openWorkspace(FakeWorkspaceBackend(name = "other")))
        assertEquals(1, s.tabs.size)
        assertTrue(s.message!!.contains("unsaved"))
    }

    @Test
    fun safFoldersSkipTextSearchAndCopyIntoProjects() {
        val host = FakeHost(tmp.root.path)
        val s = state(FakeWorkspaceBackend(name = "Shared", kind = WorkspaceKind.SAF), host)
        s.searchQuery = "fn"
        s.runSearch()
        assertNull("no ripgrep on SAF", s.searchResult)
        assertTrue(s.commands.execute(CommandIds.COPY_INTO_PROJECTS))
        assertEquals(listOf("${tmp.root.path}/Shared"), host.opened)
        assertEquals(WorkspaceKind.LOCAL, s.backend.kind)
        assertTrue(s.message!!.contains("Git and search now work"))
        // Only offered for SAF folders.
        assertFalse(s.commands.execute(CommandIds.COPY_INTO_PROJECTS))
    }

    @Test
    fun copiesGetUniqueNames() {
        tmp.newFolder("Shared")
        tmp.newFolder("Shared 2")
        assertEquals("${tmp.root.path}/Shared 3", uniqueDir(tmp.root.path, "Shared"))
        assertEquals("${tmp.root.path}/a_b", uniqueDir(tmp.root.path, "a/b"))
    }

    @Test
    fun treeUrisMapToStoragePaths() {
        val a = StoragePaths.EXTERNAL_STORAGE_AUTHORITY
        assertEquals("/storage/emulated/0/Projects/app", StoragePaths.treeUriToPath(a, "primary:Projects/app"))
        assertEquals("/storage/emulated/0", StoragePaths.treeUriToPath(a, "primary:"))
        assertEquals("/storage/1A2B-3C4D/code", StoragePaths.treeUriToPath(a, "1A2B-3C4D:code"))
        assertNull("other providers stay SAF", StoragePaths.treeUriToPath("com.termux.documents", "primary:x"))
        assertNull(StoragePaths.treeUriToPath(a, "nocolon"))
        assertEquals("app", SafDocumentTree.displayNameFromId("primary:Projects/app"))
        assertEquals("primary", SafDocumentTree.displayNameFromId("primary:"))
    }
}
