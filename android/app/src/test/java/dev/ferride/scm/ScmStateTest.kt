package dev.ferride.scm

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ferride.core.ScmGroup
import dev.ferride.workbench.FakeWorkspaceBackend
import dev.ferride.workbench.InMemorySettingsStore
import dev.ferride.workbench.Settings
import dev.ferride.workbench.WorkbenchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScmStateTest {
    private val settings = InMemorySettingsStore()

    private fun state(repo: FakeScmRepo? = FakeScmRepo("/sample/sample")): WorkbenchState {
        val s = WorkbenchState(
            FakeWorkspaceBackend(),
            CoroutineScope(Dispatchers.Unconfined),
            Dispatchers.Unconfined,
            settingsStore = settings,
            scmProvider = FakeScmProvider(repo),
        )
        s.start(openFirst = null)
        return s
    }

    @Test
    fun loadsStatusHeadAndBadge() {
        val scm = state().scm
        assertEquals(ScmMode.Ready, scm.mode)
        assertEquals(6, scm.count)
        assertEquals(2, scm.staged.size)
        assertEquals(4, scm.unstaged.size)
        assertEquals("main*+", scm.headLabel)
    }

    @Test
    fun stageUnstageAndDiscardWithConfirmation() {
        val scm = state().scm
        val readme = scm.unstaged.first { it.path == "README.md" }
        scm.stage(readme)
        assertTrue(scm.staged.any { it.path == "README.md" && it.letter == "M" })
        scm.unstage(scm.staged.first { it.path == "README.md" })
        assertTrue(scm.unstaged.any { it.path == "README.md" })

        val untracked = scm.unstaged.first { it.letter == "U" }
        scm.discard(untracked)
        assertEquals("Are you sure you want to DELETE todo.md?", scm.confirm?.message)
        assertEquals("Delete File", scm.confirm?.buttons?.single()?.first)
        scm.discard(readme)
        assertEquals("Are you sure you want to discard changes in README.md?", scm.confirm?.message)
        scm.confirm!!.buttons.single().second()
        assertFalse(scm.unstaged.any { it.path == "README.md" })

        scm.discardAll()
        assertEquals("Discard All 3 Files", scm.confirm?.buttons?.single()?.first)
    }

    @Test
    fun commitAsksForIdentityThenUsesSettings() {
        val wb = state()
        val scm = wb.scm
        scm.commitMessage = "Add things"
        scm.commit()
        // No identity yet: two input boxes, saved to settings.json, then the commit goes through.
        assertEquals("Name", scm.inputBox?.placeholder)
        scm.inputBox!!.onAccept("Ada Lovelace")
        assertEquals("Email", scm.inputBox?.placeholder)
        scm.inputBox!!.onAccept("ada@example.com")
        assertEquals("Ada Lovelace", wb.settings.gitUserName)
        assertTrue(settings.read()!!.contains("git.user.email"))
        assertTrue(settings.read()!!.contains("ada@example.com"))
        assertEquals("", scm.commitMessage)
        assertTrue(scm.staged.isEmpty())
        assertEquals("Add things", (scm.repo as FakeScmRepo).commits.first().summary)
    }

    @Test
    fun smartCommitPromptsWhenNothingIsStaged() {
        val wb = state()
        wb.updateSettings { it.copy(gitUserName = "Ada", gitUserEmail = "ada@example.com") }
        val scm = wb.scm
        scm.unstageAll()
        assertTrue(scm.staged.isEmpty())
        scm.commitMessage = "Everything"
        scm.commit()
        assertEquals("There are no staged changes to commit.", scm.confirm?.message)
        assertEquals(listOf("Yes", "Always"), scm.confirm!!.buttons.map { it.first })
        scm.confirm!!.buttons[1].second() // Always
        assertTrue(wb.settings.gitEnableSmartCommit)
        assertTrue(scm.changes.none { it.group == ScmGroup.WORKING_TREE })
    }

    @Test
    fun emptyMessageAndNoChanges() {
        val wb = state(FakeScmRepo("/sample/sample", emptyList()))
        wb.scm.commit()
        assertEquals("Please provide a commit message.", wb.message)
        wb.scm.commitMessage = "x"
        wb.scm.commit()
        assertEquals("There are no changes to commit.", wb.message)
    }

    @Test
    fun branchPickerListsLocalThenRemoteAndChecksOut() {
        val scm = state().scm
        scm.showBranchPicker()
        val pick = scm.quickPick!!
        assertEquals("Select a branch or tag to checkout", pick.placeholder)
        assertEquals(
            listOf("Create new branch...", "feature/python", "main", "origin/main", "origin/release"),
            pick.items.map { it.label },
        )
        assertEquals("branches", pick.items[1].section)
        assertEquals("remote branches", pick.items[3].section)
        pick.query = "rel"
        assertEquals(listOf("origin/release"), pick.filtered().map { it.label })
        pick.filtered().single().run()
        assertEquals("release", scm.head?.branch)
        assertNull(scm.quickPick)
    }

    @Test
    fun pushWithoutUpstreamOffersToPublish() {
        val scm = state().scm
        scm.checkout("feature/python")
        scm.push()
        assertEquals("The branch 'feature/python' has no remote branch. Would you like to publish this branch?", scm.confirm?.message)
        scm.confirm!!.buttons.single().second()
        assertEquals("origin/feature/python", scm.head?.upstream)
    }

    @Test
    fun noRepositoryOffersInitAndClone() {
        val wb = state(repo = null)
        assertEquals(ScmMode.NoRepository, wb.scm.mode)
        assertTrue(wb.commands.get(GitCommandIds.INIT)!!.enabled())
        assertFalse(wb.commands.get(GitCommandIds.COMMIT)!!.enabled())
        wb.commands.execute(GitCommandIds.INIT)
        assertEquals(ScmMode.Ready, wb.scm.mode)
        assertEquals("main", wb.scm.headLabel)
    }

    @Test
    fun cloneValidatesTheUrlAndNamesTheFolder() {
        val wb = state(repo = null)
        wb.scm.projectsDir = "/nonexistent/projects"
        wb.scm.promptClone()
        val box = wb.scm.inputBox
        assertNotNull(box)
        assertEquals("Provide repository URL", box!!.prompt)
        assertEquals("Only HTTPS URLs are supported.", box.validate("git@github.com:o/r.git"))
        assertNull(box.validate("https://github.com/o/r.git"))
        assertEquals("r", repoNameFromUrl("https://github.com/o/r.git"))
        assertEquals("ferride", repoNameFromUrl("https://github.com/FerraStack/ferride/"))
        assertEquals("https://github.com", hostKey("https://user@github.com/o/r.git"))
    }

    @Test
    fun gitSettingsRoundTrip() {
        val s = Settings.parse("""{ "git.user.name": "Ada", "git.user.email": "a@b.c", "git.enableSmartCommit": true }""")
        assertEquals("Ada", s.gitUserName)
        assertEquals("a@b.c", s.gitUserEmail)
        assertTrue(s.gitEnableSmartCommit)
        assertEquals(s, Settings.parse(s.toJson()))
    }
}
