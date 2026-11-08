package dev.ferride.editor

/** Editor operations used by commands, the symbols row, the find widget and menus. */
interface EditorActions {
    fun insertText(text: String)
    fun moveCursor(direction: Direction, extend: Boolean = false)
    fun indent()
    fun undo()
    fun redo()
    fun selectAll()
    fun copy()
    fun cut()
    fun paste()
    fun selectedText(): String
    /** 0-based line. */
    fun goToLine(line: Int)
    /** Collapses the selection (Esc). */
    fun escape()
    /** Selects [start]..[end] (UTF-16) and scrolls it into view. */
    fun select(start: Int, end: Int)
    /** Replaces a range as one undo step (find/replace). */
    fun replaceRange(start: Int, end: Int, text: String)

    /** Ctrl+D: select the word, then add the next occurrence as another cursor. */
    fun addSelectionToNextFindMatch() {}
    /** Ctrl+Shift+L: a cursor on every occurrence. */
    fun selectAllOccurrences() {}
    /** Ctrl+Alt+Up/Down: add a cursor on the line above/below. */
    fun insertCursor(above: Boolean) {}

    enum class Direction { Left, Right, Up, Down, LineStart, LineEnd, PageUp, PageDown }
}

/** A diagnostic underline in UTF-16 document offsets. Severity: 1 error, 2 warning, 3 info, 4 hint. */
data class Squiggle(val start: Int, val end: Int, val severity: Int)

/** Colors and metrics the editor view draws with (ARGB ints). */
data class EditorStyle(
    val background: Int,
    val foreground: Int,
    val lineHighlight: Int,
    val selection: Int,
    val cursor: Int,
    val lineNumber: Int,
    val lineNumberActive: Int,
    val indentGuide: Int,
    val tokens: Map<dev.ferride.core.StyleKind, Int>,
    val findMatch: Int = 0x66515C6A,
    val findMatchCurrent: Int = 0xAA9E6A03.toInt(),
    val error: Int = 0xFFF14C4C.toInt(),
    val warning: Int = 0xFFCCA700.toInt(),
    val info: Int = 0xFF3794FF.toInt(),
    val scrollbar: Int = 0x55797979,
    /** Touch selection handles (VS Code has none; they use the accent, `focusBorder`). */
    val handle: Int = 0xFF0078D4.toInt(),
    val fontSizeSp: Float = 14f,
    val tabSize: Int = 4,
    val insertSpaces: Boolean = true,
    val lineNumbers: Boolean = true,
    val indentGuides: Boolean = true,
    val highlightActiveLine: Boolean = true,
    val bracketPairs: Boolean = true,
    val wordWrap: Boolean = false,
    val bracketColors: List<Int> = listOf(0xFFFFD700.toInt(), 0xFFDA70D6.toInt(), 0xFF179FFF.toInt()),
)
