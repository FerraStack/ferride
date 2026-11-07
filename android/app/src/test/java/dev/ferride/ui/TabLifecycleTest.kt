package dev.ferride.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import dev.ferride.workbench.CommandIds
import dev.ferride.workbench.FakeWorkspaceBackend
import dev.ferride.workbench.WorkbenchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression test for the use-after-close crash: replacing a preview tab or
 * closing the active tab must never let the editor view touch a freed model.
 * FakeEditorModel throws on any use after close(), like a destroyed native Document.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class TabLifecycleTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun openTwoFilesInARowThenCloseTabs() {
        val backend = FakeWorkspaceBackend()
        val state = WorkbenchState(backend, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        compose.setContent { FerrideWorkbench(state, imeVisible = false, hardwareKeyboard = false) }

        state.openFile("${backend.root}/README.md", preview = true)
        compose.waitForIdle()
        val first = state.activeModel!!
        // Explorer click on another file replaces the preview tab.
        state.openFile("${backend.root}/Cargo.toml", preview = true)
        compose.waitForIdle()
        assertTrue("replaced preview model is freed", first.isClosed)
        assertEquals(1, state.tabs.size)

        state.openFile("${backend.root}/src/main.rs", preview = false)
        compose.waitForIdle()
        assertEquals(2, state.tabs.size)

        // Ctrl+W twice.
        state.commands.execute(CommandIds.CLOSE_EDITOR)
        compose.waitForIdle()
        state.commands.execute(CommandIds.CLOSE_EDITOR)
        compose.waitForIdle()
        assertTrue(state.tabs.isEmpty())
        assertEquals(0, state.retiredCount)
    }
}
