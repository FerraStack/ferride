package dev.ferride.workbench

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ferride.core.FerruxStatus
import dev.ferride.core.FileNode
import dev.ferride.core.SearchReport
import dev.ferride.editor.EditorActions
import dev.ferride.editor.EditorModel
import dev.ferride.theme.BuiltInThemes
import dev.ferride.theme.DarkModern
import dev.ferride.theme.LightModern
import dev.ferride.theme.WorkbenchColors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Activity Bar entries (the left rail in VS Code). */
enum class SideView(val title: String) {
    Explorer("Explorer"),
    Search("Search"),
    SourceControl("Source Control"),
    Languages("Languages"),
    Settings("Settings"),
}

enum class PanelTab(val title: String) { Problems("Problems"), Output("Output"), Terminal("Terminal") }

/** An open editor tab. [preview] tabs (italic) are replaced by the next file opened. */
class EditorTab(val path: String, val model: EditorModel) {
    val name: String = path.substringAfterLast('/')
    var preview by mutableStateOf(true)
    var dirty by mutableStateOf(false)
    /** Bumped on every edit/selection change so Compose re-reads model-derived state. */
    var revision by mutableIntStateOf(0)
}

enum class PaletteMode(val prefix: String, val placeholder: String) {
    Files("", "Search files by name (append : to go to line, @ for symbols, > for commands)"),
    Commands(">", "Type a command"),
    GotoLine(":", "Type a line number"),
    Symbols("@", "Go to symbol in editor"),
}

/** One row in the palette. */
data class PaletteItem(val label: String, val detail: String? = null, val keybinding: String? = null, val run: () -> Unit)

/**
 * Workbench state and actions, VS Code style. UI-independent: the Compose
 * shell renders it, commands mutate it. All blocking work goes to [io].
 */
class WorkbenchState(
    val backend: WorkspaceBackend,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    initialSettings: Settings = Settings(),
    private val saveSettings: (Settings) -> Unit = {},
    val extraThemes: List<WorkbenchColors> = emptyList(),
) {
    val commands = CommandRegistry()

    var settings by mutableStateOf(initialSettings)
        private set
    var systemDark by mutableStateOf(true)

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

    var paletteOpen by mutableStateOf(false)
    var paletteQuery by mutableStateOf("")

    var searchQuery by mutableStateOf("")
    var searchCaseSensitive by mutableStateOf(false)
    var searchWholeWord by mutableStateOf(false)
    var searchRegex by mutableStateOf(false)
    var searchResult by mutableStateOf<SearchReport?>(null)
    var searchError by mutableStateOf<String?>(null)

    val output = mutableStateListOf<String>()
    var message by mutableStateOf<String?>(null)
    var ferrux by mutableStateOf<FerruxStatus>(FerruxStatus.NotInstalled)
        private set
    var branch by mutableStateOf<String?>(null)
        private set

    /** The editor view currently attached to the active tab (set by the UI). */
    var editor: EditorActions? = null

    val colors: WorkbenchColors
        get() {
            val named = (BuiltInThemes + extraThemes).firstOrNull { it.name == settings.colorTheme }
            return when {
                named != null && !settings.autoDetectColorScheme -> named
                settings.autoDetectColorScheme || named == null -> if (systemDark) DarkModern else LightModern
                else -> named
            }
        }

    init {
        registerCommands()
        log("Opened workspace ${backend.name} (${backend.root})")
    }

    fun start(openFirst: String? = "src/main.rs") {
        scope.launch {
            ferrux = withContext(io) { runCatching { backend.ferrux() }.getOrDefault(FerruxStatus.NotInstalled) }
            branch = withContext(io) { backend.gitBranch() }
            loadDir(backend.root)
            if (openFirst != null) openFile("${backend.root}/$openFirst", preview = false)
        }
    }

    fun log(line: String) {
        output += line
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        settings = transform(settings)
        saveSettings(settings)
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
            val text = withContext(io) { runCatching { backend.readFile(path) } }
            text.onFailure { message = it.message }.onSuccess { content ->
                val model = backend.createModel(content, path.substringAfterLast('/'))
                if (line != null) model.selection = dev.ferride.editor.Sel.caret(model.offsetAt(line, 0))
                val tab = EditorTab(path, model).also { it.preview = preview }
                val previewIndex = tabs.indexOfFirst { it.preview && !it.dirty }
                if (preview && previewIndex >= 0) {
                    tabs[previewIndex].model.close()
                    tabs[previewIndex] = tab
                    activeIndex = previewIndex
                } else {
                    tabs += tab
                    activeIndex = tabs.lastIndex
                }
            }
        }
    }

    fun closeTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        tab.model.close()
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
        tab.dirty = tab.model.isDirty
        tab.revision++
    }

    fun save(tab: EditorTab? = activeTab) {
        tab ?: return
        val text = tab.model.text()
        scope.launch {
            val r = withContext(io) { runCatching { backend.writeFile(tab.path, text) } }
            r.onSuccess {
                tab.model.markSaved()
                tab.dirty = tab.model.isDirty
                log("Saved ${tab.name}")
            }.onFailure { message = "Couldn't save ${tab.name}: ${it.message}" }
        }
    }

    // ----- search ----------------------------------------------------------

    fun runSearch() {
        val q = searchQuery
        if (q.isEmpty()) {
            searchResult = null
            return
        }
        scope.launch {
            val r = withContext(io) {
                runCatching { backend.searchText(q, searchRegex, searchCaseSensitive, searchWholeWord) }
            }
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
        paletteQuery = mode.prefix
        paletteOpen = true
    }

    fun paletteMode(): PaletteMode = when {
        paletteQuery.startsWith(">") -> PaletteMode.Commands
        paletteQuery.startsWith(":") -> PaletteMode.GotoLine
        paletteQuery.startsWith("@") -> PaletteMode.Symbols
        else -> PaletteMode.Files
    }

    /** Palette rows for the current query. File search runs synchronously (it's quick for small trees). */
    fun paletteItems(fileHits: List<dev.ferride.core.FileHit>): List<PaletteItem> {
        val mode = paletteMode()
        val q = paletteQuery.removePrefix(mode.prefix).trim()
        return when (mode) {
            PaletteMode.Commands -> commands.search(q).filter { it.enabled() }.map { c ->
                PaletteItem(c.label, c.id, c.keybinding?.label) { paletteOpen = false; c.run() }
            }
            PaletteMode.GotoLine -> {
                val line = q.toIntOrNull()
                val max = activeTab?.model?.lineCount ?: 0
                if (line == null) listOf(PaletteItem("Type a line number between 1 and $max to navigate to", null) {})
                else listOf(PaletteItem("Go to line $line", null) { paletteOpen = false; editor?.goToLine(line - 1) })
            }
            PaletteMode.Symbols -> listOf(
                PaletteItem("Document symbols need a language server", "Requires Ferrux (see Languages)") {},
            )
            PaletteMode.Files -> fileHits.map { hit ->
                PaletteItem(hit.relPath.substringAfterLast('/'), hit.relPath.substringBeforeLast('/', "")) {
                    paletteOpen = false
                    openFile(hit.path, preview = true)
                }
            }
        }
    }

    // ----- keybindings -------------------------------------------------------

    /** Global keybinding dispatch for hardware keyboards. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val cmd = commands.forKey(event) ?: return false
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
        ) = r.register(Command(id, title, category, key, enabled, run))
        val hasEditor = { activeTab != null }

        cmd(CommandIds.SHOW_COMMANDS, "Show All Commands", null, KeyChord.ctrl(KeyEvent.KEYCODE_P, shift = true)) {
            openPalette(PaletteMode.Commands)
        }
        cmd(CommandIds.QUICK_OPEN, "Go to File…", null, KeyChord.ctrl(KeyEvent.KEYCODE_P)) { openPalette(PaletteMode.Files) }
        cmd(CommandIds.GOTO_LINE, "Go to Line/Column…", "Go", KeyChord.ctrl(KeyEvent.KEYCODE_G), hasEditor) {
            openPalette(PaletteMode.GotoLine)
        }
        cmd(CommandIds.GOTO_SYMBOL, "Go to Symbol in Editor…", "Go", KeyChord.ctrl(KeyEvent.KEYCODE_O, shift = true), hasEditor) {
            openPalette(PaletteMode.Symbols)
        }
        cmd(CommandIds.SAVE, "Save", "File", KeyChord.ctrl(KeyEvent.KEYCODE_S), hasEditor) { save() }
        cmd(CommandIds.SAVE_ALL, "Save All", "File", KeyChord(KeyEvent.KEYCODE_S, ctrl = true, alt = true)) {
            tabs.filter { it.dirty }.forEach { save(it) }
        }
        cmd(CommandIds.CLOSE_EDITOR, "Close Editor", "View", KeyChord.ctrl(KeyEvent.KEYCODE_W), hasEditor) {
            closeTab(activeIndex)
        }
        cmd(CommandIds.NEXT_EDITOR, "Open Next Editor", "View", KeyChord.ctrl(KeyEvent.KEYCODE_PAGE_DOWN), hasEditor) {
            activeIndex = (activeIndex + 1) % tabs.size
        }
        cmd(CommandIds.PREVIOUS_EDITOR, "Open Previous Editor", "View", KeyChord.ctrl(KeyEvent.KEYCODE_PAGE_UP), hasEditor) {
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
            showView(SideView.Settings)
        }
        cmd(CommandIds.TOGGLE_THEME, "Toggle between Light/Dark Themes", "Preferences") {
            val next = if (colors.isDark) LightModern else DarkModern
            updateSettings { it.copy(colorTheme = next.name, autoDetectColorScheme = false) }
        }
        cmd(CommandIds.UNDO, "Undo", "Edit", KeyChord.ctrl(KeyEvent.KEYCODE_Z), hasEditor) { editor?.undo() }
        cmd(CommandIds.REDO, "Redo", "Edit", KeyChord.ctrl(KeyEvent.KEYCODE_Y), hasEditor) { editor?.redo() }
        r.register(
            Command("redo.alt", "Redo", "Edit", KeyChord.ctrl(KeyEvent.KEYCODE_Z, shift = true), hasEditor, inPalette = false) {
                editor?.redo()
            },
        )
        cmd(CommandIds.SELECT_ALL, "Select All", "Selection", KeyChord.ctrl(KeyEvent.KEYCODE_A), hasEditor) {
            editor?.selectAll()
        }
        cmd(CommandIds.COPY, "Copy", "Edit", KeyChord.ctrl(KeyEvent.KEYCODE_C), hasEditor) { editor?.copy() }
        cmd(CommandIds.CUT, "Cut", "Edit", KeyChord.ctrl(KeyEvent.KEYCODE_X), hasEditor) { editor?.cut() }
        cmd(CommandIds.PASTE, "Paste", "Edit", KeyChord.ctrl(KeyEvent.KEYCODE_V), hasEditor) { editor?.paste() }
        cmd(CommandIds.FORMAT, "Format Document", null, KeyChord(KeyEvent.KEYCODE_F, shift = true, alt = true), hasEditor) {
            message = "Formatting needs a language server (requires Ferrux)"
        }
        cmd(CommandIds.FIND, "Find", null, KeyChord.ctrl(KeyEvent.KEYCODE_F)) {
            // In-file find widget is M1; until then, search the workspace.
            editor?.selectedText()?.takeIf { it.isNotEmpty() && '\n' !in it }?.let { searchQuery = it }
            activeView = SideView.Search
            sideBarVisible = true
        }
        cmd(CommandIds.ZOOM_IN, "Increase Editor Font Size", "Editor", KeyChord.ctrl(KeyEvent.KEYCODE_EQUALS)) {
            updateSettings { it.copy(fontSize = (it.fontSize + 1).coerceAtMost(48)) }
        }
        cmd(CommandIds.ZOOM_OUT, "Decrease Editor Font Size", "Editor", KeyChord.ctrl(KeyEvent.KEYCODE_MINUS)) {
            updateSettings { it.copy(fontSize = (it.fontSize - 1).coerceAtLeast(6)) }
        }
    }
}
