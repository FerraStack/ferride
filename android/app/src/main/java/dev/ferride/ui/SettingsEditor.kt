package dev.ferride.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ferride.theme.BuiltInThemes
import dev.ferride.theme.LocalWorkbenchColors
import dev.ferride.workbench.Settings
import dev.ferride.workbench.WorkbenchState
import kotlinx.coroutines.launch

/** Table-of-contents groups, as in VS Code's settings editor. */
enum class SettingGroup(val title: String) { TextEditor("Text Editor"), Workbench("Workbench"), Files("Files"), Ferride("Ferride") }

sealed interface SettingControl {
    data class Bool(val get: (Settings) -> Boolean, val set: (Settings, Boolean) -> Settings) : SettingControl
    data class Number(val get: (Settings) -> Int, val set: (Settings, Int) -> Settings, val range: IntRange) : SettingControl
    data class Choice(val options: List<String>, val get: (Settings) -> String, val set: (Settings, String) -> Settings) : SettingControl
}

/** One row of the settings editor. [category] is the "Editor:" prefix, [title] the bold part. */
data class SettingDef(
    val key: String,
    val group: SettingGroup,
    val category: String,
    val title: String,
    val description: String,
    val control: SettingControl,
    val commonlyUsed: Boolean = false,
)

fun settingDefs(themeNames: List<String>): List<SettingDef> = listOf(
    SettingDef(
        Settings.EDITOR_FONT_SIZE, SettingGroup.TextEditor, "Editor", "Font Size",
        "Controls the font size in pixels.",
        SettingControl.Number({ it.fontSize }, { s, v -> s.copy(fontSize = v) }, 6..48), commonlyUsed = true,
    ),
    SettingDef(
        Settings.EDITOR_TAB_SIZE, SettingGroup.TextEditor, "Editor", "Tab Size",
        "The number of spaces a tab is equal to.",
        SettingControl.Number({ it.tabSize }, { s, v -> s.copy(tabSize = v) }, 1..16), commonlyUsed = true,
    ),
    SettingDef(
        Settings.EDITOR_INSERT_SPACES, SettingGroup.TextEditor, "Editor", "Insert Spaces",
        "Insert spaces when pressing Tab.",
        SettingControl.Bool({ it.insertSpaces }, { s, v -> s.copy(insertSpaces = v) }), commonlyUsed = true,
    ),
    SettingDef(
        Settings.EDITOR_WORD_WRAP, SettingGroup.TextEditor, "Editor", "Word Wrap",
        "Controls how lines should wrap. \"default\" wraps on phones and doesn't on wider screens.",
        SettingControl.Choice(
            listOf("default", "on", "off"),
            { when (it.wordWrap) { null -> "default"; true -> "on"; false -> "off" } },
            { s, v -> s.copy(wordWrap = when (v) { "on" -> true; "off" -> false; else -> null }) },
        ),
        commonlyUsed = true,
    ),
    SettingDef(
        Settings.EDITOR_LINE_NUMBERS, SettingGroup.TextEditor, "Editor", "Line Numbers",
        "Controls the display of line numbers.",
        SettingControl.Choice(listOf("on", "off"), { if (it.lineNumbers) "on" else "off" }, { s, v -> s.copy(lineNumbers = v == "on") }),
    ),
    SettingDef(
        Settings.EDITOR_LINE_HIGHLIGHT, SettingGroup.TextEditor, "Editor", "Render Line Highlight",
        "Controls how the editor renders the current line highlight.",
        SettingControl.Choice(listOf("line", "none"), { if (it.highlightActiveLine) "line" else "none" }, { s, v -> s.copy(highlightActiveLine = v == "line") }),
    ),
    SettingDef(
        Settings.EDITOR_INDENT_GUIDES, SettingGroup.TextEditor, "Editor › Guides", "Indentation",
        "Controls whether the editor should render indent guides.",
        SettingControl.Bool({ it.renderIndentGuides }, { s, v -> s.copy(renderIndentGuides = v) }),
    ),
    SettingDef(
        Settings.EDITOR_BRACKET_PAIRS, SettingGroup.TextEditor, "Editor › Bracket Pair Colorization", "Enabled",
        "Controls whether bracket pair colorization is enabled.",
        SettingControl.Bool({ it.bracketPairColorization }, { s, v -> s.copy(bracketPairColorization = v) }),
    ),
    SettingDef(
        Settings.EDITOR_MINIMAP, SettingGroup.TextEditor, "Editor › Minimap", "Enabled",
        "Controls whether the minimap is shown. Default: on for tablets and wide windows, off on phones.",
        SettingControl.Choice(
            listOf("default", "on", "off"),
            { when (it.minimap) { null -> "default"; true -> "on"; false -> "off" } },
            { s, v -> s.copy(minimap = when (v) { "on" -> true; "off" -> false; else -> null }) },
        ),
    ),
    SettingDef(
        Settings.EDITOR_STICKY_SCROLL, SettingGroup.TextEditor, "Editor › Sticky Scroll", "Enabled",
        "Shows the nested current scopes during the scroll at the top of the editor.",
        SettingControl.Bool({ it.stickyScroll }, { s, v -> s.copy(stickyScroll = v) }),
    ),
    SettingDef(
        Settings.WORKBENCH_COLOR_THEME, SettingGroup.Workbench, "Workbench", "Color Theme",
        "Specifies the color theme used in the workbench when the system scheme isn't followed.",
        SettingControl.Choice(themeNames, { it.colorTheme.ifEmpty { themeNames.first() } }, { s, v -> s.copy(colorTheme = v, autoDetectColorScheme = false) }),
        commonlyUsed = true,
    ),
    SettingDef(
        Settings.WINDOW_AUTO_DETECT, SettingGroup.Workbench, "Window", "Auto Detect Color Scheme",
        "Follow the system light/dark mode with Ferride Light/Dark Modern.",
        SettingControl.Bool({ it.autoDetectColorScheme }, { s, v -> s.copy(autoDetectColorScheme = v) }),
    ),
    SettingDef(
        Settings.FILES_AUTO_SAVE, SettingGroup.Files, "Files", "Auto Save",
        "Controls auto save of editors that have unsaved changes.",
        SettingControl.Choice(listOf("off", "afterDelay", "onFocusChange"), { it.autoSave }, { s, v -> s.copy(autoSave = v) }),
        commonlyUsed = true,
    ),
    SettingDef(
        Settings.FERRIDE_EXTRA_KEYS, SettingGroup.Ferride, "Ferride › Extra Keys", "Enabled",
        "Show the symbols row above the soft keyboard.",
        SettingControl.Bool({ it.extraKeys }, { s, v -> s.copy(extraKeys = v) }),
    ),
)

/** The Settings editor tab (VS Code's settings UI). */
@Composable
fun SettingsEditor(state: WorkbenchState, modifier: Modifier = Modifier) {
    val c = LocalWorkbenchColors.current
    var filter by remember { mutableStateOf("") }
    val defs = settingDefs((BuiltInThemes + state.extraThemes).map { it.name })
    val visible = defs.filter {
        filter.isBlank() || listOf(it.key, it.title, it.category, it.description).any { s -> s.contains(filter, ignoreCase = true) }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Rows in TOC order: Commonly Used first, then by group.
    val sections: List<Pair<String, List<SettingDef>>> = buildList {
        val common = visible.filter { it.commonlyUsed }
        if (common.isNotEmpty() && filter.isBlank()) add("Commonly Used" to common)
        SettingGroup.entries.forEach { g ->
            val rows = visible.filter { it.group == g }
            if (rows.isNotEmpty()) add(g.title to rows)
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().background(c.editorBackground)) {
        val wide = maxWidth >= 720.dp
        Column(Modifier.fillMaxSize().padding(horizontal = if (wide) 24.dp else 12.dp)) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().height(34.dp)
                    .background(c.inputBackground, RoundedCornerShape(3.dp))
                    .border(1.dp, c.focusBorder, RoundedCornerShape(3.dp))
                    .padding(horizontal = 10.dp),
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
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxSize()) {
                if (wide) {
                    // Table of contents.
                    Column(Modifier.width(180.dp).fillMaxHeight().padding(end = 12.dp)) {
                        var index = 0
                        sections.forEach { (title, rows) ->
                            val target = index
                            Text(
                                title,
                                color = c.sideBarForeground,
                                fontSize = 13.sp,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { scope.launch { listState.animateScrollToItem(target) } }
                                    .padding(vertical = 6.dp, horizontal = 8.dp),
                            )
                            index += rows.size + 1
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = listState) {
                    sections.forEach { (title, rows) ->
                        item(key = "h:$title") {
                            Text(
                                title,
                                color = c.editorForeground,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                            )
                        }
                        items(rows, key = { "$title:${it.key}" }) { def -> SettingRow(state, def) }
                    }
                    if (sections.isEmpty()) item { Text("No settings found", color = c.descriptionForeground, fontSize = 13.sp) }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(state: WorkbenchState, def: SettingDef) {
    val c = LocalWorkbenchColors.current
    val s = state.settings
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(
            buildAnnotatedString {
                append("${def.category}: ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(def.title) }
            },
            color = c.editorForeground,
            fontSize = 13.sp,
        )
        Text(def.key, color = c.descriptionForeground, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(4.dp))
        when (val ctl = def.control) {
            is SettingControl.Bool -> Row(
                Modifier.clickable { state.updateSettings { ctl.set(it, !ctl.get(it)) } }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(ctl.get(s))
                Spacer(Modifier.width(8.dp))
                Text(def.description, color = c.descriptionForeground, fontSize = 13.sp)
            }
            is SettingControl.Number -> {
                Text(def.description, color = c.descriptionForeground, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                NumberField(ctl.get(s)) { v -> state.updateSettings { ctl.set(it, v.coerceIn(ctl.range)) } }
            }
            is SettingControl.Choice -> {
                Text(def.description, color = c.descriptionForeground, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Dropdown(ctl.get(s), ctl.options) { v -> state.updateSettings { ctl.set(it, v) } }
            }
        }
    }
}

@Composable
private fun Checkbox(checked: Boolean) {
    val c = LocalWorkbenchColors.current
    Box(
        Modifier.size(18.dp)
            .background(if (checked) c.buttonBackground else c.inputBackground, RoundedCornerShape(3.dp))
            .border(1.dp, if (checked) c.buttonBackground else c.inputBorder, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Default.Check, "Checked", tint = c.buttonForeground, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun NumberField(value: Int, onChange: (Int) -> Unit) {
    val c = LocalWorkbenchColors.current
    var text by remember(value) { mutableStateOf(value.toString()) }
    Box(
        Modifier.width(72.dp).height(28.dp)
            .background(c.inputBackground, RoundedCornerShape(2.dp))
            .border(1.dp, c.inputBorder, RoundedCornerShape(2.dp))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { t ->
                text = t.filter(Char::isDigit).take(3)
                text.toIntOrNull()?.let(onChange)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(color = c.inputForeground, fontSize = 13.sp),
            cursorBrush = SolidColor(c.editorCursor),
        )
    }
}

@Composable
private fun Dropdown(value: String, options: List<String>, onSelect: (String) -> Unit) {
    val c = LocalWorkbenchColors.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.widthIn(min = 160.dp).height(28.dp)
                .background(c.inputBackground, RoundedCornerShape(2.dp))
                .border(1.dp, c.inputBorder, RoundedCornerShape(2.dp))
                .clickable { open = true }
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, color = c.inputForeground, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.KeyboardArrowDown, null, tint = c.inputForeground, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(c.quickInputBackground)) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(o, color = c.inputForeground, fontSize = 13.sp) },
                    onClick = {
                        open = false
                        onSelect(o)
                    },
                )
            }
        }
    }
}
