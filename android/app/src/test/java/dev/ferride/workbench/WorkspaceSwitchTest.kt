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
        assertEquals("only the Welcome page is open", listOf(TabKind.Welcome), s.tabs.map { it.kind })
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

    private class RecentHost(override val projectsDir: String) : WorkspaceHost {
        var saved: List<RecentFolder> = emptyList()
        val failing = mutableSetOf<String>()
        override fun pickFolder() {}
        override fun openLocal(path: String): WorkspaceBackend = FakeWorkspaceBackend(name = path.substringAfterLast('/'), root = path)
        override fun openRecent(recent: RecentFolder): WorkspaceBackend {
            check(recent.root !in failing) { "access to this folder was revoked" }
            return openLocal(recent.root)
        }
        override fun loadRecents() = saved
        override fun saveRecents(recents: List<RecentFolder>) {
            saved = recents
        }
    }

    @Test
    fun recentFoldersAreRememberedNewestFirst() {
        val host = RecentHost(tmp.root.path)
        val s = state(FakeWorkspaceBackend(name = "a", root = "/p/a"), host)
        s.start(openFirst = null)
        s.openWorkspace(FakeWorkspaceBackend(name = "b", root = "/p/b"))
        s.openWorkspace(FakeWorkspaceBackend(name = "a", root = "/p/a"))
        assertEquals(listOf("/p/a", "/p/b"), s.recentFolders.map { it.root })
        assertEquals(s.recentFolders.toList(), host.saved)
        // A second workbench (next launch) starts with the saved list.
        val again = state(FakeWorkspaceBackend(name = "a", root = "/p/a"), host)
        assertEquals(listOf("/p/a", "/p/b"), again.recentFolders.map { it.root })
    }

    @Test
    fun openingARecentFolderSwitchesOrDropsIt() {
        val host = RecentHost(tmp.root.path)
        host.saved = listOf(
            RecentFolder(WorkspaceKind.LOCAL, "/p/b", "b"),
            RecentFolder(WorkspaceKind.SAF, "content://x/tree/gone", "gone"),
        )
        host.failing += "content://x/tree/gone"
        val s = state(FakeWorkspaceBackend(name = "a", root = "/p/a"), host)
        s.openRecent(s.recentFolders.first { it.name == "b" })
        assertEquals("/p/b", s.backend.root)
        s.openRecent(s.recentFolders.first { it.name == "gone" })
        assertEquals("/p/b", s.backend.root)
        assertFalse(s.recentFolders.any { it.name == "gone" })
        assertTrue(s.message!!.contains("removed from Recent"))
    }

    @Test
    fun welcomeOpensOnStartAndAfterSwitchingUnlessTurnedOff() {
        val s = state()
        s.start("src/main.rs", showWelcome = true)
        assertEquals(listOf(TabKind.Welcome, TabKind.File), s.tabs.map { it.kind })
        s.updateSettings { it.copy(startupEditor = "none") }
        s.closeTab(1)
        s.openWorkspace(FakeWorkspaceBackend(name = "other"))
        assertTrue(s.tabs.isEmpty())
        assertTrue(s.commands.execute(CommandIds.WELCOME))
        assertEquals(TabKind.Welcome, s.activeTab!!.kind)
    }
}
