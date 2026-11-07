package dev.ferride.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.ferride.workbench.CommandIds
import dev.ferride.workbench.ExplorerEdit
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ferride.core.FileNode
import dev.ferride.theme.LocalWorkbenchColors
import dev.ferride.workbench.SideView
import dev.ferride.workbench.WorkbenchState

/** The Side Bar: one view per Activity Bar entry. */
@Composable
fun SideBar(state: WorkbenchState, modifier: Modifier = Modifier) {
    val c = LocalWorkbenchColors.current
    Column(modifier.background(c.sideBarBackground)) {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(state.activeView.title)
        }
        when (state.activeView) {
            SideView.Explorer -> Explorer(state)
            SideView.Search -> SearchView(state)
            SideView.SourceControl -> SourceControlView(state)
            SideView.Languages -> LanguagesView()
        }
    }
}

// ----- Explorer ----------------------------------------------------------------

/** Where a row is on screen, for anchoring its long-press menu (not state: no recomposition). */
class CoordsHolder {
    var value: LayoutCoordinates? = null
}

/** A flattened Explorer row: a file/folder, or the inline name box. */
private sealed interface ExplorerRow {
    val key: String

    data class Node(val node: FileNode, val depth: Int) : ExplorerRow {
        override val key get() = node.path
    }

    data class Edit(val edit: ExplorerEdit, val depth: Int) : ExplorerRow {
        override val key get() = "edit:${edit.parent}:${edit.kind}"
    }
}

private fun explorerRows(state: WorkbenchState): List<ExplorerRow> {
    val out = mutableListOf<ExplorerRow>()
    val edit = state.explorerEdit
    fun walk(dir: String, depth: Int) {
        // New items get their name box at the top of their folder, like VS Code.
        if (edit != null && edit.kind != ExplorerEdit.Kind.Rename && edit.parent == dir) out += ExplorerRow.Edit(edit, depth)
        for (node in state.tree[dir].orEmpty()) {
            if (edit?.kind == ExplorerEdit.Kind.Rename && edit.target?.path == node.path) {
                out += ExplorerRow.Edit(edit, depth)
            } else {
                out += ExplorerRow.Node(node, depth)
            }
            if (node.isDir && node.path in state.expanded) walk(node.path, depth + 1)
        }
    }
    walk(state.backend.root, 0)
    return out
}

@Composable
private fun Explorer(state: WorkbenchState) {
    val c = LocalWorkbenchColors.current
    val headerCoords = remember { CoordsHolder() }
    // Section header with VS Code's title actions (always visible on touch).
    Row(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .onGloballyPositioned { headerCoords.value = it }
            .pointerInput(state.backend.root) {
                detectTapGestures(onLongPress = { at ->
                    val p = headerCoords.value?.localToRoot(at) ?: at
                    state.openExplorerMenu(FileNode(state.backend.name, state.backend.root, "", true, false), p.x, p.y)
                })
            }
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.KeyboardArrowDown, null, tint = c.sideBarForeground, modifier = Modifier.size(16.dp))
        Text(
            state.backend.name.uppercase(),
            color = c.sideBarForeground,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        ExplorerAction(Codicon.NEW_FILE, "New File…") { state.commands.execute(CommandIds.EXPLORER_NEW_FILE) }
        ExplorerAction(Codicon.NEW_FOLDER, "New Folder…") { state.commands.execute(CommandIds.EXPLORER_NEW_FOLDER) }
        ExplorerAction(Codicon.REFRESH, "Refresh Explorer") { state.commands.execute(CommandIds.REFRESH_EXPLORER) }
        ExplorerAction(Codicon.COLLAPSE_ALL, "Collapse Folders in Explorer") { state.commands.execute(CommandIds.COLLAPSE_EXPLORER) }
    }
    val activePath = state.activeTab?.path
    val rows = explorerRows(state)
    val listState = rememberLazyListState()
    val reveal = state.revealRequest
    LaunchedEffect(reveal, rows.size) {
        if (reveal != null) {
            val i = rows.indexOfFirst { it is ExplorerRow.Node && it.node.path == reveal }
            if (i >= 0) {
                listState.animateScrollToItem((i - 3).coerceAtLeast(0))
                state.revealRequest = null
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        items(rows, key = { it.key }) { row ->
            when (row) {
                is ExplorerRow.Node -> TreeRow(state, row.node, row.depth, row.node.path == activePath)
                is ExplorerRow.Edit -> NameBox(state, row.edit, row.depth)
            }
        }
    }
}

@Composable
private fun ExplorerAction(glyph: Char, description: String, onClick: () -> Unit) {
    val c = LocalWorkbenchColors.current
    Box(
        Modifier.size(32.dp, 28.dp).clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CodiconIcon(glyph, c.sideBarForeground, description = description)
    }
}

@Composable
private fun TreeRow(state: WorkbenchState, node: FileNode, depth: Int, active: Boolean) {
    val c = LocalWorkbenchColors.current
    val coords = remember { CoordsHolder() }
    Row(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(if (active) c.listActiveSelectionBackground else c.sideBarBackground)
            .onGloballyPositioned { coords.value = it }
            .pointerInput(node.path) {
                detectTapGestures(
                    onTap = {
                        if (node.isDir) {
                            state.toggleDir(node.path)
                        } else {
                            state.openFile(node.path, preview = true)
                            if (!state.sideBarDocked) state.sideBarVisible = false
                        }
                    },
                    onLongPress = { at ->
                        val p = coords.value?.localToRoot(at) ?: at
                        state.openExplorerMenu(node, p.x, p.y)
                    },
                )
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                // TalkBack's long-press action opens the same menu at the row.
                onLongClick(label = "Show actions") {
                    val box = coords.value ?: return@onLongClick false
                    val p = box.localToRoot(Offset(box.size.width / 2f, box.size.height / 2f))
                    state.openExplorerMenu(node, p.x, p.y)
                    true
                }
            }
            .padding(start = (8 + depth * 12).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(node.isDir, node.path in state.expanded, node.name)
        Text(
            node.name,
            color = if (node.isHidden) c.descriptionForeground else c.sideBarForeground,
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun RowIcon(isDir: Boolean, expanded: Boolean, name: String) {
    val c = LocalWorkbenchColors.current
    if (isDir) {
        Icon(
            if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            null,
            tint = c.sideBarForeground,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
    } else {
        Spacer(Modifier.width(4.dp))
        FileIcon(name)
        Spacer(Modifier.width(4.dp))
    }
}

/**
 * VS Code's inline name box in the tree: Enter applies, Back/Esc cancels;
 * leaving it applies a valid name and cancels otherwise. Validation messages
 * show under it in `inputValidation.*` colors.
 */
@Composable
private fun NameBox(state: WorkbenchState, edit: ExplorerEdit, depth: Int) {
    val c = LocalWorkbenchColors.current
    val focus = remember { FocusRequester() }
    var value by remember(edit) {
        // Renaming selects the name without its extension, like VS Code.
        val name = edit.initial
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        mutableStateOf(TextFieldValue(name, TextRange(0, dot)))
    }
    val error = if (value.text.isEmpty() && edit.kind != ExplorerEdit.Kind.Rename) null else state.validateName(edit, value.text)
    var hadFocus by remember(edit) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = (8 + depth * 12).dp, end = 8.dp, top = 2.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val isDir = edit.kind == ExplorerEdit.Kind.NewFolder || edit.target?.isDir == true
            RowIcon(isDir, false, value.text.ifEmpty { "file" })
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = TextStyle(color = c.inputForeground, fontSize = 13.sp),
                cursorBrush = SolidColor(c.editorCursor),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { state.commitExplorerEdit(value.text) }),
                modifier = Modifier
                    .weight(1f)
                    .height(26.dp)
                    .background(c.inputBackground)
                    .border(1.dp, if (error != null) c.inputValidationErrorBorder else c.focusBorder)
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .focusRequester(focus)
                    .onFocusChanged { f ->
                        if (f.isFocused) {
                            hadFocus = true
                        } else if (hadFocus && state.explorerEdit == edit) {
                            if (value.text.isNotBlank() && state.validateName(edit, value.text) == null) {
                                state.commitExplorerEdit(value.text)
                            } else {
                                state.cancelExplorerEdit()
                            }
                        }
                    }
                    .testTag("explorer-name-box"),
            )
        }
        if (error != null) {
            Text(
                markdownBold(error),
                color = c.inputForeground,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier
                    .padding(start = 24.dp)
                    .fillMaxWidth()
                    .background(c.inputValidationErrorBackground)
                    .border(1.dp, c.inputValidationErrorBorder)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
    LaunchedEffect(edit) { runCatching { focus.requestFocus() } }
    BackHandler { state.cancelExplorerEdit() }
}

/** `**bold**` spans, as VS Code's validation messages use them. */
private fun markdownBold(text: String): AnnotatedString = buildAnnotatedString {
    text.split("**").forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) } else append(part)
    }
}

// ----- Search ------------------------------------------------------------------

@Composable
private fun SearchView(state: WorkbenchState) {
    if (!state.backend.supportsTextSearch) {
        CopyIntoProjectsWelcome(state, "Text search")
        return
    }
    val c = LocalWorkbenchColors.current
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().height(32.dp)
                .background(c.inputBackground, RoundedCornerShape(3.dp))
                .border(1.dp, c.inputBorder, RoundedCornerShape(3.dp))
                .padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (state.searchQuery.isEmpty()) Text("Search", color = c.descriptionForeground, fontSize = 13.sp)
                BasicTextField(
                    value = state.searchQuery,
                    onValueChange = { state.searchQuery = it },
                    singleLine = true,
                    textStyle = TextStyle(color = c.inputForeground, fontSize = 13.sp),
                    cursorBrush = SolidColor(c.editorCursor),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { state.runSearch() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Toggle("Aa", "Match Case", state.searchCaseSensitive) { state.searchCaseSensitive = it; state.runSearch() }
            Toggle("ab", "Match Whole Word", state.searchWholeWord) { state.searchWholeWord = it; state.runSearch() }
            Toggle(".*", "Use Regular Expression", state.searchRegex) { state.searchRegex = it; state.runSearch() }
        }
        Spacer(Modifier.height(6.dp))
        state.searchError?.let { Text(it, color = c.errorForeground, fontSize = 12.sp) }
        val result = state.searchResult
        if (result != null) {
            val files = result.hits.groupBy { it.relPath }
            Text(
                "${result.hits.size} results in ${files.size} files" + if (result.truncated) " (truncated)" else "",
                color = c.descriptionForeground,
                fontSize = 12.sp,
            )
            LazyColumn(Modifier.fillMaxSize()) {
                files.forEach { (rel, hits) ->
                    item(key = "f:$rel") {
                        Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
                            FileIcon(rel.substringAfterLast('/'))
                            Spacer(Modifier.width(4.dp))
                            Text(rel.substringAfterLast('/'), color = c.sideBarForeground, fontSize = 13.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(rel.substringBeforeLast('/', ""), color = c.descriptionForeground, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
                            CountBadge(hits.size)
                        }
                    }
                    items(hits, key = { "h:${it.relPath}:${it.line}" }) { hit ->
                        val text = buildAnnotatedString {
                            val t = hit.lineText.trimStart()
                            val cut = hit.lineText.length - t.length
                            append(t)
                            hit.ranges.forEach { r ->
                                val a = (r.start - cut).coerceIn(0, t.length)
                                val b = (r.end - cut).coerceIn(a, t.length)
                                addStyle(SpanStyle(background = c.editorSelection, color = c.editorForeground), a, b)
                            }
                        }
                        Text(
                            text,
                            color = c.sideBarForeground,
                            fontSize = 12.sp,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { state.openFile(hit.path, preview = true, line = hit.line)
                                    if (!state.sideBarDocked) state.sideBarVisible = false }
                                .padding(start = 24.dp, top = 3.dp, bottom = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, description: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalWorkbenchColors.current
    Box(
        Modifier.size(28.dp).padding(2.dp)
            .background(if (on) c.listActiveSelectionBackground else c.inputBackground, RoundedCornerShape(3.dp))
            .border(1.dp, if (on) c.focusBorder else c.inputBackground, RoundedCornerShape(3.dp))
            .clickable(onClickLabel = description) { onChange(!on) },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = c.inputForeground, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

// ----- Source Control ------------------------------------------------------------

@Composable
private fun SourceControlView(state: WorkbenchState) {
    val c = LocalWorkbenchColors.current
    Column(Modifier.padding(16.dp)) {
        Text(
            if (state.branch != null) "Branch: ${state.branch}" else "The folder isn't a git repository.",
            color = c.sideBarForeground,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Git integration (status, diffs, staging, commits) is coming in milestone M5, powered by gitoxide.",
            color = c.descriptionForeground,
            fontSize = 12.sp,
        )
    }
}

// ----- Languages (the Extensions equivalent) ----------------------------------------

private data class LanguageInfo(val name: String, val server: String)

private val LANGUAGES = listOf(
    LanguageInfo("Rust", "rust-analyzer"),
    LanguageInfo("Python", "basedpyright / pyright"),
    LanguageInfo("JavaScript", "typescript-language-server"),
    LanguageInfo("TypeScript", "typescript-language-server"),
    LanguageInfo("JSON", "vscode-json-languageserver"),
    LanguageInfo("Markdown", "marksman"),
)

@Composable
private fun LanguagesView() {
    val c = LocalWorkbenchColors.current
    LazyColumn(Modifier.fillMaxSize()) {
        items(LANGUAGES) { lang ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).background(c.inputBackground, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    FileIcon("x." + when (lang.name) {
                        "Rust" -> "rs"; "Python" -> "py"; "JavaScript" -> "js"; "TypeScript" -> "ts"; "JSON" -> "json"; else -> "md"
                    })
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(lang.name, color = c.sideBarForeground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Syntax highlighting: built in (tree-sitter)", color = c.descriptionForeground, fontSize = 11.sp)
                    Text("Language server: ${lang.server} — requires Ferrux", color = c.descriptionForeground, fontSize = 11.sp)
                }
            }
        }
    }
}

/**
 * VS Code-style "view welcome" for folders opened with the system picker
 * (SAF): no real path, so ripgrep and Git can't run. Says so plainly and
 * offers the fix instead of pretending.
 */
@Composable
fun CopyIntoProjectsWelcome(state: WorkbenchState, feature: String) {
    val c = LocalWorkbenchColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            "$feature isn't available for folders opened with the system file picker: Android only lets " +
                "Ferride read them one file at a time.",
            color = c.sideBarForeground,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Copy this folder into Ferride projects to enable Git and fast search. The original stays where it is.",
            color = c.descriptionForeground,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(14.dp))
        val busy = state.copyProgress
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(c.buttonBackground.copy(alpha = if (busy == null) 1f else 0.6f), RoundedCornerShape(2.dp))
                .clickable(enabled = busy == null, role = androidx.compose.ui.semantics.Role.Button) {
                    state.commands.execute(dev.ferride.workbench.CommandIds.COPY_INTO_PROJECTS)
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(busy ?: "Copy into Ferride Projects", color = c.buttonForeground, fontSize = 13.sp, maxLines = 1)
        }
    }
}
