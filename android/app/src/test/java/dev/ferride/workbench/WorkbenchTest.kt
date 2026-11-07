package dev.ferride.workbench

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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
    fun keybindingPartsAreOneKeycapEach() {
        assertEquals(listOf("Ctrl", "Shift", "P"), KeyChord.ctrl(KeyEvent.KEYCODE_P, shift = true).parts)
        // Punctuation keys keep their glyph (these used to render as a truncated "Ctrl+", #16).
        assertEquals(listOf("Ctrl", "`"), KeyChord.ctrl(KeyEvent.KEYCODE_GRAVE).parts)
        assertEquals(listOf("Ctrl", "-"), KeyChord.ctrl(KeyEvent.KEYCODE_MINUS).parts)
        assertEquals(listOf("Ctrl", "PageDown"), KeyChord.ctrl(KeyEvent.KEYCODE_PAGE_DOWN).parts)
        val s = state()
        s.openPalette(PaletteMode.Commands)
        val item = s.paletteItems(emptyList()).first { it.detail == CommandIds.TOGGLE_TERMINAL }
        assertEquals(listOf("Ctrl", "`"), item.keybinding?.parts)
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
        tab.model!!.insert("x", true)
        s.onEditorChanged(tab, contentChanged = true)
        assertFalse(tab.preview)
        assertTrue(tab.dirty)
        s.openFile("$root/src/lib.rs")
        assertEquals(2, s.tabs.size)
        s.save(tab)
        assertFalse(tab.dirty)
        s.closeTab(1)
        assertEquals(0, s.activeIndex)
        // Closed/replaced models are retired, then freed once the UI lets go of them.
        assertEquals(2, s.retiredCount)
        s.disposeRetired(s.activeModel)
        assertEquals(0, s.retiredCount)
    }

    @Test
    fun editingChordsOnlyFireWithEditorFocus() {
        val s = state()
        s.openFile("${s.backend.root}/README.md")
        s.editorFocused = false
        assertFalse(s.onKeyEvent(key(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON)))
        s.editorFocused = true
        assertTrue(s.onKeyEvent(key(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON)))
        // Global chords work regardless of focus.
        s.editorFocused = false
        assertTrue(s.onKeyEvent(key(KeyEvent.KEYCODE_B, KeyEvent.META_CTRL_ON)))
    }

    @Test
    fun editsDuringSaveStayDirty() {
        lateinit var s: WorkbenchState
        val backend = FakeWorkspaceBackend(onWrite = { s.activeModel!!.insert("typed during save", true) })
        s = WorkbenchState(backend, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        s.openFile("${backend.root}/README.md")
        val tab = s.activeTab!!
        tab.model!!.insert("x", true)
        s.onEditorChanged(tab, true)
        s.save(tab)
        assertTrue("the edit made while writing must stay unsaved", tab.dirty)
    }

    @Test
    fun closingADirtyTabAsks() {
        val s = state()
        s.openFile("${s.backend.root}/README.md")
        val tab = s.activeTab!!
        tab.model!!.insert("x", true)
        s.onEditorChanged(tab, true)
        s.requestCloseTab(0)
        assertEquals(tab, s.closeRequest)
        assertEquals(1, s.tabs.size)
        s.resolveCloseRequest(null) // Cancel
        assertEquals(1, s.tabs.size)
        s.requestCloseTab(0)
        s.resolveCloseRequest(false) // Don't Save
        assertTrue(s.tabs.isEmpty())
    }

    @Test
    fun brokenSettingsAreNeverOverwritten() {
        val broken = "{ \"editor.fontSize\": 16, oops }"
        val store = InMemorySettingsStore(broken)
        val s = WorkbenchState(FakeWorkspaceBackend(), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, store)
        assertNotNull(s.settingsError)
        s.updateSettings { it.copy(fontSize = 20) }
        assertEquals(broken, store.read())
    }

    @Test
    fun settingsEditsKeepComments() {
        val original = """
            {
              // my font
              "editor.fontSize": 16,
              "my.extension.flag": true, // keep me
            }
        """.trimIndent()
        val store = InMemorySettingsStore(original)
        val s = WorkbenchState(FakeWorkspaceBackend(), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, store)
        s.updateSettings { it.copy(fontSize = 18, tabSize = 2) }
        val text = store.read()!!
        assertTrue(text, text.contains("// my font"))
        assertTrue(text, text.contains("// keep me"))
        assertTrue(text, text.contains("\"editor.fontSize\": 18"))
        assertTrue(text, text.contains("\"editor.tabSize\": 2"))
        assertEquals(18, Settings.parse(text).fontSize)
        assertEquals(2, Settings.parse(text).tabSize)
    }

    @Test
    fun findAndReplace() {
        val s = state()
        s.openFile("${s.backend.root}/src/main.rs", preview = false)
        s.openFind(replace = true)
        s.find.query = "counts"
        assertEquals(8, s.findMatchesInActive().size) // includes "Counts" in the doc comment
        s.find.wholeWord = true
        assertEquals(6, s.findMatchesInActive().size) // not "word_counts"
        s.find.caseSensitive = true
        assertEquals(5, s.findMatchesInActive().size)
        assertTrue(findMatches("a(b", "(", caseSensitive = false, wholeWord = false, regex = true).isEmpty())
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
        assertEquals(true, parsed.wordWrap)
        assertEquals("Ferride Light Modern", parsed.colorTheme)
        val again = Settings.parse(parsed.toJson())
        assertEquals(parsed, again)
        assertTrue(parsed.toJson().contains("my.extension.flag"))
    }
}
