package dev.ferride.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.ferride.editor.CodeEditorView
import dev.ferride.editor.EditorModel
import dev.ferride.editor.SelectionUi
import dev.ferride.editor.Squiggle
import dev.ferride.theme.LocalWorkbenchColors
import dev.ferride.scm.GitCommandIds
import dev.ferride.workbench.CommandIds
import dev.ferride.workbench.EditorTab
import dev.ferride.workbench.TabKind
import dev.ferride.workbench.WorkbenchState

/** Tabs, breadcrumbs and the editor (or Settings / the welcome page). */
@Composable
fun EditorArea(state: WorkbenchState, modifier: Modifier = Modifier) {
    val c = LocalWorkbenchColors.current
    Column(modifier.fillMaxWidth().background(c.editorBackground)) {
        val tab = state.activeTab
        if (state.tabs.isNotEmpty()) TabStrip(state)
        val model = tab?.model?.takeIf { !it.isClosed }
        when {
            tab == null -> {
                Welcome(state, Modifier.weight(1f))
                SideEffect { state.disposeRetired(null) }
            }
            tab.kind == TabKind.Settings -> {
                SettingsEditor(state, Modifier.weight(1f))
                SideEffect { state.disposeRetired(null) }
            }
            tab.kind == TabKind.Welcome -> {
                WelcomePage(state, Modifier.weight(1f))
                SideEffect { state.disposeRetired(null) }
            }
            model != null -> {
                Breadcrumbs(state, tab)
                CodeEditor(state, tab, model, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CodeEditor(state: WorkbenchState, tab: EditorTab, model: EditorModel, modifier: Modifier) {
    val c = LocalWorkbenchColors.current
    tab.revision // re-read decorations after edits
    val matches = state.findMatchesInActive()
    val squiggles = state.diagnostics[tab.path].orEmpty().map { d ->
        Squiggle(model.offsetAt(d.line, d.startColumn), model.offsetAt(d.endLine, d.endColumn), d.severity)
    }
    val style = editorStyle(c, state.settings, state.wordWrap, state.minimapEnabled)
    // Sticky scroll follows the outline: every symbol spanning several lines.
    val stickyScopes = if (state.settings.stickyScroll) {
        state.activeSymbols().filter { it.endLine > it.startLine }.map { it.startLine..it.endLine }
    } else {
        emptyList()
    }
    var selectionUi by remember { mutableStateOf<SelectionUi?>(null) }
    val viewRef = remember { arrayOfNulls<CodeEditorView>(1) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        AndroidView(
            factory = { ctx -> CodeEditorView(ctx).also { viewRef[0] = it } },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                view.style = style
                // Switch to the new model first, then free closed ones (never the reverse).
                view.setModel(model)
                state.disposeRetired(model)
                view.onChange = { changed -> state.onEditorChanged(tab, changed) }
                view.onSyntaxUpdated = { tab.revision++ }
                view.onFocus = { focused -> state.editorFocused = focused }
                view.findMatches = matches
                view.currentMatch = state.find.current
                view.squiggles = squiggles
                view.stickyScrollEnabled = state.settings.stickyScroll
                view.stickyScopes = stickyScopes
                view.onSelectionUi = { selectionUi = it }
                state.editor = view
            },
            onRelease = { view ->
                view.onSyntaxUpdated = null
                view.onSelectionUi = null
                selectionUi = null
                if (viewRef[0] === view) viewRef[0] = null
                if (state.editor === view) state.editor = null
                view.setModel(null)
                state.disposeRetired(null)
                state.editorFocused = false
            },
        )
        if (state.find.visible) {
            FindWidget(state, matches.size, maxWidth, Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 14.dp))
        }
        selectionUi?.let { ui ->
            SelectionToolbar(ui, onAction = { viewRef[0]?.onSelectionToolbarAction(it) })
            BackHandler { viewRef[0]?.hideSelectionControls() }
        }
    }
}

@Composable
private fun TabStrip(state: WorkbenchState) {
    val c = LocalWorkbenchColors.current
    val listState = rememberLazyListState()
    LaunchedEffect(state.activeIndex) {
        if (state.activeIndex >= 0) listState.animateScrollToItem(state.activeIndex)
    }
    val tab = state.activeTab
    Row(Modifier.fillMaxWidth().height(38.dp).background(c.editorGroupHeaderBackground)) {
        LazyRow(state = listState, modifier = Modifier.weight(1f).fillMaxHeight()) {
            itemsIndexed(state.tabs, key = { _, t -> t.path }) { index, t ->
                EditorTabItem(t, index == state.activeIndex, onSelect = { state.activeIndex = index }) {
                    state.requestCloseTab(index)
                }
            }
        }
        // Editor title actions (VS Code shows these at the right of the tab bar).
        if (tab?.kind == TabKind.Settings) {
            BarIcon(Icons.Outlined.DataObject, "Open Settings (JSON)", c.tabActiveForeground, size = 38.dp, iconSize = 18.dp) {
                state.commands.execute(CommandIds.OPEN_SETTINGS_JSON)
            }
        }
    }
}

@Composable
private fun EditorTabItem(tab: EditorTab, active: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val c = LocalWorkbenchColors.current
    val fg = if (active) c.tabActiveForeground else c.tabInactiveForeground
    Box(
        Modifier
            .fillMaxHeight()
            .background(if (active) c.tabActiveBackground else c.tabInactiveBackground)
            .clickable(onClick = {
                if (active) tab.preview = false
                onSelect()
            }),
    ) {
        Row(Modifier.fillMaxHeight().padding(start = 10.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tab.kind == TabKind.Settings) {
                Icon(Icons.Outlined.Settings, null, tint = fg, modifier = Modifier.size(16.dp))
            } else if (tab.kind == TabKind.Welcome) {
                CodiconIcon(Codicon.ROCKET, fg)
            } else {
                FileIcon(tab.name)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                tab.name,
                color = fg,
                fontSize = 13.sp,
                fontStyle = if (tab.preview) FontStyle.Italic else FontStyle.Normal,
                maxLines = 1,
            )
            Box(
                Modifier.size(32.dp).clickable(onClickLabel = "Close ${tab.name}", onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                if (tab.dirty) {
                    Box(Modifier.size(9.dp).background(fg, CircleShape))
                } else {
                    Icon(Icons.Default.Close, "Close", tint = fg, modifier = Modifier.size(15.dp))
                }
            }
        }
        if (active) Box(Modifier.fillMaxWidth().height(1.5.dp).background(c.tabActiveBorderTop).align(Alignment.TopStart))
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.tabBorder).align(Alignment.CenterEnd))
    }
}

@Composable
private fun Breadcrumbs(state: WorkbenchState, tab: EditorTab) {
    val c = LocalWorkbenchColors.current
    tab.revision // follow the caret
    val parts = when (tab.kind) {
        TabKind.SettingsJson -> listOf("User", "settings.json")
        else -> listOf(state.backend.name) + tab.path.removePrefix(state.backend.root).trimStart('/').split('/')
    }
    // VS Code's symbol segments: the symbols enclosing the caret, outermost first.
    val symbols = if (tab.kind == TabKind.File) state.activeSymbols() else emptyList()
    val caret = tab.model?.takeIf { !it.isClosed }?.selection?.head ?: -1
    val chain = state.symbolChainAt(symbols, caret)
    val scroll = rememberScrollState()
    LaunchedEffect(chain, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(
        Modifier.fillMaxWidth().height(24.dp).horizontalScroll(scroll).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEachIndexed { i, p ->
            if (i == parts.lastIndex) FileIcon(p)
            Text(p, color = c.breadcrumbForeground, fontSize = 12.sp, maxLines = 1, softWrap = false)
            if (i < parts.lastIndex || chain.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.breadcrumbForeground, modifier = Modifier.size(14.dp))
            }
        }
        chain.forEachIndexed { k, i ->
            val s = symbols[i]
            Row(
                Modifier.clickable(onClickLabel = "Go to ${s.name}") { state.revealSymbol(s) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CodiconIcon(SymbolIcons.glyph(s.kind), SymbolIcons.color(s.kind, c.isDark, c.breadcrumbForeground), size = 14.dp)
                Spacer(Modifier.width(3.dp))
                Text(s.name, color = c.breadcrumbForeground, fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
            if (k < chain.lastIndex) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.breadcrumbForeground, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun Welcome(state: WorkbenchState, modifier: Modifier) {
    val c = LocalWorkbenchColors.current
    Column(modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("Ferride", color = c.editorForeground, fontSize = 34.sp, fontWeight = FontWeight.Light)
        Text("A code editor for your phone", color = c.descriptionForeground, fontSize = 16.sp)
        Spacer(Modifier.height(28.dp))
        Text("Start", color = c.editorForeground, fontSize = 17.sp)
        Spacer(Modifier.height(8.dp))
        listOf(
            "Go to File…" to CommandIds.QUICK_OPEN,
            "Show All Commands" to CommandIds.SHOW_COMMANDS,
            "Open Explorer" to CommandIds.VIEW_EXPLORER,
            "Clone Git Repository…" to GitCommandIds.CLONE,
            "Open Settings" to CommandIds.OPEN_SETTINGS,
        ).forEach { (label, id) ->
            val key = state.commands.get(id)?.keybinding
            Row(
                Modifier.fillMaxWidth().clickable { state.commands.execute(id) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, color = c.focusBorder, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (key != null) KeybindingLabel(key)
            }
        }
    }
}
