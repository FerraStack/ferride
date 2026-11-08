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
    /** Creates an empty file (and missing parents); returns its path. Fails if it exists. */
    fun createFile(path: String): String
    /** Creates a folder (and missing parents); returns its path. Fails if it exists. */
    fun createDir(path: String): String
    /** Renames/moves [from] to [to] (must not exist); returns the new path. */
    fun rename(from: String, to: String): String
    /** Deletes a file or a folder with everything in it. */
    fun delete(path: String)
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
    override fun createFile(path: String): String = handle.createFile(path)
    override fun createDir(path: String): String = handle.createDir(path)
    override fun rename(from: String, to: String): String = handle.rename(from, to)
    override fun delete(path: String) = handle.delete(path)
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
    initialFiles: Map<String, String> = SampleProject.files,
    override val name: String = SampleProject.NAME,
    private val branch: String? = "main",
    /** Called inside [writeFile] (tests use it to simulate edits during a save). */
    private val onWrite: (String) -> Unit = {},
    /** Called at the start of [searchText] (tests use it to make a search slow). */
    private val onSearch: (String, SearchToken) -> Unit = { _, _ -> },
    override val kind: WorkspaceKind = WorkspaceKind.LOCAL,
    override val root: String = if (kind == WorkspaceKind.SAF) "content://sample.documents/tree/$name" else "/sample/$name",
    /** Editor models for opened files (tests pass `RustEditorModel::create` for real highlighting). */
    private val modelFactory: (text: String, fileName: String) -> EditorModel = ::FakeEditorModel,
) : WorkspaceBackend {
    override val supportsTextSearch: Boolean get() = kind != WorkspaceKind.SAF

    /** Relative path → content; mutated by create/rename/delete. */
    private val files: MutableMap<String, String> = initialFiles.toMutableMap()
    /** Folders that exist without files in them. */
    private val emptyDirs = mutableSetOf<String>()

    private fun rel(path: String) = path.removePrefix(root).trimStart('/')

    private fun exists(rel: String) =
        rel in files || rel in emptyDirs || files.keys.any { it.startsWith("$rel/") } || emptyDirs.any { it.startsWith("$rel/") }

    override fun listDir(path: String): List<FileNode> {
        val dir = rel(path)
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val children = (files.keys + emptyDirs.map { "$it/" }).filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
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

    override fun createFile(path: String): String {
        val r = rel(path)
        check(!exists(r)) { "$r already exists" }
        files[r] = ""
        emptyDirs.removeAll { r.startsWith("$it/") }
        return "$root/$r"
    }

    override fun createDir(path: String): String {
        val r = rel(path)
        check(!exists(r)) { "$r already exists" }
        emptyDirs += r
        return "$root/$r"
    }

    override fun rename(from: String, to: String): String {
        val a = rel(from)
        val b = rel(to)
        check(exists(a)) { "$a doesn't exist" }
        check(!exists(b)) { "$b already exists" }
        fun move(p: String) = if (p == a) b else b + p.removePrefix(a)
        val moved = files.filterKeys { it == a || it.startsWith("$a/") }
        moved.keys.forEach(files::remove)
        moved.forEach { (k, v) -> files[move(k)] = v }
        val dirs = emptyDirs.filter { it == a || it.startsWith("$a/") }
        emptyDirs.removeAll(dirs.toSet())
        emptyDirs.addAll(dirs.map(::move))
        return "$root/$b"
    }

    override fun delete(path: String) {
        val r = rel(path)
        check(r.isNotEmpty()) { "can't delete the workspace root" }
        files.keys.removeAll { it == r || it.startsWith("$r/") }
        emptyDirs.removeAll { it == r || it.startsWith("$r/") }
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

    override fun createModel(text: String, fileName: String): EditorModel = modelFactory(text, fileName)
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
