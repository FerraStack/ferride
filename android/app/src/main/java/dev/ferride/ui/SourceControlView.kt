package dev.ferride.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ferride.core.ScmChange
import dev.ferride.scm.GitCommandIds
import dev.ferride.scm.ScmMode
import dev.ferride.scm.ScmState
import dev.ferride.theme.LocalWorkbenchColors
import dev.ferride.theme.WorkbenchColors
import dev.ferride.workbench.WorkbenchState

/** VS Code's git decoration colors (`gitDecoration.*ResourceForeground`), from the theme or VS Code's defaults. */
fun WorkbenchColors.gitDecoration(change: ScmChange): Color = when (change.letter) {
    "A" -> color("gitDecoration.addedResourceForeground", if (isDark) Color(0xFF81B88B) else Color(0xFF587C0C))
    "D" -> color("gitDecoration.deletedResourceForeground", if (isDark) Color(0xFFC74E39) else Color(0xFFAD0707))
    "U" -> color("gitDecoration.untrackedResourceForeground", if (isDark) Color(0xFF73C991) else Color(0xFF007100))
    "!" -> color("gitDecoration.conflictingResourceForeground", if (isDark) Color(0xFFE4676B) else Color(0xFFAD0707))
    else -> color("gitDecoration.modifiedResourceForeground", if (isDark) Color(0xFFE2C08D) else Color(0xFF895503))
}

/** `activityBarBadge.*`: the count on the Source Control icon. */
val WorkbenchColors.activityBarBadgeBackground: Color
    get() = color("activityBarBadge.background", if (isDark) Color(0xFF0078D4) else Color(0xFF005FB8))
val WorkbenchColors.activityBarBadgeForeground: Color
    get() = color("activityBarBadge.foreground", Color.White)

/** A codicon by name (the icons quick picks and menus refer to). */
fun codicon(name: String?): ImageVector? = when (name) {
    "add" -> Codicons.add
    "git-branch", "source-control" -> Codicons.sourceControl
    "cloud" -> Codicons.cloud
    "tag" -> Codicons.tag
    "repo-clone" -> Codicons.repoClone
    else -> null
}

/** Title bar actions of the Source Control view: Commit, Refresh, More Actions. */
@Composable
fun ScmTitleActions(state: WorkbenchState) {
    val c = LocalWorkbenchColors.current
    val scm = state.scm
    if (scm.mode != ScmMode.Ready) return
    BarIcon(Codicons.check, "Commit", c.sideBarForeground, size = 32.dp, iconSize = 16.dp) {
        state.commands.execute(GitCommandIds.COMMIT)
    }
    BarIcon(Codicons.refresh, "Refresh", c.sideBarForeground, size = 32.dp, iconSize = 16.dp) {
        state.commands.execute(GitCommandIds.REFRESH)
    }
    var open by remember { mutableStateOf(false) }
    Box {
        BarIcon(Codicons.ellipsis, "More Actions...", c.sideBarForeground, size = 32.dp, iconSize = 16.dp) { open = true }
        DropdownMenu(open, { open = false }, Modifier.background(c.menuBackground)) {
            MORE_ACTIONS.forEach { group ->
                if (group !== MORE_ACTIONS.first()) HorizontalDivider(color = c.menuSeparator)
                group.forEach { (label, id) ->
                    val enabled = state.commands.get(id)?.enabled?.invoke() == true
                    DropdownMenuItem(
                        text = { Text(label, color = if (enabled) c.menuForeground else c.descriptionForeground, fontSize = 13.sp) },
                        enabled = enabled,
                        onClick = {
                            open = false
                            state.commands.execute(id)
                        },
                    )
                }
            }
        }
    }
}

private val MORE_ACTIONS: List<List<Pair<String, String>>> = listOf(
    listOf("Pull" to GitCommandIds.PULL, "Push" to GitCommandIds.PUSH, "Fetch" to GitCommandIds.FETCH, "Sync" to GitCommandIds.SYNC),
    listOf("Checkout to..." to GitCommandIds.CHECKOUT, "Create Branch..." to GitCommandIds.BRANCH, "Publish Branch..." to GitCommandIds.PUBLISH),
    listOf(
        "Commit Staged (Amend)" to GitCommandIds.COMMIT_AMEND,
        "Stage All Changes" to GitCommandIds.STAGE_ALL,
        "Unstage All Changes" to GitCommandIds.UNSTAGE_ALL,
        "Discard All Changes" to GitCommandIds.CLEAN_ALL,
    ),
    listOf("Clone" to GitCommandIds.CLONE),
)

/** The Source Control view: commit box and button, then Merge Changes / Staged Changes / Changes. */
@Composable
fun SourceControlView(state: WorkbenchState) {
    val scm = state.scm
    val c = LocalWorkbenchColors.current
    Column(Modifier.fillMaxSize()) {
        val op = scm.operation
        if (op != null) {
            val total = op.total
            if (total != null && total > 0) {
                LinearProgressIndicator(
                    progress = { (op.current.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = c.focusBorder,
                    trackColor = Color.Transparent,
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = c.focusBorder, trackColor = Color.Transparent)
            }
        } else {
            Spacer(Modifier.height(2.dp))
        }
        when (scm.mode) {
            ScmMode.Loading -> {}
            ScmMode.Unsupported -> WelcomeText(
                listOf(
                    "This folder was opened through Android's file picker, where Git can't work.",
                    "Copy this folder into Ferride projects to enable Git.",
                ),
                emptyList(),
            )
            ScmMode.NoRepository -> WelcomeText(
                listOf(
                    "The folder currently open doesn't have a git repository. You can initialize a repository " +
                        "which will enable source control features powered by Git.",
                    "You can also clone a repository from a URL into Ferride's projects.",
                ),
                listOf(
                    "Initialize Repository" to { state.commands.execute(GitCommandIds.INIT); Unit },
                    "Clone Repository" to { state.commands.execute(GitCommandIds.CLONE); Unit },
                ),
            )
            ScmMode.Ready -> RepositoryView(state, scm)
        }
    }
}

@Composable
private fun WelcomeText(paragraphs: List<String>, buttons: List<Pair<String, () -> Unit>>) {
    val c = LocalWorkbenchColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        paragraphs.forEachIndexed { i, p ->
            Text(p, color = c.sideBarForeground, fontSize = 13.sp, lineHeight = 19.sp)
            if (i < buttons.size) {
                Spacer(Modifier.height(12.dp))
                PrimaryButton(buttons[i].first, null, Modifier.fillMaxWidth(), onClick = buttons[i].second)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PrimaryButton(
    label: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = LocalWorkbenchColors.current
    val bg = if (enabled) c.buttonBackground else c.buttonBackground.copy(alpha = 0.5f)
    val fg = if (enabled) c.buttonForeground else c.buttonForeground.copy(alpha = 0.6f)
    Row(
        modifier
            .heightIn(min = 32.dp)
            .background(bg, RoundedCornerShape(2.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = fg, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun RepositoryView(state: WorkbenchState, scm: ScmState) {
    val c = LocalWorkbenchColors.current
    val head = scm.head
    var stagedOpen by rememberSaveable { mutableStateOf(true) }
    var changesOpen by rememberSaveable { mutableStateOf(true) }
    var mergeOpen by rememberSaveable { mutableStateOf(true) }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "input") {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)) {
                CommitInput(state, scm)
                Spacer(Modifier.height(8.dp))
                val hasChanges = scm.changes.isNotEmpty()
                when {
                    hasChanges || head == null -> PrimaryButton("Commit", Codicons.check, Modifier.fillMaxWidth(), enabled = hasChanges) {
                        state.commands.execute(GitCommandIds.COMMIT)
                    }
                    head.branch != null && head.upstream == null && head.commit != null ->
                        PrimaryButton("Publish Branch", Codicons.cloudUpload, Modifier.fillMaxWidth()) {
                            state.commands.execute(GitCommandIds.PUBLISH)
                        }
                    head.ahead > 0 || head.behind > 0 -> {
                        val counts = buildString {
                            if (head.behind > 0) append(" ${head.behind}↓")
                            if (head.ahead > 0) append(" ${head.ahead}↑")
                        }
                        PrimaryButton("Sync Changes$counts", Codicons.sync, Modifier.fillMaxWidth()) {
                            state.commands.execute(GitCommandIds.SYNC)
                        }
                    }
                    else -> PrimaryButton("Commit", Codicons.check, Modifier.fillMaxWidth(), enabled = false) {}
                }
            }
        }
        group(
            "merge", "Merge Changes", scm.merge, mergeOpen, { mergeOpen = !mergeOpen },
            headerActions = { },
            rowActions = { ch ->
                RowAction(Codicons.goToFile, "Open File") { scm.open(ch) }
                RowAction(Codicons.add, "Stage Changes") { scm.stage(ch) }
            },
            onRow = { scm.open(it) },
        )
        group(
            "staged", "Staged Changes", scm.staged, stagedOpen, { stagedOpen = !stagedOpen },
            headerActions = { RowAction(Codicons.remove, "Unstage All Changes") { scm.unstageAll() } },
            rowActions = { ch ->
                RowAction(Codicons.goToFile, "Open File") { scm.open(ch) }
                RowAction(Codicons.remove, "Unstage Changes") { scm.unstage(ch) }
            },
            onRow = { scm.open(it) },
        )
        group(
            "changes", "Changes", scm.unstaged, changesOpen, { changesOpen = !changesOpen },
            headerActions = {
                RowAction(Codicons.discard, "Discard All Changes") { scm.discardAll() }
                RowAction(Codicons.add, "Stage All Changes") { scm.stageAll() }
            },
            rowActions = { ch ->
                RowAction(Codicons.goToFile, "Open File") { scm.open(ch) }
                RowAction(Codicons.discard, "Discard Changes") { scm.discard(ch) }
                RowAction(Codicons.add, "Stage Changes") { scm.stage(ch) }
            },
            onRow = { scm.open(it) },
            alwaysShow = true,
        )
    }
}

@Composable
private fun CommitInput(state: WorkbenchState, scm: ScmState) {
    val c = LocalWorkbenchColors.current
    val branch = scm.head?.branch ?: scm.head?.label
    val placeholder = if (branch != null) "Message (Ctrl+Enter to commit on \"$branch\")" else "Message (Ctrl+Enter to commit)"
    Box(
        Modifier.fillMaxWidth()
            .heightIn(min = 32.dp)
            .background(c.inputBackground, RoundedCornerShape(2.dp))
            .border(1.dp, c.inputBorder, RoundedCornerShape(2.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        if (scm.commitMessage.isEmpty()) {
            Text(placeholder, color = c.descriptionForeground, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        BasicTextField(
            value = scm.commitMessage,
            onValueChange = { scm.commitMessage = it },
            textStyle = TextStyle(color = c.inputForeground, fontSize = 13.sp),
            cursorBrush = SolidColor(c.editorCursor),
            maxLines = 10,
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                val native = e.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_ENTER && native.isCtrlPressed) {
                    state.commands.execute(GitCommandIds.COMMIT)
                    true
                } else {
                    false
                }
            },
        )
    }
}

private fun LazyListScope.group(
    key: String,
    title: String,
    changes: List<ScmChange>,
    open: Boolean,
    toggle: () -> Unit,
    headerActions: @Composable () -> Unit,
    rowActions: @Composable (ScmChange) -> Unit,
    onRow: (ScmChange) -> Unit,
    alwaysShow: Boolean = false,
) {
    if (changes.isEmpty() && !alwaysShow) return
    item(key = "h:$key") {
        val c = LocalWorkbenchColors.current
        Row(
            Modifier.fillMaxWidth().height(30.dp).clickable(onClickLabel = title, onClick = toggle).padding(start = 4.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (open) Codicons.chevronDown else Codicons.chevronRight,
                null,
                tint = c.sideBarForeground,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(2.dp))
            Text(title, color = c.sideBarForeground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (changes.isNotEmpty()) headerActions()
            Spacer(Modifier.width(4.dp))
            CountBadge(changes.size)
        }
    }
    if (open) {
        items(changes, key = { "$key:${it.path}" }) { ch -> ChangeRow(ch, rowActions, onRow) }
    }
}

@Composable
private fun RowAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    val c = LocalWorkbenchColors.current
    BarIcon(icon, description, c.sideBarForeground, size = 28.dp, iconSize = 16.dp, onClick = onClick)
}

@Composable
private fun ChangeRow(change: ScmChange, actions: @Composable (ScmChange) -> Unit, onRow: (ScmChange) -> Unit) {
    val c = LocalWorkbenchColors.current
    val name = change.path.substringAfterLast('/')
    val dir = change.path.substringBeforeLast('/', "")
    val color = c.gitDecoration(change)
    Row(
        Modifier.fillMaxWidth().height(30.dp).clickable(onClickLabel = "Open ${change.path}") { onRow(change) }
            .padding(start = 22.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileIcon(name)
        Spacer(Modifier.width(4.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                color = color,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (change.deleted) TextDecoration.LineThrough else null,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (dir.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(dir, color = c.descriptionForeground, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions(change)
        Text(
            change.letter,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(18.dp).padding(start = 6.dp),
        )
    }
}
