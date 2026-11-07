package dev.ferride.editor

import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/**
 * IME bridge for [CodeEditorView]. Works directly against the [EditorModel]
 * (no `Editable` mirror of the document): composing text, commits and
 * deletions become `typing` edits in UTF-16 offsets, which the Rust buffer
 * coalesces into one undo step per word/composition.
 */
class EditorInputConnection(private val view: CodeEditorView) : BaseInputConnection(view, false) {
    private var batchDepth = 0

    val inBatch: Boolean get() = batchDepth > 0

    override fun beginBatchEdit(): Boolean {
        batchDepth++
        return true
    }

    override fun endBatchEdit(): Boolean {
        batchDepth = (batchDepth - 1).coerceAtLeast(0)
        if (batchDepth == 0) view.onImeBatchFinished()
        return batchDepth > 0
    }

    private fun replaceForIme(text: CharSequence, newCursorPosition: Int, composing: Boolean): Boolean {
        val m = view.liveModel ?: return false
        val sel = m.selection
        val (start, end) = view.composingRange() ?: (sel.start to sel.end)
        val s = text.toString()
        if (!composing && s == "\n") {
            if (start != end) m.replace(start, end, "", true)
            view.clearComposing()
            m.newline()
            view.afterEdit(fromIme = true)
            return true
        }
        // The core may normalize line endings, so use the end it reports.
        val newEnd = m.replace(start, end, s, true)
        if (composing && s.isNotEmpty()) view.setComposing(start, newEnd) else view.clearComposing()
        val cursor = if (newCursorPosition > 0) newEnd + newCursorPosition - 1 else start + newCursorPosition
        m.selection = Sel.caret(cursor.coerceIn(0, m.length))
        view.afterEdit(fromIme = true)
        return true
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int) =
        replaceForIme(text, newCursorPosition, composing = false)

    override fun setComposingText(text: CharSequence, newCursorPosition: Int) =
        replaceForIme(text, newCursorPosition, composing = true)

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        val m = view.liveModel ?: return false
        val a = minOf(start, end).coerceIn(0, m.length)
        val b = maxOf(start, end).coerceIn(0, m.length)
        if (a == b) view.clearComposing() else view.setComposing(a, b)
        view.invalidate()
        return true
    }

    override fun finishComposingText(): Boolean {
        view.clearComposing()
        view.invalidate()
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val m = view.liveModel ?: return false
        val sel = m.selection
        // After first, so the "before" offsets stay valid.
        if (afterLength > 0) m.replace(sel.end, minOf(m.length, sel.end + afterLength), "", true)
        if (beforeLength > 0) m.replace(maxOf(0, sel.start - beforeLength), sel.start, "", true)
        view.clearComposing()
        view.afterEdit(fromIme = true)
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        val m = view.liveModel ?: return false
        val sel = m.selection
        val before = m.textRange(maxOf(0, sel.start - beforeLength * 2), sel.start)
        val after = m.textRange(sel.end, minOf(m.length, sel.end + afterLength * 2))
        val b = before.length - before.offsetByCodePointsSafe(before.length, -beforeLength)
        val a = after.offsetByCodePointsSafe(0, afterLength)
        return deleteSurroundingText(b, a)
    }

    private fun String.offsetByCodePointsSafe(index: Int, count: Int): Int {
        var i = index
        var n = count
        while (n > 0 && i < length) {
            i += Character.charCount(codePointAt(i))
            n--
        }
        while (n < 0 && i > 0) {
            i -= Character.charCount(codePointBefore(i))
            n++
        }
        return i
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
        val m = view.liveModel ?: return ""
        val s = m.selection.start
        return m.textRange(maxOf(0, s - n), s)
    }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence {
        val m = view.liveModel ?: return ""
        val e = m.selection.end
        return m.textRange(e, minOf(m.length, e + n))
    }

    override fun getSelectedText(flags: Int): CharSequence? {
        val m = view.liveModel ?: return null
        val s = m.selection
        return if (s.isEmpty) null else m.textRange(s.start, s.end)
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        val m = view.liveModel ?: return false
        val next = Sel(start.coerceIn(0, m.length), end.coerceIn(0, m.length))
        // The keyboard moved the cursor (e.g. Gboard's space-bar swipe): touch handles go away.
        if (next != m.selection) view.hideSelectionControls()
        m.selection = next
        view.afterSelectionChange(fromIme = true)
        return true
    }

    /** A window of text around the selection; with `GET_EXTRACTED_TEXT_MONITOR` the view keeps it updated. */
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
        view.extractedText(request, monitor = flags and InputConnection.GET_EXTRACTED_TEXT_MONITOR != 0)

    override fun getCursorCapsMode(reqModes: Int): Int = 0

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        view.dispatchKeyEvent(event)
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        view.liveModel?.newline() ?: return false
        view.afterEdit(fromIme = true)
        return true
    }

    override fun performContextMenuAction(id: Int): Boolean {
        when (id) {
            android.R.id.selectAll -> view.selectAll()
            android.R.id.cut -> view.cut()
            android.R.id.copy -> view.copy()
            android.R.id.paste -> view.paste()
            else -> return false
        }
        return true
    }

    override fun closeConnection() {
        view.clearComposing()
        view.stopExtractedTextMonitor()
        batchDepth = 0
        super.closeConnection()
    }
}
