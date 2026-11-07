package dev.ferride.editor

import android.view.View
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputMethodManager

/**
 * Everything [CodeEditorView] pushes to the keyboard. The default goes to
 * [InputMethodManager]; JVM tests swap in a recorder to check what a keyboard
 * would have been told (Robolectric has no real IME).
 */
interface ImeSink {
    fun updateSelection(view: View, selStart: Int, selEnd: Int, composingStart: Int, composingEnd: Int)
    fun updateExtractedText(view: View, token: Int, text: ExtractedText)
    fun updateCursorAnchorInfo(view: View, info: CursorAnchorInfo)
    fun restartInput(view: View)
}

/** The real keyboard. */
class ImmSink(private val imm: () -> InputMethodManager?) : ImeSink {
    override fun updateSelection(view: View, selStart: Int, selEnd: Int, composingStart: Int, composingEnd: Int) {
        imm()?.updateSelection(view, selStart, selEnd, composingStart, composingEnd)
    }

    override fun updateExtractedText(view: View, token: Int, text: ExtractedText) {
        imm()?.updateExtractedText(view, token, text)
    }

    override fun updateCursorAnchorInfo(view: View, info: CursorAnchorInfo) {
        imm()?.updateCursorAnchorInfo(view, info)
    }

    override fun restartInput(view: View) {
        imm()?.restartInput(view)
    }
}
