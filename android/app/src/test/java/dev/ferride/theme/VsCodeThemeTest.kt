package dev.ferride.theme

import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ferride.core.StyleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VsCodeThemeTest {
    private val theme = """
        // A VS Code theme with comments and trailing commas (JSONC).
        {
          "name": "Test Light",
          "type": "light",
          "colors": {
            "editor.background": "#fafafa",
            "statusBar.background": "#00f8", /* 8-digit with alpha */
            "activityBar.background": "#abc",
          },
          "tokenColors": [
            { "scope": "keyword", "settings": { "foreground": "#ff0000" } },
            { "scope": ["keyword.control", "storage.type"], "settings": { "foreground": "#00ff00" } },
            { "scope": "string, string.quoted.double", "settings": { "foreground": "#0000ff" } },
            { "scope": "source.js comment", "settings": { "foreground": "#123456" } },
            { "scope": "constant.numeric", "settings": { "fontStyle": "italic" } },
          ],
        }
    """.trimIndent()

    @Test
    fun parsesColorsAndTokenScopes() {
        val t = VsCodeTheme.parse(theme)
        assertEquals("Test Light", t.name)
        assertFalse(t.isDark)
        assertEquals(Color(0xFFFAFAFA), t.editorBackground)
        assertEquals(Color(0x880000FF), t.statusBarBackground)
        assertEquals(Color(0xFFAABBCC), t.activityBarBackground)
        // Unset keys fall back to the light base theme.
        assertEquals(LightModern.sideBarBackground, t.sideBarBackground)
        // keyword.control is more specific than keyword.
        assertEquals(Color(0xFF00FF00), t.token(StyleKind.KEYWORD))
        assertEquals(Color(0xFF0000FF), t.token(StyleKind.STRING))
        // Descendant selectors use their last scope.
        assertEquals(Color(0xFF123456), t.token(StyleKind.COMMENT))
        // A rule without a foreground doesn't override the base color.
        assertEquals(LightModern.token(StyleKind.NUMBER), t.token(StyleKind.NUMBER))
    }

    @Test
    fun resolvePrefersLongestThenLatest() {
        val rules = listOf(
            VsCodeTheme.Rule(listOf("entity"), Color.Red),
            VsCodeTheme.Rule(listOf("entity.name"), Color.Green),
            VsCodeTheme.Rule(listOf("entity.name"), Color.Blue),
        )
        assertEquals(Color.Blue, VsCodeTheme.resolve(rules, listOf("entity.name.function")))
        assertNull(VsCodeTheme.resolve(rules, listOf("string")))
        assertNull(VsCodeTheme.resolve(rules, listOf("entityx")))
    }

    @Test
    fun colorFormats() {
        assertEquals(Color(0xFF112233), VsCodeTheme.parseColor("#123"))
        assertEquals(Color(0x44112233), VsCodeTheme.parseColor("#1234"))
        assertNull(VsCodeTheme.parseColor("red"))
        assertNull(VsCodeTheme.parseColor("#12345"))
    }

    @Test
    fun everyStyleKindHasScopes() {
        StyleKind.entries.filter { it != StyleKind.PLAIN }.forEach {
            assert(VsCodeTheme.TEXTMATE_SCOPES[it]?.isNotEmpty() == true) { "no scopes for $it" }
        }
    }
}
