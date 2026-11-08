package dev.ferride.workbench

/** One entry of a context menu (VS Code's labels; [keybinding] shown dimmed on the right). */
data class ContextMenuItem(
    val label: String,
    val keybinding: String? = null,
    val enabled: Boolean = true,
    /** Draws a separator above this entry (VS Code's menu groups). */
    val separatorBefore: Boolean = false,
    val onClick: () -> Unit,
)

/** A context menu opened at ([x], [y]) in window pixels (long press position). */
data class ContextMenuRequest(val x: Float, val y: Float, val items: List<ContextMenuItem>)
