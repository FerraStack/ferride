package dev.ferride.workbench

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ferride.core.FerruxStatus
import dev.ferride.core.LspCompletion
import dev.ferride.core.LspTextEdit
import dev.ferride.core.WorkspaceKind
import dev.ferride.editor.EditorModel
import dev.ferride.editor.RustEditorModel
import dev.ferride.editor.Sel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LspManagerTest {
    private data class SpawnCall(
        val argv: List<String>,
        val cwd: String?,
        val session: FakeLspSession,
        val callbacks: LspCallbacks,
    )

    private class FakeCancellation : LspCancellationToken {
        @Volatile var cancelled = false
        override fun cancel() {
            cancelled = true
        }
    }

    private class FakeLspSession(
        private val serverName: String = "fake-lsp",
        private val triggerChars: List<String> = listOf("."),
        private val formattingSupported: Boolean = true,
    ) : LspSessionHandle {
        val events = CopyOnWriteArrayList<String>()
        var initializedRoot: String? = null
        var nextCompletions: List<LspCompletion> = emptyList()
        var nextHover: String? = null
        var nextFormatting: List<LspTextEdit> = emptyList()
        var closed = false
        var shutdownCalled = false
        val openedLatch = CountDownLatch(1)
        val changeLatch = CountDownLatch(1)
        val saveLatch = CountDownLatch(1)
        val closeDocLatch = CountDownLatch(1)

        override fun initialize(rootPath: String?): String {
            initializedRoot = rootPath
            events += "initialize:$rootPath"
            return serverName
        }

        override fun didOpen(path: String, languageId: String, version: Int, text: String) {
            events += "didOpen:$path:$languageId:$version"
            openedLatch.countDown()
        }

        override fun didChangeFull(path: String, version: Int, text: String) {
            events += "didChangeFull:$path:$version"
            changeLatch.countDown()
        }

        override fun didChangeDocument(path: String, version: Int, model: EditorModel): Boolean {
            events += "didChange:$path:$version:${model.text()}"
            changeLatch.countDown()
            return true
        }

        override fun didSave(path: String) {
            events += "didSave:$path"
            saveLatch.countDown()
        }

        override fun didClose(path: String) {
            events += "didClose:$path"
            closeDocLatch.countDown()
        }

        override fun completion(path: String, line: Int, column: Int, cancel: LspCancellationToken?): List<LspCompletion> {
            events += "completion:$path:$line:$column"
            return nextCompletions
        }

        override fun hover(path: String, line: Int, column: Int, cancel: LspCancellationToken?): String? {
            events += "hover:$path:$line:$column"
            return nextHover
        }

        override fun formatting(path: String, tabSize: Int, insertSpaces: Boolean, cancel: LspCancellationToken?): List<LspTextEdit> {
            events += "formatting:$path:$tabSize:$insertSpaces"
            return nextFormatting
        }

        override fun supportsFormatting(): Boolean = formattingSupported
        override fun completionTriggerCharacters(): List<String> = triggerChars

        override fun shutdown() {
            shutdownCalled = true
            events += "shutdown"
        }

        override fun isClosed(): Boolean = closed

        override fun close() {
            closed = true
            events += "close"
        }
    }

    private class FakeLspLauncher(
        private val onSpawn: (argv: List<String>, callbacks: LspCallbacks) -> FakeLspSession,
    ) : LspLauncher {
        val spawns = CopyOnWriteArrayList<SpawnCall>()
        val cancellations = CopyOnWriteArrayList<FakeCancellation>()

        override fun spawn(argv: List<String>, env: Map<String, String>, cwd: String?, callbacks: LspCallbacks): LspSessionHandle {
            val s = onSpawn(argv, callbacks)
            spawns += SpawnCall(argv, cwd, s, callbacks)
            return s
        }

        override fun newCancellation(): LspCancellationToken = FakeCancellation().also { cancellations += it }
    }

    @Test
    fun settingsRoundTripAndApplyToPreserveLspServers() {
        val json = """
            {
              // comment preserved
              "ferride.lsp.enabled": false,
              "ferride.lsp.servers": {
                "rust": ["rust-analyzer", "--log-file", "/tmp/ra.log"],
                "python": "pylsp --verbose"
              }
            }
        """.trimIndent()
        val parsed = Settings.parseResult(json).getOrThrow()
        assertFalse(parsed.lspEnabled)
        assertEquals(listOf("rust-analyzer", "--log-file", "/tmp/ra.log"), parsed.lspServers["rust"])
        assertEquals(listOf("pylsp", "--verbose"), parsed.lspServers["python"])

        val serialized = Settings.parseResult(parsed.toJson()).getOrThrow()
        assertEquals(parsed.lspEnabled, serialized.lspEnabled)
        assertEquals(parsed.lspServers, serialized.lspServers)

        // Changing an unrelated setting leaves ferride.lsp.servers untouched in JSONC.
        val updated = parsed.copy(fontSize = 18)
        val edited = updated.applyTo(json, parsed)
        assertTrue(edited.contains("// comment preserved"))
        assertTrue(edited.contains("\"python\": \"pylsp --verbose\""))
    }

    @Test
    fun unavailableStatusAndCustomServerOverride() {
        val pool = Executors.newCachedThreadPool()
        val io = pool.asCoroutineDispatcher()
        try {
            val launcher = FakeLspLauncher { argv, _ ->
                if (argv == listOf("rust-analyzer")) error("missing primary binary")
                FakeLspSession(serverName = "custom-ra")
            }
            val files = linkedMapOf(
                "src/main.rs" to "fn main() {}\n",
                "notes.txt" to "plain text\n",
            )
            val backend = FakeWorkspaceBackend(
                root = "/data/user/0/dev.ferrux/files/home/proj",
                kind = WorkspaceKind.FERRUX,
                initialFiles = files,
                ferruxProvider = { FerruxStatus.Ready("Ferrux (API v1)") },
                modelFactory = RustEditorModel::create,
            )
            val state = WorkbenchState(
                backend = backend,
                scope = CoroutineScope(Dispatchers.Unconfined),
                io = io,
                lspLauncher = launcher,
            )
            state.start(openFirst = "src/main.rs")
            waitUntil { state.lsp.statusForLanguage("rust") is LspServerStatus.Unavailable }
            assertEquals("unavailable", state.lsp.statusSummary(state.activeTab))
            assertTrue(launcher.spawns.isEmpty())

            // Applying a custom lspServers override starts the configured server.
            state.updateSettings { it.copy(lspServers = mapOf("rust" to listOf("/opt/ra", "--stdio"))) }
            waitUntil { launcher.spawns.size == 1 && launcher.spawns[0].session.openedLatch.count == 0L }
            assertEquals(listOf("/opt/ra", "--stdio"), launcher.spawns.single().argv)
            assertEquals("custom-ra", state.lsp.statusSummary(state.activeTab))
            state.closeLspNow()
        } finally {
            io.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun lifecycleSyncDiagnosticsCompletionHoverAndFormatting() {
        val pool = Executors.newCachedThreadPool()
        val io = pool.asCoroutineDispatcher()
        try {
            val launcher = FakeLspLauncher { argv, _ ->
                if (argv.firstOrNull() == "typescript-language-server") error("missing tsserver")
                FakeLspSession(
                    serverName = "mock-${argv.firstOrNull()}",
                    triggerChars = listOf("."),
                    formattingSupported = true,
                ).apply {
                    nextCompletions = listOf(
                        LspCompletion("println!", "macro", "println!(\"\")"),
                        LspCompletion("print!", "macro", "print!()"),
                        LspCompletion("len", "fn(&self) -> usize", "len()"),
                    )
                    nextHover = "fn main()\nEntry point"
                    nextFormatting = listOf(
                        LspTextEdit(0, 0, 0, 12, "fn main() {}"),
                    )
                }
            }
            var ferruxState: FerruxStatus = FerruxStatus.Ready("Ferrux (API v1)")
            val files = linkedMapOf(
                "src/main.rs" to "fn  main(){}\n",
                "src/app.ts" to "const x = 1;\n",
            )
            val backend = FakeWorkspaceBackend(
                root = "/data/user/0/dev.ferrux/files/home/proj",
                kind = WorkspaceKind.FERRUX,
                initialFiles = files,
                ferruxProvider = { ferruxState },
                modelFactory = RustEditorModel::create,
            )
            val state = WorkbenchState(
                backend = backend,
                scope = CoroutineScope(Dispatchers.Unconfined),
                io = io,
                lspLauncher = launcher,
            )
            state.start(openFirst = "src/main.rs")
            waitUntil { launcher.spawns.size == 1 && launcher.spawns[0].session.openedLatch.count == 0L }

            val rsSpawn = launcher.spawns.single()
            assertEquals(listOf("rust-analyzer"), rsSpawn.argv)
            assertEquals(backend.root, rsSpawn.cwd)
            assertEquals(backend.root, rsSpawn.session.initializedRoot)
            assertEquals("mock-rust-analyzer", state.lsp.statusSummary(state.activeTab))

            // Diagnostics callback populates WorkbenchState.diagnostics and counts.
            val mainPath = "${backend.root}/src/main.rs"
            rsSpawn.callbacks.onDiagnostics(
                mainPath,
                1,
                listOf(Diagnostic(0, 0, 0, 2, 1, "syntax error", "rustc")),
            )
            waitUntil { state.errorCount == 1 }
            assertEquals(1, state.diagnostics[mainPath]?.size)

            // Hover request.
            state.activeModel!!.selection = Sel.caret(3)
            state.lsp.requestHover()
            waitUntil { state.lsp.hoverPopup != null }
            assertEquals("fn main()\nEntry point", state.lsp.hoverPopup?.text)

            // Cursor move dismisses hover.
            state.activeModel!!.selection = Sel.caret(4)
            state.onEditorChanged(state.activeTab!!, contentChanged = false)
            assertNull(state.lsp.hoverPopup)

            // Trigger completion and filter by typing prefix.
            val tab = state.activeTab!!
            val model = tab.model!!
            model.selection = Sel.caret(model.length)
            model.insert("pr", true)
            state.onEditorChanged(tab, contentChanged = true)
            state.lsp.triggerCompletion()
            waitUntil { state.lsp.completionPopup != null && !state.lsp.completionPopup!!.loading }
            assertEquals(2, state.lsp.completionPopup!!.filteredItems.size)
            assertEquals("println!", state.lsp.completionPopup!!.filteredItems[0].label)

            // Arrow down selects second filtered item and Enter accepts it.
            assertTrue(state.onEditorKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)))
            assertEquals(1, state.lsp.completionPopup!!.selectedIndex)
            assertTrue(state.onEditorKeyDown(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
            assertNull(state.lsp.completionPopup)
            assertTrue(model.text().endsWith("print!()"))

            // Debounced didChange eventually reaches the session.
            assertTrue(rsSpawn.session.changeLatch.await(5, TimeUnit.SECONDS))

            // Trigger-character auto-completion fires when typing ".".
            model.insert(".", true)
            state.onEditorChanged(tab, contentChanged = true)
            waitUntil { state.lsp.completionPopup != null && !state.lsp.completionPopup!!.loading }
            // Global key dispatch accepts completion even when the editor doesn't hold view focus
            // (e.g. right after invoking Trigger Suggest from the Command Palette), but not while
            // the Command Palette itself is open.
            state.editorFocused = false
            state.paletteOpen = true
            assertFalse(state.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
            state.paletteOpen = false
            assertTrue(state.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
            assertNull(state.lsp.completionPopup)

            // Format document replaces line 0 using LspTextEdit.
            state.lsp.formatDocument()
            waitUntil { model.text().startsWith("fn main() {}") }

            // Saving flushes and sends didSave.
            state.save(tab)
            assertTrue(rsSpawn.session.saveLatch.await(5, TimeUnit.SECONDS))

            // Candidate fallback: typescript-language-server throws -> falls back to vtsls.
            state.openFile("${backend.root}/src/app.ts", preview = false)
            waitUntil { launcher.spawns.size == 2 && launcher.spawns[1].session.openedLatch.count == 0L }
            assertEquals(listOf("vtsls", "--stdio"), launcher.spawns[1].argv)

            // Disabling LSP closes sessions and clears diagnostics.
            state.updateSettings { it.copy(lspEnabled = false) }
            waitUntil { rsSpawn.session.closed && state.diagnostics.isEmpty() }
            assertEquals("disabled", state.lsp.statusSummary(tab))

            // Re-enabling opens active tabs again.
            state.updateSettings { it.copy(lspEnabled = true) }
            waitUntil { launcher.spawns.size == 4 }

            // Ferrux disconnect tears down sessions.
            ferruxState = FerruxStatus.NotPaired
            state.refreshFerruxStatus()
            waitUntil { launcher.spawns.all { it.session.closed } }
            assertEquals("off", state.lsp.statusSummary(tab))
        } finally {
            io.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun safWorkspacePathsAreIgnoredByLsp() {
        val pool = Executors.newCachedThreadPool()
        val io = pool.asCoroutineDispatcher()
        try {
            val launcher = FakeLspLauncher { _, _ -> FakeLspSession() }
            val backend = FakeWorkspaceBackend(
                name = "Shared",
                kind = WorkspaceKind.SAF,
                root = "content://com.android.externalstorage/tree/1234",
                initialFiles = linkedMapOf("src/main.rs" to "fn main() {}\n"),
                ferruxProvider = { FerruxStatus.Ready("Ferrux (API v1)") },
                modelFactory = RustEditorModel::create,
            )
            val state = WorkbenchState(
                backend = backend,
                scope = CoroutineScope(Dispatchers.Unconfined),
                io = io,
                lspLauncher = launcher,
            )
            state.start(openFirst = "src/main.rs")
            waitUntil { state.tabs.isNotEmpty() }
            assertEquals("off", state.lsp.statusSummary(state.activeTab))
            assertTrue(launcher.spawns.isEmpty())
            state.closeLspNow()
        } finally {
            io.close()
            pool.shutdownNow()
        }
    }

    private fun waitUntil(timeoutMs: Long = 5000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("Condition not met within ${timeoutMs}ms")
            }
            Thread.sleep(10)
        }
    }
}
