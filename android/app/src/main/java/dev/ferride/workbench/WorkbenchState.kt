package dev.ferride.workbench

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ferride.core.DocumentSymbol
import dev.ferride.core.FerruxStatus
import dev.ferride.core.FileHit
import dev.ferride.core.FileNode
import dev.ferride.core.SearchReport
import dev.ferride.core.SymbolHit
import dev.ferride.core.WorkspaceKind
import dev.ferride.editor.EditorActions
import dev.ferride.editor.EditorModel
import dev.ferride.editor.Sel
import dev.ferride.scm.CredentialStore
import dev.ferride.scm.InMemoryCredentialStore
import dev.ferride.scm.NoScmProvider
import dev.ferride.scm.ScmProvider
import dev.ferride.scm.ScmState
import dev.ferride.theme.BuiltInThemes
import dev.ferride.theme.DarkModern
import dev.ferride.theme.LightModern
import dev.ferride.theme.WorkbenchColors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Activity Bar views (the gear opens the Settings editor instead). */
enum class SideView(val title: String) {
    Explorer("Explorer"),
    Search("Search"),
    SourceControl("Source Control"),
    Languages("Languages"),
}

enum class PanelTab(val title: String) { Problems("Problems"), Output("Output"), Terminal("Terminal") }

enum class TabKind { File, Settings, SettingsJson, Welcome }

/** A folder on the Welcome page's Recent list (newest first). */
data class RecentFolder(val kind: WorkspaceKind, val root: String, val name: String) {
    /** The path line under the name: a file path, or where a picked folder lives. */
    val location: String
        get() = when (kind) {
            WorkspaceKind.SAF -> "System file picker"
            WorkspaceKind.FERRUX -> "Ferrux"
            WorkspaceKind.LOCAL -> root.substringBeforeLast('/')
        }
}

/**
 * An open editor tab. [preview] tabs (italic) are replaced by the next file
 * opened. Settings tabs have no [model].
 */
class EditorTab(val path: String, val model: EditorModel?, val kind: TabKind = TabKind.File) {
    val name: String = when (kind) {
        TabKind.Settings -> "Settings"
        TabKind.Welcome -> "Welcome"
        else -> path.substringAfterLast('/')
    }
    var preview by mutableStateOf(kind == TabKind.File)
    var dirty by mutableStateOf(false)
    /** Bumped on every edit/selection change so Compose re-reads model-derived state. */
    var revision by mutableIntStateOf(0)
}

/**
 * The Explorer's inline name box (VS Code edits names in the tree itself):
 * a new file or folder inside [parent], or renaming [target].
 */
data class ExplorerEdit(val kind: Kind, val parent: String, val target: FileNode? = null) {
    enum class Kind { NewFile, NewFolder, Rename }

    val initial: String get() = target?.name ?: ""
}

enum class PaletteMode(val prefix: String, val placeholder: String) {
    Files("", "Search files by name (append : to go to line or @ to go to symbol)"),
    Commands(">", "Search commands"),
    GotoLine(":", "Type a line number to go to"),
    Symbols("@", "Go to symbol in editor"),
    WorkspaceSymbols("#", "Go to symbol in workspace"),
    /** A command's own list ([WorkbenchState.quickPick]), e.g. Change Language Mode. */
    QuickPick("", ""),
}

/**
 * A command-specific quick pick (VS Code's `showQuickPick`): the palette
 * shows [items] filtered by the typed text, under [placeholder].
 */
class QuickPick(val placeholder: String, val items: () -> List<PaletteItem>)

/**
 * One row in the palette. [label] is shown with [category] dimmed in front
 * (`File: Save`); [matches] are char indices of [label] to bold. [section] is
 * a group header shown above the row ("recently used", "other commands").
 */
data class PaletteItem(
    val label: String,
    val category: String? = null,
    val detail: String? = null,
    /** Dimmed text right after the label (VS Code's quick pick `description`). */
    val description: String? = null,
    val keybinding: KeyChord? = null,
    val matches: List<Int> = emptyList(),
    val section: String? = null,
    /** LSP `SymbolKind` of a Go to Symbol row (drawn as its codicon). */
    val symbolKind: Int? = null,
    val run: () -> Unit,
)

/**
 * What opening folders needs from the platform: the system folder picker,
 * Ferride's private projects directory and opening a local folder. The
 * activity implements it; tests pass a fake.
 */
interface WorkspaceHost {
    /** `files/projects`: the default home of new, copied and cloned projects. */
    val projectsDir: String
    /** Shows the system folder picker; the result comes back through [WorkbenchState.openWorkspace]. */
    fun pickFolder()
    /** Opens a real directory (blocks; called on the IO dispatcher). */
    fun openLocal(path: String): WorkspaceBackend
    /** Reopens a Recent entry (blocks; called on the IO dispatcher). Fails if its grant is gone. */
    fun openRecent(recent: RecentFolder): WorkspaceBackend = openLocal(recent.root)
    fun loadRecents(): List<RecentFolder> = emptyList()
    fun saveRecents(recents: List<RecentFolder>) {}
}

/** Where `settings.json` lives (a file in the app; memory in tests). */
interface SettingsStore {
    fun read(): String?
    fun write(text: String)
}

class InMemorySettingsStore(private var text: String? = null) : SettingsStore {
    override fun read(): String? = text
    override fun write(text: String) {
        this.text = text
    }
}

/**
 * Workbench state and actions, VS Code style. UI-independent: the Compose
 * shell renders it, commands mutate it. All blocking work goes to [io].
 */
class WorkbenchState(
    backend: WorkspaceBackend,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val settingsStore: SettingsStore = InMemorySettingsStore(),
    val extraThemes: List<WorkbenchColors> = emptyList(),
    var host: WorkspaceHost? = null,
    scmProvider: ScmProvider = NoScmProvider,
    credentialStore: CredentialStore = InMemoryCredentialStore(),
) {
    val commands = CommandRegistry()

    /** Source Control (git) for the workspace folder. */
    val scm = ScmState(this, scmProvider, scope, io, credentialStore)

    /** The open folder; replaced by [openWorkspace]. */
    var backend: WorkspaceBackend by mutableStateOf(backend)
        private set

    /** Recently opened folders, newest first (the Welcome page's Recent list). */
    val recentFolders = mutableStateListOf<RecentFolder>().also { list ->
        host?.let { h -> list += runCatching { h.loadRecents() }.getOrDefault(emptyList()) }
    }

    private fun rememberRecent(b: WorkspaceBackend) {
        if (b.kind == WorkspaceKind.FERRUX) return
        val entry = RecentFolder(b.kind, b.root, b.name)
        recentFolders.removeAll { it.root == entry.root }
        recentFolders.add(0, entry)
        while (recentFolders.size > MAX_RECENT_FOLDERS) recentFolders.removeAt(recentFolders.lastIndex)
        host?.saveRecents(recentFolders.toList())
    }

    fun removeRecent(recent: RecentFolder) {
        recentFolders.remove(recent)
        host?.saveRecents(recentFolders.toList())
    }

    /** Welcome › Recent: reopens a folder (drops it from the list if it can't be opened anymore). */
    fun openRecent(recent: RecentFolder) {
        val h = host ?: return
        if (recent.root == backend.root) {
            message = "${recent.name} is already open"
            return
        }
        scope.launch {
            val r = withContext(io) { runCatching { h.openRecent(recent) } }
            r.onSuccess { openWorkspace(it) }.onFailure {
                removeRecent(recent)
                message = "Couldn't open ${recent.name}: ${it.message}. It was removed from Recent."
            }
        }
    }

    /** Help: Welcome — the Get Started page as an editor tab. */
    fun openWelcome() {
        val i = tabs.indexOfFirst { it.kind == TabKind.Welcome }
        if (i >= 0) activeIndex = i else addTab(EditorTab(WELCOME_PATH, null, TabKind.Welcome).also { it.preview = false })
    }

    /** Called after [openWorkspace] switched folders (the app remembers the last one). */
    var onWorkspaceOpened: ((WorkspaceBackend) -> Unit)? = null

    /** Progress text while a folder is being copied into Ferride projects. */
    var copyProgress by mutableStateOf<String?>(null)
        private set

    /** Parse error of the user's settings.json, if any (then we never overwrite it). */
    var settingsError by mutableStateOf<String?>(null)
        private set

    var settings by mutableStateOf(loadSettings())
        private set

    private fun loadSettings(): Settings {
        val text = runCatching { settingsStore.read() }.getOrNull() ?: return Settings()
        return Settings.parseResult(text).fold(
            onSuccess = { settingsError = null; it },
            onFailure = { settingsError = it.message ?: "invalid JSON"; Settings() },
        )
    }

    /** True while the editor view has focus (keybinding context, like `editorTextFocus`). */
    var editorFocused by mutableStateOf(false)

    /** A dirty tab waiting for "Save / Don't Save / Cancel". */
    var closeRequest by mutableStateOf<EditorTab?>(null)

    /** Models of closed tabs, freed once the editor view has switched away from them. */
    private val retired = mutableListOf<EditorModel>()
    var systemDark by mutableStateOf(true)
    /** Set by the UI from the window width class (compact = phone portrait). */
    var compactWindow by mutableStateOf(false)

    var activeView by mutableStateOf(SideView.Explorer)
    var sideBarVisible by mutableStateOf(false)
    /** True when the Side Bar is docked next to the editor (expanded width) rather than a drawer. */
    var sideBarDocked by mutableStateOf(false)
    var panelVisible by mutableStateOf(false)
    var panelTab by mutableStateOf(PanelTab.Problems)

    /** Directory path → children, loaded lazily. */
    val tree = mutableStateMapOf<String, List<FileNode>>()
    val expanded = mutableStateListOf<String>()

    val tabs = mutableStateListOf<EditorTab>()
    var activeIndex by mutableIntStateOf(-1)
    val activeTab: EditorTab? get() = tabs.getOrNull(activeIndex)
    val activeModel: EditorModel? get() = activeTab?.model

    var paletteOpen by mutableStateOf(false)
    var paletteQuery by mutableStateOf("")
    /** Set while the palette shows a command's own list instead of a prefix mode. */
    var quickPick by mutableStateOf<QuickPick?>(null)
        private set
    /** Most recently run command ids, newest first. */
    val recentCommands = mutableStateListOf<String>()

    val find = FindState()

    var searchQuery by mutableStateOf("")
    var searchCaseSensitive by mutableStateOf(false)
    var searchWholeWord by mutableStateOf(false)
    var searchRegex by mutableStateOf(false)
    var searchResult by mutableStateOf<SearchReport?>(null)
    var searchError by mutableStateOf<String?>(null)

    /** File path → problems (from language servers; injected in tests). */
    val diagnostics = mutableStateMapOf<String, List<Diagnostic>>()
    val errorCount: Int get() = diagnostics.values.sumOf { d -> d.count { it.severity == 1 } }
    val warningCount: Int get() = diagnostics.values.sumOf { d -> d.count { it.severity == 2 } }

    val output = mutableStateListOf<String>()
    var message by mutableStateOf<String?>(null)
    var ferrux by mutableStateOf<FerruxStatus>(FerruxStatus.NotInstalled)
        private set
    var branch by mutableStateOf<String?>(null)
        private set

    /** The editor view currently attached to the active tab (set by the UI). */
    var editor: EditorActions? = null

    // ----- symbols: Outline, breadcrumbs, Go to Symbol, sticky scroll -------------

    /** The Explorer's OUTLINE section is expanded (VS Code shows it collapsed at first). */
    var outlineExpanded by mutableStateOf(false)
    /** Outline rows the user collapsed, by symbol path ("Circle/area"). */
    val outlineCollapsed = mutableStateListOf<String>()
    private var symbolCache: Triple<EditorModel, Long, List<DocumentSymbol>>? = null

    /** Symbols of the active editor, recomputed when its text changes. */
    fun activeSymbols(): List<DocumentSymbol> {
        val m = activeModel?.takeIf { !it.isClosed } ?: return emptyList()
        symbolCache?.let { (cm, v, list) -> if (cm === m && v == m.version) return list }
        val list = runCatching { m.symbols() }.getOrDefault(emptyList())
        symbolCache = Triple(m, m.version, list)
        return list
    }

    /** "Circle/area": the names from the outermost symbol down to [index]. */
    fun symbolPath(symbols: List<DocumentSymbol>, index: Int): String {
        val names = ArrayDeque<String>()
        var i = index
        while (i >= 0) {
            names.addFirst(symbols[i].name)
            i = symbols[i].parent
        }
        return names.joinToString("/")
    }

    /** Indices of the symbols enclosing [offset], outermost first (the breadcrumbs). */
    fun symbolChainAt(symbols: List<DocumentSymbol>, offset: Int): List<Int> {
        var deepest = -1
        symbols.forEachIndexed { i, s ->
            if (offset >= s.start && offset <= s.end && (deepest < 0 || s.depth >= symbols[deepest].depth)) deepest = i
        }
        val chain = ArrayDeque<Int>()
        var i = deepest
        while (i >= 0) {
            chain.addFirst(i)
            i = symbols[i].parent
        }
        return chain
    }

    /** Selects a symbol's name in the editor and scrolls to it (Outline, `@`, breadcrumbs). */
    fun revealSymbol(symbol: DocumentSymbol) {
        paletteOpen = false
        if (!sideBarDocked) sideBarVisible = false
        editor?.select(symbol.nameStart, symbol.nameEnd)
    }

    val colors: WorkbenchColors
        get() {
            val named = (BuiltInThemes + extraThemes).firstOrNull { it.name == settings.colorTheme }
            return when {
                named != null && !settings.autoDetectColorScheme -> named
                else -> if (systemDark) DarkModern else LightModern
            }
        }

    /** `editor.wordWrap`: the user's value, else on for compact windows (phones). */
    val wordWrap: Boolean get() = settings.wordWrap ?: compactWindow

    /** `editor.minimap.enabled`: the user's value, else on for wide windows and off on phones. */
    val minimapEnabled: Boolean get() = settings.minimap ?: !compactWindow

    init {
        registerCommands()
        scm.registerCommands()
        log("Opened workspace ${backend.name} (${backend.root})")
    }

    /**
     * First load. With [showWelcome] (the app, not tests) the Welcome page
     * opens too, per `workbench.startupEditor`.
     */
    fun start(openFirst: String? = "src/main.rs", showWelcome: Boolean = false) {
        rememberRecent(backend)
        if (showWelcome && settings.startupEditor == "welcomePage") openWelcome()
        settingsError?.let {
            message = "Couldn't parse settings.json ($it). It was left untouched; fix it and save."
            openSettingsJson()
        }
        scope.launch {
            ferrux = withContext(io) { runCatching { backend.ferrux() }.getOrDefault(FerruxStatus.NotInstalled) }
            branch = withContext(io) { runCatching { backend.gitBranch() }.getOrNull() }
            scm.start()
            loadDir(backend.root)
            if (openFirst != null) openFile("${backend.root}/$openFirst", preview = false)
        }
    }

    /**
     * Switches to another folder (VS Code's File: Open Folder): closes all
     * editors and resets the Explorer and Search. Refuses while an editor has
     * unsaved changes. Returns whether it switched.
     */
    fun openWorkspace(next: WorkspaceBackend): Boolean {
        if (tabs.any { it.dirty }) {
            message = "Save or close editors with unsaved changes before opening another folder"
            return false
        }
        tabs.forEach { retire(it.model) }
        tabs.clear()
        activeIndex = -1
        tree.clear()
        expanded.clear()
        searchResult = null
        searchError = null
        diagnostics.clear()
        backend = next
        log("Opened workspace ${next.name} (${next.root})")
        rememberRecent(next)
        onWorkspaceOpened?.invoke(next)
        scm.start()
        if (settings.startupEditor == "welcomePage") openWelcome()
        scope.launch {
            branch = withContext(io) { runCatching { next.gitBranch() }.getOrNull() }
            loadDir(next.root)
        }
        return true
    }

    /**
     * Copies a system-picker (SAF) folder into `files/projects/<name>` and
     * opens the copy, so Git and fast search work on it.
     */
    fun copyIntoProjects() {
        val h = host ?: return
        val source = backend
        if (copyProgress != null) return
        copyProgress = "Copying ${source.name} into Ferride projects…"
        scope.launch {
            val result = withContext(io) {
                runCatching {
                    val dest = uniqueDir(h.projectsDir, source.name)
                    h.openLocal(source.copyInto(dest))
                }
            }
            copyProgress = null
            result.onSuccess { local ->
                if (openWorkspace(local)) message = "Copied to ${local.root}. Git and search now work on this copy."
            }.onFailure { message = "Couldn't copy the folder: ${it.message}" }
        }
    }

    fun log(line: String) {
        output += line
    }

    /**
     * Changes settings and writes only the changed keys into settings.json as
     * minimal text edits (comments and unknown keys survive). Refuses while the
     * file has a parse error, so a typo never wipes the user's settings.
     */
    fun updateSettings(transform: (Settings) -> Settings) {
        if (settingsError != null) {
            message = "settings.json has errors; fix them first"
            openSettingsJson()
            return
        }
        val old = settings
        val new = transform(old)
        if (new == old) return
        val result = runCatching {
            val text = settingsStore.read() ?: ""
            settingsStore.write(new.applyTo(text, old))
        }
        result.onSuccess { settings = new }.onFailure { message = "Couldn't save settings: ${it.message}" }
    }

    // ----- explorer --------------------------------------------------------

    fun loadDir(path: String) {
        scope.launch {
            val children = withContext(io) { runCatching { backend.listDir(path) } }
            children.onSuccess { tree[path] = it }.onFailure { message = it.message }
        }
    }

    fun toggleDir(path: String) {
        if (path in expanded) {
            expanded -= path
        } else {
            expanded += path
            if (path !in tree) loadDir(path)
        }
    }

    // ----- explorer actions ----------------------------------------------------

    /** The inline new-file / new-folder / rename box, if open. */
    var explorerEdit by mutableStateOf<ExplorerEdit?>(null)
        private set
    /** "Delete 'x'?" confirmation. */
    var deleteRequest by mutableStateOf<FileNode?>(null)
    /** The open context menu (long press), drawn by the workbench. */
    var contextMenu by mutableStateOf<ContextMenuRequest?>(null)
    /** A path the Explorer should scroll to (Reveal Active File). */
    var revealRequest by mutableStateOf<String?>(null)
    /** Copies text to the system clipboard (set by the UI). */
    var setClipboard: (String) -> Unit = {}

    private val rootNode: FileNode get() = FileNode(backend.name, backend.root, "", true, false)

    /** Folder that new files go into: the given one, else the active file's folder, else the root. */
    private fun targetDir(dir: String?): String =
        dir ?: activeTab?.takeIf { it.kind == TabKind.File && it.path.startsWith(backend.root) }?.path?.substringBeforeLast('/')
            ?: backend.root

    fun startNewFile(dir: String? = null) = startEdit(ExplorerEdit(ExplorerEdit.Kind.NewFile, targetDir(dir)))
    fun startNewFolder(dir: String? = null) = startEdit(ExplorerEdit(ExplorerEdit.Kind.NewFolder, targetDir(dir)))
    fun startRename(node: FileNode) = startEdit(ExplorerEdit(ExplorerEdit.Kind.Rename, node.path.substringBeforeLast('/'), node))

    private fun startEdit(edit: ExplorerEdit) {
        activeView = SideView.Explorer
        sideBarVisible = true
        // The box shows inside its folder, so open the folder (and its parents).
        expandTo(edit.parent)
        explorerEdit = edit
    }

    fun cancelExplorerEdit() {
        explorerEdit = null
    }

    /** VS Code's validation messages for the name box; null when [name] is acceptable. */
    fun validateName(edit: ExplorerEdit, name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "A file or folder name must be provided."
        if (trimmed.startsWith('/') || trimmed.endsWith('/') || trimmed.split('/').any { it == ".." || it == "." || it.isEmpty() }) {
            return "The name **$trimmed** is not valid as a file or folder name. Please choose a different name."
        }
        if (edit.kind == ExplorerEdit.Kind.Rename && trimmed == edit.target?.name) return null
        val siblings = tree[edit.parent].orEmpty()
        if (siblings.any { it.name == trimmed.substringBefore('/') && !trimmed.contains('/') }) {
            return "A file or folder **$trimmed** already exists at this location. Please choose a different name."
        }
        return null
    }

    /** Applies the name box (Enter). Names may contain `/` to create nested folders, like VS Code. */
    fun commitExplorerEdit(name: String) {
        val edit = explorerEdit ?: return
        val trimmed = name.trim()
        if (validateName(edit, trimmed) != null) return
        explorerEdit = null
        if (edit.kind == ExplorerEdit.Kind.Rename && trimmed == edit.target?.name) return
        val path = "${edit.parent}/$trimmed"
        scope.launch {
            val result = withContext(io) {
                runCatching {
                    when (edit.kind) {
                        ExplorerEdit.Kind.NewFile -> backend.createFile(path)
                        ExplorerEdit.Kind.NewFolder -> backend.createDir(path)
                        ExplorerEdit.Kind.Rename -> backend.rename(edit.target!!.path, path)
                    }
                }
            }
            result.onFailure { message = it.message }.onSuccess { created ->
                refreshDir(edit.parent)
                when (edit.kind) {
                    ExplorerEdit.Kind.NewFile -> {
                        trimmed.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { expandTo("${edit.parent}/$it") }
                        openFile(created, preview = false)
                    }
                    ExplorerEdit.Kind.NewFolder -> expandTo(created)
                    ExplorerEdit.Kind.Rename -> retarget(edit.target!!.path, created)
                }
            }
        }
    }

    /** Open editors follow a renamed file or folder. */
    private fun retarget(from: String, to: String) {
        tabs.forEachIndexed { i, tab ->
            if (tab.kind == TabKind.File && (tab.path == from || tab.path.startsWith("$from/"))) {
                val moved = EditorTab(to + tab.path.removePrefix(from), tab.model, tab.kind)
                moved.preview = tab.preview
                moved.dirty = tab.dirty
                tabs[i] = moved
            }
        }
        if (expanded.remove(from)) expanded += to
        tree.remove(from)
    }

    fun requestDelete(node: FileNode) {
        deleteRequest = node
    }

    /** Confirmed delete: removes the file or folder and closes its editors. */
    fun confirmDelete() {
        val node = deleteRequest ?: return
        deleteRequest = null
        scope.launch {
            val r = withContext(io) { runCatching { backend.delete(node.path) } }
            r.onFailure { message = it.message }.onSuccess {
                for (i in tabs.indices.reversed()) {
                    val t = tabs[i]
                    if (t.kind == TabKind.File && (t.path == node.path || t.path.startsWith("${node.path}/"))) closeTab(i)
                }
                expanded.removeAll { it == node.path || it.startsWith("${node.path}/") }
                tree.keys.filter { it == node.path || it.startsWith("${node.path}/") }.forEach { tree.remove(it) }
                refreshDir(node.path.substringBeforeLast('/'))
            }
        }
    }

    fun copyPath(node: FileNode, relative: Boolean) {
        setClipboard(if (relative) node.relPath else node.path)
    }

    /** Re-reads one folder (after a change made here or outside). */
    fun refreshDir(dir: String) {
        backend.refresh(dir)
        loadDir(dir)
    }

    /** Refresh Explorer: re-reads the root and every expanded folder. */
    fun refreshExplorer() {
        backend.refresh(backend.root)
        loadDir(backend.root)
        expanded.toList().forEach(::loadDir)
    }

    fun collapseExplorer() {
        expanded.clear()
    }

    /** Expands [dir] and every folder above it (loading them as needed). */
    private fun expandTo(dir: String) {
        val root = backend.root
        if (!dir.startsWith(root)) return
        var path = root
        for (part in dir.removePrefix(root).trim('/').split('/').filter { it.isNotEmpty() }) {
            path = "$path/$part"
            if (path !in expanded) expanded += path
            if (path !in tree) loadDir(path)
        }
    }

    /** File: Reveal Active File in Explorer View. */
    fun revealActiveFile() {
        val path = activeTab?.takeIf { it.kind == TabKind.File }?.path ?: return
        activeView = SideView.Explorer
        sideBarVisible = true
        expandTo(path.substringBeforeLast('/'))
        revealRequest = path
    }

    /** Long press on an Explorer row: VS Code's context menu for files and folders. */
    fun openExplorerMenu(node: FileNode, x: Float, y: Float) {
        val dir = if (node.isDir) node.path else node.path.substringBeforeLast('/')
        val items = buildList {
            if (node.isDir) {
                add(ContextMenuItem("New File…") { startNewFile(dir) })
                add(ContextMenuItem("New Folder…") { startNewFolder(dir) })
            }
            add(ContextMenuItem("Copy Path", keybinding = "Shift+Alt+C", separatorBefore = node.isDir) { copyPath(node, relative = false) })
            add(ContextMenuItem("Copy Relative Path") { copyPath(node, relative = true) })
            val isRoot = node.path == backend.root
            add(ContextMenuItem("Rename…", keybinding = "F2", enabled = !isRoot, separatorBefore = true) { startRename(node) })
            add(ContextMenuItem("Delete", keybinding = "Delete", enabled = !isRoot) { requestDelete(node) })
        }
        contextMenu = ContextMenuRequest(x, y, items)
    }

    // ----- editors ---------------------------------------------------------

    fun openFile(path: String, preview: Boolean = true, line: Int? = null) {
        val existing = tabs.indexOfFirst { it.path == path }
        if (existing >= 0) {
            activeIndex = existing
            if (!preview) tabs[existing].preview = false
            line?.let { l -> editor?.goToLine(l) }
            return
        }
        scope.launch {
            // Reading and the initial parse both happen off the main thread.
            val loaded = withContext(io) {
                runCatching { backend.createModel(backend.readFile(path), path.substringAfterLast('/')) }
            }
            loaded.onFailure { message = it.message }.onSuccess { model ->
                if (line != null) model.selection = Sel.caret(model.offsetAt(line, 0))
                addTab(EditorTab(path, model).also { it.preview = preview })
            }
        }
    }

    private fun addTab(tab: EditorTab) {
        val previewIndex = tabs.indexOfFirst { it.preview && !it.dirty }
        if (tab.preview && previewIndex >= 0) {
            retire(tabs[previewIndex].model)
            tabs[previewIndex] = tab
            activeIndex = previewIndex
        } else {
            tabs += tab
            activeIndex = tabs.lastIndex
        }
    }

    /** Opens (or focuses) the Settings editor tab. */
    fun openSettings() {
        val i = tabs.indexOfFirst { it.kind == TabKind.Settings }
        if (i >= 0) activeIndex = i else addTab(EditorTab(SETTINGS_PATH, null, TabKind.Settings))
        if (!sideBarDocked) sideBarVisible = false
    }

    /** Opens `settings.json` as a text editor tab ("Open Settings (JSON)"). */
    fun openSettingsJson() {
        val i = tabs.indexOfFirst { it.kind == TabKind.SettingsJson }
        if (i >= 0) {
            activeIndex = i
            return
        }
        val text = settingsStore.read() ?: settings.toJson()
        val model = backend.createModel(text, "settings.json")
        addTab(EditorTab(SETTINGS_JSON_PATH, model, TabKind.SettingsJson).also { it.preview = false })
    }

    private fun retire(model: EditorModel?) {
        if (model != null) retired += model
    }

    /**
     * Frees models of closed tabs except [inUse]. The UI calls this after the
     * editor view has switched to the new model, so the view never touches a
     * freed native document.
     */
    fun disposeRetired(inUse: EditorModel?) {
        val it = retired.iterator()
        while (it.hasNext()) {
            val m = it.next()
            if (m !== inUse) {
                m.close()
                it.remove()
            }
        }
    }

    /** Number of closed-but-not-yet-freed models (tests). */
    val retiredCount: Int get() = retired.size

    /** Close with VS Code's unsaved-changes prompt for dirty tabs. */
    fun requestCloseTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        if (tab.dirty) closeRequest = tab else closeTab(index)
    }

    fun resolveCloseRequest(save: Boolean?) {
        val tab = closeRequest ?: return
        closeRequest = null
        when (save) {
            null -> {}
            true -> save(tab) { closeTab(tabs.indexOf(tab)) }
            false -> closeTab(tabs.indexOf(tab))
        }
    }

    fun closeTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        retire(tab.model)
        tabs.removeAt(index)
        activeIndex = when {
            tabs.isEmpty() -> -1
            activeIndex >= tabs.size -> tabs.lastIndex
            index < activeIndex -> activeIndex - 1
            else -> activeIndex
        }
    }

    /** Called by the editor view after each edit or cursor move. */
    fun onEditorChanged(tab: EditorTab, contentChanged: Boolean) {
        if (contentChanged) tab.preview = false
        tab.dirty = tab.model?.isDirty == true
        tab.revision++
    }

    /**
     * Saves a tab. The version is captured before the (async) write, so edits
     * typed while writing stay dirty. [then] runs after a successful save.
     */
    fun save(tab: EditorTab? = activeTab, then: () -> Unit = {}) {
        tab ?: return
        val model = tab.model ?: return
        val version = model.version
        val text = model.text()
        if (tab.kind == TabKind.SettingsJson) {
            runCatching { settingsStore.write(text) }
                .onSuccess {
                    Settings.parseResult(text).fold(
                        onSuccess = { settings = it; settingsError = null },
                        onFailure = { settingsError = it.message; message = "settings.json has errors: ${it.message}" },
                    )
                    model.markSaved(version)
                    tab.dirty = model.isDirty
                    log("Saved settings.json")
                    then()
                }
                .onFailure { message = "Couldn't save settings.json: ${it.message}" }
            return
        }
        scope.launch {
            val r = withContext(io) { runCatching { backend.writeFile(tab.path, text) } }
            r.onSuccess {
                if (!model.isClosed) {
                    model.markSaved(version)
                    tab.dirty = model.isDirty
                }
                log("Saved ${tab.name}")
                scm.refresh()
                then()
            }.onFailure { message = "Couldn't save ${tab.name}: ${it.message}" }
        }
    }

    // ----- find / replace (in the active editor) --------------------------------

    fun openFind(replace: Boolean) {
        val selected = editor?.selectedText()?.takeIf { it.isNotEmpty() && '\n' !in it }
        if (selected != null) find.query = selected
        find.visible = true
        find.replaceVisible = replace
        find.current = -1
    }

    fun closeFind() {
        find.visible = false
        find.current = -1
    }

    fun findMatchesInActive(): List<IntRange> {
        val m = activeModel ?: return emptyList()
        if (!find.visible) return emptyList()
        return find.matches(m.text())
    }

    /** Moves to the next (or previous) match after the caret and selects it. */
    fun findNext(backwards: Boolean = false) {
        val m = activeModel ?: return
        val matches = find.matches(m.text())
        if (matches.isEmpty()) {
            find.current = -1
            return
        }
        val caret = m.selection.start
        val i = if (find.current in matches.indices) {
            (find.current + if (backwards) -1 else 1).mod(matches.size)
        } else if (backwards) {
            matches.indexOfLast { it.first < caret }.let { if (it < 0) matches.lastIndex else it }
        } else {
            matches.indexOfFirst { it.first >= caret }.let { if (it < 0) 0 else it }
        }
        find.current = i
        editor?.select(matches[i].first, matches[i].last + 1)
    }

    fun replaceCurrent() {
        val m = activeModel ?: return
        val matches = find.matches(m.text())
        val i = find.current
        if (i !in matches.indices) {
            findNext()
            return
        }
        editor?.replaceRange(matches[i].first, matches[i].last + 1, find.replacement)
        find.current = i - 1
        findNext()
    }

    fun replaceAll() {
        val m = activeModel ?: return
        val matches = find.matches(m.text())
        if (matches.isEmpty()) return
        // Back to front so offsets stay valid.
        matches.asReversed().forEach { r -> editor?.replaceRange(r.first, r.last + 1, find.replacement) }
        message = "Replaced ${matches.size} occurrences"
        find.current = -1
    }

    // ----- search ----------------------------------------------------------

    /** The running search, cancelled when a newer one starts. */
    private var searchToken: SearchToken? = null
    private var searchGeneration = 0

    /** True while a search is running (the Search view shows progress). */
    var searching by mutableStateOf(false)
        private set

    /**
     * Runs the Search view's query. A newer query cancels the running search,
     * and results from an older one are dropped even if it finishes last (#9).
     */
    fun runSearch() {
        val q = searchQuery
        searchToken?.cancel()
        val generation = ++searchGeneration
        // System-picker (SAF) folders have no real path for ripgrep; the view explains that.
        if (!backend.supportsTextSearch) {
            searching = false
            return
        }
        if (q.isEmpty()) {
            searchToken = null
            searchResult = null
            searchError = null
            searching = false
            return
        }
        val token = SearchToken().also { searchToken = it }
        val regex = searchRegex
        val caseSensitive = searchCaseSensitive
        val wholeWord = searchWholeWord
        searching = true
        scope.launch {
            val r = withContext(io) {
                runCatching { backend.searchText(q, regex, caseSensitive, wholeWord, token) }
            }
            if (generation != searchGeneration) return@launch
            searching = false
            r.onSuccess {
                searchResult = it
                searchError = null
            }.onFailure { searchError = it.message }
        }
    }

    // ----- views -------------------------------------------------------------

    fun showView(view: SideView) {
        if (sideBarVisible && activeView == view) {
            sideBarVisible = false
        } else {
            activeView = view
            sideBarVisible = true
        }
    }

    fun showPanel(tab: PanelTab) {
        if (panelVisible && panelTab == tab) {
            panelVisible = false
        } else {
            panelTab = tab
            panelVisible = true
        }
    }

    fun openPalette(mode: PaletteMode) {
        quickPick = null
        paletteQuery = mode.prefix
        paletteOpen = true
    }

    fun showQuickPick(pick: QuickPick) {
        quickPick = pick
        paletteQuery = ""
        paletteOpen = true
    }

    /** Placeholder of the palette's input for the current mode. */
    fun palettePlaceholder(): String = quickPick?.placeholder ?: paletteMode().placeholder

    /** Esc: close the innermost overlay, then the find widget, then collapse the selection. */
    fun escape() {
        when {
            paletteOpen -> paletteOpen = false
            sideBarVisible && !sideBarDocked -> sideBarVisible = false
            find.visible -> closeFind()
            else -> editor?.escape()
        }
    }

    fun paletteMode(): PaletteMode = when {
        quickPick != null -> PaletteMode.QuickPick
        paletteQuery.startsWith(">") -> PaletteMode.Commands
        paletteQuery.startsWith(":") -> PaletteMode.GotoLine
        paletteQuery.startsWith("@") -> PaletteMode.Symbols
        paletteQuery.startsWith("#") -> PaletteMode.WorkspaceSymbols
        else -> PaletteMode.Files
    }

    private fun runFromPalette(c: Command) {
        paletteOpen = false
        recordRecent(c.id)
        c.run()
    }

    private fun recordRecent(id: String) {
        recentCommands.remove(id)
        recentCommands.add(0, id)
        while (recentCommands.size > MAX_RECENT) recentCommands.removeAt(recentCommands.lastIndex)
    }

    /** Palette rows for the current query. */
    fun paletteItems(fileHits: List<FileHit>, symbolHits: List<SymbolHit> = emptyList()): List<PaletteItem> {
        val mode = paletteMode()
        val q = paletteQuery.removePrefix(mode.prefix).trim()
        return when (mode) {
            PaletteMode.Commands -> {
                val enabled = commands.search(q).filter { it.enabled() }
                val recent = recentCommands.mapNotNull { id -> enabled.firstOrNull { it.id == id } }
                val ordered = if (q.isEmpty()) recent + enabled.filter { it !in recent } else enabled
                ordered.mapIndexed { i, c ->
                    PaletteItem(
                        label = c.title,
                        category = c.category,
                        detail = c.id,
                        keybinding = c.keybinding,
                        matches = fuzzyPositions(q, c.label).map { it - (c.label.length - c.title.length) }
                            .filter { it >= 0 },
                        section = when {
                            recent.isEmpty() -> null
                            i == 0 && c in recent -> "recently used"
                            i == recent.size && q.isEmpty() -> "other commands"
                            else -> null
                        },
                    ) { runFromPalette(c) }
                }
            }
            PaletteMode.GotoLine -> {
                val line = q.toIntOrNull()
                val max = activeModel?.lineCount ?: 0
                if (line == null) listOf(PaletteItem("Type a line number between 1 and $max to navigate to") {})
                else listOf(PaletteItem("Go to line $line") { paletteOpen = false; editor?.goToLine(line - 1) })
            }
            PaletteMode.Symbols -> {
                // Go to Symbol in Editor: document order, the container as description.
                val symbols = activeSymbols()
                val rows = symbols.mapIndexedNotNull { i, s ->
                    val m = if (q.isEmpty()) emptyList() else fuzzyPositions(q, s.name)
                    if (q.isNotEmpty() && m.isEmpty()) return@mapIndexedNotNull null
                    PaletteItem(
                        label = s.name,
                        description = symbols.getOrNull(s.parent)?.name,
                        matches = m,
                        symbolKind = s.kind,
                    ) { revealSymbol(s) }
                }
                when {
                    activeModel == null -> listOf(PaletteItem("Open a file to go to its symbols") {})
                    symbols.isEmpty() -> listOf(PaletteItem("No editor symbols") {})
                    else -> rows.mapIndexed { i, r -> if (i == 0) r.copy(section = "symbols (${rows.size})") else r }
                }
            }
            PaletteMode.WorkspaceSymbols -> symbolHits.map { h ->
                // VS Code: name, then the container and file dimmed.
                PaletteItem(
                    label = h.name,
                    description = listOfNotNull(h.container, h.relPath).joinToString(" · "),
                    matches = h.positions,
                    symbolKind = h.kind,
                ) {
                    paletteOpen = false
                    openFile(h.path, preview = true, line = h.line)
                }
            }
            PaletteMode.QuickPick -> {
                val all = quickPick?.items?.invoke().orEmpty()
                if (q.isEmpty()) {
                    all
                } else {
                    // Keep the original order (VS Code doesn't re-sort a quick pick) and drop section headers.
                    all.mapNotNull { item ->
                        val m = fuzzyPositions(q, item.label)
                        if (m.isEmpty()) null else item.copy(matches = m, section = null)
                    }
                }
            }
            PaletteMode.Files -> fileHits.map { hit ->
                val name = hit.relPath.substringAfterLast('/')
                PaletteItem(
                    label = name,
                    detail = hit.relPath.substringBeforeLast('/', ""),
                    matches = fuzzyPositions(q, name),
                ) {
                    paletteOpen = false
                    openFile(hit.path, preview = true)
                }
            }
        }
    }

    // ----- keybindings -------------------------------------------------------

    /** Global keybinding dispatch for hardware keyboards. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE && event.action == KeyEvent.ACTION_DOWN &&
            (paletteOpen || find.visible || (sideBarVisible && !sideBarDocked))
        ) {
            escape()
            return true
        }
        val cmd = commands.forKey(event, editorFocused) ?: return false
        if (!cmd.enabled()) return false
        cmd.run()
        return true
    }

    private fun registerCommands() {
        val r = commands
        fun cmd(
            id: String,
            title: String,
            category: String?,
            key: KeyChord? = null,
            enabled: () -> Boolean = { true },
            run: () -> Unit,
        ) = r.register(Command(id, title, category, key, enabled, run = run))
        val hasEditor = { activeModel != null }

        cmd(CommandIds.SHOW_COMMANDS, "Show All Commands", null, KeyChord.ctrl(KeyEvent.KEYCODE_P, shift = true)) {
            openPalette(PaletteMode.Commands)
        }
        cmd(CommandIds.QUICK_OPEN, "Go to File…", null, KeyChord.ctrl(KeyEvent.KEYCODE_P)) { openPalette(PaletteMode.Files) }
        cmd(CommandIds.OPEN_FOLDER, "Open Folder…", "File", enabled = { host != null }) { host?.pickFolder() }
        cmd(CommandIds.EXPLORER_NEW_FILE, "New File…", "File") { startNewFile() }
        cmd(CommandIds.WELCOME, "Welcome", "Help") { openWelcome() }
        cmd(CommandIds.EXPLORER_NEW_FOLDER, "New Folder…", "File") { startNewFolder() }
        cmd(CommandIds.REFRESH_EXPLORER, "Refresh Explorer", "File") { refreshExplorer() }
        cmd(CommandIds.COLLAPSE_EXPLORER, "Collapse Folders in Explorer", "File") { collapseExplorer() }
        val hasFile = { activeTab?.kind == TabKind.File }
        cmd(CommandIds.REVEAL_IN_EXPLORER, "Reveal Active File in Explorer View", "File", enabled = hasFile) { revealActiveFile() }
        cmd(CommandIds.COPY_PATH, "Copy Path of Active File", "File", KeyChord(KeyEvent.KEYCODE_C, shift = true, alt = true), hasFile) {
            activeTab?.let { setClipboard(it.path) }
        }
        cmd(CommandIds.COPY_RELATIVE_PATH, "Copy Relative Path of Active File", "File", enabled = hasFile) {
            activeTab?.let { setClipboard(it.path.removePrefix(backend.root).trimStart('/')) }
        }
        cmd(
            CommandIds.COPY_INTO_PROJECTS,
            "Copy Folder into Ferride Projects",
            "File",
            enabled = { host != null && backend.kind == WorkspaceKind.SAF },
        ) { copyIntoProjects() }
        cmd(CommandIds.GOTO_LINE, "Go to Line/Column…", "Go", KeyChord.ctrl(KeyEvent.KEYCODE_G), hasEditor) {
            openPalette(PaletteMode.GotoLine)
        }
        cmd(CommandIds.GOTO_SYMBOL, "Go to Symbol in Editor…", "Go", KeyChord.ctrl(KeyEvent.KEYCODE_O, shift = true), hasEditor) {
            openPalette(PaletteMode.Symbols)
        }
        cmd(CommandIds.SHOW_ALL_SYMBOLS, "Go to Symbol in Workspace…", "Go", KeyChord.ctrl(KeyEvent.KEYCODE_T)) {
            openPalette(PaletteMode.WorkspaceSymbols)
        }
        cmd(CommandIds.CHANGE_LANGUAGE_MODE, "Change Language Mode", null, enabled = hasEditor) {
            showQuickPick(languagePick())
        }
        cmd(CommandIds.SAVE, "Save", "File", KeyChord.ctrl(KeyEvent.KEYCODE_S), hasEditor) { save() }
        cmd(CommandIds.SAVE_ALL, "Save All", "File", KeyChord(KeyEvent.KEYCODE_S, ctrl = true, alt = true)) {
            tabs.filter { it.dirty }.forEach { save(it) }
        }
        cmd(CommandIds.CLOSE_EDITOR, "Close Editor", "View", KeyChord.ctrl(KeyEvent.KEYCODE_W), { activeTab != null }) {
            requestCloseTab(activeIndex)
        }
        cmd(CommandIds.NEXT_EDITOR, "Open Next Editor", "View", KeyChord.ctrl(KeyEvent.KEYCODE_PAGE_DOWN), { tabs.isNotEmpty() }) {
            activeIndex = (activeIndex + 1) % tabs.size
        }
        cmd(CommandIds.PREVIOUS_EDITOR, "Open Previous Editor", "View", KeyChord.ctrl(KeyEvent.KEYCODE_PAGE_UP), { tabs.isNotEmpty() }) {
            activeIndex = (activeIndex - 1 + tabs.size) % tabs.size
        }
        cmd(CommandIds.TOGGLE_SIDEBAR, "Toggle Primary Side Bar Visibility", "View", KeyChord.ctrl(KeyEvent.KEYCODE_B)) {
            sideBarVisible = !sideBarVisible
        }
        cmd(CommandIds.TOGGLE_PANEL, "Toggle Panel Visibility", "View", KeyChord.ctrl(KeyEvent.KEYCODE_J)) {
            panelVisible = !panelVisible
        }
        cmd(CommandIds.VIEW_EXPLORER, "Show Explorer", "View", KeyChord.ctrl(KeyEvent.KEYCODE_E, shift = true)) {
            showView(SideView.Explorer)
        }
        cmd(CommandIds.VIEW_SEARCH, "Show Search", "View", KeyChord.ctrl(KeyEvent.KEYCODE_F, shift = true)) {
            showView(SideView.Search)
        }
        cmd(CommandIds.VIEW_SCM, "Show Source Control", "View", KeyChord.ctrl(KeyEvent.KEYCODE_G, shift = true)) {
            showView(SideView.SourceControl)
        }
        cmd(CommandIds.VIEW_LANGUAGES, "Show Languages", "View", KeyChord.ctrl(KeyEvent.KEYCODE_X, shift = true)) {
            showView(SideView.Languages)
        }
        cmd(CommandIds.VIEW_PROBLEMS, "Focus Problems", "View", KeyChord.ctrl(KeyEvent.KEYCODE_M, shift = true)) {
            showPanel(PanelTab.Problems)
        }
        cmd(CommandIds.VIEW_OUTPUT, "Toggle Output", "View", KeyChord.ctrl(KeyEvent.KEYCODE_U, shift = true)) {
            showPanel(PanelTab.Output)
        }
        cmd(CommandIds.TOGGLE_TERMINAL, "Toggle Terminal", "View", KeyChord.ctrl(KeyEvent.KEYCODE_GRAVE)) {
            showPanel(PanelTab.Terminal)
        }
        cmd(CommandIds.OPEN_SETTINGS, "Open Settings", "Preferences", KeyChord.ctrl(KeyEvent.KEYCODE_COMMA)) {
            openSettings()
        }
        cmd(CommandIds.OPEN_SETTINGS_JSON, "Open User Settings (JSON)", "Preferences") { openSettingsJson() }
        cmd(CommandIds.TOGGLE_THEME, "Toggle between Light/Dark Themes", "Preferences") {
            val next = if (colors.isDark) LightModern else DarkModern
            updateSettings { it.copy(colorTheme = next.name, autoDetectColorScheme = false) }
        }
        // Text-editing chords only fire while the editor has focus, so Ctrl+C/V/X/A/Z
        // keep working normally in the palette, search box and settings fields.
        fun editCmd(id: String, title: String, category: String, key: KeyChord, inPalette: Boolean = true, run: () -> Unit) =
            r.register(Command(id, title, category, key, hasEditor, inPalette = inPalette, keyWhenEditorFocus = true, run = run))
        editCmd(CommandIds.UNDO, "Undo", "Edit", KeyChord.ctrl(KeyEvent.KEYCODE_Z)) { editor?.undo() }
        editCmd(CommandIds.REDO, "Redo", "Edit", KeyChord.ctrl(KeyEvent.KEYCODE_Y)) { editor?.redo() }
        editCmd("redo.alt", "Redo", "Edit", KeyChord.ctrl(KeyEvent.KEYCODE_Z, shift = true), inPalette = false) { editor?.redo() }
        editCmd(CommandIds.SELECT_ALL, "Select All", "Selection", KeyChord.ctrl(KeyEvent.KEYCODE_A)) { editor?.selectAll() }
        editCmd(CommandIds.COPY, "Copy", "Edit", KeyChord.ctrl(KeyEvent.KEYCODE_C)) { editor?.copy() }
        editCmd(CommandIds.CUT, "Cut", "Edit", KeyChord.ctrl(KeyEvent.KEYCODE_X)) { editor?.cut() }
        editCmd(CommandIds.PASTE, "Paste", "Edit", KeyChord.ctrl(KeyEvent.KEYCODE_V)) { editor?.paste() }
        cmd(CommandIds.FORMAT, "Format Document", null, KeyChord(KeyEvent.KEYCODE_F, shift = true, alt = true), hasEditor) {
            message = "Formatting needs a language server (requires Ferrux)"
        }
        cmd(CommandIds.FIND, "Find", "Edit", KeyChord.ctrl(KeyEvent.KEYCODE_F), hasEditor) { openFind(replace = false) }
        cmd(CommandIds.REPLACE, "Replace", "Edit", KeyChord.ctrl(KeyEvent.KEYCODE_H), hasEditor) { openFind(replace = true) }
        cmd(CommandIds.FIND_NEXT, "Find Next", "Edit", KeyChord(KeyEvent.KEYCODE_F3), { find.visible }) { findNext() }
        cmd(CommandIds.FIND_PREVIOUS, "Find Previous", "Edit", KeyChord(KeyEvent.KEYCODE_F3, shift = true), { find.visible }) {
            findNext(backwards = true)
        }
        cmd(CommandIds.TOGGLE_WORD_WRAP, "Toggle Word Wrap", "View", KeyChord(KeyEvent.KEYCODE_Z, alt = true)) {
            updateSettings { it.copy(wordWrap = !wordWrap) }
        }
        cmd(CommandIds.ZOOM_IN, "Increase Editor Font Size", "Editor", KeyChord.ctrl(KeyEvent.KEYCODE_EQUALS)) {
            updateSettings { it.copy(fontSize = (it.fontSize + 1).coerceAtMost(48)) }
        }
        cmd(CommandIds.ZOOM_OUT, "Decrease Editor Font Size", "Editor", KeyChord.ctrl(KeyEvent.KEYCODE_MINUS)) {
            updateSettings { it.copy(fontSize = (it.fontSize - 1).coerceAtLeast(6)) }
        }
    }

    /**
     * VS Code's "Select Language Mode" list: Auto Detect, then every language
     * by name with its id, the current one marked "Configured Language".
     */
    fun languagePick(): QuickPick = QuickPick("Select Language Mode") {
        val tab = activeTab
        val model = tab?.model?.takeIf { !it.isClosed }
        val current = model?.language ?: Languages.PLAIN_TEXT_ID
        fun choose(apply: (EditorModel) -> Unit) {
            paletteOpen = false
            quickPick = null
            if (model == null || model.isClosed) return
            apply(model)
            tab.revision++
            log("Language mode of ${tab.name}: ${Languages.displayName(model.language)}")
        }
        val languages = (Languages.all.map { it.id to it.name } + (Languages.PLAIN_TEXT_ID to Languages.PLAIN_TEXT_NAME))
            .sortedBy { it.second.lowercase() }
        listOf(PaletteItem("Auto Detect") { choose { it.detectLanguage() } }) +
            languages.mapIndexed { i, (id, name) ->
                PaletteItem(
                    label = name,
                    description = if (id == current) "($id) - Configured Language" else "($id)",
                    section = if (i == 0) "languages (identifier)" else null,
                ) { choose { it.language = id.takeIf { id != Languages.PLAIN_TEXT_ID } } }
            }
    }

    companion object {
        const val SETTINGS_PATH = "ferride:settings"
        const val SETTINGS_JSON_PATH = "ferride:settings.json"
        const val WELCOME_PATH = "ferride:welcome"
        private const val MAX_RECENT_FOLDERS = 10
        private const val MAX_RECENT = 5
    }
}

/** `<parent>/<name>`, or `<name> 2`, `<name> 3`… if that already exists. */
fun uniqueDir(parent: String, name: String): String {
    val safe = name.replace('/', '_').ifBlank { "folder" }
    var candidate = java.io.File(parent, safe)
    var n = 2
    while (candidate.exists()) candidate = java.io.File(parent, "$safe ${n++}")
    return candidate.path
}
