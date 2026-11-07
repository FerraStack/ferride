package dev.ferride.workbench

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkbenchTest {
    private fun state() = WorkbenchState(FakeWorkspaceBackend(), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    private fun key(code: Int, meta: Int) = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta)

    @Test
    fun keybindingsMatchVsCodeDefaults() {
        val s = state()
        assertFalse(s.paletteOpen)
        assertTrue(s.onKeyEvent(key(KeyEvent.KEYCODE_P, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)))
        assertTrue(s.paletteOpen)
        assertEquals(PaletteMode.Commands, s.paletteMode())
        assertTrue(s.onKeyEvent(key(KeyEvent.KEYCODE_P, KeyEvent.META_CTRL_ON)))
        assertEquals(PaletteMode.Files, s.paletteMode())
        assertTrue(s.onKeyEvent(key(KeyEvent.KEYCODE_B, KeyEvent.META_CTRL_ON)))
        assertTrue(s.sideBarVisible)
        assertTrue(s.onKeyEvent(key(KeyEvent.KEYCODE_GRAVE, KeyEvent.META_CTRL_ON)))
        assertEquals(PanelTab.Terminal, s.panelTab)
        assertTrue(s.panelVisible)
        // Unbound chord.
        assertFalse(s.onKeyEvent(key(KeyEvent.KEYCODE_Q, KeyEvent.META_CTRL_ON)))
        // Editor commands are disabled without an editor.
        assertFalse(s.onKeyEvent(key(KeyEvent.KEYCODE_S, KeyEvent.META_CTRL_ON)))
        assertEquals("Ctrl+Shift+P", s.commands.get(CommandIds.SHOW_COMMANDS)?.keybinding?.label)
    }

    @Test
    fun paletteFiltersCommandsFuzzily() {
        val s = state()
        val labels = s.commands.search("tgl term").map { it.id }
        assertEquals(CommandIds.TOGGLE_TERMINAL, labels.first())
        assertTrue(s.commands.search("zzzz").isEmpty())
        assertNotNull(fuzzyScore("fs", "File: Save"))
        assertNull(fuzzyScore("sf", "Save"))
    }

    @Test
    fun previewTabsAreReplacedUntilEdited() {
        val s = state()
        val root = s.backend.root
        s.openFile("$root/README.md")
        s.openFile("$root/Cargo.toml")
        assertEquals(1, s.tabs.size)
        assertTrue(s.tabs[0].preview)
        val tab = s.tabs[0]
        tab.model.insert("x", true)
        s.onEditorChanged(tab, contentChanged = true)
        assertFalse(tab.preview)
        assertTrue(tab.dirty)
        s.openFile("$root/src/lib.rs")
        assertEquals(2, s.tabs.size)
        s.save(tab)
        assertFalse(tab.dirty)
        s.closeTab(1)
        assertEquals(0, s.activeIndex)
    }

    @Test
    fun explorerLoadsLazily() {
        val s = state()
        s.start(openFirst = null)
        val root = s.backend.root
        assertEquals(listOf("scripts", "src", "web", "Cargo.toml", "README.md"), s.tree[root]?.map { it.name })
        s.toggleDir("$root/src")
        assertEquals(listOf("lib.rs", "main.rs"), s.tree["$root/src"]?.map { it.name })
    }

    @Test
    fun settingsRoundTripKeepsUnknownKeys() {
        val parsed = Settings.parse(
            """
            {
              // comments are fine
              "editor.fontSize": 16,
              "editor.wordWrap": "on",
              "workbench.colorTheme": "Ferride Light Modern",
              "my.extension.flag": true,
            }
            """.trimIndent(),
        )
        assertEquals(16, parsed.fontSize)
        assertTrue(parsed.wordWrap)
        assertEquals("Ferride Light Modern", parsed.colorTheme)
        val again = Settings.parse(parsed.toJson())
        assertEquals(parsed, again)
        assertTrue(parsed.toJson().contains("my.extension.flag"))
    }
}
