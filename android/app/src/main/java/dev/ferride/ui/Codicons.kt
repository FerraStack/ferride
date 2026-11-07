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

    const val ADD = '\uEA60'
    const val CHECK = '\uEAB2'
    const val KEYBOARD = '\uEA65'
    const val SETTINGS_GEAR = '\uEB51'
    const val CLOSE = '\uEA76'
    const val COLLAPSE_ALL = '\uEAC5'
    const val COPY = '\uEBCC'
    const val EDIT = '\uEA73'
    const val ELLIPSIS = '\uEA7C'
    const val FILES = '\uEAF0'
    const val FOLDER = '\uEA83'
    const val FOLDER_OPENED = '\uEAF7'
    const val GO_TO_FILE = '\uEA94'
    const val HISTORY = '\uEA82'
    const val NEW_FILE = '\uEA7F'
    const val NEW_FOLDER = '\uEA80'
    const val REFRESH = '\uEB37'
    const val REPO_CLONE = '\uEB3E'
    const val SEARCH = '\uEA6D'
    const val SOURCE_CONTROL = '\uEA68'
    const val TRASH = '\uEA81'
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
