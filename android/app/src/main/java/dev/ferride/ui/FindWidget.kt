package dev.ferride.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ferride.theme.LocalWorkbenchColors
import dev.ferride.workbench.WorkbenchState

/** VS Code's find/replace widget, anchored to the editor's top-right corner. */
@Composable
fun FindWidget(state: WorkbenchState, matchCount: Int, maxWidth: Dp, modifier: Modifier = Modifier) {
    val c = LocalWorkbenchColors.current
    val f = state.find
    Row(
        modifier
            .widthIn(max = minOf(440.dp, maxWidth - 12.dp))
            .shadow(8.dp, RoundedCornerShape(4.dp))
            .background(c.quickInputBackground, RoundedCornerShape(4.dp))
            .border(1.dp, c.panelBorder, RoundedCornerShape(4.dp))
            .padding(4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        BarIcon(
            if (f.replaceVisible) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            "Toggle Replace",
            c.inputForeground,
            size = 28.dp,
            iconSize = 16.dp,
        ) { f.replaceVisible = !f.replaceVisible }
        Column(Modifier.weight(1f, fill = false)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FindField(f.query, "Find", Modifier.weight(1f, fill = false)) {
                    f.query = it
                    f.current = -1
                }
                FindToggle("Aa", "Match Case", f.caseSensitive) { f.caseSensitive = it; f.current = -1 }
                FindToggle("ab", "Match Whole Word", f.wholeWord) { f.wholeWord = it; f.current = -1 }
                FindToggle(".*", "Use Regular Expression", f.regex) { f.regex = it; f.current = -1 }
                Spacer(Modifier.width(4.dp))
                Text(
                    when {
                        f.query.isEmpty() -> "No results"
                        matchCount == 0 -> "No results"
                        f.current >= 0 -> "${f.current + 1} of $matchCount"
                        else -> "? of $matchCount"
                    },
                    color = if (f.query.isNotEmpty() && matchCount == 0) c.errorForeground else c.descriptionForeground,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.widthIn(min = 56.dp),
                )
                BarIcon(Icons.Default.ArrowUpward, "Previous Match", c.inputForeground, size = 28.dp, iconSize = 16.dp) {
                    state.findNext(backwards = true)
                }
                BarIcon(Icons.Default.ArrowDownward, "Next Match", c.inputForeground, size = 28.dp, iconSize = 16.dp) {
                    state.findNext()
                }
                BarIcon(Icons.Default.Close, "Close", c.inputForeground, size = 28.dp, iconSize = 16.dp) { state.closeFind() }
            }
            if (f.replaceVisible) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FindField(f.replacement, "Replace", Modifier.weight(1f, fill = false)) { f.replacement = it }
                    TextAction("Replace") { state.replaceCurrent() }
                    TextAction("All") { state.replaceAll() }
                }
            }
        }
    }
}

@Composable
private fun FindField(value: String, placeholder: String, modifier: Modifier, onChange: (String) -> Unit) {
    val c = LocalWorkbenchColors.current
    Box(
        modifier.widthIn(min = 120.dp, max = 220.dp).height(28.dp)
            .background(c.inputBackground, RoundedCornerShape(2.dp))
            .border(1.dp, c.inputBorder, RoundedCornerShape(2.dp))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, color = c.descriptionForeground, fontSize = 13.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = c.inputForeground, fontSize = 13.sp),
            cursorBrush = SolidColor(c.editorCursor),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {}),
        )
    }
}

@Composable
private fun FindToggle(label: String, description: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalWorkbenchColors.current
    Box(
        Modifier.padding(start = 2.dp).width(26.dp).height(26.dp)
            .background(if (on) c.listActiveSelectionBackground else c.quickInputBackground, RoundedCornerShape(3.dp))
            .border(1.dp, if (on) c.focusBorder else c.quickInputBackground, RoundedCornerShape(3.dp))
            .clickable(onClickLabel = description) { onChange(!on) },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = c.inputForeground, fontSize = 11.sp)
    }
}

@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    val c = LocalWorkbenchColors.current
    Text(
        label,
        color = c.inputForeground,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 4.dp)
            .background(c.inputBackground, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}
