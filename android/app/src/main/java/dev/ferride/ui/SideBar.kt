package dev.ferride.ui

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
import androidx.compose.foundation.lazy.LazyListScope
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
import dev.ferride.workbench.Languages
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

@Composable
private fun Explorer(state: WorkbenchState) {
    val c = LocalWorkbenchColors.current
    Row(Modifier.fillMaxWidth().height(24.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.KeyboardArrowDown, null, tint = c.sideBarForeground, modifier = Modifier.size(16.dp))
        Text(state.backend.name.uppercase(), color = c.sideBarForeground, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
    val activePath = state.activeTab?.path
    LazyColumn(Modifier.fillMaxSize()) {
        treeItems(state, state.backend.root, 0, activePath)
    }
}

private fun LazyListScope.treeItems(state: WorkbenchState, dir: String, depth: Int, activePath: String?) {
    val children = state.tree[dir] ?: return
    for (node in children) {
        item(key = node.path) { TreeRow(state, node, depth, node.path == activePath) }
        if (node.isDir && node.path in state.expanded) treeItems(state, node.path, depth + 1, activePath)
    }
}

@Composable
private fun TreeRow(state: WorkbenchState, node: FileNode, depth: Int, active: Boolean) {
    val c = LocalWorkbenchColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(if (active) c.listActiveSelectionBackground else c.sideBarBackground)
            .clickable {
                if (node.isDir) {
                    state.toggleDir(node.path)
                } else {
                    state.openFile(node.path, preview = true)
                    if (!state.sideBarDocked) state.sideBarVisible = false
                }
            }
            .padding(start = (8 + depth * 12).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.isDir) {
            Icon(
                if (node.path in state.expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                tint = c.sideBarForeground,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
        } else {
            Spacer(Modifier.width(4.dp))
            FileIcon(node.name)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            node.name,
            color = if (node.isHidden) c.descriptionForeground else c.sideBarForeground,
            fontSize = 13.sp,
            maxLines = 1,
        )
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

@Composable
private fun LanguagesView() {
    val c = LocalWorkbenchColors.current
    LazyColumn(Modifier.fillMaxSize()) {
        items(Languages.all) { lang ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).background(c.inputBackground, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    FileIcon(lang.iconFile)
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
