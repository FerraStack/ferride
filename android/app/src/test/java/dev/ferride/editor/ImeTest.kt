package dev.ferride.editor

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/** What a keyboard would have been told by the editor. */
class RecordingImeSink : ImeSink {
    data class Selection(val start: Int, val end: Int, val composingStart: Int, val composingEnd: Int)

    val selections = mutableListOf<Selection>()
    val extracted = mutableListOf<Pair<Int, ExtractedText>>()
    val anchors = mutableListOf<CursorAnchorInfo>()
    var restarts = 0

    override fun updateSelection(view: View, selStart: Int, selEnd: Int, composingStart: Int, composingEnd: Int) {
        selections += Selection(selStart, selEnd, composingStart, composingEnd)
    }

    override fun updateExtractedText(view: View, token: Int, text: ExtractedText) {
        extracted += token to text
    }

    override fun updateCursorAnchorInfo(view: View, info: CursorAnchorInfo) {
        anchors += info
    }

    override fun restartInput(view: View) {
        restarts++
    }
}

/**
 * The editor's InputConnection driven with the call sequences Gboard sends
 * (composition, autocorrect, re-composing a word on backspace, gesture typing)
 * plus extracted-text requests from keyboards that use them.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImeTest {
    private lateinit var view: CodeEditorView
    private lateinit var model: FakeEditorModel
    private lateinit var ic: InputConnection
    private val sink = RecordingImeSink()

    private fun editor(text: String, caret: Int = text.length) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        view = CodeEditorView(activity).apply { magnifierEnabled = false }
        model = FakeEditorModel(text, "notes.txt")
        view.setModel(model)
        view.imeSink = sink
        activity.setContentView(view)
        shadowOf(Looper.getMainLooper()).idle()
        model.selection = Sel.caret(caret)
        ic = view.onCreateInputConnection(EditorInfo())
    }

    private val last get() = sink.selections.last()

    @Test
    fun typingAWordThenSpace() {
        editor("")
        for (prefix in listOf("h", "he", "hel", "hell", "hello")) ic.setComposingText(prefix, 1)
        assertEquals(RecordingImeSink.Selection(5, 5, 0, 5), last)
        ic.commitText("hello", 1)
        ic.commitText(" ", 1)
        assertEquals("hello ", model.text())
        assertEquals(RecordingImeSink.Selection(6, 6, -1, -1), last)
    }

    @Test
    fun autocorrectReplacesTheComposingWord() {
        editor("x ")
        ic.setComposingText("teh", 1)
        ic.beginBatchEdit()
        ic.commitText("the", 1)
        ic.commitText(" ", 1)
        ic.endBatchEdit()
        assertEquals("x the ", model.text())
        assertEquals(RecordingImeSink.Selection(6, 6, -1, -1), last)
    }

    @Test
    fun backspaceRecomposesThePreviousWord() {
        editor("hello world ")
        // Gboard: delete the space, then make "world" the composing region again.
        ic.beginBatchEdit()
        ic.deleteSurroundingText(1, 0)
        ic.setComposingRegion(6, 11)
        val beforeEnd = sink.selections.size
        ic.endBatchEdit()
        assertEquals("selection is reported once, after the batch", beforeEnd + 1, sink.selections.size)
        assertEquals("hello world", model.text())
        assertEquals(RecordingImeSink.Selection(11, 11, 6, 11), last)
        // Picking a suggestion replaces the re-composed word.
        ic.setComposingText("worlds", 1)
        ic.finishComposingText()
        assertEquals("hello worlds", model.text())
        assertEquals(12, model.selection.head)
    }

    @Test
    fun gestureTypingCommitsWithAutoSpace() {
        editor("")
        ic.setComposingText("swipe", 1)
        ic.beginBatchEdit()
        ic.finishComposingText()
        ic.commitText(" ", 1)
        ic.setComposingText("typing", 1)
        ic.endBatchEdit()
        assertEquals("swipe typing", model.text())
        assertEquals(RecordingImeSink.Selection(12, 12, 6, 12), last)
    }

    @Test
    fun extractedTextIsAWindowAroundTheSelection() {
        val text = "a".repeat(10_000)
        editor(text, caret = 5_000)
        val et = ic.getExtractedText(ExtractedTextRequest(), 0)!!
        assertEquals(5_000 - CodeEditorView.EXTRACT_WINDOW, et.startOffset)
        assertEquals(2 * CodeEditorView.EXTRACT_WINDOW, et.text.length)
        assertEquals(CodeEditorView.EXTRACT_WINDOW, et.selectionStart)
        assertEquals(CodeEditorView.EXTRACT_WINDOW, et.selectionEnd)
        val small = ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 100 }, 0)!!
        assertEquals(100, small.text.length)
        // Near the start the window is clipped, offsets stay document-relative.
        editor("hello", caret = 2)
        val head = ic.getExtractedText(ExtractedTextRequest(), 0)!!
        assertEquals(0, head.startOffset)
        assertEquals("hello", head.text.toString())
        assertEquals(2, head.selectionStart)
    }

    @Test
    fun monitoredExtractedTextFollowsEdits() {
        editor("hello", caret = 5)
        val request = ExtractedTextRequest().apply { token = 7 }
        ic.getExtractedText(request, InputConnection.GET_EXTRACTED_TEXT_MONITOR)
        ic.commitText("!", 1)
        val (token, et) = sink.extracted.last()
        assertEquals(7, token)
        assertEquals("hello!", et.text.toString())
        assertEquals(6, et.selectionStart)
        // Moving inside the window the keyboard already has needs no new copy.
        val count = sink.extracted.size
        ic.setSelection(1, 1)
        assertEquals(count, sink.extracted.size)
        // Without the monitor flag nothing is pushed.
        ic = view.onCreateInputConnection(EditorInfo())
        ic.getExtractedText(request, 0)
        ic.commitText("?", 1)
        assertEquals(count, sink.extracted.size)
        assertTrue(model.text().contains("?"))
    }

    @Test
    fun cursorAnchorInfoForCandidateWindows() {
        editor("let x = 1\n", caret = 4)
        sink.anchors.clear()
        assertTrue(ic.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE))
        assertEquals("immediate request sends one update", 1, sink.anchors.size)
        val first = sink.anchors.last()
        assertEquals(4, first.selectionStart)
        assertTrue(first.insertionMarkerHorizontal > 0f)
        assertTrue(first.insertionMarkerBottom > first.insertionMarkerTop)
        // Not monitoring: edits don't push updates.
        ic.commitText("y", 1)
        assertEquals(1, sink.anchors.size)

        // Monitoring: composition updates carry the composing text and per-char bounds.
        ic.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR)
        ic.setComposingText("にほん", 1)
        val info = sink.anchors.last()
        assertEquals(5, info.composingTextStart)
        assertEquals("にほん", info.composingText.toString())
        val a = info.getCharacterBounds(5)!!
        val b = info.getCharacterBounds(6)!!
        assertTrue("chars advance left to right", b.left >= a.right - 0.5f)
        assertEquals(a.top, b.top, 0.01f)
        assertEquals(8, info.selectionStart)
    }
}
