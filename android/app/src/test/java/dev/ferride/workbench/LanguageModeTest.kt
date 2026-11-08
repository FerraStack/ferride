package dev.ferride.workbench

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ferride.core.StyleKind
import dev.ferride.core.languageForFile
import dev.ferride.core.supportedLanguages
import dev.ferride.editor.RustEditorModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Language detection and Change Language Mode against the real Rust core
 * (host build loaded through JNA, see `cargoHostBuild` in build.gradle.kts).
 */
@RunWith(AndroidJUnit4::class)
class LanguageModeTest {
    private fun state(files: Map<String, String>) = WorkbenchState(
        FakeWorkspaceBackend(files = files, modelFactory = RustEditorModel::create),
        CoroutineScope(Dispatchers.Unconfined),
        Dispatchers.Unconfined,
    )

    @Test
    fun catalogMatchesBundledGrammars() {
        assertEquals(supportedLanguages().sorted(), Languages.all.map { it.id }.sorted())
        assertEquals("Shell Script", Languages.displayName("shellscript"))
        assertEquals("Plain Text", Languages.displayName(null))
    }

    @Test
    fun detectionByNameExtensionAndShebang() {
        assertEquals("cpp", languageForFile("include/words.h", null))
        assertEquals("kotlin", languageForFile("build.gradle.kts", null))
        assertEquals("shellscript", languageForFile(".bashrc", null))
        assertEquals("toml", languageForFile("Cargo.lock", null))
        assertEquals("python", languageForFile("tool", "#!/usr/bin/env python3"))
        assertNull(languageForFile("notes", "hello"))
    }

    @Test
    fun everySampleGetsHighlighted() {
        for (lang in Languages.all) {
            val name = SAMPLES.getValue(lang.id)
            val text = LanguageModeTest.sample(name)
            val model = RustEditorModel.create(text, name)
            try {
                assertEquals(name, lang.id, model.language)
                val kinds = model.viewport(0, model.lineCount).flatMap { l -> l.spans.map { it.kind } }.toSet()
                assertTrue("$name has no highlighted tokens", kinds.size >= 3)
            } finally {
                model.close()
            }
        }
    }

    @Test
    fun changeLanguageModeFromTheStatusBarCommand() {
        val s = state(mapOf("bin/deploy" to "#!/bin/sh\necho \"hello\"\n"))
        s.start("bin/deploy")
        val tab = s.activeTab!!
        val model = tab.model!!
        assertEquals("shellscript", model.language)

        assertTrue(s.commands.execute(CommandIds.CHANGE_LANGUAGE_MODE))
        assertTrue(s.paletteOpen)
        assertEquals(PaletteMode.QuickPick, s.paletteMode())
        assertEquals("Select Language Mode", s.palettePlaceholder())
        val items = s.paletteItems(emptyList())
        assertEquals("Auto Detect", items.first().label)
        assertEquals("languages (identifier)", items[1].section)
        assertEquals("(shellscript) - Configured Language", items.first { it.label == "Shell Script" }.description)
        assertEquals("(plaintext)", items.first { it.label == "Plain Text" }.description)
        // Sorted by name, like VS Code.
        val names = items.drop(1).map { it.label }
        assertEquals(names.sortedBy { it.lowercase() }, names)

        // Typing filters (fuzzy) and picking switches the grammar.
        s.paletteQuery = "pyth"
        val filtered = s.paletteItems(emptyList())
        assertEquals(listOf("Python"), filtered.map { it.label })
        val revision = tab.revision
        filtered.single().run()
        assertFalse(s.paletteOpen)
        assertEquals("python", model.language)
        assertTrue(tab.revision > revision)

        // Plain Text turns highlighting off; Auto Detect brings the shebang's language back.
        s.commands.execute(CommandIds.CHANGE_LANGUAGE_MODE)
        s.paletteItems(emptyList()).first { it.label == "Plain Text" }.run()
        assertNull(model.language)
        assertTrue(model.viewport(1, 1).single().spans.isEmpty())
        s.commands.execute(CommandIds.CHANGE_LANGUAGE_MODE)
        s.paletteItems(emptyList()).first { it.label == "Auto Detect" }.run()
        assertEquals("shellscript", model.language)
        assertTrue(model.viewport(1, 1).single().spans.any { it.kind == StyleKind.STRING })

        // Opening another prefix mode leaves the quick pick.
        s.commands.execute(CommandIds.CHANGE_LANGUAGE_MODE)
        s.openPalette(PaletteMode.Commands)
        assertEquals(PaletteMode.Commands, s.paletteMode())
    }

    companion object {
        /** Text of a file in `crates/ferride-syntax/samples` (its path comes from Gradle). */
        fun sample(name: String): String =
            java.io.File(checkNotNull(System.getProperty("ferride.samples.dir")) { "ferride.samples.dir not set" }, name).readText()

        /** Language id → sample file in `crates/ferride-syntax/samples`. */
        val SAMPLES = mapOf(
            "c" to "sample.c",
            "cpp" to "sample.cpp",
            "css" to "style.css",
            "go" to "main.go",
            "html" to "index.html",
            "java" to "Main.java",
            "javascript" to "app.js",
            "json" to "package.json",
            "kotlin" to "Main.kt",
            "markdown" to "README.md",
            "python" to "stats.py",
            "rust" to "main.rs",
            "shellscript" to "script.sh",
            "sql" to "query.sql",
            "toml" to "sample.toml",
            "typescript" to "app.ts",
            "typescriptreact" to "App.tsx",
            "yaml" to "config.yaml",
        )
    }
}
