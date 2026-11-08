package dev.ferride.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ferride.editor.CodeEditorView
import dev.ferride.editor.RustEditorModel
import dev.ferride.editor.Sel
import dev.ferride.workbench.FakeWorkspaceBackend
import dev.ferride.workbench.LanguageModeTest
import dev.ferride.workbench.PaletteMode
import dev.ferride.workbench.SideView
import dev.ferride.workbench.WorkbenchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Outline view, breadcrumbs, Go to Symbol (`@`, `#`) and sticky scroll, from tree-sitter symbols. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OutlineScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shoot(png: String, file: String, setup: (WorkbenchState) -> Unit = {}, after: (WorkbenchState) -> Unit = {}) {
        val files = LanguageModeTest.SAMPLES.values.associate { "src/$it" to LanguageModeTest.sample(it) }
        val state = WorkbenchState(
            FakeWorkspaceBackend(initialFiles = files, modelFactory = RustEditorModel::create),
            CoroutineScope(Dispatchers.Unconfined),
            Dispatchers.Unconfined,
        )
        state.systemDark = true
        state.start("src/$file")
        setup(state)
        compose.setContent { FerrideWorkbench(state, imeVisible = false, hardwareKeyboard = false) }
        compose.waitForIdle()
        compose.runOnIdle { after(state) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$png.png")
        state.tabs.forEach { it.model?.close() }
    }

    /** Caret inside `area`: the outline highlights it and the breadcrumbs end with impl Circle › area. */
    private fun caretInArea(s: WorkbenchState) {
        val m = s.activeModel!!
        m.selection = Sel.caret(m.text().indexOf("PI *"))
        s.activeTab!!.revision++
    }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletOutlineDark() = shoot("tablet_outline_dark", "main.rs", setup = { s ->
        s.showView(SideView.Explorer)
        s.outlineExpanded = true
        caretInArea(s)
    })

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneOutlineDark() = shoot("phone_outline_dark", "sample.cpp", setup = { s ->
        s.showView(SideView.Explorer)
        s.outlineExpanded = true
    })

    /** Scrolled into Counter::top: namespace, class and method stick at the top. */
    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneStickyScrollDark() = shoot("phone_sticky_scroll_dark", "sample.cpp", after = { s ->
        (s.editor as CodeEditorView).goToLine(19)
    })

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneGoToSymbolDark() = shoot("phone_goto_symbol_dark", "main.rs", setup = { s ->
        caretInArea(s)
        s.openPalette(PaletteMode.Symbols)
    })

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneWorkspaceSymbolsDark() = shoot("phone_workspace_symbols_dark", "main.rs", setup = { s ->
        s.openPalette(PaletteMode.WorkspaceSymbols)
        s.paletteQuery = "#count"
    })
}
