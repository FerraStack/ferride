package dev.ferride.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import dev.ferride.core.FerruxStatus
import dev.ferride.editor.EditorActions
import dev.ferride.theme.LocalWorkbenchColors
import dev.ferride.workbench.CommandIds
import dev.ferride.workbench.PaletteMode
import dev.ferride.workbench.PanelTab
import dev.ferride.workbench.SideView
import dev.ferride.workbench.TabKind
import dev.ferride.workbench.WorkbenchState

fun SideView.icon(): ImageVector = when (this) {
    SideView.Explorer -> Icons.Outlined.FileCopy
    SideView.Search -> Icons.Outlined.Search
    SideView.SourceControl -> Icons.Outlined.AccountTree
    SideView.Languages -> Icons.Outlined.Extension
}

/**
 * Activity Bar: a left rail (medium/expanded) or a bottom bar (compact). The
 * active view gets VS Code's 2dp accent bar; the gear opens the Settings editor.
 */
@Composable
fun ActivityBar(state: WorkbenchState, vertical: Boolean) {
    val c = LocalWorkbenchColors.current
    @Composable
    fun Item(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
        val tint = if (active) c.activityBarForeground else c.activityBarInactiveForeground
        Box(contentAlignment = if (vertical) Alignment.CenterStart else Alignment.TopCenter) {
            BarIcon(icon, label, tint, size = 48.dp, iconSize = 24.dp, onClick = onClick)
            if (active) {
                val indicator = if (vertical) Modifier.width(2.dp).height(48.dp) else Modifier.width(32.dp).height(2.dp)
                Box(indicator.background(c.activityBarActiveBorder))
            }
        }
    }
    @Composable
    fun Views() {
        SideView.entries.forEach { v ->
            // On the bottom bar the current view stays marked even while the drawer is closed.
            val active = state.activeView == v && (state.sideBarVisible || !vertical) &&
                state.activeTab?.kind != TabKind.Settings
            Item(v.icon(), v.title, active) { state.showView(v) }
        }
    }
    val settingsActive = state.activeTab?.kind == TabKind.Settings
    if (vertical) {
        Row(Modifier.fillMaxHeight()) {
            Column(
                Modifier.width(48.dp).fillMaxHeight().background(c.activityBarBackground),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Views()
                Spacer(Modifier.weight(1f))
                Item(Icons.Outlined.Settings, "Settings", settingsActive) { state.commands.execute(CommandIds.OPEN_SETTINGS) }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.panelBorder))
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.panelBorder))
            Row(
                Modifier.fillMaxWidth().height(48.dp).background(c.activityBarBackground),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Views()
                Item(Icons.Outlined.Settings, "Settings", settingsActive) { state.commands.execute(CommandIds.OPEN_SETTINGS) }
            }
        }
    }
}

private data class MenuEntry(val label: String, val commandId: String)

private val MENUS: List<Pair<String, List<MenuEntry>>> = listOf(
    "File" to listOf(
        MenuEntry("Go to File…", CommandIds.QUICK_OPEN),
        MenuEntry("Save", CommandIds.SAVE),
        MenuEntry("Save All", CommandIds.SAVE_ALL),
        MenuEntry("Close Editor", CommandIds.CLOSE_EDITOR),
        MenuEntry("Settings", CommandIds.OPEN_SETTINGS),
    ),
    "Edit" to listOf(
        MenuEntry("Undo", CommandIds.UNDO),
        MenuEntry("Redo", CommandIds.REDO),
        MenuEntry("Cut", CommandIds.CUT),
        MenuEntry("Copy", CommandIds.COPY),
        MenuEntry("Paste", CommandIds.PASTE),
        MenuEntry("Find", CommandIds.FIND),
        MenuEntry("Replace", CommandIds.REPLACE),
    ),
    "View" to listOf(
        MenuEntry("Command Palette…", CommandIds.SHOW_COMMANDS),
        MenuEntry("Explorer", CommandIds.VIEW_EXPLORER),
        MenuEntry("Search", CommandIds.VIEW_SEARCH),
        MenuEntry("Problems", CommandIds.VIEW_PROBLEMS),
        MenuEntry("Terminal", CommandIds.TOGGLE_TERMINAL),
        MenuEntry("Word Wrap", CommandIds.TOGGLE_WORD_WRAP),
        MenuEntry("Toggle Primary Side Bar", CommandIds.TOGGLE_SIDEBAR),
        MenuEntry("Toggle Panel", CommandIds.TOGGLE_PANEL),
    ),
)

@Composable
private fun MenuItems(state: WorkbenchState, entries: List<MenuEntry>, dismiss: () -> Unit) {
    val c = LocalWorkbenchColors.current
    entries.forEach { e ->
        val cmd = state.commands.get(e.commandId)
        val enabled = cmd?.enabled?.invoke() ?: false
        DropdownMenuItem(
            text = {
                Row(Modifier.widthIn(min = 200.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        e.label,
                        color = if (enabled) c.inputForeground else c.descriptionForeground,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    cmd?.keybinding?.let { Text(it.label, color = c.descriptionForeground, fontSize = 12.sp) }
                }
            },
            enabled = enabled,
            onClick = {
                dismiss()
                state.commands.execute(e.commandId)
            },
        )
    }
}

/**
 * Title bar: menus (a "File Edit View" menu bar on wide screens, a hamburger
 * on phones), VS Code's command center pill in the middle, and the palette button.
 */
@Composable
fun TitleBar(state: WorkbenchState, wide: Boolean) {
    val c = LocalWorkbenchColors.current
    Column(Modifier.fillMaxWidth().background(c.activityBarBackground)) {
        Box(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 4.dp)) {
            Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                if (wide) {
                    MENUS.forEach { (title, entries) ->
                        var open by remember { mutableStateOf(false) }
                        Box {
                            Text(
                                title,
                                color = c.activityBarForeground,
                                fontSize = 13.sp,
                                modifier = Modifier.clickable { open = true }.padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                            DropdownMenu(open, { open = false }, Modifier.background(c.quickInputBackground)) {
                                MenuItems(state, entries) { open = false }
                            }
                        }
                    }
                } else {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        BarIcon(Icons.Default.Menu, "Application Menu", c.activityBarForeground, size = 36.dp, iconSize = 20.dp) {
                            open = true
                        }
                        DropdownMenu(open, { open = false }, Modifier.background(c.quickInputBackground)) {
                            MENUS.forEachIndexed { i, (title, entries) ->
                                if (i > 0) HorizontalDivider(color = c.panelBorder)
                                Text(
                                    title.uppercase(),
                                    color = c.descriptionForeground,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                                MenuItems(state, entries) { open = false }
                            }
                        }
                    }
                }
            }
            // Command center.
            Row(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(if (wide) 0.38f else 0.66f)
                    .height(26.dp)
                    .background(c.inputBackground, RoundedCornerShape(6.dp))
                    .border(1.dp, c.inputBorder, RoundedCornerShape(6.dp))
                    .clickable(onClickLabel = "Search files") { state.openPalette(PaletteMode.Files) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Outlined.Search, null, tint = c.descriptionForeground, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(state.backend.name, color = c.inputForeground, fontSize = 13.sp, maxLines = 1)
            }
            BarIcon(
                Icons.Default.MoreHoriz,
                "Show All Commands",
                c.activityBarForeground,
                modifier = Modifier.align(Alignment.CenterEnd),
                size = 36.dp,
                iconSize = 20.dp,
            ) { state.commands.execute(CommandIds.SHOW_COMMANDS) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.panelBorder))
    }
}

/** VS Code's status bar: Ferrux, branch, problems, then cursor/indent/encoding/EOL/language/LSP. */
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
            Modifier.fillMaxHeight().clickable { state.commands.execute(CommandIds.VIEW_PROBLEMS) }.padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ErrorOutline, "Errors", tint = fg, modifier = Modifier.size(14.dp))
            Text(" ${state.errorCount} ", color = fg, fontSize = 12.sp)
            Icon(Icons.Outlined.WarningAmber, "Warnings", tint = fg, modifier = Modifier.size(14.dp))
            Text(" ${state.warningCount}", color = fg, fontSize = 12.sp)
        }
        Spacer(Modifier.weight(1f))
        val m = tab?.model?.takeIf { !it.isClosed }
        if (m != null) {
            val head = m.selection.head
            val line = m.lineOfOffset(head)
            val col = head - m.lineStart(line) + 1
            StatusItem(null, "Ln ${line + 1}, Col $col") { state.commands.execute(CommandIds.GOTO_LINE) }
            if (!compact) {
                val s = state.settings
                StatusItem(null, if (s.insertSpaces) "Spaces: ${s.tabSize}" else "Tab Size: ${s.tabSize}") {
                    state.openSettings()
                }
                StatusItem(null, "UTF-8") {}
                StatusItem(null, "LF") {}
            }
            StatusItem(null, languageDisplayName(m.language)) { state.commands.execute(CommandIds.CHANGE_LANGUAGE_MODE) }
            if (!compact) StatusItem(null, "{ } LSP: off") { state.showView(SideView.Languages) }
        }
        StatusItem(Icons.Outlined.Notifications, null) { state.showPanel(PanelTab.Output) }
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

/** A key of the symbols row: what it shows, what it inserts/does, and long-press alternates. */
private data class ExtraKey(
    val label: String,
    val description: String = label,
    val alternates: List<String> = emptyList(),
    val action: (WorkbenchState, EditorActions) -> Unit,
)

private fun ins(s: String, vararg alternates: String) =
    ExtraKey(s, s, alternates.toList()) { _, e -> e.insertText(s) }

private val EXTRA_KEYS: List<ExtraKey> = listOf(
    ExtraKey("Esc", "Escape") { st, _ -> st.escape() },
    ExtraKey("⇥", "Tab") { _, e -> e.indent() },
    ExtraKey("←", "Cursor left") { _, e -> e.moveCursor(EditorActions.Direction.Left) },
    ExtraKey("↑", "Cursor up") { _, e -> e.moveCursor(EditorActions.Direction.Up) },
    ExtraKey("↓", "Cursor down") { _, e -> e.moveCursor(EditorActions.Direction.Down) },
    ExtraKey("→", "Cursor right") { _, e -> e.moveCursor(EditorActions.Direction.Right) },
    ins("(", "()", "[", "]", "{", "}"),
    ins(")", "]", "}"),
    ins("{", "{}", "}"),
    ins("}"),
    ins("[", "[]", "]"),
    ins("]"),
    ins(";", ";\n"),
    ins(":", "::"),
    ins("\"", "\"\"", "`"),
    ins("'", "''", "`"),
    ins("=", "==", "=>", "!=", "==="),
    ins("<", "<=", "</", "<>"),
    ins(">", ">=", "->", "=>"),
    ins("/", "//", "\\", "/*"),
    ins("|", "||"),
    ins("&", "&&"),
    ins("_", "-", "__"),
    ins("-", "->", "--", "+"),
    ins("+", "+=", "++"),
    ins("*", "**", "*="),
    ins("!", "!="),
    ins("#", "#["),
    ins("$", "\${"),
    ins(".", "..", "..."),
    ins(",", ", "),
)

/**
 * Extra keys above the soft keyboard: Esc, Tab, arrows, brackets and common
 * symbols, undo/redo. Equal-width monospace keys with press feedback; long
 * press shows alternates (e.g. `(` → `()` `[` `]` `{` `}`).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SymbolsRow(state: WorkbenchState) {
    val c = LocalWorkbenchColors.current
    Column(Modifier.fillMaxWidth().background(c.sideBarBackground)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.panelBorder))
        Row(
            Modifier.fillMaxWidth().height(44.dp).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EXTRA_KEYS.forEach { key ->
                var showAlternates by remember { mutableStateOf(false) }
                Box(
                    Modifier
                        .width(44.dp)
                        .fillMaxHeight()
                        .combinedClickable(
                            onClickLabel = key.description,
                            onLongClick = if (key.alternates.isNotEmpty()) ({ showAlternates = true }) else null,
                        ) { state.editor?.let { key.action(state, it) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(key.label, color = c.sideBarForeground, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
                    if (key.alternates.isNotEmpty()) {
                        // Small hint dot for keys with alternates.
                        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp).size(3.dp).background(c.descriptionForeground, RoundedCornerShape(50)))
                    }
                    if (showAlternates) {
                        Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, -140), onDismissRequest = { showAlternates = false }) {
                            Row(
                                Modifier.background(c.quickInputBackground, RoundedCornerShape(6.dp))
                                    .border(1.dp, c.panelBorder, RoundedCornerShape(6.dp))
                                    .padding(4.dp),
                            ) {
                                key.alternates.forEach { alt ->
                                    Box(
                                        Modifier.widthIn(min = 40.dp).height(40.dp).clickable {
                                            showAlternates = false
                                            state.editor?.insertText(alt)
                                        },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            alt.replace("\n", "⏎"),
                                            color = c.inputForeground,
                                            fontSize = 15.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(horizontal = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Box(Modifier.width(1.dp).height(24.dp).background(c.panelBorder))
            BarIcon(Icons.AutoMirrored.Filled.Undo, "Undo", c.sideBarForeground, size = 44.dp, iconSize = 20.dp) {
                state.editor?.undo()
            }
            BarIcon(Icons.AutoMirrored.Filled.Redo, "Redo", c.sideBarForeground, size = 44.dp, iconSize = 20.dp) {
                state.editor?.redo()
            }
        }
    }
}
