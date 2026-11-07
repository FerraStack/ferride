package dev.ferride.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ferride.editor.SelectionAction
import dev.ferride.editor.SelectionUi
import dev.ferride.editor.placeSelectionToolbar
import dev.ferride.theme.LocalWorkbenchColors

/**
 * The floating Cut / Copy / Paste / Select All toolbar shown over a touch
 * selection. Styled like VS Code's context menu (`menu.*` colors, 1 px border,
 * 5 dp corners, widget shadow) but laid out horizontally like Android's text
 * toolbar, with touch-sized entries. Fills its parent (the editor box) only to
 * position the bar; touches outside the bar fall through to the editor.
 */
@Composable
fun SelectionToolbar(ui: SelectionUi, onAction: (SelectionAction) -> Unit, modifier: Modifier = Modifier) {
    Layout(
        content = { ToolbarBar(ui.actions, onAction) },
        modifier = modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val bar = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val (x, y) = placeSelectionToolbar(
            ui, bar.width, bar.height, constraints.maxWidth, constraints.maxHeight,
            gap = 6.dp.roundToPx(), margin = 8.dp.roundToPx(),
        )
        layout(constraints.maxWidth, constraints.maxHeight) { bar.place(x, y) }
    }
}

@Composable
private fun ToolbarBar(actions: List<SelectionAction>, onAction: (SelectionAction) -> Unit) {
    val c = LocalWorkbenchColors.current
    val shape = RoundedCornerShape(5.dp)
    Row(
        Modifier
            .testTag("selection-toolbar")
            .shadow(8.dp, shape, ambientColor = c.widgetShadow, spotColor = c.widgetShadow)
            .background(c.menuBackground, shape)
            .border(1.dp, c.menuBorder, shape)
            .height(44.dp)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEachIndexed { i, action ->
            // VS Code groups Cut/Copy/Paste and puts Select All in its own group.
            if (action == SelectionAction.SelectAll && i > 0) {
                Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(20.dp).background(c.menuSeparator))
            }
            ToolbarItem(action.label) { onAction(action) }
        }
    }
}

@Composable
private fun ToolbarItem(label: String, onClick: () -> Unit) {
    val c = LocalWorkbenchColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .fillMaxHeight()
            .widthIn(min = 48.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (pressed) c.menuSelectionBackground else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (pressed) c.menuSelectionForeground else c.menuForeground, fontSize = 13.sp, maxLines = 1)
    }
}
