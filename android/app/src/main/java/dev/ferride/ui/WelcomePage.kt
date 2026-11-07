package dev.ferride.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ferride.theme.LocalWorkbenchColors
import dev.ferride.workbench.CommandIds
import dev.ferride.workbench.ContextMenuItem
import dev.ferride.workbench.ContextMenuRequest
import dev.ferride.workbench.RecentFolder
import dev.ferride.workbench.WorkbenchState

/**
 * The Welcome page (VS Code's Get Started), as an editor tab: Start (New File,
 * Open Folder, Clone Git Repository when Source Control provides it), Recent
 * folders, and Help shortcuts; two columns on wide screens. Links use
 * `textLink.foreground`; "Show welcome page on startup" edits
 * `workbench.startupEditor`.
 */
@Composable
fun WelcomePage(state: WorkbenchState, modifier: Modifier = Modifier) {
    val c = LocalWorkbenchColors.current
    BoxWithConstraints(modifier.fillMaxSize().background(c.editorBackground)) {
        val wide = maxWidth >= 720.dp
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (wide) 64.dp else 24.dp, vertical = if (wide) 48.dp else 28.dp),
        ) {
            Text("Ferride", color = c.editorForeground, fontSize = if (wide) 40.sp else 32.sp, fontWeight = FontWeight.Light)
            Text("Editing evolved, in your pocket", color = c.descriptionForeground, fontSize = if (wide) 20.sp else 16.sp)
            Spacer(Modifier.height(if (wide) 40.dp else 28.dp))
            if (wide) {
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        StartSection(state)
                        Spacer(Modifier.height(32.dp))
                        RecentSection(state)
                    }
                    Spacer(Modifier.width(64.dp))
                    Column(Modifier.weight(1f)) { HelpSection(state) }
                }
            } else {
                StartSection(state)
                Spacer(Modifier.height(28.dp))
                RecentSection(state)
                Spacer(Modifier.height(28.dp))
                HelpSection(state)
            }
            Spacer(Modifier.height(36.dp))
            StartupCheckbox(state)
        }
    }
}

@Composable
private fun Heading(text: String) {
    val c = LocalWorkbenchColors.current
    Text(text, color = c.editorForeground, fontSize = 18.sp, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun StartSection(state: WorkbenchState) {
    Heading("Start")
    LinkRow(Codicon.NEW_FILE, "New File…") { state.commands.execute(CommandIds.EXPLORER_NEW_FILE) }
    LinkRow(Codicon.FOLDER_OPENED, "Open Folder…", enabled = state.host != null) { state.commands.execute(CommandIds.OPEN_FOLDER) }
    if (state.commands.get(CommandIds.GIT_CLONE) != null) {
        LinkRow(Codicon.REPO_CLONE, "Clone Git Repository…") { state.commands.execute(CommandIds.GIT_CLONE) }
    }
}

@Composable
private fun RecentSection(state: WorkbenchState) {
    val c = LocalWorkbenchColors.current
    Heading("Recent")
    val recents = state.recentFolders.filter { it.root != state.backend.root }
    if (recents.isEmpty()) {
        Text(
            "You have no recent folders, open a folder to start.",
            color = c.descriptionForeground,
            fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
    recents.forEach { RecentRow(state, it) }
}

@Composable
private fun RecentRow(state: WorkbenchState, recent: RecentFolder) {
    val c = LocalWorkbenchColors.current
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .onGloballyPositioned { coords = it }
            .pointerInput(recent) {
                detectTapGestures(
                    onTap = { state.openRecent(recent) },
                    onLongPress = { at ->
                        val p = coords?.localToRoot(at) ?: at
                        state.contextMenu = ContextMenuRequest(
                            p.x,
                            p.y,
                            listOf(ContextMenuItem("Remove from Recently Opened") { state.removeRecent(recent) }),
                        )
                    },
                )
            }
            .semantics(mergeDescendants = true) { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(recent.name, color = c.textLinkForeground, fontSize = 14.sp, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        Text(
            recent.location,
            color = c.descriptionForeground,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HelpSection(state: WorkbenchState) {
    Heading("Help")
    listOf(
        Triple(Codicon.KEYBOARD, "Show All Commands", CommandIds.SHOW_COMMANDS),
        Triple(Codicon.GO_TO_FILE, "Go to File", CommandIds.QUICK_OPEN),
        Triple(Codicon.SETTINGS_GEAR, "Open Settings", CommandIds.OPEN_SETTINGS),
    ).forEach { (glyph, label, id) ->
        LinkRow(glyph, label, keybinding = state.commands.get(id)?.keybinding) { state.commands.execute(id) }
    }
}

@Composable
private fun LinkRow(
    glyph: Char,
    label: String,
    enabled: Boolean = true,
    keybinding: dev.ferride.workbench.KeyChord? = null,
    onClick: () -> Unit,
) {
    val c = LocalWorkbenchColors.current
    val color = if (enabled) c.textLinkForeground else c.descriptionForeground
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CodiconIcon(glyph, color)
        Spacer(Modifier.width(8.dp))
        Text(label, color = color, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
        keybinding?.let { KeybindingLabel(it) }
    }
}

@Composable
private fun StartupCheckbox(state: WorkbenchState) {
    val c = LocalWorkbenchColors.current
    val on = state.settings.startupEditor == "welcomePage"
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .clickable(role = Role.Checkbox) {
                state.updateSettings { it.copy(startupEditor = if (on) "none" else "welcomePage") }
            }
            .semantics { stateDescription = if (on) "Checked" else "Not checked" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .background(c.inputBackground, RoundedCornerShape(3.dp))
                .border(1.dp, c.inputBorder, RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (on) CodiconIcon(Codicon.CHECK, c.inputForeground, size = 14.dp)
        }
        Spacer(Modifier.width(8.dp))
        Text("Show welcome page on startup", color = c.editorForeground, fontSize = 13.sp, modifier = Modifier.widthIn(max = 400.dp))
    }
}
