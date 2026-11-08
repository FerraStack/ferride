package dev.ferride.workbench

import dev.ferride.core.FerruxStatus
import dev.ferride.core.FileHit
import dev.ferride.core.FileNode
import dev.ferride.core.MatchRange
import dev.ferride.core.SearchHit
import dev.ferride.core.SearchCancellation
import dev.ferride.core.SearchReport
import dev.ferride.core.SafTree
import dev.ferride.core.WorkspaceHandle
import dev.ferride.core.WorkspaceKind
import dev.ferride.core.ferruxStatus
import dev.ferride.editor.EditorModel
import dev.ferride.editor.FakeEditorModel
import dev.ferride.editor.RustEditorModel
import java.io.File

/**
 * Everything the workbench needs from the core, behind an interface so the UI
 * can run on the JVM (previews, Robolectric screenshot tests) without the
 * native library. Calls may block: use them off the main thread.
 */
interface WorkspaceBackend {
    val root: String
    val name: String
    /** Where the files live: a real folder, a system-picker (SAF) folder or Ferrux. */
    val kind: WorkspaceKind
    /** ripgrep search and Git need a real directory (not SAF). */
    val supportsTextSearch: Boolean
    fun listDir(path: String): List<FileNode>
    fun readFile(path: String): String
    fun writeFile(path: String, contents: String)
    fun findFiles(query: String, limit: Int): List<FileHit>
    /** Full-text search; returns early (partial, `cancelled = true`) once [token] is cancelled. */
    fun searchText(query: String, regex: Boolean, caseSensitive: Boolean, wholeWord: Boolean, token: SearchToken = SearchToken()): SearchReport
    fun createModel(text: String, fileName: String): EditorModel
    fun ferrux(): FerruxStatus
    /** Current branch from `.git/HEAD`, if the folder is a repository. */
    fun gitBranch(): String?
    /** Copies the whole workspace into a new local folder [dest]; returns its path. */
    fun copyInto(dest: String): String
    /** Forgets cached listings under [path] (SAF), before reloading the tree. */
    fun refresh(path: String) {}
}

class RustWorkspaceBackend(private val handle: WorkspaceHandle) : WorkspaceBackend {
    override val root: String = handle.root()
    override val name: String = handle.name()
    override val kind: WorkspaceKind = handle.kind()
    override val supportsTextSearch: Boolean = handle.supportsTextSearch()
    override fun listDir(path: String) = handle.listDir(path)
    override fun readFile(path: String) = handle.readFile(path)
    override fun writeFile(path: String, contents: String) = handle.writeFile(path, contents)
    override fun findFiles(query: String, limit: Int) = handle.findFiles(query, limit)
    override fun searchText(
        query: String,
        regex: Boolean,
        caseSensitive: Boolean,
        wholeWord: Boolean,
        token: SearchToken,
    ): SearchReport = SearchCancellation().use { native ->
        token.onCancel { native.cancel() }
        // Detach before `use` frees the native object, so a late cancel() can't touch it.
        try {
            handle.searchText(query, regex, caseSensitive, wholeWord, 2000, native)
        } finally {
            token.clearListener()
        }
    }
    override fun createModel(text: String, fileName: String): EditorModel = RustEditorModel.create(text, fileName)
    override fun ferrux(): FerruxStatus = ferruxStatus()
    override fun gitBranch(): String? = handle.localRoot()?.let { readBranch(File(it, ".git/HEAD")) }
    override fun copyInto(dest: String): String = handle.copyInto(dest)
    override fun refresh(path: String) = handle.refresh(path)

    companion object {
        /** A real directory. Blocks (canonicalizes the path). */
        fun open(root: String) = RustWorkspaceBackend(WorkspaceHandle.open(root))

        /** A system-picker folder; [uri] (the tree URI) prefixes every path. Blocks (queries the provider). */
        fun openSaf(uri: String, tree: SafTree) = RustWorkspaceBackend(WorkspaceHandle.openSaf(uri, tree))
    }
}

fun readBranch(head: File): String? = runCatching {
    head.readText().trim().removePrefix("ref: refs/heads/").takeIf { it.isNotEmpty() }
}.getOrNull()

/** In-memory workspace for previews and screenshot tests. */
class FakeWorkspaceBackend(
    private val files: Map<String, String> = SampleProject.files,
    override val name: String = SampleProject.NAME,
    private val branch: String? = "main",
    /** Called inside [writeFile] (tests use it to simulate edits during a save). */
    private val onWrite: (String) -> Unit = {},
    /** Called at the start of [searchText] (tests use it to make a search slow). */
    private val onSearch: (String, SearchToken) -> Unit = { _, _ -> },
    override val kind: WorkspaceKind = WorkspaceKind.LOCAL,
    override val root: String = if (kind == WorkspaceKind.SAF) "content://sample.documents/tree/$name" else "/sample/$name",
) : WorkspaceBackend {
    override val supportsTextSearch: Boolean get() = kind != WorkspaceKind.SAF

    private fun rel(path: String) = path.removePrefix(root).trimStart('/')

    override fun listDir(path: String): List<FileNode> {
        val dir = rel(path)
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val children = files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        val dirs = children.filter { '/' in it }.map { it.substringBefore('/') }.distinct().sorted()
        val plain = children.filter { '/' !in it }.sortedBy { it.lowercase() }
        return dirs.map { node(prefix + it, true) } + plain.map { node(prefix + it, false) }
    }

    private fun node(relPath: String, isDir: Boolean) = FileNode(
        name = relPath.substringAfterLast('/'),
        path = "$root/$relPath",
        relPath = relPath,
        isDir = isDir,
        isHidden = relPath.substringAfterLast('/').startsWith("."),
    )

    override fun readFile(path: String) = files[rel(path)] ?: error("no such file: $path")
    override fun writeFile(path: String, contents: String) {
        onWrite(path)
    }

    override fun findFiles(query: String, limit: Int) = files.keys
        .mapNotNull { p -> fuzzyScore(query, p)?.let { FileHit("$root/$p", p, it.toLong(), emptyList()) } }
        .sortedByDescending { it.score }
        .take(limit)

    override fun searchText(
        query: String,
        regex: Boolean,
        caseSensitive: Boolean,
        wholeWord: Boolean,
        token: SearchToken,
    ): SearchReport {
        onSearch(query, token)
        if (token.cancelled) return SearchReport(emptyList(), 0, false, true)
        val hits = files.toSortedMap().flatMap { (p, text) ->
            text.lines().mapIndexedNotNull { i, line ->
                val at = line.indexOf(query, ignoreCase = !caseSensitive)
                if (query.isEmpty() || at < 0) null
                else SearchHit("$root/$p", p, i, line, listOf(MatchRange(at, at + query.length)))
            }
        }
        return SearchReport(hits, files.size, false, false)
    }

    override fun createModel(text: String, fileName: String): EditorModel = FakeEditorModel(text, fileName)
    override fun ferrux(): FerruxStatus = FerruxStatus.NotInstalled
    override fun gitBranch(): String? = branch
    override fun copyInto(dest: String): String = dest
}

/**
 * Cancels a running workspace search when a newer one starts (#9). Safe to
 * cancel from any thread, before or during the search.
 */
class SearchToken {
    @Volatile
    var cancelled = false
        private set
    private var listener: (() -> Unit)? = null

    // The listener runs under the lock, so clearListener() guarantees it won't run afterwards.
    @Synchronized
    fun cancel() {
        cancelled = true
        listener?.invoke()
    }

    /** Runs [block] on cancel (immediately if already cancelled). */
    @Synchronized
    fun onCancel(block: () -> Unit) {
        listener = block
        if (cancelled) block()
    }

    @Synchronized
    fun clearListener() {
        listener = null
    }
}
