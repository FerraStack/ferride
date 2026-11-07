package dev.ferride

import android.app.Application
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.ferride.theme.VsCodeTheme
import dev.ferride.ui.FerrideWorkbench
import dev.ferride.workbench.RustWorkspaceBackend
import dev.ferride.workbench.SampleProject
import dev.ferride.workbench.SettingsStore
import dev.ferride.workbench.WorkbenchState
import kotlinx.coroutines.Dispatchers
import java.io.File

class FerrideViewModel(app: Application) : AndroidViewModel(app) {
    private val settingsFile = File(app.filesDir, "settings.json")

    /** User themes: VS Code `*-color-theme.json` files dropped into `files/themes/`. */
    private val userThemes = File(app.filesDir, "themes").listFiles { f -> f.extension == "json" }.orEmpty()
        .sortedBy { it.name }
        .mapNotNull { f -> runCatching { VsCodeTheme.parse(f.readText(), f.nameWithoutExtension) }.getOrNull() }

    private val settingsStore = object : SettingsStore {
        override fun read(): String? = settingsFile.takeIf { it.exists() }?.readText()
        override fun write(text: String) {
            // Atomic replace so a crash mid-write can't truncate the user's settings.
            val tmp = File(settingsFile.parentFile, "settings.json.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(settingsFile)) error("couldn't replace settings.json")
        }
    }

    val state: WorkbenchState = WorkbenchState(
        backend = RustWorkspaceBackend.open(SampleProject.seed(File(app.filesDir, "projects")).absolutePath),
        scope = viewModelScope,
        io = Dispatchers.IO,
        settingsStore = settingsStore,
        extraThemes = userThemes,
    ).also { it.start() }
}

class MainActivity : ComponentActivity() {
    private val vm: FerrideViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect { vm.state.systemDark = dark }
            FerrideWorkbench(vm.state)
        }
    }

    /** VS Code keybindings for hardware keyboards go through the command registry first. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val chord = event.isCtrlPressed || event.isAltPressed || event.keyCode == KeyEvent.KEYCODE_ESCAPE ||
            event.keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12
        if (chord && vm.state.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }
}
