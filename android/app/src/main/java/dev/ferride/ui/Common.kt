package dev.ferride.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ferride.core.StyleKind
import dev.ferride.editor.EditorStyle
import dev.ferride.theme.LocalWorkbenchColors
import dev.ferride.theme.WorkbenchColors
import dev.ferride.workbench.Settings

/** A square, touch-friendly icon button without Material's ripple container styling. */
@Composable
fun BarIcon(
    icon: ImageVector,
    description: String,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier.size(size).clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Seti-style file badge: a short colored label per file type. */
@Composable
fun FileIcon(name: String, modifier: Modifier = Modifier) {
    val (label, color) = fileBadge(name)
    Box(modifier.width(20.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

fun fileBadge(name: String): Pair<String, Color> = when (name.substringAfterLast('.', "").lowercase()) {
    "rs" -> "RS" to Color(0xFFDEA584)
    "py" -> "PY" to Color(0xFF4B8BBE)
    "ts", "mts", "cts" -> "TS" to Color(0xFF3178C6)
    "tsx" -> "TSX" to Color(0xFF3178C6)
    "js", "mjs", "cjs" -> "JS" to Color(0xFFE8D44D)
    "jsx" -> "JSX" to Color(0xFFE8D44D)
    "json" -> "{}" to Color(0xFFCBCB41)
    "md", "markdown" -> "M↓" to Color(0xFF519ABA)
    "toml" -> "⚙" to Color(0xFF9C9C9C)
    else -> "≡" to Color(0xFF9C9C9C)
}

fun languageDisplayName(id: String?): String = when (id) {
    "rust" -> "Rust"
    "python" -> "Python"
    "javascript" -> "JavaScript"
    "typescript" -> "TypeScript"
    "typescriptreact" -> "TypeScript JSX"
    "json" -> "JSON"
    "markdown" -> "Markdown"
    null -> "Plain Text"
    else -> id
}

/** Workbench colors + settings → what the editor view draws with. */
fun editorStyle(colors: WorkbenchColors, settings: Settings) = EditorStyle(
    background = colors.editorBackground.toArgb(),
    foreground = colors.editorForeground.toArgb(),
    lineHighlight = colors.editorLineHighlight.toArgb(),
    selection = colors.editorSelection.toArgb(),
    cursor = colors.editorCursor.toArgb(),
    lineNumber = colors.editorLineNumber.toArgb(),
    lineNumberActive = colors.editorLineNumberActive.toArgb(),
    indentGuide = colors.editorIndentGuide.toArgb(),
    tokens = StyleKind.entries.associateWith { colors.token(it).toArgb() },
    fontSizeSp = settings.fontSize.toFloat(),
    tabSize = settings.tabSize,
    insertSpaces = settings.insertSpaces,
    lineNumbers = settings.lineNumbers,
    indentGuides = settings.renderIndentGuides,
    highlightActiveLine = settings.highlightActiveLine,
    bracketPairs = settings.bracketPairColorization,
    bracketColors = if (colors.isDark) {
        listOf(0xFFFFD700.toInt(), 0xFFDA70D6.toInt(), 0xFF179FFF.toInt())
    } else {
        listOf(0xFF0431FA.toInt(), 0xFF319331.toInt(), 0xFF7B3814.toInt())
    },
)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    val c = LocalWorkbenchColors.current
    Text(
        text.uppercase(),
        color = c.sideBarTitleForeground,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        modifier = modifier,
    )
}

/** Small rounded count badge (problems, search results). */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    val c = LocalWorkbenchColors.current
    Box(
        modifier.background(c.badgeBackground, RoundedCornerShape(50)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            count.toString(),
            color = c.badgeForeground,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}
