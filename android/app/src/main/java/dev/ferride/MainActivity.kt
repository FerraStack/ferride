package dev.ferride

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.ferride.core.WorkspaceKind
import dev.ferride.storage.SafDocumentTree
import dev.ferride.storage.StoragePaths
import dev.ferride.theme.VsCodeTheme
import dev.ferride.ui.FerrideWorkbench
import dev.ferride.workbench.RustWorkspaceBackend
import dev.ferride.workbench.SampleProject
import dev.ferride.workbench.RecentFolder
import dev.ferride.workbench.SettingsStore
import dev.ferride.workbench.WorkbenchState
import dev.ferride.workbench.WorkspaceBackend
import dev.ferride.workbench.WorkspaceHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val prefs = app.getSharedPreferences("workspace", Context.MODE_PRIVATE)
    private val projectsDir = File(app.filesDir, "projects")

    /** Set by the activity: launches the system folder picker. */
    var pickFolderRequest: (() -> Unit)? = null

    private val host = object : WorkspaceHost {
        override val projectsDir: String = this@FerrideViewModel.projectsDir.absolutePath
        override fun pickFolder() {
            if (BuildConfig.ALL_FILES_ACCESS && !Environment.isExternalStorageManager()) {
                state.message = "Tip: run \"Allow Access to All Files\" so folders in device storage get Git and fast search."
            }
            pickFolderRequest?.invoke()
        }
        override fun openLocal(path: String): WorkspaceBackend = RustWorkspaceBackend.open(path)

        override fun openRecent(recent: RecentFolder): WorkspaceBackend = when (recent.kind) {
            WorkspaceKind.SAF -> {
                val uri = Uri.parse(recent.root)
                check(getApplication<Application>().contentResolver.persistedUriPermissions.any { it.uri == uri }) {
                    "access to this folder was revoked"
                }
                backendFor(uri)
            }
            else -> RustWorkspaceBackend.open(recent.root)
        }

        override fun loadRecents(): List<RecentFolder> = runCatching {
            val arr = org.json.JSONArray(prefs.getString(KEY_RECENTS, "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RecentFolder(WorkspaceKind.valueOf(o.getString("kind")), o.getString("root"), o.getString("name"))
            }
        }.getOrDefault(emptyList())

        override fun saveRecents(recents: List<RecentFolder>) {
            val arr = org.json.JSONArray()
            recents.forEach { r ->
                arr.put(org.json.JSONObject().put("kind", r.kind.name).put("root", r.root).put("name", r.name))
            }
            prefs.edit().putString(KEY_RECENTS, arr.toString()).apply()
        }
    }

    // The last local folder reopens directly; the sample project otherwise.
    private val last: String? = prefs.getString(KEY_LAST, null)
    private val restoredLocal: WorkspaceBackend? = last?.removePrefix("path:")?.takeIf { last.startsWith("path:") }
        ?.let { runCatching { RustWorkspaceBackend.open(it) }.getOrNull() }

    val state: WorkbenchState = WorkbenchState(
        backend = restoredLocal ?: RustWorkspaceBackend.open(SampleProject.seed(projectsDir).absolutePath),
        scope = viewModelScope,
        io = Dispatchers.IO,
        settingsStore = settingsStore,
        extraThemes = userThemes,
        host = host,
    ).also {
        it.onWorkspaceOpened = ::remember
        it.snippetLoader = ::snippetsFor
        it.start(openFirst = if (restoredLocal == null) "src/main.rs" else null, showWelcome = true)
    }

    init {
        // A system-picker folder needs its persisted grant; reopen it off the main thread.
        val saf = last?.removePrefix("saf:")?.takeIf { last.startsWith("saf:") }?.let(Uri::parse)
        if (saf != null && app.contentResolver.persistedUriPermissions.any { it.uri == saf }) openFolder(saf)
    }

    private val snippetCache = HashMap<String, List<dev.ferride.editor.Snippet>>()

    /**
     * Built-in snippets (`assets/snippets/<language>.json`) plus the user's
     * (`files/snippets/<language>.json`, VS Code's format), cached per language.
     */
    private fun snippetsFor(language: String): List<dev.ferride.editor.Snippet> = synchronized(snippetCache) {
        snippetCache.getOrPut(language) {
            val app = getApplication<Application>()
            val file = when (language) {
                "typescriptreact" -> "typescript"
                "javascriptreact" -> "javascript"
                else -> language
            }
            val builtIn = runCatching {
                app.assets.open("snippets/$file.json").bufferedReader().use { r -> dev.ferride.editor.Snippets.parse(r.readText()) }
            }.getOrDefault(emptyList())
            val user = runCatching {
                dev.ferride.editor.Snippets.parse(File(app.filesDir, "snippets/$language.json").readText())
            }.getOrDefault(emptyList())
            builtIn + user
        }
    }

    /** Result of the system folder picker (or a restored grant). */
    fun openFolder(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        runCatching {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        viewModelScope.launch {
            val backend = withContext(Dispatchers.IO) { runCatching { backendFor(uri) } }
            backend.onSuccess { state.openWorkspace(it) }
                .onFailure { state.message = "Couldn't open the folder: ${it.message}" }
        }
    }

    /** foss + all-files access + a folder in device storage → a real path; anything else → SAF. */
    private fun backendFor(uri: Uri): WorkspaceBackend {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        if (BuildConfig.ALL_FILES_ACCESS && Environment.isExternalStorageManager()) {
            val path = StoragePaths.treeUriToPath(uri.authority, docId)
            if (path != null && File(path).isDirectory) return RustWorkspaceBackend.open(path)
        }
        val tree = SafDocumentTree(getApplication<Application>().contentResolver, uri)
        return RustWorkspaceBackend.openSaf(uri.toString(), tree)
    }

    private fun remember(backend: WorkspaceBackend) {
        val value = when (backend.kind) {
            WorkspaceKind.LOCAL -> "path:${backend.root}"
            WorkspaceKind.SAF -> "saf:${backend.root}"
            WorkspaceKind.FERRUX -> return
        }
        prefs.edit().putString(KEY_LAST, value).apply()
    }

    companion object {
        private const val KEY_LAST = "last"
        private const val KEY_RECENTS = "recents"
    }
}

class MainActivity : ComponentActivity() {
    private val vm: FerrideViewModel by viewModels()

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.openFolder(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        vm.pickFolderRequest = { folderPicker.launch(null) }
        if (BuildConfig.ALL_FILES_ACCESS) {
            vm.state.commands.register(
                dev.ferride.workbench.Command(
                    id = "ferride.storage.allowAllFiles",
                    title = "Allow Access to All Files",
                    category = "Ferride",
                    enabled = { !Environment.isExternalStorageManager() },
                ) {
                    startActivity(
                        Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
                    )
                },
            )
        }
        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect { vm.state.systemDark = dark }
            FerrideWorkbench(vm.state)
        }
    }

    override fun onDestroy() {
        vm.pickFolderRequest = null
        super.onDestroy()
    }

    /** VS Code keybindings for hardware keyboards go through the command registry first. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val chord = event.isCtrlPressed || event.isAltPressed || event.keyCode == KeyEvent.KEYCODE_ESCAPE ||
            event.keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12
        if (chord && vm.state.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }
}
