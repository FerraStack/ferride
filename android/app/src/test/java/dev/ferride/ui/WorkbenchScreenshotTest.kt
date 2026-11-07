package dev.ferride.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ferride.editor.Sel
import dev.ferride.workbench.FakeWorkspaceBackend
import dev.ferride.workbench.PaletteMode
import dev.ferride.workbench.PanelTab
import dev.ferride.workbench.SideView
import dev.ferride.workbench.WorkbenchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of the main workbench screens, rendered on the JVM with
 * Robolectric (no emulator, no native library: the workspace and editor models
 * are the pure-Kotlin fakes). Run with `-Proborazzi.test.record=true` to write
 * PNGs to `app/build/outputs/roborazzi/` (`<screen>_<light|dark>.png`).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkbenchScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shoot(name: String, dark: Boolean, setup: (WorkbenchState) -> Unit = {}) {
        val backend = FakeWorkspaceBackend()
        val state = WorkbenchState(backend, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        state.systemDark = dark
        state.start("src/main.rs")
        state.toggleDir("${backend.root}/src")
        state.activeTab?.model?.let { m ->
            val at = m.text().indexOf("word_counts(text)")
            m.selection = Sel(at, at + "word_counts".length)
        }
        setup(state)
        compose.setContent { FerrideWorkbench(state, imeVisible = false) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/${name}_${if (dark) "dark" else "light"}.png")
    }

    // Phone, portrait.
    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneEditorDark() = shoot("phone_editor", dark = true)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneEditorLight() = shoot("phone_editor", dark = false)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneExplorerDark() = shoot("phone_explorer", dark = true) { it.showView(SideView.Explorer) }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneExplorerLight() = shoot("phone_explorer", dark = false) { it.showView(SideView.Explorer) }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phonePaletteDark() = shoot("phone_palette", dark = true) { it.openPalette(PaletteMode.Commands) }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phonePaletteLight() = shoot("phone_palette", dark = false) { it.openPalette(PaletteMode.Commands) }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneTerminalDark() = shoot("phone_terminal", dark = true) { it.showPanel(PanelTab.Terminal) }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneTerminalLight() = shoot("phone_terminal", dark = false) { it.showPanel(PanelTab.Terminal) }

    // Tablet, landscape: desktop-like layout with docked side bar and panel.
    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletDark() = shoot("tablet_workbench", dark = true) {
        it.openFile("${it.backend.root}/web/app.ts", preview = true)
        it.showPanel(PanelTab.Problems)
    }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletLight() = shoot("tablet_workbench", dark = false) {
        it.openFile("${it.backend.root}/web/app.ts", preview = true)
        it.showPanel(PanelTab.Problems)
    }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletSettingsDark() = shoot("tablet_settings", dark = true) { it.showView(SideView.Settings) }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletSettingsLight() = shoot("tablet_settings", dark = false) { it.showView(SideView.Settings) }
}
