package dev.ferride.editor

import android.app.Activity
import android.os.Bundle
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/** #7: TalkBack sees an editable text field and can move and select through it. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibilityTest {
    private val text = "fn main() {\n    let total = 1;\n}\n"

    private fun editor(): Pair<CodeEditorView, FakeEditorModel> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = CodeEditorView(activity).apply { magnifierEnabled = false }
        val model = FakeEditorModel(text, "main.rs")
        view.setModel(model)
        view.accessibilityLabel = "main.rs, editor"
        activity.setContentView(view)
        shadowOf(Looper.getMainLooper()).idle()
        return view to model
    }

    @Test
    fun nodeExposesTextSelectionAndActions() {
        val (view, model) = editor()
        model.selection = Sel(3, 7)
        val info = view.createAccessibilityNodeInfo()
        assertEquals("android.widget.EditText", info.className)
        assertEquals(text, info.text.toString())
        assertEquals("main.rs, editor", info.hintText.toString())
        assertTrue(info.isEditable && info.isMultiLine)
        assertEquals(3, info.textSelectionStart)
        assertEquals(7, info.textSelectionEnd)
        val ids = info.actionList.map { it.id }
        assertTrue(AccessibilityNodeInfo.ACTION_SET_SELECTION in ids)
        assertTrue(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY in ids)
        assertTrue(AccessibilityNodeInfo.ACTION_PASTE in ids)
    }

    @Test
    fun setSelectionAndGranularityMoves() {
        val (view, model) = editor()
        view.performAccessibilityAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 3)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 7)
            },
        )
        assertEquals(Sel(3, 7), model.selection)

        fun move(granularity: Int, forward: Boolean, extend: Boolean = false) = view.performAccessibilityAction(
            if (forward) AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY else AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, granularity)
                putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, extend)
            },
        )
        view.select(0, 0)
        assertTrue(move(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD, forward = true))
        assertEquals("after \"fn\"", 2, model.selection.head)
        move(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD, forward = true)
        assertEquals("after \"main\"", 7, model.selection.head)
        move(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER, forward = true, extend = true)
        assertEquals(Sel(7, 8), model.selection)
        view.select(0, 0)
        move(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE, forward = true)
        assertEquals("end of line 1", 11, model.selection.head)
        move(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE, forward = true)
        assertEquals("end of line 2", 30, model.selection.head)
        move(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE, forward = false)
        assertEquals("start of line 2", 12, model.selection.head)
    }
}
