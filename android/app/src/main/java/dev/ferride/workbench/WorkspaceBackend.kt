package dev.ferride.workbench

import dev.ferride.core.FerruxStatus
import dev.ferride.core.FileHit
import dev.ferride.core.FileNode
import dev.ferride.core.MatchRange
import dev.ferride.core.SearchHit
import dev.ferride.core.SearchReport
import dev.ferride.core.WorkspaceHandle
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
    fun listDir(path: String): List<FileNode>
    fun readFile(path: String): String
    fun writeFile(path: String, contents: String)
    fun findFiles(query: String, limit: Int): List<FileHit>
    fun searchText(query: String, regex: Boolean, caseSensitive: Boolean, wholeWord: Boolean): SearchReport
    fun createModel(text: String, fileName: String): EditorModel
    fun ferrux(): FerruxStatus
    /** Current branch from `.git/HEAD`, if the folder is a repository. */
    fun gitBranch(): String?
}

class RustWorkspaceBackend(private val handle: WorkspaceHandle) : WorkspaceBackend {
    override val root: String = handle.root()
    override val name: String = handle.name()
    override fun listDir(path: String) = handle.listDir(path)
    override fun readFile(path: String) = handle.readFile(path)
    override fun writeFile(path: String, contents: String) = handle.writeFile(path, contents)
    override fun findFiles(query: String, limit: Int) = handle.findFiles(query, limit)
    override fun searchText(query: String, regex: Boolean, caseSensitive: Boolean, wholeWord: Boolean) =
        handle.searchText(query, regex, caseSensitive, wholeWord, 2000)
    override fun createModel(text: String, fileName: String): EditorModel = RustEditorModel.create(text, fileName)
    override fun ferrux(): FerruxStatus = ferruxStatus()
    override fun gitBranch(): String? = readBranch(File(root, ".git/HEAD"))

    companion object {
        fun open(root: String) = RustWorkspaceBackend(WorkspaceHandle.open(root))
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
) : WorkspaceBackend {
    override val root: String = "/sample/$name"

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
    override fun writeFile(path: String, contents: String) {}

    override fun findFiles(query: String, limit: Int) = files.keys
        .mapNotNull { p -> fuzzyScore(query, p)?.let { FileHit("$root/$p", p, it.toLong(), emptyList()) } }
        .sortedByDescending { it.score }
        .take(limit)

    override fun searchText(query: String, regex: Boolean, caseSensitive: Boolean, wholeWord: Boolean): SearchReport {
        val hits = files.toSortedMap().flatMap { (p, text) ->
            text.lines().mapIndexedNotNull { i, line ->
                val at = line.indexOf(query, ignoreCase = !caseSensitive)
                if (query.isEmpty() || at < 0) null
                else SearchHit("$root/$p", p, i, line, listOf(MatchRange(at, at + query.length)))
            }
        }
        return SearchReport(hits, files.size, false)
    }

    override fun createModel(text: String, fileName: String): EditorModel = FakeEditorModel(text, fileName)
    override fun ferrux(): FerruxStatus = FerruxStatus.NotInstalled
    override fun gitBranch(): String? = branch
}
