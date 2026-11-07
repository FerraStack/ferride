package dev.ferride.editor

import dev.ferride.core.BracketMark
import dev.ferride.core.StyleKind
import dev.ferride.core.StyleSpan
import dev.ferride.core.ViewLine

/**
 * Pure-Kotlin [EditorModel] with a tiny regex highlighter. Used by Compose
 * previews and Robolectric screenshot tests, which run on the JVM without the
 * Rust library. Not meant for real editing (O(n) edits, snapshot undo).
 */
class FakeEditorModel(text: String, override val fileName: String) : EditorModel {
    private val sb = StringBuilder(text)

    override var isClosed: Boolean = false
        private set

    /** Like a freed native document: any use after [close] is a bug. */
    private fun live(): StringBuilder {
        check(!isClosed) { "FakeEditorModel($fileName) used after close()" }
        return sb
    }

    override fun close() {
        isClosed = true
    }
    private val undoStack = ArrayDeque<Pair<String, Sel>>()
    private val redoStack = ArrayDeque<Pair<String, Sel>>()
    private var savedVersion = 0L

    override val language: String? = languageFor(fileName)
    override var version: Long = 0
        private set
    override var selection: Sel = Sel.caret(0)
        set(value) {
            field = Sel(value.anchor.coerceIn(0, length), value.head.coerceIn(0, length))
        }

    override val lineCount: Int get() = live().count { it == '\n' } + 1
    override val length: Int get() = live().length
    override val isDirty: Boolean get() = version != savedVersion
    override val canUndo: Boolean get() = undoStack.isNotEmpty()
    override val canRedo: Boolean get() = redoStack.isNotEmpty()

    override fun text(): String = live().toString()

    override fun lineStart(line: Int): Int {
        live()
        if (line <= 0) return 0
        var l = 0
        for (i in sb.indices) {
            if (sb[i] == '\n') {
                l++
                if (l == line) return i + 1
            }
        }
        return sb.length
    }

    override fun lineEnd(line: Int): Int {
        val start = lineStart(line)
        val nl = sb.indexOf("\n", start)
        return if (nl < 0) sb.length else nl
    }

    override fun lineOfOffset(offset: Int): Int {
        live()
        val o = offset.coerceIn(0, sb.length)
        var line = 0
        for (i in 0 until o) if (sb[i] == '\n') line++
        return line
    }

    override fun offsetAt(line: Int, column: Int): Int {
        if (line >= lineCount) return sb.length
        return (lineStart(line) + column.coerceAtLeast(0)).coerceAtMost(lineEnd(line))
    }

    override fun textRange(start: Int, end: Int): String {
        val a = minOf(start, end).coerceIn(0, sb.length)
        val b = maxOf(start, end).coerceIn(0, sb.length)
        return sb.substring(a, b)
    }

    override fun wordAt(offset: Int): Sel {
        val isWord = { c: Char -> c.isLetterOrDigit() || c == '_' }
        var s = offset.coerceIn(0, sb.length)
        var e = s
        while (s > 0 && isWord(sb[s - 1])) s--
        while (e < sb.length && isWord(sb[e])) e++
        return Sel(s, e)
    }

    override fun viewport(firstLine: Int, count: Int): List<ViewLine> {
        val lines = live().toString().split('\n')
        val end = minOf(lines.size, firstLine + count)
        if (firstLine >= end) return emptyList()
        var offset = lines.take(firstLine).sumOf { it.length + 1 }
        // Bracket depth: naive count from the top (fine for previews/tests).
        var depth = 0
        lines.take(firstLine).forEach { l -> depth = bracketsOf(l, highlight(l), depth).second }
        return (firstLine until end).map { i ->
            val text = lines[i]
            val spans = highlight(text)
            val (marks, after) = bracketsOf(text, spans, depth)
            depth = after
            ViewLine(i, text, offset, spans, marks).also { offset += text.length + 1 }
        }
    }

    private fun bracketsOf(text: String, spans: List<StyleSpan>, depthIn: Int): Pair<List<BracketMark>, Int> {
        var depth = depthIn
        val marks = mutableListOf<BracketMark>()
        text.forEachIndexed { i, c ->
            if (spans.any { (it.kind == StyleKind.STRING || it.kind == StyleKind.COMMENT) && i >= it.start && i < it.end }) return@forEachIndexed
            when (c) {
                '(', '[', '{' -> { marks += BracketMark(i, depth); depth++ }
                ')', ']', '}' -> { depth = maxOf(0, depth - 1); marks += BracketMark(i, depth) }
            }
        }
        return marks to depth
    }

    private fun snapshot() {
        undoStack.addLast(sb.toString() to selection)
        redoStack.clear()
    }

    override fun replace(start: Int, end: Int, text: String, typing: Boolean): Int {
        live()
        val a = minOf(start, end).coerceIn(0, sb.length)
        val b = maxOf(start, end).coerceIn(0, sb.length)
        snapshot()
        sb.replace(a, b, text)
        version++
        selection = Sel.caret(a + text.length)
        return a + text.length
    }

    override fun insert(text: String, typing: Boolean) {
        replace(selection.start, selection.end, text, typing)
    }

    override fun newline() {
        val line = lineOfOffset(selection.start)
        val indent = textRange(lineStart(line), lineEnd(line)).takeWhile { it == ' ' || it == '\t' }
        insert("\n$indent", false)
    }

    override fun backspace() {
        val s = selection
        if (!s.isEmpty) replace(s.start, s.end, "", true)
        else if (s.head > 0) replace(s.head - 1, s.head, "", true)
    }

    override fun deleteForward() {
        val s = selection
        if (!s.isEmpty) replace(s.start, s.end, "", true)
        else if (s.head < sb.length) replace(s.head, s.head + 1, "", true)
    }

    override fun undo(): Boolean {
        val (text, sel) = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(sb.toString() to selection)
        sb.setLength(0)
        sb.append(text)
        version++
        selection = sel
        return true
    }

    override fun redo(): Boolean {
        val (text, sel) = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(sb.toString() to selection)
        sb.setLength(0)
        sb.append(text)
        version++
        selection = sel
        return true
    }

    override fun sealUndoGroup() {
        live()
    }

    override fun markSaved(version: Long) {
        live()
        savedVersion = version
    }

    private fun highlight(line: String): List<StyleSpan> {
        val spans = mutableListOf<StyleSpan>()
        val taken = BooleanArray(line.length)
        fun add(range: IntRange, kind: StyleKind) {
            if (range.isEmpty() || range.any { taken[it] }) return
            range.forEach { taken[it] = true }
            spans += StyleSpan(range.first, range.last + 1, kind)
        }
        val commentStart = when (language) {
            "python" -> line.indexOf('#')
            "markdown", "json", null -> -1
            else -> line.indexOf("//")
        }
        if (language == "markdown" && line.startsWith("#")) {
            add(line.indices, StyleKind.HEADING)
            return spans
        }
        STRING.findAll(line).forEach { m ->
            if (commentStart < 0 || m.range.first < commentStart) add(m.range, StyleKind.STRING)
        }
        if (commentStart >= 0 && !taken[commentStart]) add(commentStart until line.length, StyleKind.COMMENT)
        val keywords = KEYWORDS[language].orEmpty()
        WORD.findAll(line).forEach { m ->
            val w = m.value
            val next = line.getOrNull(m.range.last + 1)
            val kind = when {
                w in keywords -> StyleKind.KEYWORD
                w in CONSTANTS -> StyleKind.CONSTANT
                w.first().isDigit() -> StyleKind.NUMBER
                next == '(' || next == '!' -> StyleKind.FUNCTION
                w.first().isUpperCase() -> StyleKind.TYPE
                else -> null
            }
            if (kind != null) add(m.range, kind)
        }
        return spans.sortedBy { it.start }
    }

    companion object {
        private val STRING = Regex("\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'")
        private val WORD = Regex("[A-Za-z_][A-Za-z0-9_]*|\\d+(?:\\.\\d+)?")
        private val CONSTANTS = setOf("true", "false", "None", "True", "False", "null", "undefined")
        private val KEYWORDS = mapOf(
            "rust" to setOf(
                "fn", "let", "mut", "pub", "use", "mod", "struct", "enum", "impl", "trait", "for", "in",
                "while", "loop", "if", "else", "match", "return", "self", "Self", "const", "static", "where",
                "async", "await", "move", "ref", "crate", "super", "type", "as", "break", "continue",
            ),
            "python" to setOf(
                "def", "class", "return", "if", "elif", "else", "for", "while", "in", "import", "from",
                "as", "with", "try", "except", "finally", "raise", "lambda", "yield", "pass", "and", "or",
                "not", "is", "async", "await", "self",
            ),
            "javascript" to JS_KEYWORDS,
            "typescript" to JS_KEYWORDS + setOf("interface", "type", "enum", "implements", "readonly", "number", "string", "boolean"),
            "typescriptreact" to JS_KEYWORDS + setOf("interface", "type"),
        )

        private val JS_KEYWORDS: Set<String>
            get() = setOf(
                "function", "const", "let", "var", "return", "if", "else", "for", "while", "of", "in",
                "import", "from", "export", "default", "class", "extends", "new", "this", "async", "await",
                "try", "catch", "throw", "typeof",
            )

        fun languageFor(fileName: String): String? = when (fileName.substringAfterLast('.', "").lowercase()) {
            "rs" -> "rust"
            "py" -> "python"
            "js", "mjs", "cjs", "jsx" -> "javascript"
            "ts", "mts", "cts" -> "typescript"
            "tsx" -> "typescriptreact"
            "json" -> "json"
            "md", "markdown" -> "markdown"
            else -> null
        }
    }
}
