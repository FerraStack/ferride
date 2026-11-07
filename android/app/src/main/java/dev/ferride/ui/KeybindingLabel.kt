package dev.ferride.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ferride.theme.LocalWorkbenchColors
import dev.ferride.workbench.KeyChord

/**
 * A keybinding as VS Code draws it in the Command Palette and on the Welcome
 * page: one keycap per key with `+` between them (`Ctrl` + `Shift` + `P`),
 * using the `keybindingLabel.*` theme colors. Never truncates: callers give
 * the remaining width to the text next to it instead.
 */
@Composable
fun KeybindingLabel(chord: KeyChord, modifier: Modifier = Modifier) {
    val c = LocalWorkbenchColors.current
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = chord.label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chord.parts.forEachIndexed { i, key ->
            if (i > 0) {
                Text("+", color = c.keybindingLabelForeground, fontSize = 11.sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 2.dp))
            }
            Keycap(key)
        }
    }
}

@Composable
private fun Keycap(key: String) {
    val c = LocalWorkbenchColors.current
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier
            .background(c.keybindingLabelBackground, shape)
            .border(1.dp, c.keybindingLabelBorder, shape)
            // VS Code's keycaps have a heavier bottom edge (`keybindingLabel.bottomBorder`).
            .drawBehind {
                val y = size.height - 1.5.dp.toPx()
                val inset = 3.dp.toPx()
                drawLine(c.keybindingLabelBottomBorder, Offset(inset, y), Offset(size.width - inset, y), strokeWidth = 1.dp.toPx())
            }
            // Every keycap is at least as wide as a letter key, so ` and - don't shrink to slivers.
            .widthIn(min = KEYCAP_MIN_WIDTH)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Punctuation glyphs are tiny at 11 sp; draw them a little larger so they read at a glance.
        val punctuation = key.length == 1 && !key[0].isLetterOrDigit()
        Text(
            key,
            color = c.keybindingLabelForeground,
            fontSize = if (punctuation) 13.sp else 11.sp,
            fontWeight = if (punctuation) FontWeight.SemiBold else FontWeight.Normal,
            lineHeight = 14.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Width of a one-letter keycap (`S`): 5 dp padding on each side plus the glyph. */
private val KEYCAP_MIN_WIDTH = 20.dp
