package dev.ferride.theme

import androidx.compose.ui.graphics.Color
import dev.ferride.core.StyleKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * Loads VS Code color themes (`*-color-theme.json`): `type`, `colors` and
 * `tokenColors` (TextMate scopes). Unknown keys are ignored; anything the theme
 * doesn't set falls back to the matching built-in theme.
 *
 * Ferride highlights with tree-sitter, so each [StyleKind] is mapped to the
 * TextMate scopes VS Code grammars would produce for the same tokens
 * ([TEXTMATE_SCOPES]); the theme rule that matches the most specific scope wins.
 */
object VsCodeTheme {
    /** tree-sitter highlight class → representative TextMate scopes, most specific first. */
    val TEXTMATE_SCOPES: Map<StyleKind, List<String>> = mapOf(
        StyleKind.KEYWORD to listOf("keyword.control", "storage.type", "storage.modifier", "keyword"),
        StyleKind.FUNCTION to listOf("entity.name.function", "support.function", "meta.function-call"),
        StyleKind.TYPE to listOf("entity.name.type", "entity.name.class", "support.type", "support.class"),
        StyleKind.STRING to listOf("string.quoted", "string"),
        StyleKind.NUMBER to listOf("constant.numeric"),
        StyleKind.COMMENT to listOf("comment.line", "comment"),
        StyleKind.CONSTANT to listOf("constant.language", "variable.other.constant", "constant"),
        StyleKind.VARIABLE to listOf("variable.other.readwrite", "variable.parameter", "variable"),
        StyleKind.PROPERTY to listOf("variable.other.property", "support.type.property-name", "meta.object-literal.key"),
        StyleKind.OPERATOR to listOf("keyword.operator"),
        StyleKind.PUNCTUATION to listOf("punctuation.separator", "punctuation"),
        StyleKind.TAG to listOf("entity.name.tag"),
        StyleKind.ATTRIBUTE to listOf("entity.other.attribute-name", "meta.attribute"),
        StyleKind.HEADING to listOf("markup.heading", "entity.name.section"),
        StyleKind.LINK to listOf("markup.underline.link", "string.other.link"),
        StyleKind.ESCAPE to listOf("constant.character.escape"),
        StyleKind.EMPHASIS to listOf("markup.bold", "markup.italic"),
        StyleKind.LABEL to listOf("entity.name.label"),
        StyleKind.BUILTIN to listOf("support.function.builtin", "variable.language", "support.type.primitive"),
    )

    fun parse(json: String, fallbackName: String = "Custom theme"): WorkbenchColors {
        val root = JSONObject(stripJsonc(json))
        val dark = !root.optString("type", "dark").startsWith("light")
        val base = if (dark) DarkModern else LightModern
        val colors = root.optJSONObject("colors") ?: JSONObject()
        fun col(key: String, fallback: Color): Color = colors.optString(key, "").let { parseColor(it) } ?: fallback
        val rules = parseRules(root.optJSONArray("tokenColors"))
        val editorFg = col("editor.foreground", base.editorForeground)
        val tokens = StyleKind.entries.associateWith { kind ->
            resolve(rules, TEXTMATE_SCOPES[kind].orEmpty()) ?: base.tokens[kind] ?: editorFg
        }
        return base.copy(
            name = root.optString("name", fallbackName),
            isDark = dark,
            activityBarBackground = col("activityBar.background", base.activityBarBackground),
            activityBarForeground = col("activityBar.foreground", base.activityBarForeground),
            activityBarInactiveForeground = col("activityBar.inactiveForeground", base.activityBarInactiveForeground),
            activityBarActiveBorder = col("activityBar.activeBorder", base.activityBarActiveBorder),
            sideBarBackground = col("sideBar.background", base.sideBarBackground),
            sideBarForeground = col("sideBar.foreground", base.sideBarForeground),
            sideBarTitleForeground = col("sideBarTitle.foreground", base.sideBarTitleForeground),
            sideBarSectionHeaderBackground = col("sideBarSectionHeader.background", base.sideBarSectionHeaderBackground),
            listActiveSelectionBackground = col("list.activeSelectionBackground", base.listActiveSelectionBackground),
            listHoverBackground = col("list.hoverBackground", base.listHoverBackground),
            editorBackground = col("editor.background", base.editorBackground),
            editorForeground = editorFg,
            editorLineHighlight = col("editor.lineHighlightBackground", base.editorLineHighlight),
            editorSelection = col("editor.selectionBackground", base.editorSelection),
            editorCursor = col("editorCursor.foreground", base.editorCursor),
            editorLineNumber = col("editorLineNumber.foreground", base.editorLineNumber),
            editorLineNumberActive = col("editorLineNumber.activeForeground", base.editorLineNumberActive),
            editorIndentGuide = col("editorIndentGuide.background1", col("editorIndentGuide.background", base.editorIndentGuide)),
            editorWhitespace = col("editorWhitespace.foreground", base.editorWhitespace),
            findMatchBackground = col("editor.findMatchHighlightBackground", base.findMatchBackground),
            findMatchCurrentBackground = col("editor.findMatchBackground", base.findMatchCurrentBackground),
            errorSquiggle = col("editorError.foreground", base.errorSquiggle),
            warningSquiggle = col("editorWarning.foreground", base.warningSquiggle),
            infoSquiggle = col("editorInfo.foreground", base.infoSquiggle),
            scrollbarSlider = col("scrollbarSlider.background", base.scrollbarSlider),
            minimapSliderBackground = col("minimapSlider.background", base.minimapSliderBackground),
            editorGroupHeaderBackground = col("editorGroupHeader.tabsBackground", base.editorGroupHeaderBackground),
            tabActiveBackground = col("tab.activeBackground", base.tabActiveBackground),
            tabInactiveBackground = col("tab.inactiveBackground", base.tabInactiveBackground),
            tabActiveForeground = col("tab.activeForeground", base.tabActiveForeground),
            tabInactiveForeground = col("tab.inactiveForeground", base.tabInactiveForeground),
            tabBorder = col("tab.border", base.tabBorder),
            tabActiveBorderTop = col("tab.activeBorderTop", base.tabActiveBorderTop),
            breadcrumbForeground = col("breadcrumb.foreground", base.breadcrumbForeground),
            panelBackground = col("panel.background", base.panelBackground),
            panelBorder = col("panel.border", base.panelBorder),
            panelTitleActiveForeground = col("panelTitle.activeForeground", base.panelTitleActiveForeground),
            panelTitleInactiveForeground = col("panelTitle.inactiveForeground", base.panelTitleInactiveForeground),
            panelTitleActiveBorder = col("panelTitle.activeBorder", base.panelTitleActiveBorder),
            statusBarBackground = col("statusBar.background", base.statusBarBackground),
            statusBarForeground = col("statusBar.foreground", base.statusBarForeground),
            focusBorder = col("focusBorder", base.focusBorder),
            inputBackground = col("input.background", base.inputBackground),
            inputForeground = col("input.foreground", base.inputForeground),
            inputBorder = col("input.border", base.inputBorder),
            inputValidationErrorBackground = col("inputValidation.errorBackground", base.inputValidationErrorBackground),
            inputValidationErrorBorder = col("inputValidation.errorBorder", base.inputValidationErrorBorder),
            quickInputBackground = col("quickInput.background", base.quickInputBackground),
            widgetShadow = col("widget.shadow", base.widgetShadow),
            badgeBackground = col("badge.background", base.badgeBackground),
            badgeForeground = col("badge.foreground", base.badgeForeground),
            keybindingLabelBackground = col("keybindingLabel.background", base.keybindingLabelBackground),
            keybindingLabelForeground = col("keybindingLabel.foreground", base.keybindingLabelForeground),
            keybindingLabelBorder = col("keybindingLabel.border", base.keybindingLabelBorder),
            keybindingLabelBottomBorder = col("keybindingLabel.bottomBorder", base.keybindingLabelBottomBorder),
            buttonBackground = col("button.background", base.buttonBackground),
            buttonForeground = col("button.foreground", base.buttonForeground),
            descriptionForeground = col("descriptionForeground", base.descriptionForeground),
            errorForeground = col("errorForeground", base.errorForeground),
            warningForeground = col("editorWarning.foreground", base.warningForeground),
            menuBackground = col("menu.background", col("editorWidget.background", base.menuBackground)),
            menuForeground = col("menu.foreground", col("editorWidget.foreground", base.menuForeground)),
            menuBorder = col("menu.border", col("editorWidget.border", base.menuBorder)),
            menuSelectionBackground = col("menu.selectionBackground", col("list.activeSelectionBackground", base.menuSelectionBackground)),
            menuSelectionForeground = col("menu.selectionForeground", base.menuSelectionForeground),
            menuSeparator = col("menu.separatorBackground", base.menuSeparator),
            tokens = tokens,
        )
    }

    /** A `tokenColors` rule: selector scopes and its foreground. */
    data class Rule(val scopes: List<String>, val foreground: Color?)

    fun parseRules(array: JSONArray?): List<Rule> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val settings = obj.optJSONObject("settings") ?: return@mapNotNull null
            val fg = parseColor(settings.optString("foreground", ""))
            val scopes = when (val s = obj.opt("scope")) {
                is String -> s.split(',')
                is JSONArray -> (0 until s.length()).map { s.optString(it) }
                else -> emptyList()
            }.map { it.trim().substringAfterLast(' ') }.filter { it.isNotEmpty() }
            Rule(scopes, fg)
        }
    }

    /**
     * Picks the foreground for the first target scope that any rule matches.
     * A rule scope `a.b` matches `a.b` and `a.b.c`; longer matches win, and
     * later rules win ties (like VS Code).
     */
    fun resolve(rules: List<Rule>, targets: List<String>): Color? {
        for (target in targets) {
            var best: Color? = null
            var bestLen = -1
            for (rule in rules) {
                val fg = rule.foreground ?: continue
                for (s in rule.scopes) {
                    if ((target == s || target.startsWith("$s.")) && s.length >= bestLen) {
                        best = fg
                        bestLen = s.length
                    }
                }
            }
            if (best != null) return best
        }
        return null
    }

    /** `#RGB`, `#RGBA`, `#RRGGBB`, `#RRGGBBAA` → Color. */
    fun parseColor(s: String): Color? {
        if (!s.startsWith("#")) return null
        val h = s.substring(1)
        val full = when (h.length) {
            3, 4 -> h.map { "$it$it" }.joinToString("")
            6, 8 -> h
            else -> return null
        }
        val v = full.toLongOrNull(16) ?: return null
        val (rgb, a) = if (full.length == 8) (v shr 8) to (v and 0xFF) else v to 0xFFL
        return Color((a shl 24) or rgb)
    }

    /** Removes comments and trailing commas (JSONC) with a real tokenizer. */
    fun stripJsonc(src: String): String = dev.ferride.workbench.Jsonc.strip(src)
}
