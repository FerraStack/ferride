package dev.ferride.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ferride.core.WorkspaceKind
import dev.ferride.editor.CodeEditorView
import dev.ferride.editor.Sel
import dev.ferride.theme.VsCodeTheme
import dev.ferride.workbench.CommandIds
import dev.ferride.workbench.Diagnostic
import dev.ferride.workbench.FakeWorkspaceBackend
import dev.ferride.workbench.InMemorySettingsStore
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

    private val nightOwl by lazy {
        val text = javaClass.classLoader!!.getResource("themes/night-owl-color-theme.jsonc")!!.readText()
        VsCodeTheme.parse(text)
    }

    private fun shoot(
        name: String,
        dark: Boolean?,
        settingsJson: String? = null,
        ime: Boolean = false,
        afterContent: (WorkbenchState) -> Unit = {},
        backend: FakeWorkspaceBackend = FakeWorkspaceBackend(),
        setup: (WorkbenchState) -> Unit = {},
    ) {
        val state = WorkbenchState(
            backend,
            CoroutineScope(Dispatchers.Unconfined),
            Dispatchers.Unconfined,
            settingsStore = InMemorySettingsStore(settingsJson),
            extraThemes = listOf(nightOwl),
        )
        state.systemDark = dark ?: true
        state.start("src/main.rs")
        state.toggleDir("${backend.root}/src")
        state.activeModel?.let { m ->
            val at = m.text().indexOf("word_counts(text)")
            m.selection = Sel(at, at + "word_counts".length)
        }
        setup(state)
        compose.setContent {
            if (ime) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { FerrideWorkbench(state, imeVisible = true, hardwareKeyboard = false) }
                    FakeKeyboard(dark ?: true)
                }
            } else {
                FerrideWorkbench(state, imeVisible = false, hardwareKeyboard = false)
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { afterContent(state) }
        compose.waitForIdle()
        val suffix = when (dark) {
            null -> ""
            true -> "_dark"
            false -> "_light"
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name$suffix.png")
    }

    // ----- phone, portrait ------------------------------------------------------

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneEditorDark() = shoot("phone_editor", dark = true)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneEditorLight() = shoot("phone_editor", dark = false)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneNoWrapDark() = shoot("phone_nowrap", dark = true, settingsJson = """{ "editor.wordWrap": "off" }""")

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneNoWrapLight() = shoot("phone_nowrap", dark = false, settingsJson = """{ "editor.wordWrap": "off" }""")

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneExplorerDark() = shoot("phone_explorer", dark = true) { it.showView(SideView.Explorer) }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneExplorerLight() = shoot("phone_explorer", dark = false) { it.showView(SideView.Explorer) }

    private fun palette(s: WorkbenchState) {
        s.recentCommands += listOf(CommandIds.TOGGLE_TERMINAL, CommandIds.SAVE)
        s.openPalette(PaletteMode.Commands)
    }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phonePaletteDark() = shoot("phone_palette", dark = true, setup = ::palette)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phonePaletteLight() = shoot("phone_palette", dark = false, setup = ::palette)

    private fun quickOpen(s: WorkbenchState) {
        s.openPalette(PaletteMode.Files)
        s.paletteQuery = "ma"
    }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneQuickOpenDark() = shoot("phone_quickopen", dark = true, setup = ::quickOpen)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneQuickOpenLight() = shoot("phone_quickopen", dark = false, setup = ::quickOpen)

    private fun typing(s: WorkbenchState) {
        // An unsaved edit: the tab shows the dirty dot.
        val tab = s.activeTab!!
        val m = tab.model!!
        val at = m.text().indexOf("counts\n}") + "counts".length
        m.selection = Sel.caret(at)
        m.insert(" // total", true)
        s.onEditorChanged(tab, contentChanged = true)
    }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneImeDark() = shoot("phone_ime", dark = true, ime = true, setup = ::typing)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneImeLight() = shoot("phone_ime", dark = false, ime = true, setup = ::typing)

    private fun find(s: WorkbenchState) {
        s.openFind(replace = true)
        s.find.query = "counts"
        s.find.replacement = "tally"
        s.findNext()
    }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneFindDark() = shoot("phone_find", dark = true, setup = ::find)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneFindLight() = shoot("phone_find", dark = false, setup = ::find)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneTerminalDark() = shoot("phone_terminal", dark = true) { it.showPanel(PanelTab.Terminal) }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneTerminalLight() = shoot("phone_terminal", dark = false) { it.showPanel(PanelTab.Terminal) }

    /** Long-press result: handles around `word_counts` and the floating toolbar (clipboard has text). */
    private fun selectionControls(s: WorkbenchState) {
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("code", "counts"))
        val view = s.editor as CodeEditorView
        view.requestFocus()
        view.showSelectionControls(toolbar = true)
    }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneSelectionDark() = shoot("phone_selection", dark = true, afterContent = ::selectionControls)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneSelectionLight() = shoot("phone_selection", dark = false, afterContent = ::selectionControls)

    /** Tap on the caret: caret handle + the insertion menu (Paste, Select All). */
    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneCaretMenuDark() = shoot(
        "phone_caret_menu",
        dark = true,
        afterContent = { s ->
            val m = s.activeModel!!
            m.selection = Sel.caret(m.text().indexOf("counts\n}") + 3)
            selectionControls(s)
        },
    )

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletSelectionDark() = shoot("tablet_selection", dark = true, afterContent = ::selectionControls)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletSelectionLight() = shoot("tablet_selection", dark = false, afterContent = ::selectionControls)

    /** A folder opened with the system picker (SAF): Search explains why it's off and offers the copy. */
    private val safBackend get() = FakeWorkspaceBackend(name = "Shared", kind = WorkspaceKind.SAF)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneSearchSafDark() = shoot("phone_search_saf", dark = true, backend = safBackend) { it.showView(SideView.Search) }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneSearchSafLight() = shoot("phone_search_saf", dark = false, backend = safBackend) { it.showView(SideView.Search) }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletSearchSafDark() = shoot("tablet_search_saf", dark = true, backend = safBackend) { it.showView(SideView.Search) }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletSearchSafLight() = shoot("tablet_search_saf", dark = false, backend = safBackend) { it.showView(SideView.Search) }

    // ----- tablet, landscape: desktop-like layout -----------------------------------

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletDark() = shoot("tablet_workbench", dark = true) {
        it.openFile("${it.backend.root}/web/app.ts", preview = true)
        it.toggleDir("${it.backend.root}/web")
    }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletLight() = shoot("tablet_workbench", dark = false) {
        it.openFile("${it.backend.root}/web/app.ts", preview = true)
        it.toggleDir("${it.backend.root}/web")
    }

    private fun problems(s: WorkbenchState) {
        val root = s.backend.root
        s.diagnostics["$root/src/main.rs"] = listOf(
            Diagnostic(13, 8, 13, 14, 2, "unused variable: `counts`", "rust-analyzer", "unused_variables"),
            Diagnostic(15, 10, 15, 17, 1, "no method named `sort_by` found for struct `Vec<&(&str, &usize)>`", "rustc", "E0599"),
        )
        s.diagnostics["$root/web/app.ts"] = listOf(
            Diagnostic(14, 0, 14, 7, 3, "'console' is a global; consider a logger", "eslint", "no-console"),
        )
        s.showPanel(PanelTab.Problems)
    }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletProblemsDark() = shoot("tablet_problems", dark = true, setup = ::problems)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletProblemsLight() = shoot("tablet_problems", dark = false, setup = ::problems)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletPaletteDark() = shoot("tablet_palette", dark = true, setup = ::palette)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletPaletteLight() = shoot("tablet_palette", dark = false, setup = ::palette)

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletSettingsDark() = shoot("tablet_settings", dark = true) { it.openSettings() }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletSettingsLight() = shoot("tablet_settings", dark = false) { it.openSettings() }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneSettingsDark() = shoot("phone_settings", dark = true) { it.openSettings() }

    @Test @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun phoneSettingsLight() = shoot("phone_settings", dark = false) { it.openSettings() }

    /** A third-party VS Code theme (Night Owl, MIT) loaded from a JSONC fixture. */
    @Test @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun tabletThemeNightOwl() = shoot(
        "tablet_theme_night_owl",
        dark = null,
        settingsJson = """{ "window.autoDetectColorScheme": false, "workbench.colorTheme": "Night Owl" }""",
    ) {
        it.openFile("${it.backend.root}/scripts/stats.py", preview = false)
        it.showPanel(PanelTab.Problems)
    }
}

/** A drawing of a soft keyboard (Robolectric has no IME), so the IME screenshot shows the resized layout. */
@Composable
private fun FakeKeyboard(dark: Boolean) {
    val bg = if (dark) Color(0xFF2B2B2F) else Color(0xFFE3E5EA)
    val key = if (dark) Color(0xFF47474D) else Color.White
    val fg = if (dark) Color(0xFFE8E8E8) else Color(0xFF202124)
    val rows = listOf("qwertyuiop", "asdfghjkl", "⇧zxcvbnm⌫", "?123 , space . ⏎")
    Column(
        Modifier.fillMaxWidth().height(260.dp).background(bg).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        rows.forEach { row ->
            val keys = if (' ' in row) row.split(' ') else row.map { it.toString() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                keys.forEach { k ->
                    Box(
                        Modifier
                            .weight(if (k == "space") 4f else 1f)
                            .padding(3.dp)
                            .height(44.dp)
                            .background(key, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (k == "space") "" else k, color = fg, fontSize = 18.sp)
                    }
                }
            }
        }
    }
}
