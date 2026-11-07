package dev.ferride.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.ferride.editor.CodeEditorView
import dev.ferride.theme.LocalWorkbenchColors
import dev.ferride.workbench.CommandIds
import dev.ferride.workbench.EditorTab
import dev.ferride.workbench.WorkbenchState

/** Tabs, breadcrumbs and the editor (or the welcome page when nothing is open). */
@Composable
fun EditorArea(state: WorkbenchState, modifier: Modifier = Modifier) {
    val c = LocalWorkbenchColors.current
    Column(modifier.fillMaxWidth().background(c.editorBackground)) {
        val tab = state.activeTab
        if (state.tabs.isNotEmpty()) TabStrip(state)
        if (tab == null) {
            Welcome(state, Modifier.weight(1f))
        } else {
            Breadcrumbs(state, tab)
            AndroidView(
                factory = { ctx -> CodeEditorView(ctx) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                update = { view ->
                    view.style = editorStyle(c, state.settings)
                    view.setModel(tab.model)
                    view.onChange = { changed -> state.onEditorChanged(tab, changed) }
                    state.editor = view
                },
                onRelease = { if (state.editor === it) state.editor = null },
            )
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
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().height(38.dp).background(c.editorGroupHeaderBackground),
    ) {
        itemsIndexed(state.tabs, key = { _, t -> t.path }) { index, tab ->
            EditorTabItem(state, tab, index == state.activeIndex, onSelect = { state.activeIndex = index }) {
                state.closeTab(index)
            }
        }
    }
}

@Composable
private fun EditorTabItem(state: WorkbenchState, tab: EditorTab, active: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val c = LocalWorkbenchColors.current
    Box(
        Modifier
            .fillMaxHeight()
            .background(if (active) c.tabActiveBackground else c.tabInactiveBackground)
            .clickable(onClick = {
                if (active) tab.preview = false
                onSelect()
            }),
    ) {
        Row(
            Modifier.fillMaxHeight().padding(start = 10.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileIcon(tab.name)
            Spacer(Modifier.width(4.dp))
            Text(
                tab.name,
                color = if (active) c.tabActiveForeground else c.tabInactiveForeground,
                fontSize = 13.sp,
                fontStyle = if (tab.preview) FontStyle.Italic else FontStyle.Normal,
                maxLines = 1,
            )
            Box(Modifier.size(32.dp).clickable(onClickLabel = "Close ${tab.name}", onClick = onClose), contentAlignment = Alignment.Center) {
                if (tab.dirty) {
                    Box(Modifier.size(9.dp).background(if (active) c.tabActiveForeground else c.tabInactiveForeground, CircleShape))
                } else {
                    androidx.compose.material3.Icon(
                        Icons.Default.Close,
                        "Close",
                        tint = if (active) c.tabActiveForeground else c.tabInactiveForeground,
                        modifier = Modifier.size(15.dp),
                    )
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
    val rel = tab.path.removePrefix(state.backend.root).trimStart('/')
    val parts = listOf(state.backend.name) + rel.split('/')
    Row(
        Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEachIndexed { i, p ->
            if (i == parts.lastIndex) {
                FileIcon(p)
            }
            Text(p, color = c.breadcrumbForeground, fontSize = 12.sp, maxLines = 1)
            if (i < parts.lastIndex) {
                androidx.compose.material3.Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    null,
                    tint = c.breadcrumbForeground,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun Welcome(state: WorkbenchState, modifier: Modifier) {
    val c = LocalWorkbenchColors.current
    Column(
        modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Ferride", color = c.editorForeground, fontSize = 34.sp, fontWeight = FontWeight.Light)
        Text("A code editor for your phone", color = c.descriptionForeground, fontSize = 16.sp)
        Spacer(Modifier.height(28.dp))
        Text("Start", color = c.editorForeground, fontSize = 17.sp)
        Spacer(Modifier.height(8.dp))
        listOf(
            "Go to File…" to CommandIds.QUICK_OPEN,
            "Show All Commands" to CommandIds.SHOW_COMMANDS,
            "Open Explorer" to CommandIds.VIEW_EXPLORER,
            "Open Settings" to CommandIds.OPEN_SETTINGS,
        ).forEach { (label, id) ->
            val key = state.commands.get(id)?.keybinding?.label
            Row(
                Modifier.fillMaxWidth().clickable { state.commands.execute(id) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, color = c.focusBorder, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (key != null) Text(key, color = c.descriptionForeground, fontSize = 12.sp)
            }
        }
    }
}
