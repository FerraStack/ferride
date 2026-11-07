package dev.ferride.editor

import dev.ferride.core.Document
import dev.ferride.core.ViewLine

/** A selection in UTF-16 offsets. */
data class Sel(val anchor: Int, val head: Int) {
    val start: Int get() = minOf(anchor, head)
    val end: Int get() = maxOf(anchor, head)
    val isEmpty: Boolean get() = anchor == head

    companion object {
        fun caret(pos: Int) = Sel(pos, pos)
    }
}

/**
 * What [CodeEditorView] needs from a document. All offsets are UTF-16 code
 * units, like Android's `String` and `InputConnection`.
 *
 * [RustEditorModel] is the real implementation (Rust buffer + tree-sitter via
 * UniFFI). [FakeEditorModel] is pure Kotlin, for Compose previews and JVM
 * screenshot tests where the native library can't be loaded.
 */
interface EditorModel {
    val fileName: String
    /**
     * VS Code language id of the active grammar (`null` = Plain Text).
     * Setting it is Change Language Mode: an unknown id means plain text.
     */
    var language: String?
    /** Re-detects the language from the file name and `#!` line ("Auto Detect"). */
    fun detectLanguage(): String?
    /** Bumped by every content change. */
    val version: Long
    val lineCount: Int
    val length: Int
    val isDirty: Boolean
    var selection: Sel
    val canUndo: Boolean
    val canRedo: Boolean

    fun text(): String
    fun viewport(firstLine: Int, count: Int): List<ViewLine>
    fun lineOfOffset(offset: Int): Int
    fun lineStart(line: Int): Int
    /** End of [line] before its terminator. */
    fun lineEnd(line: Int): Int
    fun offsetAt(line: Int, column: Int): Int
    fun textRange(start: Int, end: Int): String
    fun wordAt(offset: Int): Sel

    /**
     * Grapheme cluster boundaries (cursor Left/Right): never inside an emoji
     * sequence, a flag, `e` + combining accent or `\r\n`.
     */
    fun graphemeBefore(offset: Int): Int
    fun graphemeAfter(offset: Int): Int

    /**
     * Replaces [start]..[end]; `typing` edits are coalesced for undo. Returns
     * where the inserted text ends (it may differ from `start + text.length`:
     * line endings are normalized to the file's and CRLF pairs are never split).
     */
    fun replace(start: Int, end: Int, text: String, typing: Boolean): Int
    /** Types at every selection (multi-cursor ready). */
    fun insert(text: String, typing: Boolean)
    /** Newline with auto-indent. */
    fun newline()
    fun backspace()
    fun deleteForward()
    fun undo(): Boolean
    fun redo(): Boolean
    fun sealUndoGroup()
    /** Marks [version] (captured before an async write) as saved. */
    fun markSaved(version: Long = this.version)
    /** True after [close]; a closed model must not be touched. */
    val isClosed: Boolean
    /** Frees the underlying document. Idempotent. */
    fun close()
}

class RustEditorModel(private val doc: Document, override val fileName: String) : EditorModel {
    override var language: String?
        get() = doc.language()
        set(value) = doc.setLanguage(value)

    override fun detectLanguage(): String? = doc.detectLanguage()
    override val version: Long get() = doc.version()
    override val lineCount: Int get() = doc.lineCount()
    override val length: Int get() = doc.lenUtf16()
    override val isDirty: Boolean get() = doc.isDirty()
    override val canUndo: Boolean get() = doc.canUndo()
    override val canRedo: Boolean get() = doc.canRedo()

    override var selection: Sel
        get() = doc.selection().let { Sel(it.anchor, it.head) }
        set(value) = doc.setSelection(value.anchor, value.head)

    override fun text(): String = doc.text()
    override fun viewport(firstLine: Int, count: Int): List<ViewLine> = doc.viewport(firstLine, count)
    override fun lineOfOffset(offset: Int): Int = doc.lineOfOffset(offset)
    override fun lineStart(line: Int): Int = doc.lineStartOffset(line)
    override fun lineEnd(line: Int): Int = doc.lineEndOffset(line)
    override fun offsetAt(line: Int, column: Int): Int = doc.offsetAt(line, column)
    override fun textRange(start: Int, end: Int): String = doc.textRange(start, end)
    override fun wordAt(offset: Int): Sel = doc.wordAt(offset).let { Sel(it.anchor, it.head) }
    override fun graphemeBefore(offset: Int): Int = doc.graphemeBefore(offset)
    override fun graphemeAfter(offset: Int): Int = doc.graphemeAfter(offset)

    override fun replace(start: Int, end: Int, text: String, typing: Boolean): Int =
        doc.replace(start, end, text, typing).end

    override fun insert(text: String, typing: Boolean) {
        doc.insert(text, typing)
    }

    override fun newline() {
        doc.newline()
    }

    override fun backspace() {
        doc.backspace()
    }

    override fun deleteForward() {
        doc.deleteForward()
    }

    override fun undo(): Boolean = doc.undo().changed
    override fun redo(): Boolean = doc.redo().changed
    override fun sealUndoGroup() = doc.sealUndoGroup()
    override fun markSaved(version: Long) = doc.markSavedVersion(version)

    override var isClosed: Boolean = false
        private set

    override fun close() {
        if (isClosed) return
        isClosed = true
        doc.close()
    }

    companion object {
        fun create(text: String, fileName: String): RustEditorModel =
            RustEditorModel(Document(text, fileName), fileName)
    }
}
