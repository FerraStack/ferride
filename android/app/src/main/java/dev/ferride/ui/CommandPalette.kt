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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ferride.core.FileHit
import dev.ferride.core.SymbolHit
import dev.ferride.theme.LocalWorkbenchColors
import dev.ferride.theme.WorkbenchColors
import dev.ferride.workbench.PaletteItem
import dev.ferride.workbench.PaletteMode
import dev.ferride.workbench.WorkbenchState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Quick Open / Command Palette, VS Code's quick input: a top-anchored sheet
 * over a dimmed workbench. Prefixes: none = files, `>` = commands,
 * `:` = go to line, `@` = symbols; or a command's own quick pick. Matched characters are bold, command
 * categories dimmed, keybindings right-aligned.
 */
@Composable
fun CommandPalette(state: WorkbenchState, maxWidth: Dp, autoFocus: Boolean = true) {
    val c = LocalWorkbenchColors.current
    val mode = state.paletteMode()
    val symbolHits by produceState(emptyList<SymbolHit>(), state.paletteQuery, mode) {
        value = if (mode == PaletteMode.WorkspaceSymbols) {
            withContext(Dispatchers.Default) {
                val q = state.paletteQuery.removePrefix(mode.prefix).trim()
                runCatching { state.backend.workspaceSymbols(q, 50) }.getOrDefault(emptyList())
            }
        } else {
            emptyList()
        }
    }
    val fileHits by produceState(emptyList<FileHit>(), state.paletteQuery, mode) {
        value = if (mode == PaletteMode.Files) {
            withContext(Dispatchers.Default) {
                runCatching { state.backend.findFiles(state.paletteQuery.trim(), 30) }.getOrDefault(emptyList())
            }
        } else {
            emptyList()
        }
    }
    val items = state.paletteItems(fileHits, symbolHits)
    val focus = remember { FocusRequester() }

    // Scrim: dims the workbench and closes on tap.
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = SCRIM_ALPHA)).clickable(
            interactionSource = MutableInteractionSource(),
            indication = null,
            onClickLabel = "Close",
        ) { state.paletteOpen = false },
    )
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .width(minOf(600.dp, maxWidth - 16.dp))
                .shadow(16.dp, RoundedCornerShape(8.dp))
                .background(c.quickInputBackground, RoundedCornerShape(8.dp))
                .border(1.dp, c.panelBorder, RoundedCornerShape(8.dp))
                .padding(6.dp),
        ) {
            Box(
                Modifier.fillMaxWidth()
                    .background(c.inputBackground, RoundedCornerShape(4.dp))
                    .border(1.dp, c.focusBorder, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 7.dp),
            ) {
                val typed = state.paletteQuery.removePrefix(mode.prefix)
                if (typed.isEmpty()) {
                    // Placeholder after the prefix (the prefix itself is invisible here).
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = Color.Transparent)) { append(mode.prefix) }
                            append(state.palettePlaceholder())
                        },
                        color = c.descriptionForeground,
                        fontSize = 14.sp,
                        maxLines = 1,
                    )
                }
                // A TextFieldValue so a query set from outside (">" from Show All Commands)
                // gets the caret at its end, like VS Code; typing then goes after the prefix.
                var field by remember { mutableStateOf(TextFieldValue(state.paletteQuery, TextRange(state.paletteQuery.length))) }
                if (field.text != state.paletteQuery) {
                    field = TextFieldValue(state.paletteQuery, TextRange(state.paletteQuery.length))
                }
                BasicTextField(
                    value = field,
                    onValueChange = {
                        field = it
                        state.paletteQuery = it.text
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = c.inputForeground, fontSize = 14.sp),
                    cursorBrush = SolidColor(c.editorCursor),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { items.firstOrNull()?.run?.invoke() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            LazyColumn(Modifier.heightIn(max = 440.dp).padding(top = 4.dp)) {
                itemsIndexed(items) { i, item -> PaletteRow(item, selected = i == 0, files = mode == PaletteMode.Files, c = c) }
                if (items.isEmpty()) {
                    item {
                        Text(
                            when (mode) {
                                PaletteMode.Files -> "No matching files"
                                PaletteMode.QuickPick -> "No matching results"
                                PaletteMode.Symbols, PaletteMode.WorkspaceSymbols -> "No matching symbols"
                                else -> "No matching commands"
                            },
                            color = c.descriptionForeground,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        }
    }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun PaletteRow(item: PaletteItem, selected: Boolean, files: Boolean, c: WorkbenchColors) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) c.listActiveSelectionBackground else c.quickInputBackground, RoundedCornerShape(4.dp))
            .clickable { item.run() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item.symbolKind?.let { kind ->
            CodiconIcon(SymbolIcons.glyph(kind), SymbolIcons.color(kind, c.isDark, c.inputForeground))
            Spacer(Modifier.width(6.dp))
        }
        if (files) {
            FileIcon(item.label)
            Spacer(Modifier.width(6.dp))
        }
        // The label takes the remaining width (one weighted child, so it isn't
        // squeezed to half the row by a weighted spacer).
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                highlighted(item, c),
                color = c.inputForeground,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (!item.description.isNullOrEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(item.description, color = c.descriptionForeground, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (files && !item.detail.isNullOrEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(item.detail, color = c.descriptionForeground, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        item.section?.let {
            Text(it, color = c.descriptionForeground, fontSize = 11.sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 6.dp))
        }
        item.keybinding?.let { KeybindingLabel(it, Modifier.padding(start = 6.dp)) }
    }
}

/** `Category: ` dimmed, then the label with fuzzy-matched characters in bold. */
private fun highlighted(item: PaletteItem, c: WorkbenchColors): AnnotatedString = buildAnnotatedString {
    item.category?.let { cat ->
        withStyle(SpanStyle(color = c.descriptionForeground)) { append("$cat: ") }
    }
    val bold = item.matches.toSet()
    item.label.forEachIndexed { i, ch ->
        if (i in bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = c.focusBorder)) { append(ch) } else append(ch)
    }
}
