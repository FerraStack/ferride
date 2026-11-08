package dev.ferride.workbench

import android.view.KeyEvent

/** A key combination, VS Code style (`ctrl+shift+p`). */
data class KeyChord(
    val keyCode: Int,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
) {
    /** The keys in order (`Ctrl`, `Shift`, `P`): one keycap each in the UI. */
    val parts: List<String>
        get() = buildList {
            if (ctrl) add("Ctrl")
            if (shift) add("Shift")
            if (alt) add("Alt")
            add(keyName(keyCode))
        }

    /** Human-readable label, e.g. `Ctrl+Shift+P` (menus show it as plain text, like VS Code). */
    val label: String get() = parts.joinToString("+")

    fun matches(event: KeyEvent): Boolean =
        event.keyCode == keyCode && event.isCtrlPressed == ctrl && event.isShiftPressed == shift &&
            event.isAltPressed == alt

    companion object {
        fun ctrl(key: Int, shift: Boolean = false) = KeyChord(key, ctrl = true, shift = shift)

        private fun keyName(code: Int): String = when (code) {
            KeyEvent.KEYCODE_GRAVE -> "`"
            KeyEvent.KEYCODE_COMMA -> ","
            KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
            KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
            KeyEvent.KEYCODE_TAB -> "Tab"
            KeyEvent.KEYCODE_ESCAPE -> "Esc"
            KeyEvent.KEYCODE_EQUALS -> "="
            KeyEvent.KEYCODE_MINUS -> "-"
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (code - KeyEvent.KEYCODE_A)).toString()
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (code - KeyEvent.KEYCODE_0)).toString()
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "F${code - KeyEvent.KEYCODE_F1 + 1}"
            else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").let {
                if (it.length == 1) it else it.lowercase().replaceFirstChar(Char::uppercase)
            }
        }
    }
}

/**
 * A command, identified like VS Code (`workbench.action.files.save`). The
 * palette, keybindings, menus and toolbar buttons all go through the registry.
 */
data class Command(
    val id: String,
    val title: String,
    val category: String? = null,
    val keybinding: KeyChord? = null,
    val enabled: () -> Boolean = { true },
    /** False for alternate bindings of another command (kept out of the palette). */
    val inPalette: Boolean = true,
    /** The keybinding only applies while the editor has focus (VS Code's `when: editorTextFocus`). */
    val keyWhenEditorFocus: Boolean = false,
    val run: () -> Unit,
) {
    /** What the palette shows: `File: Save`. */
    val label: String get() = if (category != null) "$category: $title" else title
}

class CommandRegistry {
    private val commands = LinkedHashMap<String, Command>()

    fun register(command: Command) {
        commands[command.id] = command
    }

    fun get(id: String): Command? = commands[id]

    val all: List<Command> get() = commands.values.toList()

    /** Runs a command; returns false if it's unknown or disabled. */
    fun execute(id: String): Boolean {
        val c = commands[id] ?: return false
        if (!c.enabled()) return false
        c.run()
        return true
    }

    /** Finds the command bound to a key-down event (hardware keyboards). */
    fun forKey(event: KeyEvent, editorFocused: Boolean = true): Command? =
        if (event.action != KeyEvent.ACTION_DOWN) null
        else commands.values.firstOrNull {
            it.keybinding?.matches(event) == true && (!it.keyWhenEditorFocus || editorFocused)
        }

    /** Palette filtering: fuzzy match on the label, best first. */
    fun search(query: String): List<Command> {
        val q = query.trim()
        val candidates = all.filter { it.inPalette }
        if (q.isEmpty()) return candidates.sortedBy { it.label }
        return candidates.mapNotNull { c -> fuzzyScore(q, c.label)?.let { c to it } }
            .sortedWith(compareByDescending<Pair<Command, Int>> { it.second }.thenBy { it.first.label })
            .map { it.first }
    }
}

/**
 * Subsequence match, case-insensitive. Rewards word starts and consecutive
 * runs. Returns null when [query] isn't a subsequence of [text].
 */
fun fuzzyScore(query: String, text: String): Int? {
    var score = 0
    var ti = 0
    var prev = -2
    for (qc in query) {
        if (qc == ' ') continue
        var found = -1
        while (ti < text.length) {
            if (text[ti].equals(qc, ignoreCase = true)) {
                found = ti
                break
            }
            ti++
        }
        if (found < 0) return null
        score += 1
        if (found == 0 || !text[found - 1].isLetterOrDigit()) score += 5
        if (found == prev + 1) score += 3
        prev = found
        ti = found + 1
    }
    return score - text.length / 8
}

/** Char indices of [text] matched by [query] (same greedy walk as [fuzzyScore]). */
fun fuzzyPositions(query: String, text: String): List<Int> {
    val out = mutableListOf<Int>()
    var ti = 0
    for (qc in query) {
        if (qc == ' ') continue
        while (ti < text.length && !text[ti].equals(qc, ignoreCase = true)) ti++
        if (ti >= text.length) return emptyList()
        out += ti
        ti++
    }
    return out
}

/** Well-known command ids (VS Code names where one exists). */
object CommandIds {
    const val SHOW_COMMANDS = "workbench.action.showCommands"
    const val QUICK_OPEN = "workbench.action.quickOpen"
    const val GOTO_LINE = "workbench.action.gotoLine"
    const val GOTO_SYMBOL = "workbench.action.gotoSymbol"
    const val CHANGE_LANGUAGE_MODE = "workbench.action.editor.changeLanguageMode"
    const val SAVE = "workbench.action.files.save"
    const val SAVE_ALL = "workbench.action.files.saveAll"
    const val NEW_FILE = "workbench.action.files.newUntitledFile"
    const val OPEN_FOLDER = "workbench.action.files.openFolder"
    const val EXPLORER_NEW_FILE = "explorer.newFile"
    const val EXPLORER_NEW_FOLDER = "explorer.newFolder"
    const val REFRESH_EXPLORER = "workbench.files.action.refreshFilesExplorer"
    const val COLLAPSE_EXPLORER = "workbench.files.action.collapseExplorerFolders"
    const val REVEAL_IN_EXPLORER = "workbench.files.action.showActiveFileInExplorer"
    const val COPY_PATH = "copyFilePath"
    const val COPY_RELATIVE_PATH = "copyRelativeFilePath"
    const val COPY_INTO_PROJECTS = "ferride.workspace.copyIntoProjects"
    const val CLOSE_EDITOR = "workbench.action.closeActiveEditor"
    const val NEXT_EDITOR = "workbench.action.nextEditor"
    const val PREVIOUS_EDITOR = "workbench.action.previousEditor"
    const val TOGGLE_SIDEBAR = "workbench.action.toggleSidebarVisibility"
    const val TOGGLE_PANEL = "workbench.action.togglePanel"
    const val VIEW_EXPLORER = "workbench.view.explorer"
    const val VIEW_SEARCH = "workbench.view.search"
    const val VIEW_SCM = "workbench.view.scm"
    const val VIEW_LANGUAGES = "ferride.view.languages"
    const val VIEW_PROBLEMS = "workbench.actions.view.problems"
    const val VIEW_OUTPUT = "workbench.action.output.toggleOutput"
    const val TOGGLE_TERMINAL = "workbench.action.terminal.toggleTerminal"
    const val OPEN_SETTINGS = "workbench.action.openSettings"
    const val OPEN_SETTINGS_JSON = "workbench.action.openSettingsJson"
    const val REPLACE = "editor.action.startFindReplaceAction"
    const val FIND_NEXT = "editor.action.nextMatchFindAction"
    const val FIND_PREVIOUS = "editor.action.previousMatchFindAction"
    const val TOGGLE_WORD_WRAP = "editor.action.toggleWordWrap"
    const val TOGGLE_THEME = "workbench.action.toggleLightDarkThemes"
    const val UNDO = "undo"
    const val REDO = "redo"
    const val SELECT_ALL = "editor.action.selectAll"
    const val COPY = "editor.action.clipboardCopyAction"
    const val CUT = "editor.action.clipboardCutAction"
    const val PASTE = "editor.action.clipboardPasteAction"
    const val FORMAT = "editor.action.formatDocument"
    const val FIND = "actions.find"
    const val ZOOM_IN = "editor.action.fontZoomIn"
    const val ZOOM_OUT = "editor.action.fontZoomOut"
}
