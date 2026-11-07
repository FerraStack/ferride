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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import dev.ferride.theme.BuiltInThemes
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
            SideView.Settings -> SettingsView(state)
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

// ----- Settings --------------------------------------------------------------------

@Composable
private fun SettingsView(state: WorkbenchState) {
    val c = LocalWorkbenchColors.current
    val s = state.settings
    var filter by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Box(
            Modifier.fillMaxWidth().height(32.dp)
                .background(c.inputBackground, RoundedCornerShape(3.dp))
                .border(1.dp, c.inputBorder, RoundedCornerShape(3.dp))
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (filter.isEmpty()) Text("Search settings", color = c.descriptionForeground, fontSize = 13.sp)
            BasicTextField(
                value = filter,
                onValueChange = { filter = it },
                singleLine = true,
                textStyle = TextStyle(color = c.inputForeground, fontSize = 13.sp),
                cursorBrush = SolidColor(c.editorCursor),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            fun visible(vararg words: String) = filter.isBlank() || words.any { it.contains(filter, ignoreCase = true) }
            if (visible("theme", "color", "workbench")) item {
                SettingRow("Workbench › Color Theme", "workbench.colorTheme") {
                    Column {
                        ThemeChoice("Follow system (light/dark)", s.autoDetectColorScheme) {
                            state.updateSettings { it.copy(autoDetectColorScheme = true, colorTheme = "") }
                        }
                        (BuiltInThemes + state.extraThemes).forEach { t ->
                            ThemeChoice(t.name, !s.autoDetectColorScheme && s.colorTheme == t.name) {
                                state.updateSettings { it.copy(autoDetectColorScheme = false, colorTheme = t.name) }
                            }
                        }
                    }
                }
            }
            if (visible("font", "size", "editor")) item {
                SettingRow("Editor › Font Size", "editor.fontSize") {
                    Stepper(s.fontSize) { v -> state.updateSettings { it.copy(fontSize = v.coerceIn(6, 48)) } }
                }
            }
            if (visible("tab", "size", "editor")) item {
                SettingRow("Editor › Tab Size", "editor.tabSize") {
                    Stepper(s.tabSize) { v -> state.updateSettings { it.copy(tabSize = v.coerceIn(1, 16)) } }
                }
            }
            val switches = listOf(
                Triple("Editor › Insert Spaces", "editor.insertSpaces", s.insertSpaces) to { v: Boolean -> state.updateSettings { it.copy(insertSpaces = v) } },
                Triple("Editor › Line Numbers", "editor.lineNumbers", s.lineNumbers) to { v: Boolean -> state.updateSettings { it.copy(lineNumbers = v) } },
                Triple("Editor › Guides: Indentation", "editor.guides.indentation", s.renderIndentGuides) to { v: Boolean -> state.updateSettings { it.copy(renderIndentGuides = v) } },
                Triple("Editor › Bracket Pair Colorization", "editor.bracketPairColorization.enabled", s.bracketPairColorization) to { v: Boolean -> state.updateSettings { it.copy(bracketPairColorization = v) } },
                Triple("Editor › Render Line Highlight", "editor.renderLineHighlight", s.highlightActiveLine) to { v: Boolean -> state.updateSettings { it.copy(highlightActiveLine = v) } },
                Triple("Editor › Word Wrap (coming in M1)", "editor.wordWrap", s.wordWrap) to { v: Boolean -> state.updateSettings { it.copy(wordWrap = v) } },
                Triple("Editor › Minimap (coming later)", "editor.minimap.enabled", s.minimap) to { v: Boolean -> state.updateSettings { it.copy(minimap = v) } },
                Triple("Ferride › Extra Keys Row", "ferride.extraKeys.enabled", s.extraKeys) to { v: Boolean -> state.updateSettings { it.copy(extraKeys = v) } },
            )
            switches.forEach { (meta, set) ->
                val (title, key, value) = meta
                if (visible(title, key)) item(key = key) {
                    SettingRow(title, key) {
                        Switch(
                            checked = value,
                            onCheckedChange = set,
                            colors = SwitchDefaults.colors(checkedTrackColor = c.buttonBackground),
                        )
                    }
                }
            }
            item {
                Text(
                    "Stored as settings.json with VS Code key names.",
                    color = c.descriptionForeground,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, key: String, control: @Composable () -> Unit) {
    val c = LocalWorkbenchColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(
            buildAnnotatedString {
                val parts = title.split(" › ")
                parts.dropLast(1).forEach { append("$it: ") }
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(parts.last()) }
            },
            color = c.sideBarForeground,
            fontSize = 13.sp,
        )
        Text(key, color = c.descriptionForeground, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(4.dp))
        control()
    }
}

@Composable
private fun Stepper(value: Int, onChange: (Int) -> Unit) {
    val c = LocalWorkbenchColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        listOf("−" to -1, null, "+" to 1).forEach { entry ->
            if (entry == null) {
                Text(value.toString(), color = c.inputForeground, fontSize = 14.sp, modifier = Modifier.width(44.dp).padding(horizontal = 12.dp))
            } else {
                Box(
                    Modifier.size(32.dp).background(c.inputBackground, RoundedCornerShape(3.dp))
                        .border(1.dp, c.inputBorder, RoundedCornerShape(3.dp))
                        .clickable { onChange(value + entry.second) },
                    contentAlignment = Alignment.Center,
                ) { Text(entry.first, color = c.inputForeground, fontSize = 16.sp) }
            }
        }
    }
}

@Composable
private fun ThemeChoice(name: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalWorkbenchColors.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(14.dp).border(1.dp, c.inputBorder, RoundedCornerShape(50))
                .padding(3.dp)
                .background(if (selected) c.focusBorder else c.sideBarBackground, RoundedCornerShape(50)),
        )
        Spacer(Modifier.width(8.dp))
        Text(name, color = c.sideBarForeground, fontSize = 13.sp)
    }
}
