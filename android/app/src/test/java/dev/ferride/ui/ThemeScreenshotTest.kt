package dev.ferride.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ferride.editor.RustEditorModel
import dev.ferride.theme.VsCodeTheme
import dev.ferride.workbench.FakeWorkspaceBackend
import dev.ferride.workbench.InMemorySettingsStore
import dev.ferride.workbench.LanguageModeTest
import dev.ferride.workbench.WorkbenchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * VS Code theme fidelity (#11): real tree-sitter scopes styled by three
 * popular MIT themes (`theme_<theme>_<file>.png`), including `fontStyle`
 * (italic comments/types in Dracula and Night Owl, bold/italic Markdown).
 * The colors are checked against VS Code itself in ferride-theme's
 * `vscode_parity` test; these screenshots show the result.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class ThemeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun theme(file: String) = VsCodeTheme.parse(
        File(checkNotNull(System.getProperty("ferride.themes.dir")), file).readText(),
    )

    private fun shoot(png: String, sample: String, themeName: String?) {
        val files = LanguageModeTest.SAMPLES.values.associateWith { LanguageModeTest.sample(it) }
        val themes = listOf(theme("dracula.json"), theme("one-dark-pro.json"), theme("night-owl.json"))
        val settings = themeName?.let { """{ "window.autoDetectColorScheme": false, "workbench.colorTheme": "$it" }""" }
        val state = WorkbenchState(
            FakeWorkspaceBackend(initialFiles = files, modelFactory = RustEditorModel::create),
            CoroutineScope(Dispatchers.Unconfined),
            Dispatchers.Unconfined,
            settingsStore = InMemorySettingsStore(settings),
            extraThemes = themes,
        )
        state.systemDark = true
        state.start(sample)
        compose.setContent { FerrideWorkbench(state, imeVisible = false, hardwareKeyboard = false) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$png.png")
        state.tabs.forEach { it.model?.close() }
    }

    @Test fun draculaRust() = shoot("theme_dracula_rust", "main.rs", "Dracula")
    @Test fun draculaMarkdown() = shoot("theme_dracula_markdown", "README.md", "Dracula")
    @Test fun oneDarkProRust() = shoot("theme_one_dark_pro_rust", "main.rs", "One Dark Pro")
    @Test fun oneDarkProTypeScript() = shoot("theme_one_dark_pro_typescript", "app.ts", "One Dark Pro")
    @Test fun nightOwlPython() = shoot("theme_night_owl_python", "stats.py", "Night Owl")
    @Test fun nightOwlMarkdown() = shoot("theme_night_owl_markdown", "README.md", "Night Owl")

    /** Built-in theme: Ferride's colors, VS Code's Markdown font styles (bold headings, italic/bold emphasis). */
    @Test fun darkModernMarkdown() = shoot("theme_markdown_dark", "README.md", null)
}
