package dev.ferride.editor

import android.app.Activity
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/** Cursor keys and Delete step over whole grapheme clusters (#6). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GraphemeCursorTest {
    private val family = "👨‍👩‍👧" // 👨‍👩‍👧, 8 UTF-16 units
    private val flag = "🇺🇦" // 🇺🇦
    private val eAcute = "é"

    private fun editor(text: String): Pair<CodeEditorView, FakeEditorModel> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = CodeEditorView(activity)
        val model = FakeEditorModel(text, "notes.txt")
        view.setModel(model)
        activity.setContentView(view)
        shadowOf(Looper.getMainLooper()).idle()
        return view to model
    }

    @Test
    fun arrowsSkipWholeClusters() {
        val text = "a${family}b$flag$eAcute"
        val (view, model) = editor(text)
        view.select(1, 1)
        val stops = mutableListOf(1)
        repeat(4) {
            view.moveCursor(EditorActions.Direction.Right)
            stops += model.selection.head
        }
        assertEquals(listOf(1, 1 + family.length, 2 + family.length, 2 + family.length + flag.length, text.length), stops)
        repeat(4) { view.moveCursor(EditorActions.Direction.Left) }
        assertEquals(1, model.selection.head)
    }

    @Test
    fun shiftArrowSelectsWholeCluster() {
        val (view, model) = editor("${family}z")
        view.select(0, 0)
        view.moveCursor(EditorActions.Direction.Right, extend = true)
        assertEquals(Sel(0, family.length), model.selection)
    }

    @Test
    fun forwardDeleteRemovesTheCluster() {
        val (view, model) = editor("${flag}z")
        view.select(0, 0)
        model.deleteForward()
        assertEquals("z", model.text())
        assertEquals(0, view.liveModel!!.selection.head)
    }
}
