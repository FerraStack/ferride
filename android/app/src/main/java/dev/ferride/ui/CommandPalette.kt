package dev.ferride.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ferride.core.FileHit
import dev.ferride.theme.LocalWorkbenchColors
import dev.ferride.workbench.PaletteMode
import dev.ferride.workbench.WorkbenchState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Quick Open / Command Palette. Same prefixes as VS Code: none = files,
 * `>` = commands, `:` = go to line, `@` = symbols.
 */
@Composable
fun CommandPalette(state: WorkbenchState, maxWidth: Dp, autoFocus: Boolean = true) {
    val c = LocalWorkbenchColors.current
    val mode = state.paletteMode()
    val fileHits by produceState(emptyList<FileHit>(), state.paletteQuery, mode) {
        value = if (mode == PaletteMode.Files) {
            withContext(Dispatchers.Default) {
                runCatching { state.backend.findFiles(state.paletteQuery.trim(), 30) }.getOrDefault(emptyList())
            }
        } else {
            emptyList()
        }
    }
    val items = state.paletteItems(fileHits)
    val focus = remember { FocusRequester() }

    Box(
        Modifier.fillMaxSize().clickable(interactionSource = MutableInteractionSource(), indication = null) {
            state.paletteOpen = false
        },
    )
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .width(minOf(600.dp, maxWidth - 16.dp))
                .shadow(12.dp, RoundedCornerShape(6.dp))
                .background(c.quickInputBackground, RoundedCornerShape(6.dp))
                .border(1.dp, c.panelBorder, RoundedCornerShape(6.dp))
                .padding(6.dp),
        ) {
            Box(
                Modifier.fillMaxWidth()
                    .background(c.inputBackground, RoundedCornerShape(3.dp))
                    .border(1.dp, c.focusBorder, RoundedCornerShape(3.dp))
                    .padding(horizontal = 8.dp, vertical = 7.dp),
            ) {
                if (state.paletteQuery.isEmpty()) Text(mode.placeholder, color = c.descriptionForeground, fontSize = 13.sp, maxLines = 1)
                BasicTextField(
                    value = state.paletteQuery,
                    onValueChange = { state.paletteQuery = it },
                    singleLine = true,
                    textStyle = TextStyle(color = c.inputForeground, fontSize = 14.sp),
                    cursorBrush = SolidColor(c.editorCursor),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { items.firstOrNull()?.run?.invoke() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 4.dp)) {
                itemsIndexed(items) { i, item ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (i == 0) c.listActiveSelectionBackground else c.quickInputBackground, RoundedCornerShape(3.dp))
                            .clickable { item.run() }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (mode == PaletteMode.Files) {
                            FileIcon(item.label)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(item.label, color = c.inputForeground, fontSize = 13.sp, maxLines = 1)
                        if (mode == PaletteMode.Files && !item.detail.isNullOrEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text(item.detail, color = c.descriptionForeground, fontSize = 12.sp, maxLines = 1)
                        }
                        Spacer(Modifier.weight(1f))
                        item.keybinding?.let {
                            Text(
                                it,
                                color = c.descriptionForeground,
                                fontSize = 11.sp,
                                modifier = Modifier.background(c.inputBackground, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
