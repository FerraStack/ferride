package dev.ferride.editor

/**
 * What the floating selection toolbar needs from [CodeEditorView]: the
 * selection's bounding box in view pixels (`bottom` already includes the
 * handles, so a toolbar placed below never covers them) and which actions
 * apply.
 */
data class SelectionUi(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val hasSelection: Boolean,
    val canPaste: Boolean,
) {
    /** Toolbar entries in display order (VS Code's labels; Cut/Copy need a selection, Paste a clip). */
    val actions: List<SelectionAction>
        get() = buildList {
            if (hasSelection) {
                add(SelectionAction.Cut)
                add(SelectionAction.Copy)
            }
            if (canPaste) add(SelectionAction.Paste)
            add(SelectionAction.SelectAll)
        }
}

enum class SelectionAction(val label: String) {
    Cut("Cut"),
    Copy("Copy"),
    Paste("Paste"),
    SelectAll("Select All"),
}

/**
 * Where to put a toolbar of [w]×[h] px for [ui] inside a [containerW]×[containerH]
 * box: centered over the selection and [gap] px above it; below it (under the
 * handles) when there's no room above; vertically centered when neither fits
 * (e.g. Select All on a long file). Always kept [margin] px inside the box.
 */
fun placeSelectionToolbar(
    ui: SelectionUi,
    w: Int,
    h: Int,
    containerW: Int,
    containerH: Int,
    gap: Int,
    margin: Int,
): Pair<Int, Int> {
    val centerX = ((ui.left + ui.right) / 2f).toInt()
    val maxX = (containerW - w - margin).coerceAtLeast(margin)
    val x = (centerX - w / 2).coerceIn(margin, maxX)
    val above = ui.top.toInt() - gap - h
    val below = ui.bottom.toInt() + gap
    val y = when {
        above >= margin -> above
        below + h <= containerH - margin -> below
        else -> ((containerH - h) / 2).coerceAtLeast(0)
    }
    return x to y
}
