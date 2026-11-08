package dev.ferride.editor

import android.graphics.Paint
import android.graphics.Typeface
import dev.ferride.core.TokenTheme

/**
 * Looks up the VS Code theme style of a span's scope id (see `scopeStack`):
 * [color] (ARGB) and [fontStyle] bits (1 italic, 2 bold, 4 underline,
 * 8 strikethrough). Results are cached per id, so drawing doesn't cross the
 * FFI for every span.
 */
class TokenStyles private constructor(private val theme: TokenTheme?) {
    private val colors = HashMap<Int, Int>()
    private val fonts = HashMap<Int, Int>()

    /** False if the native theme couldn't be created (JVM previews without the Rust library). */
    val available: Boolean get() = theme != null

    fun color(scope: Int): Int? = theme?.let { t -> colors.getOrPut(scope) { load(t, scope); colors.getValue(scope) } }

    fun fontStyle(scope: Int): Int = theme?.let { t -> fonts.getOrPut(scope) { load(t, scope); fonts.getValue(scope) } } ?: 0

    private fun load(t: TokenTheme, scope: Int) {
        val s = t.style(scope)
        colors[scope] = s.foreground.toInt()
        fonts[scope] = s.fontStyle.toInt()
    }

    companion object {
        /** From a VS Code color theme file (JSONC); never throws. */
        fun fromThemeJson(json: String): TokenStyles = TokenStyles(
            try {
                TokenTheme(json)
            } catch (_: Throwable) {
                null // no native library (previews) or an invalid theme
            },
        )

        /**
         * VS Code's Dark+/Light+ font styles for Markdown and emphasis, which
         * Ferride's built-in themes use on top of their own colors.
         */
        val builtInFontStyles: TokenStyles by lazy {
            fromThemeJson(
                """
                { "tokenColors": [
                  { "scope": "emphasis", "settings": { "fontStyle": "italic" } },
                  { "scope": "strong", "settings": { "fontStyle": "bold" } },
                  { "scope": "markup.italic", "settings": { "fontStyle": "italic" } },
                  { "scope": "markup.bold", "settings": { "fontStyle": "bold" } },
                  { "scope": "markup.heading", "settings": { "fontStyle": "bold" } },
                  { "scope": "markup.underline", "settings": { "fontStyle": "underline" } },
                  { "scope": "markup.strikethrough", "settings": { "fontStyle": "strikethrough" } }
                ] }
                """.trimIndent(),
            )
        }

        const val ITALIC = 1
        const val BOLD = 2
        const val UNDERLINE = 4
        const val STRIKETHROUGH = 8

        private val typefaces = arrayOfNulls<Typeface>(4)

        /** Applies [fontStyle] bits to a monospace text paint. */
        fun apply(paint: Paint, fontStyle: Int) {
            val tf = fontStyle and (ITALIC or BOLD)
            paint.typeface = typefaces[tf] ?: Typeface.create(
                Typeface.MONOSPACE,
                when (tf) {
                    ITALIC -> Typeface.ITALIC
                    BOLD -> Typeface.BOLD
                    ITALIC or BOLD -> Typeface.BOLD_ITALIC
                    else -> Typeface.NORMAL
                },
            ).also { typefaces[tf] = it }
            paint.isUnderlineText = fontStyle and UNDERLINE != 0
            paint.isStrikeThruText = fontStyle and STRIKETHROUGH != 0
        }
    }
}
