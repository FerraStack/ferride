package dev.ferride.ui

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import dev.ferride.workbench.FakeWorkspaceBackend
import dev.ferride.workbench.PaletteMode
import dev.ferride.workbench.WorkbenchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Show All Commands opens with ">" and the caret after it, so typing filters commands (VS Code). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class PaletteInputTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun typingGoesAfterTheCommandPrefix() {
        val state = WorkbenchState(FakeWorkspaceBackend(), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        state.openPalette(PaletteMode.Commands)
        compose.setContent { FerrideWorkbench(state, imeVisible = false, hardwareKeyboard = false) }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput("save")
        compose.waitForIdle()
        assertEquals(">save", state.paletteQuery)
        assertEquals(PaletteMode.Commands, state.paletteMode())
    }
}
