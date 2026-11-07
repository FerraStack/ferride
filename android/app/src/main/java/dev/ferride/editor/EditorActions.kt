package dev.ferride.editor

/** Editor operations used by commands, the symbols row and menus. */
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

    enum class Direction { Left, Right, Up, Down, LineStart, LineEnd, PageUp, PageDown }
}

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
    val fontSizeSp: Float = 14f,
    val tabSize: Int = 4,
    val insertSpaces: Boolean = true,
    val lineNumbers: Boolean = true,
    val indentGuides: Boolean = true,
    val highlightActiveLine: Boolean = true,
    val bracketPairs: Boolean = true,
    val bracketColors: List<Int> = listOf(0xFFFFD700.toInt(), 0xFFDA70D6.toInt(), 0xFF179FFF.toInt()),
)
