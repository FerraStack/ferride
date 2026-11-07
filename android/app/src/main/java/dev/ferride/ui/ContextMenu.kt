package dev.ferride.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import dev.ferride.theme.LocalWorkbenchColors
import dev.ferride.workbench.ContextMenuRequest
import dev.ferride.workbench.ContextMenuItem

/**
 * A VS Code context menu (`menu.*` colors, separators between groups,
 * keybindings on the right) opened by a long press. Drawn in the workbench
 * window, not a popup, so it shows in screenshots and UI Automator. Tapping
 * outside or Back closes it.
 */
@Composable
fun ContextMenuOverlay(request: ContextMenuRequest, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClickLabel = "Close menu",
            onClick = onDismiss,
        ),
    )
    Layout(content = { MenuBody(request.items, onDismiss) }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val menu = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val margin = 8.dp.roundToPx()
        val maxX = (constraints.maxWidth - menu.width - margin).coerceAtLeast(margin)
        val x = request.x.toInt().coerceIn(margin, maxX)
        // Open below the finger; flip above when it would run off the bottom.
        val below = request.y.toInt()
        val y = if (below + menu.height <= constraints.maxHeight - margin) {
            below
        } else {
            (request.y.toInt() - menu.height).coerceAtLeast(margin)
        }
        layout(constraints.maxWidth, constraints.maxHeight) { menu.place(x, y) }
    }
}

@Composable
private fun MenuBody(items: List<ContextMenuItem>, onDismiss: () -> Unit) {
    val c = LocalWorkbenchColors.current
    val shape = RoundedCornerShape(5.dp)
    Column(
        Modifier
            .testTag("context-menu")
            .widthIn(min = 220.dp, max = 320.dp)
            .shadow(8.dp, shape, ambientColor = c.widgetShadow, spotColor = c.widgetShadow)
            .background(c.menuBackground, shape)
            .border(1.dp, c.menuBorder, shape)
            .padding(4.dp)
            // Swallow taps on the menu's padding so they don't close it.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        items.forEach { item ->
            if (item.separatorBefore) {
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 0.dp).height(1.dp).background(c.menuSeparator))
            }
            MenuRow(item) {
                onDismiss()
                item.onClick()
            }
        }
    }
}

@Composable
private fun MenuRow(item: ContextMenuItem, onClick: () -> Unit) {
    val c = LocalWorkbenchColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fg = when {
        !item.enabled -> c.descriptionForeground
        pressed -> c.menuSelectionForeground
        else -> c.menuForeground
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (pressed && item.enabled) c.menuSelectionBackground else Color.Transparent)
            .clickable(interaction, indication = null, enabled = item.enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(item.label, color = fg, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
        item.keybinding?.let {
            Box(Modifier.width(16.dp))
            Text(it, color = fg.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1)
        }
    }
}
