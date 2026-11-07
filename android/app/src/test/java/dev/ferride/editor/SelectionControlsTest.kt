package dev.ferride.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionControlsTest {
    private fun ui(top: Float, bottom: Float, left: Float = 400f, right: Float = 600f, sel: Boolean = true, paste: Boolean = true) =
        SelectionUi(left, top, right, bottom, hasSelection = sel, canPaste = paste)

    @Test
    fun actionsFollowVsCodeOrderAndContext() {
        assertEquals(
            listOf(SelectionAction.Cut, SelectionAction.Copy, SelectionAction.Paste, SelectionAction.SelectAll),
            ui(500f, 560f).actions,
        )
        // A caret (no selection) only offers Paste and Select All; no clip, no Paste.
        assertEquals(listOf(SelectionAction.Paste, SelectionAction.SelectAll), ui(500f, 560f, sel = false).actions)
        assertEquals(listOf(SelectionAction.SelectAll), ui(500f, 560f, sel = false, paste = false).actions)
    }

    @Test
    fun placedAboveTheSelectionAndCentered() {
        val (x, y) = placeSelectionToolbar(ui(500f, 560f), w = 300, h = 100, containerW = 1000, containerH = 2000, gap = 10, margin = 20)
        assertEquals(500 - 150, x)
        assertEquals(500 - 10 - 100, y)
    }

    @Test
    fun placedBelowWhenNoRoomAbove() {
        val (_, y) = placeSelectionToolbar(ui(40f, 160f), w = 300, h = 100, containerW = 1000, containerH = 2000, gap = 10, margin = 20)
        assertEquals(170, y)
    }

    @Test
    fun centeredWhenSelectionCoversTheView() {
        val (_, y) = placeSelectionToolbar(ui(-500f, 4000f), w = 300, h = 100, containerW = 1000, containerH = 2000, gap = 10, margin = 20)
        assertEquals(950, y)
    }

    @Test
    fun keptInsideHorizontally() {
        val left = placeSelectionToolbar(ui(500f, 560f, left = 0f, right = 10f), 300, 100, 1000, 2000, 10, 20)
        assertEquals(20, left.first)
        val right = placeSelectionToolbar(ui(500f, 560f, left = 990f, right = 1000f), 300, 100, 1000, 2000, 10, 20)
        assertEquals(1000 - 300 - 20, right.first)
        // Wider than the container: pinned to the margin instead of throwing.
        val wide = placeSelectionToolbar(ui(500f, 560f), 2000, 100, 1000, 2000, 10, 20)
        assertEquals(20, wide.first)
    }
}
