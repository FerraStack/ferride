package dev.ferride.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ferride.editor.RustEditorModel
import dev.ferride.theme.VsCodeTheme
import dev.ferride.workbench.CommandIds
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

/**
 * One sample file per language, highlighted by the real Rust core (tree-sitter,
 * loaded through JNA), in Dark Modern (`lang_<id>_dark.png`) and in a
 * third-party VS Code theme, Night Owl (`lang_<id>_night_owl.png`).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class LanguageScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val nightOwl by lazy {
        VsCodeTheme.parse(javaClass.classLoader!!.getResource("themes/night-owl-color-theme.jsonc")!!.readText())
    }

    private fun sample(name: String) = LanguageModeTest.sample(name)

    private fun shoot(png: String, file: String, nightOwlTheme: Boolean, setup: (WorkbenchState) -> Unit = {}) {
        val files = LanguageModeTest.SAMPLES.values.associateWith { sample(it) }
        val state = WorkbenchState(
            FakeWorkspaceBackend(initialFiles = files, modelFactory = RustEditorModel::create),
            CoroutineScope(Dispatchers.Unconfined),
            Dispatchers.Unconfined,
            settingsStore = InMemorySettingsStore(
                if (nightOwlTheme) """{ "window.autoDetectColorScheme": false, "workbench.colorTheme": "Night Owl" }""" else null,
            ),
            extraThemes = listOf(nightOwl),
        )
        state.systemDark = true
        state.start(file)
        setup(state)
        compose.setContent { FerrideWorkbench(state, imeVisible = false, hardwareKeyboard = false) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$png.png")
        state.tabs.forEach { it.model?.close() }
    }

    private fun language(id: String, nightOwlTheme: Boolean) =
        shoot("lang_${id}_" + if (nightOwlTheme) "night_owl" else "dark", LanguageModeTest.SAMPLES.getValue(id), nightOwlTheme)

    @Test fun cDark() = language("c", false)
    @Test fun cNightOwl() = language("c", true)
    @Test fun cppDark() = language("cpp", false)
    @Test fun cppNightOwl() = language("cpp", true)
    @Test fun goDark() = language("go", false)
    @Test fun goNightOwl() = language("go", true)
    @Test fun javaDark() = language("java", false)
    @Test fun javaNightOwl() = language("java", true)
    @Test fun kotlinDark() = language("kotlin", false)
    @Test fun kotlinNightOwl() = language("kotlin", true)
    @Test fun htmlDark() = language("html", false)
    @Test fun htmlNightOwl() = language("html", true)
    @Test fun cssDark() = language("css", false)
    @Test fun cssNightOwl() = language("css", true)
    @Test fun yamlDark() = language("yaml", false)
    @Test fun yamlNightOwl() = language("yaml", true)
    @Test fun tomlDark() = language("toml", false)
    @Test fun tomlNightOwl() = language("toml", true)
    @Test fun shellDark() = language("shellscript", false)
    @Test fun shellNightOwl() = language("shellscript", true)
    @Test fun sqlDark() = language("sql", false)
    @Test fun sqlNightOwl() = language("sql", true)

    // Injections (#12): Markdown inline syntax and fenced code; CSS/JS inside HTML
    // (html above); SQL and CSS in JavaScript tagged templates.
    @Test fun markdownDark() = language("markdown", false)
    @Test fun markdownNightOwl() = language("markdown", true)
    @Test fun javascriptDark() = language("javascript", false)
    @Test fun javascriptNightOwl() = language("javascript", true)

    /** Change Language Mode, opened from the status bar's language item. */
    @Test
    fun languagePickerDark() = shoot("phone_language_mode_dark", "script.sh", nightOwlTheme = false) {
        it.commands.execute(CommandIds.CHANGE_LANGUAGE_MODE)
    }
}
