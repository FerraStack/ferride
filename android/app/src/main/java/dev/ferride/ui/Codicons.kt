package dev.ferride.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ferride.R

/**
 * VS Code's icon font (Codicons, CC BY 4.0, see NOTICE). Glyphs are the
 * codepoints from `@vscode/codicons` 0.0.36 `codicon.css`.
 */
object Codicon {
    val Font = FontFamily(Font(R.font.codicon))

    const val ADD = ''
    const val CLOSE = ''
    const val COLLAPSE_ALL = ''
    const val COPY = ''
    const val EDIT = ''
    const val ELLIPSIS = ''
    const val FILES = ''
    const val FOLDER = ''
    const val FOLDER_OPENED = ''
    const val GO_TO_FILE = ''
    const val HISTORY = ''
    const val NEW_FILE = ''
    const val NEW_FOLDER = ''
    const val REFRESH = ''
    const val REPO_CLONE = ''
    const val SEARCH = ''
    const val SOURCE_CONTROL = ''
    const val TRASH = ''
}

/** One Codicon glyph, [size] square (VS Code draws them at 16 px). */
@Composable
fun CodiconIcon(glyph: Char, tint: Color, modifier: Modifier = Modifier, size: Dp = 16.dp, description: String? = null) {
    // Icons keep their size when the user enlarges text (dp, not sp).
    val glyphSize = with(LocalDensity.current) { size.toSp() }
    Box(
        modifier.size(size).let { m -> if (description != null) m.semantics { contentDescription = description } else m },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph.toString(),
            color = tint,
            style = TextStyle(
                fontFamily = Codicon.Font,
                fontSize = glyphSize,
                lineHeight = glyphSize,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}
