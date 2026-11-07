package dev.ferride.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ferride.core.FerruxStatus
import dev.ferride.editor.EditorActions
import dev.ferride.theme.LocalWorkbenchColors
import dev.ferride.workbench.CommandIds
import dev.ferride.workbench.PaletteMode
import dev.ferride.workbench.SideView
import dev.ferride.workbench.WorkbenchState

fun SideView.icon(): ImageVector = when (this) {
    SideView.Explorer -> Icons.Outlined.FileCopy
    SideView.Search -> Icons.Outlined.Search
    SideView.SourceControl -> Icons.Outlined.AccountTree
    SideView.Languages -> Icons.Outlined.Extension
    SideView.Settings -> Icons.Outlined.Settings
}

/** Activity Bar: a left rail (medium/expanded) or a bottom bar (compact). */
@Composable
fun ActivityBar(state: WorkbenchState, vertical: Boolean) {
    val c = LocalWorkbenchColors.current
    val main = listOf(SideView.Explorer, SideView.Search, SideView.SourceControl, SideView.Languages)
    @Composable
    fun Item(view: SideView) {
        val active = state.sideBarVisible && state.activeView == view
        val tint = if (active) c.activityBarForeground else c.activityBarInactiveForeground
        Box(contentAlignment = if (vertical) Alignment.CenterStart else Alignment.TopCenter) {
            BarIcon(view.icon(), view.title, tint, size = 48.dp, iconSize = 24.dp) { state.showView(view) }
            if (active) {
                val indicator = if (vertical) Modifier.width(2.dp).height(48.dp) else Modifier.width(48.dp).height(2.dp)
                Box(indicator.background(c.activityBarActiveBorder))
            }
        }
    }
    if (vertical) {
        Column(
            Modifier.width(48.dp).fillMaxHeight().background(c.activityBarBackground),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            main.forEach { Item(it) }
            Spacer(Modifier.weight(1f))
            Item(SideView.Settings)
        }
    } else {
        Row(
            Modifier.fillMaxWidth().height(48.dp).background(c.activityBarBackground),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            (main + SideView.Settings).forEach { Item(it) }
        }
    }
}

/** Compact title bar with VS Code's "command center" (Quick Open) and the palette button. */
@Composable
fun TitleBar(state: WorkbenchState) {
    val c = LocalWorkbenchColors.current
    Row(
        Modifier.fillMaxWidth().height(40.dp).background(c.activityBarBackground).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(34.dp))
        Row(
            Modifier
                .weight(1f)
                .height(28.dp)
                .background(c.inputBackground, RoundedCornerShape(6.dp))
                .border(1.dp, c.inputBorder, RoundedCornerShape(6.dp))
                .clickable { state.openPalette(PaletteMode.Files) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Outlined.Search, null, tint = c.descriptionForeground, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(state.backend.name, color = c.inputForeground, fontSize = 13.sp, maxLines = 1)
        }
        BarIcon(Icons.Default.MoreHoriz, "Show All Commands", c.activityBarForeground, size = 34.dp, iconSize = 20.dp) {
            state.commands.execute(CommandIds.SHOW_COMMANDS)
        }
    }
}

/** VS Code's status bar: branch, problems, then cursor/indent/encoding/EOL/language/LSP. */
@Composable
fun StatusBar(state: WorkbenchState, compact: Boolean) {
    val c = LocalWorkbenchColors.current
    val tab = state.activeTab
    tab?.revision // recompose on cursor moves and edits
    val fg = c.statusBarForeground
    Row(
        Modifier.fillMaxWidth().height(24.dp).background(c.statusBarBackground).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusItem(Icons.Outlined.Terminal, if (compact) "Ferrux" else ferruxLabel(state.ferrux)) {
            state.commands.execute(CommandIds.TOGGLE_TERMINAL)
        }
        state.branch?.let { StatusItem(Icons.Outlined.AccountTree, it) { state.showView(SideView.SourceControl) } }
        Row(
            Modifier.clickable { state.commands.execute(CommandIds.VIEW_PROBLEMS) }.padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ErrorOutline, "Errors", tint = fg, modifier = Modifier.size(14.dp))
            Text(" 0 ", color = fg, fontSize = 12.sp)
            Icon(Icons.Outlined.WarningAmber, "Warnings", tint = fg, modifier = Modifier.size(14.dp))
            Text(" 0", color = fg, fontSize = 12.sp)
        }
        Spacer(Modifier.weight(1f))
        if (tab != null) {
            val m = tab.model
            val head = m.selection.head
            val line = m.lineOfOffset(head)
            val col = head - m.lineStart(line) + 1
            StatusItem(null, "Ln ${line + 1}, Col $col") { state.commands.execute(CommandIds.GOTO_LINE) }
            if (!compact) {
                val s = state.settings
                StatusItem(null, if (s.insertSpaces) "Spaces: ${s.tabSize}" else "Tab Size: ${s.tabSize}") {
                    state.showView(SideView.Settings)
                }
                StatusItem(null, "UTF-8") {}
                StatusItem(null, "LF") {}
            }
            StatusItem(null, languageDisplayName(m.language)) { state.showView(SideView.Languages) }
            if (!compact) StatusItem(null, "{ } LSP: off") { state.showView(SideView.Languages) }
        }
        StatusItem(Icons.Outlined.Notifications, null) { state.showPanel(dev.ferride.workbench.PanelTab.Output) }
    }
}

fun ferruxLabel(s: FerruxStatus) = when (s) {
    FerruxStatus.NotInstalled -> "Ferrux: not installed"
    FerruxStatus.NotPaired -> "Ferrux: not paired"
    is FerruxStatus.Unavailable -> "Ferrux: unavailable"
    is FerruxStatus.Ready -> "Ferrux"
}

@Composable
private fun StatusItem(icon: ImageVector?, text: String?, onClick: () -> Unit) {
    val c = LocalWorkbenchColors.current
    Row(
        Modifier.fillMaxHeight().clickable(onClick = onClick).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, text, tint = c.statusBarForeground, modifier = Modifier.size(14.dp))
            if (text != null) Spacer(Modifier.width(4.dp))
        }
        if (text != null) Text(text, color = c.statusBarForeground, fontSize = 12.sp, maxLines = 1)
    }
}

/** Extra keys above the soft keyboard: Tab, arrows, brackets, symbols, undo/redo. */
@Composable
fun SymbolsRow(state: WorkbenchState) {
    val c = LocalWorkbenchColors.current
    val ed: EditorActions? = state.editor
    @Composable
    fun Key(label: String, description: String = label, action: (EditorActions) -> Unit) {
        Box(
            Modifier
                .widthIn(min = 36.dp)
                .fillMaxHeight()
                .clickable(onClickLabel = description) { state.editor?.let(action) }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = c.sideBarForeground, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(c.sideBarBackground)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Key("⇥", "Tab") { it.indent() }
        Key("←", "Left") { it.moveCursor(EditorActions.Direction.Left) }
        Key("↑", "Up") { it.moveCursor(EditorActions.Direction.Up) }
        Key("↓", "Down") { it.moveCursor(EditorActions.Direction.Down) }
        Key("→", "Right") { it.moveCursor(EditorActions.Direction.Right) }
        listOf("{", "}", "(", ")", "[", "]", "<", ">", ";", ":", "\"", "'", "=", "_", "-", "+", "*", "/", "\\", "|", "&", "!", "#", "$", "`", ",", ".")
            .forEach { s -> Key(s) { it.insertText(s) } }
        Box(Modifier.width(1.dp).height(24.dp).background(c.panelBorder))
        BarIcon(Icons.AutoMirrored.Filled.Undo, "Undo", c.sideBarForeground, size = 40.dp, iconSize = 20.dp) {
            ed?.undo()
        }
        BarIcon(Icons.AutoMirrored.Filled.Redo, "Redo", c.sideBarForeground, size = 40.dp, iconSize = 20.dp) {
            ed?.redo()
        }
    }
}
