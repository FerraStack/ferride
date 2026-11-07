package dev.ferride.scm

import dev.ferride.core.GitException
import dev.ferride.core.ScmBranch
import dev.ferride.core.ScmCancel
import dev.ferride.core.ScmChange
import dev.ferride.core.ScmCommit
import dev.ferride.core.ScmCredential
import dev.ferride.core.ScmFileDiff
import dev.ferride.core.ScmGroup
import dev.ferride.core.ScmHead
import dev.ferride.core.ScmHunk
import dev.ferride.core.ScmLineChange
import dev.ferride.core.ScmNetworkListener
import dev.ferride.core.ScmRefKind
import dev.ferride.core.ScmRefLabel
import dev.ferride.core.ScmRepository
import dev.ferride.core.scmClone
import dev.ferride.core.scmDiscover
import dev.ferride.core.scmInit
import dev.ferride.core.scmLineChanges

/**
 * Network callbacks of one operation: credentials (may block while the user types a token) and progress.
 * Called on the operation's IO thread.
 */
interface NetworkCallbacks {
    fun credentials(url: String): ScmCredential?
    fun approved(url: String) {}
    fun rejected(url: String) {}
    fun progress(phase: String, current: Long, total: Long?) {}
}

/** Cancels a network operation; backed by the core's flag once a real repository uses it. */
class CancelSignal {
    @Volatile var cancelled = false
        private set
    private var native: ScmCancel? = null

    fun cancel() {
        cancelled = true
        synchronized(this) { native }?.cancel()
    }

    /** The core's cancel token (created on first use, so fakes never load the native library). */
    @Synchronized
    internal fun token(): ScmCancel = native ?: ScmCancel().also {
        native = it
        if (cancelled) it.cancel()
    }
}

/**
 * One git repository. Mirrors the core's `ScmRepository`; every call blocks (use the IO dispatcher).
 * Failures are [GitException]s with VS Code's wording.
 */
interface ScmRepo {
    val root: String
    fun status(): List<ScmChange>
    fun head(): ScmHead
    fun branches(): List<ScmBranch>
    fun stage(paths: List<String>)
    fun stageAll()
    fun unstage(paths: List<String>)
    fun unstageAll()
    fun discard(paths: List<String>)
    fun discardAll()
    fun stageHunks(path: String, hunks: List<ScmHunk>)
    fun unstageHunks(path: String, hunks: List<ScmHunk>)
    fun discardHunks(path: String, hunks: List<ScmHunk>)
    fun diff(path: String, staged: Boolean): ScmFileDiff
    fun quickDiffOriginal(path: String): String?
    fun commit(message: String, name: String?, email: String?, amend: Boolean, stageAllIfEmpty: Boolean): String
    fun log(start: String?, allRefs: Boolean, skip: Int, limit: Int): List<ScmCommit>
    fun createBranch(name: String, start: String?, checkout: Boolean)
    fun checkout(name: String)
    fun remotes(): List<String>
    fun fetch(callbacks: NetworkCallbacks, cancel: CancelSignal)
    fun pull(callbacks: NetworkCallbacks, cancel: CancelSignal)
    fun push(setUpstream: Boolean, force: Boolean, callbacks: NetworkCallbacks, cancel: CancelSignal)
}

/** Finding, creating and cloning repositories. */
interface ScmProvider {
    /** The repository containing [path] (a real directory), or null. */
    fun discover(path: String): ScmRepo?
    fun init(path: String): ScmRepo
    fun clone(url: String, dest: String, callbacks: NetworkCallbacks, cancel: CancelSignal): ScmRepo
    /** Gutter decorations of [modified] against [original]. */
    fun lineChanges(original: String, modified: String): List<ScmLineChange>
}

private class Listener(private val callbacks: NetworkCallbacks) : ScmNetworkListener {
    override fun credentials(url: String): ScmCredential? = callbacks.credentials(url)
    override fun credentialsApproved(url: String) = callbacks.approved(url)
    override fun credentialsRejected(url: String) = callbacks.rejected(url)
    override fun progress(phase: String, current: Long, total: Long?) = callbacks.progress(phase, current, total)
}

class RustScmRepo(private val repo: ScmRepository) : ScmRepo {
    override val root: String = repo.root()
    override fun status() = repo.status()
    override fun head() = repo.head()
    override fun branches() = repo.branches()
    override fun stage(paths: List<String>) = repo.stage(paths)
    override fun stageAll() = repo.stageAll()
    override fun unstage(paths: List<String>) = repo.unstage(paths)
    override fun unstageAll() = repo.unstageAll()
    override fun discard(paths: List<String>) = repo.discard(paths)
    override fun discardAll() = repo.discardAll()
    override fun stageHunks(path: String, hunks: List<ScmHunk>) = repo.stageHunks(path, hunks)
    override fun unstageHunks(path: String, hunks: List<ScmHunk>) = repo.unstageHunks(path, hunks)
    override fun discardHunks(path: String, hunks: List<ScmHunk>) = repo.discardHunks(path, hunks)
    override fun diff(path: String, staged: Boolean) = repo.diff(path, staged)
    override fun quickDiffOriginal(path: String) = repo.quickDiffOriginal(path)
    override fun commit(message: String, name: String?, email: String?, amend: Boolean, stageAllIfEmpty: Boolean) =
        repo.commit(message, name, email, amend, stageAllIfEmpty)
    override fun log(start: String?, allRefs: Boolean, skip: Int, limit: Int) = repo.log(start, allRefs, skip, limit)
    override fun createBranch(name: String, start: String?, checkout: Boolean) = repo.createBranch(name, start, checkout)
    override fun checkout(name: String) = repo.checkout(name)
    override fun remotes() = repo.remotes()
    override fun fetch(callbacks: NetworkCallbacks, cancel: CancelSignal) = repo.fetch(Listener(callbacks), cancel.token())
    override fun pull(callbacks: NetworkCallbacks, cancel: CancelSignal) = repo.pull(Listener(callbacks), cancel.token())
    override fun push(setUpstream: Boolean, force: Boolean, callbacks: NetworkCallbacks, cancel: CancelSignal) =
        repo.push(setUpstream, force, Listener(callbacks), cancel.token())
}

object RustScmProvider : ScmProvider {
    override fun discover(path: String): ScmRepo? = scmDiscover(path)?.let(::RustScmRepo)
    override fun init(path: String): ScmRepo = RustScmRepo(scmInit(path))
    override fun clone(url: String, dest: String, callbacks: NetworkCallbacks, cancel: CancelSignal): ScmRepo =
        RustScmRepo(scmClone(url, dest, Listener(callbacks), cancel.token()))
    override fun lineChanges(original: String, modified: String) = scmLineChanges(original, modified)
}

/** For workspaces without git support at all (tests that don't care). */
object NoScmProvider : ScmProvider {
    override fun discover(path: String): ScmRepo? = null
    override fun init(path: String): ScmRepo = throw GitException.Unsupported("Git isn't available")
    override fun clone(url: String, dest: String, callbacks: NetworkCallbacks, cancel: CancelSignal): ScmRepo =
        throw GitException.Unsupported("Git isn't available")
    override fun lineChanges(original: String, modified: String): List<ScmLineChange> = emptyList()
}

/**
 * An in-memory repository for previews and JVM screenshot tests (no native library). Status, log and
 * branches are plain lists that the operations update the way git would.
 */
class FakeScmRepo(
    override val root: String,
    changes: List<ScmChange> = SAMPLE_CHANGES,
    var commits: MutableList<ScmCommit> = SAMPLE_LOG.toMutableList(),
    var branchList: MutableList<ScmBranch> = SAMPLE_BRANCHES.toMutableList(),
    var headState: ScmHead = ScmHead("main", SAMPLE_LOG.first().id, false, "origin/main", 1, 0, "main"),
) : ScmRepo {
    val changes: MutableList<ScmChange> = changes.toMutableList()

    override fun status() = changes.toList()
    override fun head() = headState
    override fun branches() = branchList.toList()

    private fun move(paths: List<String>, from: ScmGroup, to: ScmGroup) {
        paths.forEach { p ->
            val c = changes.firstOrNull { it.path == p && it.group == from } ?: return@forEach
            changes.remove(c)
            changes.removeAll { it.path == p && it.group == to }
            if (to == ScmGroup.INDEX) {
                val letter = if (c.letter == "U") "A" else c.letter
                changes += c.copy(group = to, letter = letter, tooltip = "Index " + tooltipWord(letter))
            } else {
                val letter = if (c.letter == "A") "U" else c.letter
                changes += c.copy(group = to, letter = letter, tooltip = if (letter == "U") "Untracked" else tooltipWord(letter))
            }
        }
        changes.sortWith(compareBy({ it.group.ordinal }, { it.path }))
    }

    private fun tooltipWord(letter: String) = when (letter) {
        "A" -> "Added"; "D" -> "Deleted"; else -> "Modified"
    }

    override fun stage(paths: List<String>) = move(paths, ScmGroup.WORKING_TREE, ScmGroup.INDEX)
    override fun stageAll() = stage(changes.filter { it.group == ScmGroup.WORKING_TREE }.map { it.path })
    override fun unstage(paths: List<String>) = move(paths, ScmGroup.INDEX, ScmGroup.WORKING_TREE)
    override fun unstageAll() = unstage(changes.filter { it.group == ScmGroup.INDEX }.map { it.path })
    override fun discard(paths: List<String>) {
        changes.removeAll { it.group == ScmGroup.WORKING_TREE && it.path in paths }
    }
    override fun discardAll() = discard(changes.filter { it.group == ScmGroup.WORKING_TREE }.map { it.path })
    override fun stageHunks(path: String, hunks: List<ScmHunk>) = stage(listOf(path))
    override fun unstageHunks(path: String, hunks: List<ScmHunk>) = unstage(listOf(path))
    override fun discardHunks(path: String, hunks: List<ScmHunk>) = discard(listOf(path))
    override fun diff(path: String, staged: Boolean) = ScmFileDiff(path, "", "", true, true, false, emptyList())
    override fun quickDiffOriginal(path: String): String? = null

    override fun commit(message: String, name: String?, email: String?, amend: Boolean, stageAllIfEmpty: Boolean): String {
        if (name == null || email == null) throw GitException.MissingIdentity(MISSING_IDENTITY)
        if (stageAllIfEmpty && changes.none { it.group == ScmGroup.INDEX }) stageAll()
        if (changes.none { it.group == ScmGroup.INDEX }) throw GitException.NothingToCommit("There are no changes to commit.")
        changes.removeAll { it.group == ScmGroup.INDEX }
        val id = "%040x".format(commits.size + 0xc0ffee)
        val parent = commits.firstOrNull()
        commits.replaceAll { it.copy(refs = it.refs.filterNot { r -> r.isHead }) }
        commits.add(
            0,
            ScmCommit(
                id, id.take(7), message.lineSequence().first(), message, name, email,
                System.currentTimeMillis() / 1000, listOfNotNull(parent?.id),
                listOf(ScmRefLabel(headState.branch ?: "HEAD", ScmRefKind.LOCAL_BRANCH, true)),
            ),
        )
        headState = headState.copy(commit = id, ahead = headState.ahead + 1)
        return id
    }

    override fun log(start: String?, allRefs: Boolean, skip: Int, limit: Int) = commits.drop(skip).take(limit)
    override fun createBranch(name: String, start: String?, checkout: Boolean) {
        if (branchList.any { it.name == name }) throw GitException.Failed("A branch named '$name' already exists.")
        val tip = commits.firstOrNull()
        branchList += ScmBranch(name, false, false, tip?.shortId ?: "", tip?.summary ?: "", tip?.authorTime ?: 0L, null)
        branchList.sortWith(compareBy({ it.remote }, { it.name }))
        if (checkout) checkout(name)
    }
    override fun checkout(name: String) {
        val local = name.substringAfter('/').takeIf { name.startsWith("origin/") } ?: name
        if (branchList.none { it.name == local }) createBranch(local, name, false)
        branchList.replaceAll { it.copy(isHead = it.name == local) }
        headState = headState.copy(branch = local, label = local, upstream = null, ahead = 0, behind = 0)
    }
    override fun remotes() = listOf("origin")
    override fun fetch(callbacks: NetworkCallbacks, cancel: CancelSignal) {}
    override fun pull(callbacks: NetworkCallbacks, cancel: CancelSignal) {
        headState = headState.copy(behind = 0)
    }
    override fun push(setUpstream: Boolean, force: Boolean, callbacks: NetworkCallbacks, cancel: CancelSignal) {
        if (headState.upstream == null && !setUpstream) {
            throw GitException.NoUpstream("The branch '${headState.branch}' has no remote branch.")
        }
        headState = headState.copy(ahead = 0, upstream = headState.upstream ?: "origin/${headState.branch}")
    }

    companion object {
        const val MISSING_IDENTITY = "Make sure you configure your \"user.name\" and \"user.email\" in git."
        private const val DAY = 86_400L
        private val NOW = 1_762_560_000L // 2025-11-08

        val SAMPLE_CHANGES = listOf(
            ScmChange("src/main.rs", ScmGroup.INDEX, "M", "Index Modified", false),
            ScmChange("src/util.rs", ScmGroup.INDEX, "A", "Index Added", false),
            ScmChange("README.md", ScmGroup.WORKING_TREE, "M", "Modified", false),
            ScmChange("src/lib.rs", ScmGroup.WORKING_TREE, "M", "Modified", false),
            ScmChange("web/old.js", ScmGroup.WORKING_TREE, "D", "Deleted", true),
            ScmChange("notes/todo.md", ScmGroup.WORKING_TREE, "U", "Untracked", false),
        )

        private fun commit(n: Int, summary: String, author: String, ago: Long, vararg refs: ScmRefLabel) = ScmCommit(
            id = "%040x".format(0xabc0 + n), shortId = "%07x".format(0xabc0 + n).takeLast(7),
            summary = summary, message = "$summary\n", authorName = author, authorEmail = "${author.lowercase().replace(' ', '.')}@example.com",
            authorTime = NOW - ago, parents = emptyList(), refs = refs.toList(),
        )

        val SAMPLE_LOG = listOf(
            commit(6, "Count words with a HashMap", "Ada Lovelace", 2 * 3600L, ScmRefLabel("main", ScmRefKind.LOCAL_BRANCH, true)),
            commit(5, "Add a TypeScript front end", "Grace Hopper", DAY, ScmRefLabel("origin/main", ScmRefKind.REMOTE_BRANCH, false)),
            commit(4, "Document the CLI in README", "Ada Lovelace", 3 * DAY, ScmRefLabel("v0.2.0", ScmRefKind.TAG, false)),
            commit(3, "Split text helpers into lib.rs", "Linus Torvalds", 9 * DAY),
            commit(2, "Python port of the counter", "Grace Hopper", 40 * DAY, ScmRefLabel("feature/python", ScmRefKind.LOCAL_BRANCH, false)),
            commit(1, "Initial commit", "Ada Lovelace", 400 * DAY, ScmRefLabel("v0.1.0", ScmRefKind.TAG, false)),
        )

        val SAMPLE_BRANCHES = listOf(
            ScmBranch("feature/python", false, false, "000abc2", "Python port of the counter", NOW - 40 * DAY, null),
            ScmBranch("main", false, true, "000abc6", "Count words with a HashMap", NOW - 2 * 3600, "origin/main"),
            ScmBranch("origin/main", true, false, "000abc5", "Add a TypeScript front end", NOW - DAY, null),
            ScmBranch("origin/release", true, false, "000abc4", "Document the CLI in README", NOW - 3 * DAY, null),
        )
    }
}

/** Provider for tests and previews: [repo] for any path (or none), init/clone create fresh fakes. */
class FakeScmProvider(var repo: FakeScmRepo? = null) : ScmProvider {
    override fun discover(path: String): ScmRepo? = repo
    override fun init(path: String): ScmRepo =
        FakeScmRepo(path, emptyList(), mutableListOf(), mutableListOf(), ScmHead("main", null, false, null, 0, 0, "main"))
            .also { repo = it }
    override fun clone(url: String, dest: String, callbacks: NetworkCallbacks, cancel: CancelSignal): ScmRepo =
        FakeScmRepo(dest, emptyList())
    override fun lineChanges(original: String, modified: String): List<ScmLineChange> = emptyList()
}
