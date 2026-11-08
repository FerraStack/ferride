package dev.ferride.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ferride.core.DocumentSymbol
import dev.ferride.theme.LocalWorkbenchColors
import dev.ferride.workbench.WorkbenchState

/**
 * VS Code's OUTLINE view: a collapsible section at the bottom of the Explorer
 * with the active editor's symbols as a tree (tree-sitter, no language
 * server). The symbol at the caret is highlighted; tapping a row reveals it.
 */
@Composable
fun ColumnScope.OutlineSection(state: WorkbenchState) {
    val c = LocalWorkbenchColors.current
    val tab = state.activeTab
    tab?.revision // recompose on edits and caret moves
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.panelBorder))
    Row(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clickable(role = Role.Button, onClickLabel = if (state.outlineExpanded) "Collapse Outline" else "Expand Outline") {
                state.outlineExpanded = !state.outlineExpanded
            }
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CodiconIcon(if (state.outlineExpanded) CHEVRON_DOWN else CHEVRON_RIGHT, c.sideBarForeground)
        Text("OUTLINE", color = c.sideBarForeground, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
    if (!state.outlineExpanded) return
    val symbols = state.activeSymbols()
    if (symbols.isEmpty()) {
        Text(
            if (state.activeModel == null) {
                "There are no editors open that can provide outline information."
            } else {
                "The active editor cannot provide outline information."
            },
            color = c.descriptionForeground,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 6.dp),
        )
        return
    }
    val caret = state.activeModel?.takeIf { !it.isClosed }?.selection?.head ?: -1
    val active = state.symbolChainAt(symbols, caret).lastOrNull() ?: -1
    val rows = visibleRows(state, symbols)
    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
        items(rows, key = { it }) { i ->
            OutlineRow(state, symbols, i, selected = i == active)
        }
    }
}

/** Indices of the rows to show: children of collapsed symbols are skipped. */
private fun visibleRows(state: WorkbenchState, symbols: List<DocumentSymbol>): List<Int> {
    val out = mutableListOf<Int>()
    var hiddenBelowDepth = Int.MAX_VALUE
    symbols.forEachIndexed { i, s ->
        if (s.depth > hiddenBelowDepth) return@forEachIndexed
        hiddenBelowDepth = Int.MAX_VALUE
        out += i
        if (state.symbolPath(symbols, i) in state.outlineCollapsed) hiddenBelowDepth = s.depth
    }
    return out
}

@Composable
private fun OutlineRow(state: WorkbenchState, symbols: List<DocumentSymbol>, index: Int, selected: Boolean) {
    val c = LocalWorkbenchColors.current
    val s = symbols[index]
    val hasChildren = symbols.getOrNull(index + 1)?.parent == index
    val path = state.symbolPath(symbols, index)
    val collapsed = path in state.outlineCollapsed
    Row(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(if (selected) c.listHoverBackground else c.sideBarBackground)
            .clickable(onClickLabel = "Go to ${s.name}") { state.revealSymbol(s) }
            .padding(start = (8 + s.depth * 12).dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).clickable(enabled = hasChildren, role = Role.Button, onClickLabel = if (collapsed) "Expand" else "Collapse") {
                if (collapsed) state.outlineCollapsed.remove(path) else state.outlineCollapsed.add(path)
            },
            contentAlignment = Alignment.Center,
        ) {
            if (hasChildren) CodiconIcon(if (collapsed) CHEVRON_RIGHT else CHEVRON_DOWN, c.sideBarForeground)
        }
        CodiconIcon(SymbolIcons.glyph(s.kind), SymbolIcons.color(s.kind, c.isDark, c.sideBarForeground))
        Spacer(Modifier.width(6.dp))
        Text(s.name, color = c.sideBarForeground, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal const val CHEVRON_RIGHT = ''
internal const val CHEVRON_DOWN = ''
