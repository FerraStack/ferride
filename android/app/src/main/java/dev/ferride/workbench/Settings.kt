package dev.ferride.workbench

import org.json.JSONObject
import java.io.File

/**
 * User settings, stored as `settings.json` with VS Code's key names where one
 * exists, so users can paste their own settings. Unknown keys are preserved.
 */
data class Settings(
    val fontSize: Int = 14,
    val tabSize: Int = 4,
    val insertSpaces: Boolean = true,
    val wordWrap: Boolean = false,
    val lineNumbers: Boolean = true,
    val minimap: Boolean = false,
    val renderIndentGuides: Boolean = true,
    val highlightActiveLine: Boolean = true,
    val bracketPairColorization: Boolean = true,
    val autoSave: String = "off",
    /** Theme name, or empty to follow the system light/dark mode. */
    val colorTheme: String = "",
    val autoDetectColorScheme: Boolean = true,
    val extraKeys: Boolean = true,
    private val unknown: Map<String, Any?> = emptyMap(),
) {
    fun toJson(): String {
        val o = JSONObject()
        unknown.forEach { (k, v) -> o.put(k, v) }
        o.put(EDITOR_FONT_SIZE, fontSize)
        o.put(EDITOR_TAB_SIZE, tabSize)
        o.put(EDITOR_INSERT_SPACES, insertSpaces)
        o.put(EDITOR_WORD_WRAP, if (wordWrap) "on" else "off")
        o.put(EDITOR_LINE_NUMBERS, if (lineNumbers) "on" else "off")
        o.put(EDITOR_MINIMAP, minimap)
        o.put(EDITOR_INDENT_GUIDES, renderIndentGuides)
        o.put(EDITOR_LINE_HIGHLIGHT, if (highlightActiveLine) "line" else "none")
        o.put(EDITOR_BRACKET_PAIRS, bracketPairColorization)
        o.put(FILES_AUTO_SAVE, autoSave)
        if (colorTheme.isNotEmpty()) o.put(WORKBENCH_COLOR_THEME, colorTheme)
        o.put(WINDOW_AUTO_DETECT, autoDetectColorScheme)
        o.put(FERRIDE_EXTRA_KEYS, extraKeys)
        return o.toString(2)
    }

    companion object {
        const val EDITOR_FONT_SIZE = "editor.fontSize"
        const val EDITOR_TAB_SIZE = "editor.tabSize"
        const val EDITOR_INSERT_SPACES = "editor.insertSpaces"
        const val EDITOR_WORD_WRAP = "editor.wordWrap"
        const val EDITOR_LINE_NUMBERS = "editor.lineNumbers"
        const val EDITOR_MINIMAP = "editor.minimap.enabled"
        const val EDITOR_INDENT_GUIDES = "editor.guides.indentation"
        const val EDITOR_LINE_HIGHLIGHT = "editor.renderLineHighlight"
        const val EDITOR_BRACKET_PAIRS = "editor.bracketPairColorization.enabled"
        const val FILES_AUTO_SAVE = "files.autoSave"
        const val WORKBENCH_COLOR_THEME = "workbench.colorTheme"
        const val WINDOW_AUTO_DETECT = "window.autoDetectColorScheme"
        const val FERRIDE_EXTRA_KEYS = "ferride.extraKeys.enabled"

        private val KNOWN = setOf(
            EDITOR_FONT_SIZE, EDITOR_TAB_SIZE, EDITOR_INSERT_SPACES, EDITOR_WORD_WRAP, EDITOR_LINE_NUMBERS,
            EDITOR_MINIMAP, EDITOR_INDENT_GUIDES, EDITOR_LINE_HIGHLIGHT, EDITOR_BRACKET_PAIRS, FILES_AUTO_SAVE,
            WORKBENCH_COLOR_THEME, WINDOW_AUTO_DETECT, FERRIDE_EXTRA_KEYS,
        )

        fun parse(json: String): Settings {
            val o = runCatching { JSONObject(dev.ferride.theme.VsCodeTheme.stripJsonc(json)) }.getOrElse { JSONObject() }
            val d = Settings()
            val unknown = o.keys().asSequence().filter { it !in KNOWN }.associateWith { o.opt(it) }
            return Settings(
                fontSize = o.optInt(EDITOR_FONT_SIZE, d.fontSize).coerceIn(6, 48),
                tabSize = o.optInt(EDITOR_TAB_SIZE, d.tabSize).coerceIn(1, 16),
                insertSpaces = o.optBoolean(EDITOR_INSERT_SPACES, d.insertSpaces),
                wordWrap = o.optString(EDITOR_WORD_WRAP, "off") != "off",
                lineNumbers = o.optString(EDITOR_LINE_NUMBERS, "on") != "off",
                minimap = o.optBoolean(EDITOR_MINIMAP, d.minimap),
                renderIndentGuides = o.optBoolean(EDITOR_INDENT_GUIDES, d.renderIndentGuides),
                highlightActiveLine = o.optString(EDITOR_LINE_HIGHLIGHT, "line") != "none",
                bracketPairColorization = o.optBoolean(EDITOR_BRACKET_PAIRS, d.bracketPairColorization),
                autoSave = o.optString(FILES_AUTO_SAVE, d.autoSave),
                colorTheme = o.optString(WORKBENCH_COLOR_THEME, ""),
                autoDetectColorScheme = o.optBoolean(WINDOW_AUTO_DETECT, d.autoDetectColorScheme),
                extraKeys = o.optBoolean(FERRIDE_EXTRA_KEYS, d.extraKeys),
                unknown = unknown,
            )
        }

        fun load(file: File): Settings = if (file.exists()) parse(file.readText()) else Settings()
    }
}
