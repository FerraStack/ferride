package dev.ferride.editor

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** Touch selection: long press, handle drags and the toolbar lifecycle, on a real [CodeEditorView]. */
@RunWith(AndroidJUnit4::class)
class SelectionHandlesTest {
    private val text = "fn main() {\n    let total = word_counts(text);\n    println!(\"{total}\");\n}\n"
    private lateinit var view: CodeEditorView
    private lateinit var model: FakeEditorModel
    private var ui: SelectionUi? = null

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        view = CodeEditorView(activity)
        model = FakeEditorModel(text, "main.rs")
        view.setModel(model)
        view.onSelectionUi = { ui = it }
        activity.setContentView(view)
        idle()
    }

    private fun idle(ms: Long = 0) {
        if (ms > 0) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) else shadowOf(Looper.getMainLooper()).idle()
    }

    private var downTime = 0L

    private fun touch(action: Int, x: Float, y: Float) {
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        view.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun offsetOf(s: String) = text.indexOf(s)

    @Test
    fun longPressSelectsWordAndShowsToolbar() {
        val p = view.pointOf(offsetOf("word_counts") + 3)
        touch(MotionEvent.ACTION_DOWN, p.x, p.y)
        idle(ViewConfiguration.getLongPressTimeout() + 100L)
        touch(MotionEvent.ACTION_UP, p.x, p.y)
        idle()
        val start = offsetOf("word_counts")
        assertEquals(Sel(start, start + "word_counts".length), model.selection.let { Sel(it.start, it.end) })
        assertNotNull(ui)
        val shown = ui!!
        assertTrue(shown.hasSelection)
        assertEquals(SelectionAction.Cut, shown.actions.first())
        assertEquals(2, view.handleBodies().size)
    }

    @Test
    fun draggingTheEndHandleExtendsTheSelection() {
        val start = offsetOf("word_counts")
        view.select(start, start + "word".length)
        view.showSelectionControls(toolbar = true)
        idle()
        val end = view.handleBodies()[1]
        val target = view.pointOf(offsetOf("(text)"))
        touch(MotionEvent.ACTION_DOWN, end.x, end.y)
        assertNull("toolbar hides while dragging", ui)
        // Move the finger by the same delta as the text point: the handle follows the finger.
        val from = view.pointOf(start + "word".length)
        touch(MotionEvent.ACTION_MOVE, end.x + (target.x - from.x), end.y + (target.y - from.y))
        touch(MotionEvent.ACTION_UP, end.x + (target.x - from.x), end.y + (target.y - from.y))
        idle()
        assertEquals(start, model.selection.start)
        assertEquals(offsetOf("(text)"), model.selection.end)
        assertNotNull("toolbar comes back after the drag", ui)
    }

    @Test
    fun handlesNeverCross() {
        val start = offsetOf("word_counts")
        view.select(start, start + 4)
        view.showSelectionControls(toolbar = false)
        idle()
        val startHandle = view.handleBodies()[0]
        touch(MotionEvent.ACTION_DOWN, startHandle.x, startHandle.y)
        touch(MotionEvent.ACTION_MOVE, startHandle.x + 600f, startHandle.y)
        touch(MotionEvent.ACTION_UP, startHandle.x + 600f, startHandle.y)
        idle()
        assertEquals(start + 3, model.selection.start)
        assertEquals(start + 4, model.selection.end)
    }

    @Test
    fun typingHidesHandlesAndToolbar() {
        val start = offsetOf("word_counts")
        view.select(start, start + 4)
        view.showSelectionControls(toolbar = true)
        idle()
        assertNotNull(ui)
        view.insertText("x")
        idle()
        assertNull(ui)
        assertTrue(view.handleBodies().isEmpty())
    }

    @Test
    fun tappingTheCaretTogglesTheInsertionMenu() {
        val p = view.pointOf(offsetOf("total"))
        touch(MotionEvent.ACTION_DOWN, p.x, p.y)
        touch(MotionEvent.ACTION_UP, p.x, p.y)
        idle(ViewConfiguration.getDoubleTapTimeout() + 100L)
        assertNull("first tap only places the caret", ui)
        assertEquals(1, view.handleBodies().size)
        touch(MotionEvent.ACTION_DOWN, p.x, p.y)
        touch(MotionEvent.ACTION_UP, p.x, p.y)
        idle(ViewConfiguration.getDoubleTapTimeout() + 100L)
        val shown = ui
        assertNotNull(shown)
        assertEquals(SelectionAction.SelectAll, shown!!.actions.last())
        assertTrue(SelectionAction.Cut !in shown.actions)
    }

    @Test
    fun selectAllFromTheToolbarKeepsItOpen() {
        view.select(0, 2)
        view.showSelectionControls(toolbar = true)
        idle()
        view.onSelectionToolbarAction(SelectionAction.SelectAll)
        idle()
        assertEquals(0, model.selection.start)
        assertEquals(model.length, model.selection.end)
        assertNotNull(ui)
    }
}
