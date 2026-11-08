package dev.ferride.editor

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/** Multi-cursor: Ctrl+D, select all occurrences, cursors above/below, Alt+click, typing through the IME. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MultiCursorTest {
    private lateinit var view: CodeEditorView
    private lateinit var model: FakeEditorModel

    private fun editor(text: String) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        view = CodeEditorView(activity).apply { magnifierEnabled = false }
        model = FakeEditorModel(text, "a.txt")
        view.setModel(model)
        view.imeSink = RecordingImeSink()
        activity.setContentView(view)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private val texts get() = model.selections.map { model.textRange(it.start, it.end) }

    @Test
    fun ctrlDSelectsTheWordThenAddsNextOccurrences() {
        editor("foo bar foo baz foo")
        view.select(1, 1)
        view.addSelectionToNextFindMatch()
        assertEquals(listOf(Sel(0, 3)), model.selections)
        view.addSelectionToNextFindMatch()
        view.addSelectionToNextFindMatch()
        assertEquals(listOf("foo", "foo", "foo"), texts)
        assertEquals("the newest is primary", 16, model.selection.start)
        view.addSelectionToNextFindMatch()
        assertEquals("no more occurrences", 3, model.selections.size)
    }

    @Test
    fun imeCompositionIsMirroredAtEveryCursor() {
        editor("foo bar foo baz foo")
        view.select(0, 3)
        view.selectAllOccurrences()
        assertEquals(3, model.selections.size)
        val ic: InputConnection = view.onCreateInputConnection(EditorInfo())
        ic.setComposingText("q", 1)
        ic.setComposingText("qu", 1)
        ic.commitText("qux", 1)
        assertEquals("qux bar qux baz qux", model.text())
        // Keyboard backspace deletes before every cursor.
        ic.deleteSurroundingText(1, 0)
        assertEquals("qu bar qu baz qu", model.text())
        assertEquals(3, model.selections.size)
    }

    @Test
    fun cursorsBelowMoveTogether() {
        editor("abc\nabc\nabc")
        view.select(1, 1)
        view.insertCursor(above = false)
        view.insertCursor(above = false)
        assertEquals(listOf(1, 5, 9), model.selections.map { it.head })
        view.moveCursor(EditorActions.Direction.Right)
        assertEquals(listOf(2, 6, 10), model.selections.map { it.head })
        model.insert("!", true)
        assertEquals("ab!c\nab!c\nab!c", model.text())
        // Esc goes back to one cursor.
        view.escape()
        assertEquals(1, model.selections.size)
    }

    @Test
    fun altClickAddsACursor() {
        editor("one\ntwo\nthree")
        view.select(0, 0)
        val p = view.pointOf(5)
        val t = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, p.x, p.y, KeyEvent.META_ALT_ON))
        view.dispatchTouchEvent(MotionEvent.obtain(t, t + 10, MotionEvent.ACTION_UP, p.x, p.y, KeyEvent.META_ALT_ON))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(0, 5), model.selections.map { it.head })
    }
}
