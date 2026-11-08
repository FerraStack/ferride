package dev.ferride.workbench

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ferride.editor.RustEditorModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Outline symbols through the real Rust core: state helpers, `@` and `#`. */
@RunWith(AndroidJUnit4::class)
class SymbolsTest {
    private fun state() = WorkbenchState(
        FakeWorkspaceBackend(
            initialFiles = mapOf("src/main.rs" to LanguageModeTest.sample("main.rs"), "web/app.ts" to LanguageModeTest.sample("app.ts")),
            modelFactory = RustEditorModel::create,
        ),
        CoroutineScope(Dispatchers.Unconfined),
        Dispatchers.Unconfined,
    )

    @Test
    fun outlineAndBreadcrumbChain() {
        val s = state()
        s.start("src/main.rs")
        val symbols = s.activeSymbols()
        assertEquals(
            listOf("word_counts", "Circle", "radius", "impl Circle", "UNIT", "area", "main"),
            symbols.map { it.name },
        )
        val area = symbols.indexOfFirst { it.name == "area" }
        assertEquals("impl Circle/area", s.symbolPath(symbols, area))
        // The caret inside `area` gives the breadcrumb chain impl Circle › area.
        val inside = s.activeModel!!.text().indexOf("PI *")
        assertEquals(listOf("impl Circle", "area"), s.symbolChainAt(symbols, inside).map { symbols[it].name })
        // Cached until the text changes.
        assertTrue(s.activeSymbols() === symbols)
    }

    @Test
    fun goToSymbolInEditorAndWorkspace() {
        val s = state()
        s.start("src/main.rs")
        s.openPalette(PaletteMode.Symbols)
        val all = s.paletteItems(emptyList())
        assertEquals("symbols (7)", all.first().section)
        assertEquals("impl Circle", all.first { it.label == "area" }.description)
        s.paletteQuery = "@ar"
        assertEquals(listOf("area"), s.paletteItems(emptyList()).map { it.label })

        val hits = s.backend.workspaceSymbols("visible", 10)
        assertEquals("visible", hits.first().name)
        assertEquals("web/app.ts", hits.first().relPath)
        s.openPalette(PaletteMode.WorkspaceSymbols)
        assertEquals(PaletteMode.WorkspaceSymbols, s.paletteMode())
        val rows = s.paletteItems(emptyList(), hits)
        assertEquals("web/app.ts", rows.first().description)
    }
}
