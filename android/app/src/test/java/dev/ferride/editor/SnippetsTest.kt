package dev.ferride.editor

import android.app.Activity
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/** VS Code snippet JSON: parsing, expansion (stops, mirrors, choices, variables) and tab-stop navigation. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SnippetsTest {
    @Test
    fun parsesVsCodeSnippetFiles() {
        val list = Snippets.parse(
            """
            {
              // comments are allowed
              "For loop": { "prefix": ["for", "fori"], "body": ["for ${'$'}1 in ${'$'}2 {", "\t${'$'}0", "}"], "description": "Loop" },
              "One line": { "prefix": "log", "body": "console.log(${'$'}1);" },
              "Broken": { "prefix": "x" }
            }
            """.trimIndent(),
        )
        assertEquals(listOf("For loop", "One line"), list.map { it.name })
        assertEquals(listOf("for", "fori"), list[0].prefixes)
        assertEquals("for \$1 in \$2 {\n\t\$0\n}", list[0].body)
    }

    @Test
    fun expandsPlaceholdersChoicesVariablesAndEscapes() {
        val e = Snippets.expand(
            "fn \${1:name}(\${2}) -> \${3|u32,i64|} {\n\t\$TM_FILENAME \${1} \\$ \$0\n}",
            indent = "    ",
            tab = "    ",
        ) { if (it == "TM_FILENAME") "main.rs" else null }
        assertEquals("fn name() -> u32 {\n        main.rs  \$ \n    }", e.text)
        assertEquals(listOf(1, 2, 3, 0), e.stops.map { it.index })
        val first = e.stops[0]
        assertEquals("the placeholder and its mirror", 2, first.ranges.size)
        assertEquals("name", e.text.substring(first.ranges[0].first, first.ranges[0].last + 1))
        assertEquals(listOf("u32", "i64"), e.stops[2].choices)
        // No $0: the final stop goes at the end.
        assertEquals(3, Snippets.expand("ab\${1:c}").stops.last().ranges.single().first)
    }

    @Test
    fun builtInSnippetsParse() {
        val assets = ApplicationProvider.getApplicationContext<android.content.Context>().assets
        for (lang in listOf("rust", "python", "javascript", "typescript", "markdown")) {
            val list = assets.open("snippets/$lang.json").bufferedReader().use { Snippets.parse(it.readText()) }
            assertTrue("$lang has snippets", list.size >= 5)
            list.forEach { Snippets.expand(it.body) }
        }
    }

    @Test
    fun tabMovesThroughTheStops() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = CodeEditorView(activity).apply { magnifierEnabled = false }
        val model = FakeEditorModel("\n", "a.rs")
        view.setModel(model)
        activity.setContentView(view)
        shadowOf(Looper.getMainLooper()).idle()
        view.insertSnippet(Snippets.expand("fn \${1:name}(\${2:args}) {\n\t\$0\n}", tab = "    "))
        assertEquals("fn name(args) {\n    \n}\n", model.text())
        assertEquals(Sel(3, 7), model.selection)
        model.insert("run_all", true) // typing replaces the placeholder
        view.indent() // Tab → next stop, shifted by what was typed
        assertEquals("args", model.textRange(model.selection.start, model.selection.end))
        view.indent() // Tab → $0
        assertEquals(model.text().indexOf("\n    \n") + 5, model.selection.head)
        view.indent() // the snippet ended: Tab indents again
        assertTrue(model.text().contains("\n        \n"))
    }
}
