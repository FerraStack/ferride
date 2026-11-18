package dev.ferride.workbench

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ferride.core.EnvEntry
import dev.ferride.core.FerruxStatus
import dev.ferride.core.LspCancellation
import dev.ferride.core.LspCompletion
import dev.ferride.core.LspDiagnostic
import dev.ferride.core.LspListener
import dev.ferride.core.LspSession
import dev.ferride.core.LspTextEdit
import dev.ferride.core.WorkspaceKind
import dev.ferride.editor.EditorModel
import dev.ferride.editor.Sel
import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Server-initiated LSP callbacks dispatched from Rust's reader thread. */
interface LspCallbacks {
    fun onDiagnostics(path: String, version: Int?, diagnostics: List<Diagnostic>)
    fun onMessage(level: Int, message: String, show: Boolean)
    fun onClosed()
}

/** Cancellable token for blocking LSP requests (`completion`, `hover`, `formatting`). */
interface LspCancellationToken {
    fun cancel()
}

/** Active language server session over stdio pipes or socket fds. */
interface LspSessionHandle : Closeable {
    fun initialize(rootPath: String?): String
    fun didOpen(path: String, languageId: String, version: Int, text: String)
    fun didChangeFull(path: String, version: Int, text: String)
    fun didChangeDocument(path: String, version: Int, model: EditorModel): Boolean
    fun didSave(path: String)
    fun didClose(path: String)
    fun completion(path: String, line: Int, column: Int, cancel: LspCancellationToken?): List<LspCompletion>
    fun hover(path: String, line: Int, column: Int, cancel: LspCancellationToken?): String?
    fun formatting(path: String, tabSize: Int, insertSpaces: Boolean, cancel: LspCancellationToken?): List<LspTextEdit>
    fun supportsFormatting(): Boolean
    fun completionTriggerCharacters(): List<String>
    fun shutdown()
    fun isClosed(): Boolean
    override fun close()
}

/** Spawns language server sessions (backed by Rust's [LspSession] in production, fake in tests). */
interface LspLauncher {
    fun spawn(argv: List<String>, env: Map<String, String>, cwd: String?, callbacks: LspCallbacks): LspSessionHandle
    fun newCancellation(): LspCancellationToken
}

object RustLspLauncher : LspLauncher {
    override fun spawn(argv: List<String>, env: Map<String, String>, cwd: String?, callbacks: LspCallbacks): LspSessionHandle {
        val entries = env.map { (k, v) -> EnvEntry(k, v) }
        val listener = object : LspListener {
            override fun onDiagnostics(path: String, version: Int?, diagnostics: List<LspDiagnostic>) {
                val mapped = diagnostics.map { d ->
                    Diagnostic(
                        line = d.startLine,
                        startColumn = d.startColumn,
                        endLine = d.endLine,
                        endColumn = d.endColumn,
                        severity = d.severity,
                        message = d.message,
                        source = d.source,
                    )
                }
                callbacks.onDiagnostics(path, version, mapped)
            }

            override fun onMessage(level: Int, message: String, show: Boolean) {
                callbacks.onMessage(level, message, show)
            }

            override fun onClosed() {
                callbacks.onClosed()
            }
        }
        val session = LspSession.spawn(argv, entries, cwd, listener)
        return object : LspSessionHandle {
            override fun initialize(rootPath: String?): String = session.initialize(rootPath)
            override fun didOpen(path: String, languageId: String, version: Int, text: String) =
                session.didOpen(path, languageId, version, text)

            override fun didChangeFull(path: String, version: Int, text: String) =
                session.didChangeFull(path, version, text)

            override fun didChangeDocument(path: String, version: Int, model: EditorModel): Boolean {
                val doc = model.nativeDocument ?: run {
                    session.didChangeFull(path, version, model.text())
                    return false
                }
                return session.didChangeDocument(path, version, doc)
            }

            override fun didSave(path: String) = session.didSave(path)
            override fun didClose(path: String) = session.didClose(path)

            override fun completion(path: String, line: Int, column: Int, cancel: LspCancellationToken?): List<LspCompletion> =
                session.completion(path, line, column, (cancel as? RustCancellationToken)?.token)

            override fun hover(path: String, line: Int, column: Int, cancel: LspCancellationToken?): String? =
                session.hover(path, line, column, (cancel as? RustCancellationToken)?.token)

            override fun formatting(
                path: String,
                tabSize: Int,
                insertSpaces: Boolean,
                cancel: LspCancellationToken?,
            ): List<LspTextEdit> = session.formatting(path, tabSize, insertSpaces, (cancel as? RustCancellationToken)?.token)

            override fun supportsFormatting(): Boolean = session.supportsFormatting()
            override fun completionTriggerCharacters(): List<String> = session.completionTriggerCharacters()
            override fun shutdown() = session.shutdown()
            override fun isClosed(): Boolean = session.isClosed()
            override fun close() = session.close()
        }
    }

    override fun newCancellation(): LspCancellationToken = RustCancellationToken(LspCancellation())

    private class RustCancellationToken(val token: LspCancellation) : LspCancellationToken {
        override fun cancel() = token.cancel()
    }
}

sealed interface LspServerStatus {
    data object Stopped : LspServerStatus
    data object Starting : LspServerStatus
    data class Ready(val serverName: String) : LspServerStatus
    data class Unavailable(val reason: String) : LspServerStatus
}

data class LspServerSpec(
    val key: String,
    val displayName: String,
    val commands: List<List<String>>,
)

data class CompletionPopupState(
    val path: String,
    val anchorOffset: Int,
    val caretOffset: Int,
    val prefix: String,
    val items: List<LspCompletion>,
    val selectedIndex: Int = 0,
    val loading: Boolean = false,
) {
    val filteredItems: List<LspCompletion>
        get() {
            val q = prefix.trim()
            if (q.isEmpty()) return items
            val starts = items.filter { it.label.startsWith(q, ignoreCase = true) }
            val contains = items.filter { !it.label.startsWith(q, ignoreCase = true) && it.label.contains(q, ignoreCase = true) }
            val fuzzy = items.filter {
                !it.label.contains(q, ignoreCase = true) && fuzzyScore(q, it.label) != null
            }
            return starts + contains + fuzzy
        }
}

data class HoverPopupState(
    val path: String,
    val offset: Int,
    val text: String,
)

/**
 * Coordinates LSP server lifecycles, document synchronization, diagnostics,
 * completion, hover and formatting across [WorkbenchState].
 */
class LspManager(
    private val state: WorkbenchState,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val launcher: LspLauncher?,
) {
    private data class OpenDoc(
        val path: String,
        val serverKey: String,
        val languageId: String,
    )

    private class ServerSlot(
        val spec: LspServerSpec,
        var generation: Int,
        var channel: Channel<suspend () -> Unit>,
        var worker: Job? = null,
        var session: LspSessionHandle? = null,
        var triggerChars: Set<String> = emptySet(),
        var supportsFormatting: Boolean = false,
    )

    val statuses = mutableStateMapOf<String, LspServerStatus>()
    var completionPopup by mutableStateOf<CompletionPopupState?>(null)
        private set
    var hoverPopup by mutableStateOf<HoverPopupState?>(null)
        private set
    var lspRevision by mutableIntStateOf(0)
        private set

    private val slots = LinkedHashMap<String, ServerSlot>()
    private val openDocs = LinkedHashMap<String, OpenDoc>()
    private val changeJobs = LinkedHashMap<String, Job>()

    private var completionCancel: LspCancellationToken? = null
    private var completionGen = 0
    private var hoverCancel: LspCancellationToken? = null
    private var hoverGen = 0
    private var formatCancel: LspCancellationToken? = null
    private var formatGen = 0
    private var nextGeneration = 1

    fun serverSpecFor(languageId: String?): LspServerSpec? {
        if (languageId.isNullOrBlank() || languageId == Languages.PLAIN_TEXT_ID) return null
        val custom = state.settings.lspServers[languageId]?.takeIf { it.isNotEmpty() }
        if (custom != null) {
            return LspServerSpec(
                key = "custom:$languageId:${custom.joinToString("\u0000")}",
                displayName = custom.first(),
                commands = listOf(custom),
            )
        }
        val info = Languages.get(languageId) ?: return null
        if (info.serverCommands.isEmpty()) return null
        return LspServerSpec(key = info.server, displayName = info.server, commands = info.serverCommands)
    }

    fun statusForLanguage(languageId: String?): LspServerStatus {
        val spec = serverSpecFor(languageId) ?: return LspServerStatus.Stopped
        return statuses[spec.key] ?: LspServerStatus.Stopped
    }

    fun statusSummary(tab: EditorTab?): String {
        if (!state.settings.lspEnabled) return "disabled"
        if (state.ferrux !is FerruxStatus.Ready || launcher == null) return "off"
        if (!isEligibleTab(tab)) return "off"
        val spec = serverSpecFor(tab?.model?.takeIf { !it.isClosed }?.language) ?: return "off"
        return when (val s = statuses[spec.key]) {
            LspServerStatus.Starting -> "starting…"
            is LspServerStatus.Ready -> s.serverName.ifBlank { spec.displayName }
            is LspServerStatus.Unavailable -> "unavailable"
            LspServerStatus.Stopped, null -> "off"
        }
    }

    fun isEligibleTab(tab: EditorTab?): Boolean =
        tab != null && tab.kind == TabKind.File && tab.path.startsWith('/')

    private fun canRun(): Boolean =
        launcher != null && state.settings.lspEnabled && state.ferrux is FerruxStatus.Ready

    fun onTabOpened(tab: EditorTab) {
        if (!canRun() || !isEligibleTab(tab)) return
        val model = tab.model?.takeIf { !it.isClosed } ?: return
        val lang = model.language ?: return
        val spec = serverSpecFor(lang) ?: return
        if (statuses[spec.key] is LspServerStatus.Unavailable) return

        val prev = openDocs[tab.path]
        if (prev != null && prev.serverKey == spec.key && prev.languageId == lang) return
        if (prev != null) closeTrackedDoc(tab.path, model)

        openTrackedDoc(tab, model, lang, spec)
    }

    fun onTabClosed(tab: EditorTab) {
        if (completionPopup?.path == tab.path) dismissCompletion()
        if (hoverPopup?.path == tab.path) dismissHover()
        closeTrackedDoc(tab.path, tab.model)
    }

    fun onLanguageChanged(tab: EditorTab) {
        if (!isEligibleTab(tab)) return
        val model = tab.model?.takeIf { !it.isClosed } ?: return
        val newLang = model.language
        val newSpec = serverSpecFor(newLang)
        val prev = openDocs[tab.path]
        if (prev?.languageId == newLang && prev?.serverKey == newSpec?.key) return

        if (prev != null) closeTrackedDoc(tab.path, model)
        if (canRun() && newLang != null && newSpec != null && statuses[newSpec.key] !is LspServerStatus.Unavailable) {
            openTrackedDoc(tab, model, newLang, newSpec)
        }
    }

    fun onEditorChanged(tab: EditorTab, contentChanged: Boolean) {
        val model = tab.model?.takeIf { !it.isClosed }
        if (!contentChanged) {
            hoverPopup?.let { if (it.path == tab.path && model?.selection?.head != it.offset) dismissHover() }
            completionPopup?.let { popup ->
                if (popup.path == tab.path && model != null) {
                    val caret = model.selection.head
                    if (caret < popup.anchorOffset || caret > popup.caretOffset + 64) {
                        dismissCompletion()
                    }
                }
            }
            return
        }

        dismissHover()
        if (!canRun() || !isEligibleTab(tab) || model == null) {
            dismissCompletion()
            return
        }

        val doc = openDocs[tab.path]
        if (doc != null) {
            scheduleDidChange(tab, doc)
        }

        val caret = model.selection.head
        val popup = completionPopup
        if (popup != null && popup.path == tab.path) {
            if (caret < popup.anchorOffset) {
                dismissCompletion()
            } else {
                val typedPrefix = model.textRange(popup.anchorOffset, caret)
                if (typedPrefix.any { !isWordChar(it) }) {
                    dismissCompletion()
                } else {
                    completionPopup = popup.copy(caretOffset = caret, prefix = typedPrefix, selectedIndex = 0)
                }
            }
        }

        if (doc != null && caret > 0) {
            val slot = slots[doc.serverKey]
            val lastChar = model.textRange(caret - 1, caret)
            if (slot != null && lastChar in slot.triggerChars) {
                triggerCompletion(tab)
            }
        }
    }

    fun onSaved(tab: EditorTab) {
        if (!canRun() || !isEligibleTab(tab)) return
        val doc = openDocs[tab.path] ?: return
        val slot = slots[doc.serverKey] ?: return
        flushDidChange(tab, doc, slot)
        enqueue(slot) { session ->
            runCatching { session.didSave(tab.path) }
        }
    }

    fun syncEnvironment(tabs: List<EditorTab>) {
        if (!canRun()) {
            closeAll()
            return
        }
        // Close docs whose language/override spec changed or no longer eligible.
        val byPath = tabs.associateBy { it.path }
        for ((path, tracked) in openDocs.toList()) {
            val tab = byPath[path]
            val model = tab?.model?.takeIf { !it.isClosed }
            val spec = if (tab != null && isEligibleTab(tab)) serverSpecFor(model?.language) else null
            if (tab == null || model == null || spec == null || spec.key != tracked.serverKey || model.language != tracked.languageId) {
                closeTrackedDoc(path, model)
            }
        }
        tabs.forEach { onTabOpened(it) }
    }

    fun restartLanguage(languageId: String?, tabs: List<EditorTab>) {
        val spec = serverSpecFor(languageId) ?: return
        stopServerKey(spec.key)
        statuses.remove(spec.key)
        syncEnvironment(tabs)
    }

    fun stopLanguage(languageId: String?) {
        val spec = serverSpecFor(languageId) ?: return
        stopServerKey(spec.key)
    }

    fun restartActive(tab: EditorTab?, tabs: List<EditorTab>) {
        val lang = tab?.model?.takeIf { !it.isClosed }?.language
        if (lang != null) {
            restartLanguage(lang, tabs)
        } else {
            val keys = slots.keys.toList()
            keys.forEach(::stopServerKey)
            statuses.clear()
            syncEnvironment(tabs)
        }
    }

    fun triggerCompletion(tab: EditorTab? = state.activeTab) {
        if (!canRun() || !isEligibleTab(tab)) return
        val active = tab ?: return
        val model = active.model?.takeIf { !it.isClosed } ?: return
        val doc = ensureTracked(active, model) ?: return
        val slot = slots[doc.serverKey] ?: return

        val caret = model.selection.head
        val anchor = wordStartBefore(model, caret)
        val prefix = model.textRange(anchor, caret)
        val line = model.lineOfOffset(caret)
        val col = caret - model.lineStart(line)

        completionCancel?.cancel()
        val cancel = launcher?.newCancellation()
        completionCancel = cancel
        val gen = ++completionGen

        val existing = completionPopup?.takeIf { it.path == active.path && it.anchorOffset == anchor }
        completionPopup = CompletionPopupState(
            path = active.path,
            anchorOffset = anchor,
            caretOffset = caret,
            prefix = prefix,
            items = existing?.items.orEmpty(),
            selectedIndex = 0,
            loading = true,
        )

        val barrier = flushAndBarrier(active, doc, slot)
        scope.launch {
            val result = withContext(io) {
                runCatching {
                    val session = barrier.await() ?: return@runCatching emptyList()
                    session.completion(active.path, line, col, cancel)
                }
            }
            if (gen != completionGen) return@launch
            result.onSuccess { items ->
                val currentModel = model.takeIf { !it.isClosed }
                val currentCaret = currentModel?.selection?.head ?: caret
                if (state.activeTab !== active || currentCaret < anchor) {
                    dismissCompletion()
                    return@onSuccess
                }
                val latestPrefix = currentModel?.textRange(anchor, currentCaret) ?: prefix
                if (items.isEmpty() || latestPrefix.any { !isWordChar(it) }) {
                    dismissCompletion()
                } else {
                    completionPopup = CompletionPopupState(
                        path = active.path,
                        anchorOffset = anchor,
                        caretOffset = currentCaret,
                        prefix = latestPrefix,
                        items = items,
                        selectedIndex = 0,
                        loading = false,
                    )
                }
            }.onFailure {
                if (completionPopup?.items.isNullOrEmpty()) dismissCompletion()
                else completionPopup = completionPopup?.copy(loading = false)
            }
        }
    }

    fun moveCompletionSelection(delta: Int) {
        val popup = completionPopup ?: return
        val list = popup.filteredItems
        if (list.isEmpty()) return
        val next = (popup.selectedIndex + delta).mod(list.size)
        completionPopup = popup.copy(selectedIndex = next)
    }

    fun acceptCompletion(item: LspCompletion? = null): Boolean {
        val popup = completionPopup ?: return false
        val filtered = popup.filteredItems
        val chosen = item ?: filtered.getOrNull(popup.selectedIndex) ?: filtered.firstOrNull()
        if (chosen == null) {
            dismissCompletion()
            return false
        }
        val tab = state.tabs.firstOrNull { it.path == popup.path } ?: return false
        val model = tab.model?.takeIf { !it.isClosed } ?: return false
        val end = model.selection.head.coerceIn(popup.anchorOffset, model.length)
        val start = popup.anchorOffset.coerceIn(0, end)
        dismissCompletion()
        val editor = state.editor
        if (editor != null && state.activeTab === tab) {
            editor.replaceRange(start, end, chosen.insertText)
        } else {
            model.sealUndoGroup()
            val newPos = model.replace(start, end, chosen.insertText, false)
            model.selection = Sel.caret(newPos)
            model.sealUndoGroup()
            state.onEditorChanged(tab, true)
        }
        return true
    }

    fun dismissCompletion() {
        completionGen++
        completionCancel?.cancel()
        completionCancel = null
        completionPopup = null
    }

    fun requestHover(tab: EditorTab? = state.activeTab) {
        if (!canRun() || !isEligibleTab(tab)) {
            state.message = "Hover requires an active language server (Ferrux)"
            return
        }
        val active = tab ?: return
        val model = active.model?.takeIf { !it.isClosed } ?: return
        val doc = ensureTracked(active, model) ?: run {
            state.message = "No language server configured for ${Languages.displayName(model.language)}"
            return
        }
        val slot = slots[doc.serverKey] ?: return

        val offset = model.selection.head
        val line = model.lineOfOffset(offset)
        val col = offset - model.lineStart(line)

        hoverCancel?.cancel()
        val cancel = launcher?.newCancellation()
        hoverCancel = cancel
        val gen = ++hoverGen

        val barrier = flushAndBarrier(active, doc, slot)
        scope.launch {
            val result = withContext(io) {
                runCatching {
                    val session = barrier.await() ?: return@runCatching null
                    session.hover(active.path, line, col, cancel)
                }
            }
            if (gen != hoverGen) return@launch
            result.onSuccess { text ->
                val clean = text?.trim()?.takeIf { it.isNotEmpty() }
                if (clean != null && state.activeTab === active) {
                    hoverPopup = HoverPopupState(active.path, offset, clean)
                } else {
                    hoverPopup = null
                }
            }.onFailure { err ->
                state.log("[LSP ${slot.spec.displayName}] hover failed: ${err.message}")
            }
        }
    }

    fun dismissHover() {
        hoverGen++
        hoverCancel?.cancel()
        hoverCancel = null
        hoverPopup = null
    }

    fun formatDocument(tab: EditorTab? = state.activeTab) {
        val active = tab ?: return
        val model = active.model?.takeIf { !it.isClosed } ?: return
        if (!state.settings.lspEnabled) {
            state.message = "LSP is disabled in Settings"
            return
        }
        if (state.ferrux !is FerruxStatus.Ready) {
            state.message = "Formatting needs a language server (requires Ferrux)"
            return
        }
        if (!isEligibleTab(active)) {
            state.message = "Formatting requires a file with a real path"
            return
        }
        val doc = ensureTracked(active, model) ?: run {
            state.message = "No language server configured for ${Languages.displayName(model.language)}"
            return
        }
        val slot = slots[doc.serverKey] ?: return

        formatCancel?.cancel()
        val cancel = launcher?.newCancellation()
        formatCancel = cancel
        val gen = ++formatGen
        val startVersion = model.version
        val tabSize = state.settings.tabSize
        val insertSpaces = state.settings.insertSpaces

        val barrier = flushAndBarrier(active, doc, slot)
        scope.launch {
            val result = withContext(io) {
                runCatching {
                    val session = barrier.await() ?: error("Language server unavailable")
                    if (!session.supportsFormatting()) {
                        error("${slot.spec.displayName} does not support document formatting")
                    }
                    session.formatting(active.path, tabSize, insertSpaces, cancel)
                }
            }
            if (gen != formatGen) return@launch
            result.onSuccess { edits ->
                if (model.isClosed || model.version != startVersion) return@onSuccess
                if (edits.isEmpty()) return@onSuccess
                applyFormattingEdits(active, model, edits)
            }.onFailure { err ->
                state.message = err.message ?: "Formatting failed"
                state.log("[LSP ${slot.spec.displayName}] formatting failed: ${err.message}")
            }
        }
    }

    private fun applyFormattingEdits(tab: EditorTab, model: EditorModel, edits: List<LspTextEdit>) {
        // Convert (line, col) to UTF-16 offsets against the pre-edit document and apply back-to-front.
        val resolved = edits.map { e ->
            val start = model.offsetAt(e.startLine.coerceAtLeast(0), e.startColumn.coerceAtLeast(0))
            val end = model.offsetAt(e.endLine.coerceAtLeast(0), e.endColumn.coerceAtLeast(0))
            Triple(minOf(start, end), maxOf(start, end), e.newText)
        }.sortedWith(compareByDescending<Triple<Int, Int, String>> { it.first }.thenByDescending { it.second })

        val editor = state.editor
        if (editor != null && state.activeTab === tab) {
            resolved.forEach { (start, end, text) -> editor.replaceRange(start, end, text) }
        } else {
            model.sealUndoGroup()
            resolved.forEach { (start, end, text) -> model.replace(start, end, text, false) }
            model.sealUndoGroup()
            state.onEditorChanged(tab, true)
        }
    }

    fun closeAll() {
        dismissCompletion()
        dismissHover()
        formatGen++
        formatCancel?.cancel()
        changeJobs.values.forEach { it.cancel() }
        changeJobs.clear()
        openDocs.clear()
        state.tabs.forEach { it.model?.takeIf { m -> !m.isClosed }?.enableLspSync(false) }
        val existing = slots.values.toList()
        slots.clear()
        statuses.clear()
        state.diagnostics.clear()
        lspRevision++
        if (existing.isEmpty()) return
        scope.launch(io) {
            existing.forEach { slot ->
                slot.generation++
                slot.channel.close()
                slot.worker?.cancel()
                val s = slot.session
                slot.session = null
                if (s != null) {
                    runCatching { s.shutdown() }
                    runCatching { s.close() }
                }
            }
        }
    }

    /** Synchronous teardown when lifecycle [scope] is being cancelled. */
    fun closeNow() {
        completionGen++
        completionCancel?.cancel()
        hoverGen++
        hoverCancel?.cancel()
        formatGen++
        formatCancel?.cancel()
        changeJobs.values.forEach { it.cancel() }
        changeJobs.clear()
        openDocs.clear()
        val existing = slots.values.toList()
        slots.clear()
        statuses.clear()
        existing.forEach { slot ->
            slot.generation++
            slot.channel.close()
            slot.worker?.cancel()
            val s = slot.session
            slot.session = null
            if (s != null) {
                runCatching { s.close() }
            }
        }
    }

    private fun stopServerKey(key: String) {
        val docs = openDocs.values.filter { it.serverKey == key }.map { it.path }
        docs.forEach { path ->
            changeJobs.remove(path)?.cancel()
            openDocs.remove(path)
            state.diagnostics.remove(path)
            state.tabs.firstOrNull { it.path == path }?.model?.takeIf { !it.isClosed }?.enableLspSync(false)
        }
        val slot = slots.remove(key) ?: return
        statuses[key] = LspServerStatus.Stopped
        lspRevision++
        scope.launch(io) {
            slot.generation++
            slot.channel.close()
            slot.worker?.cancel()
            val s = slot.session
            slot.session = null
            if (s != null) {
                runCatching { s.shutdown() }
                runCatching { s.close() }
            }
        }
    }

    private fun ensureTracked(tab: EditorTab, model: EditorModel): OpenDoc? {
        openDocs[tab.path]?.let { return it }
        val lang = model.language ?: return null
        val spec = serverSpecFor(lang) ?: return null
        return openTrackedDoc(tab, model, lang, spec)
    }

    private fun openTrackedDoc(tab: EditorTab, model: EditorModel, languageId: String, spec: LspServerSpec): OpenDoc {
        val slot = getOrCreateSlot(spec)
        val text = model.text()
        val version = model.version.toInt()
        model.enableLspSync(true)
        val doc = OpenDoc(tab.path, spec.key, languageId)
        openDocs[tab.path] = doc
        enqueue(slot) { session ->
            runCatching { session.didOpen(tab.path, languageId, version, text) }
        }
        return doc
    }

    private fun closeTrackedDoc(path: String, model: EditorModel?) {
        changeJobs.remove(path)?.cancel()
        val tracked = openDocs.remove(path) ?: return
        model?.takeIf { !it.isClosed }?.enableLspSync(false)
        state.diagnostics.remove(path)
        val slot = slots[tracked.serverKey] ?: return
        enqueue(slot) { session ->
            runCatching { session.didClose(path) }
        }
    }

    private fun scheduleDidChange(tab: EditorTab, doc: OpenDoc) {
        changeJobs.remove(tab.path)?.cancel()
        val slot = slots[doc.serverKey] ?: return
        val model = tab.model?.takeIf { !it.isClosed } ?: return
        changeJobs[tab.path] = scope.launch {
            delay(CHANGE_DEBOUNCE_MS)
            changeJobs.remove(tab.path)
            if (model.isClosed || openDocs[tab.path] !== doc) return@launch
            val version = model.version.toInt()
            enqueue(slot) { session ->
                if (!model.isClosed) {
                    runCatching { session.didChangeDocument(tab.path, version, model) }
                }
            }
        }
    }

    private fun flushDidChange(tab: EditorTab, doc: OpenDoc, slot: ServerSlot) {
        val pending = changeJobs.remove(tab.path)
        if (pending != null) {
            pending.cancel()
            val model = tab.model?.takeIf { !it.isClosed } ?: return
            val version = model.version.toInt()
            enqueue(slot) { session ->
                if (!model.isClosed) {
                    runCatching { session.didChangeDocument(tab.path, version, model) }
                }
            }
        }
    }

    private fun flushAndBarrier(tab: EditorTab, doc: OpenDoc, slot: ServerSlot): CompletableDeferred<LspSessionHandle?> {
        flushDidChange(tab, doc, slot)
        val deferred = CompletableDeferred<LspSessionHandle?>()
        val sent = slot.channel.trySend {
            deferred.complete(slot.session)
        }
        if (sent.isFailure) deferred.complete(slot.session)
        return deferred
    }

    private fun getOrCreateSlot(spec: LspServerSpec): ServerSlot {
        slots[spec.key]?.let { return it }
        val gen = nextGeneration++
        val channel = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        val slot = ServerSlot(spec = spec, generation = gen, channel = channel)
        slots[spec.key] = slot
        statuses[spec.key] = LspServerStatus.Starting
        lspRevision++

        val cwd = if (state.backend.kind == WorkspaceKind.FERRUX && state.backend.root.startsWith('/')) {
            state.backend.root
        } else {
            null
        }
        val rootPath = state.backend.root.takeIf { it.startsWith('/') }
        val l = launcher!!
        val callbacks = object : LspCallbacks {
            override fun onDiagnostics(path: String, version: Int?, diagnostics: List<Diagnostic>) {
                scope.launch {
                    if (slots[spec.key] !== slot || slot.generation != gen) return@launch
                    if (diagnostics.isEmpty()) state.diagnostics.remove(path) else state.diagnostics[path] = diagnostics
                }
            }

            override fun onMessage(level: Int, message: String, show: Boolean) {
                scope.launch {
                    if (slots[spec.key] !== slot || slot.generation != gen) return@launch
                    state.log("[LSP ${spec.displayName}] $message")
                    if (show && level <= 2) state.message = "${spec.displayName}: $message"
                }
            }

            override fun onClosed() {
                scope.launch {
                    if (slots[spec.key] !== slot || slot.generation != gen) return@launch
                    slot.session = null
                    slots.remove(spec.key)
                    if (statuses[spec.key] !is LspServerStatus.Unavailable) {
                        statuses[spec.key] = LspServerStatus.Stopped
                    }
                    lspRevision++
                    state.log("[LSP ${spec.displayName}] server closed")
                }
            }
        }

        slot.worker = scope.launch(io) {
            var startedSession: LspSessionHandle? = null
            var serverName = ""
            var lastError: String? = null
            for (argv in spec.commands) {
                val attempt = runCatching {
                    val s = l.spawn(argv, emptyMap(), cwd, callbacks)
                    try {
                        val name = s.initialize(rootPath)
                        s to name
                    } catch (t: Throwable) {
                        runCatching { s.close() }
                        throw t
                    }
                }
                attempt.onSuccess { (s, name) ->
                    startedSession = s
                    serverName = name
                }.onFailure { err ->
                    lastError = err.message ?: "Failed to start ${argv.firstOrNull().orEmpty()}"
                }
                if (startedSession != null) break
            }

            val s = startedSession
            if (s == null || slot.generation != gen) {
                if (s != null) runCatching { s.close() }
                val reason = lastError ?: "${spec.displayName} unavailable"
                scope.launch {
                    if (slots[spec.key] === slot && slot.generation == gen) {
                        slots.remove(spec.key)
                        statuses[spec.key] = LspServerStatus.Unavailable(reason)
                        lspRevision++
                        state.log("[LSP ${spec.displayName}] unavailable: $reason")
                        // Drain openDocs pointing to this failed key so restarting later can re-open them.
                        openDocs.values.removeAll { it.serverKey == spec.key }
                    }
                }
                channel.close()
                return@launch
            }

            slot.session = s
            slot.triggerChars = runCatching { s.completionTriggerCharacters().toSet() }.getOrDefault(emptySet())
            slot.supportsFormatting = runCatching { s.supportsFormatting() }.getOrDefault(false)
            scope.launch {
                if (slots[spec.key] === slot && slot.generation == gen) {
                    statuses[spec.key] = LspServerStatus.Ready(serverName.ifBlank { spec.displayName })
                    lspRevision++
                    state.log("[LSP ${spec.displayName}] ready (${serverName.ifBlank { spec.displayName }})")
                }
            }

            for (task in channel) {
                if (slot.generation != gen || s.isClosed()) break
                task()
            }
        }

        return slot
    }

    private fun enqueue(slot: ServerSlot, block: suspend (LspSessionHandle) -> Unit) {
        slot.channel.trySend {
            val s = slot.session ?: return@trySend
            if (!s.isClosed()) block(s)
        }
    }

    private fun wordStartBefore(model: EditorModel, offset: Int): Int {
        var i = offset.coerceIn(0, model.length)
        while (i > 0) {
            val ch = model.textRange(i - 1, i).firstOrNull() ?: break
            if (!isWordChar(ch)) break
            i--
        }
        return i
    }

    companion object {
        private const val CHANGE_DEBOUNCE_MS = 150L
        private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'
    }
}
