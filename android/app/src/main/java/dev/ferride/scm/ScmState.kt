package dev.ferride.scm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ferride.core.GitException
import dev.ferride.core.WorkspaceKind
import dev.ferride.core.ScmChange
import dev.ferride.core.ScmCredential
import dev.ferride.core.ScmGroup
import dev.ferride.core.ScmHead
import dev.ferride.workbench.Command
import dev.ferride.workbench.WorkbenchState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CompletableFuture

/** What the Source Control view shows. */
enum class ScmMode { Loading, NoRepository, Unsupported, Ready }

/** A row of a quick pick (VS Code's quick input list). [icon] is a codicon name. */
data class QuickPickItem(
    val label: String,
    val description: String? = null,
    val detail: String? = null,
    val icon: String? = null,
    /** Separator label shown above this row ("branches", "remote branches"). */
    val section: String? = null,
    val run: () -> Unit,
)

class QuickPick(val placeholder: String, val items: List<QuickPickItem>) {
    var query by mutableStateOf("")

    fun filtered(): List<QuickPickItem> {
        val q = query.trim()
        if (q.isEmpty()) return items
        return items.filter { it.label.contains(q, ignoreCase = true) || it.description?.contains(q, ignoreCase = true) == true }
    }
}

/** VS Code's input box: [prompt] under the field, [validate] returns an error message or null. */
class InputBox(
    val prompt: String,
    val placeholder: String,
    initial: String = "",
    val validate: (String) -> String? = { null },
    val onAccept: (String) -> Unit,
) {
    var value by mutableStateOf(initial)
}

/** A modal question with buttons (VS Code's `showWarningMessage(..., { modal: true }, ...)`). */
data class Confirm(val message: String, val detail: String? = null, val buttons: List<Pair<String, () -> Unit>>)

/** "Sign in" for a remote: username + personal access token. */
class CredentialPrompt(val url: String, private val result: CompletableFuture<ScmCredential?>) {
    var username by mutableStateOf("")
    var token by mutableStateOf("")
    var remember by mutableStateOf(true)

    fun submit() {
        result.complete(ScmCredential(username.trim(), token.trim()))
    }

    fun cancel() {
        result.complete(null)
    }
}

/** A running git operation (shown as a progress bar in the view and a notification with Cancel). */
data class ScmOperation(
    val title: String,
    val phase: String? = null,
    val current: Long = 0,
    val total: Long? = null,
    val cancel: CancelSignal? = null,
)

/** Remembers credentials per host (Android Keystore in the app, memory in tests). */
interface CredentialStore {
    fun get(url: String): ScmCredential?
    fun put(url: String, credential: ScmCredential)
    fun erase(url: String)
}

class InMemoryCredentialStore : CredentialStore {
    private val map = HashMap<String, ScmCredential>()
    override fun get(url: String) = map[hostKey(url)]
    override fun put(url: String, credential: ScmCredential) {
        map[hostKey(url)] = credential
    }
    override fun erase(url: String) {
        map.remove(hostKey(url))
    }
}

/** `https://github.com/owner/repo.git` → `https://github.com` (credentials are per host, like git's default). */
fun hostKey(url: String): String {
    val scheme = url.substringBefore("://", "https")
    val host = url.substringAfter("://").substringBefore('/').substringAfterLast('@')
    return "$scheme://$host"
}

/** The repository name a clone URL implies: `https://host/owner/my-repo.git` → `my-repo`. */
fun repoNameFromUrl(url: String): String =
    url.trim().trimEnd('/').substringAfterLast('/').substringAfterLast(':').removeSuffix(".git").ifBlank { "repository" }

/**
 * Source Control state and actions, VS Code style: one repository (the workspace's), its changes in
 * "Merge Changes" / "Staged Changes" / "Changes", the commit box, branch and sync state, and the quick
 * inputs and confirmations the git commands use. All git work runs on [io].
 */
class ScmState(
    private val wb: WorkbenchState,
    private val provider: ScmProvider,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val credentialStore: CredentialStore = InMemoryCredentialStore(),
    /** Where clones go (`files/projects` in the app). */
    var projectsDir: String? = null,
) {
    var mode by mutableStateOf(ScmMode.Loading)
        private set
    var repo: ScmRepo? = null
        private set
    var changes by mutableStateOf<List<ScmChange>>(emptyList())
        private set
    var head by mutableStateOf<ScmHead?>(null)
        private set
    var commitMessage by mutableStateOf("")
    var operation by mutableStateOf<ScmOperation?>(null)
        internal set
    var quickPick by mutableStateOf<QuickPick?>(null)
    var inputBox by mutableStateOf<InputBox?>(null)
    var confirm by mutableStateOf<Confirm?>(null)
    var credentialPrompt by mutableStateOf<CredentialPrompt?>(null)
        internal set

    /** Opens a folder as the workspace (set by the app; null hides "Open" after a clone). */
    var openFolder: ((String) -> Unit)? = null

    val merge: List<ScmChange> get() = changes.filter { it.group == ScmGroup.MERGE }
    val staged: List<ScmChange> get() = changes.filter { it.group == ScmGroup.INDEX }
    val unstaged: List<ScmChange> get() = changes.filter { it.group == ScmGroup.WORKING_TREE }

    /** The Source Control badge (`scm.countBadge: all`). */
    val count: Int get() = changes.size

    /** VS Code's status bar label: `main`, plus `*` (working tree changes), `+` (staged), `!` (conflicts). */
    val headLabel: String?
        get() {
            val h = head ?: return null
            return h.label + (if (unstaged.isNotEmpty()) "*" else "") + (if (staged.isNotEmpty()) "+" else "") +
                (if (merge.isNotEmpty()) "!" else "")
        }

    private val root: String get() = wb.backend.root

    /** Git needs a real directory: system-picker (SAF) folders can't hold a usable `.git`. */
    private val rootSupported: Boolean get() = wb.backend.kind != WorkspaceKind.SAF

    /** Where clones go: [projectsDir], else the app's projects folder. */
    private val clonesDir: String? get() = projectsDir ?: wb.host?.projectsDir

    /** (Re)reads the repository of the current workspace folder; called on start and after a folder switch. */
    fun start() {
        repo = null
        changes = emptyList()
        head = null
        if (!rootSupported) {
            mode = ScmMode.Unsupported
            return
        }
        scope.launch {
            val found = withContext(io) { runCatching { provider.discover(root) } }
            repo = found.getOrNull()
            mode = if (repo != null) ScmMode.Ready else ScmMode.NoRepository
            refresh()
        }
    }

    /** Re-reads status and HEAD ("Refresh"). */
    fun refresh() {
        val r = repo ?: return
        scope.launch {
            val result = withContext(io) { runCatching { r.status() to r.head() } }
            result.onSuccess { (s, h) ->
                changes = s
                head = h
            }.onFailure { wb.message = it.message }
        }
    }

    /** Runs [work] on the IO dispatcher, then refreshes; errors become messages. */
    private fun gitOp(work: (ScmRepo) -> Unit) {
        val r = repo ?: return
        scope.launch {
            val result = withContext(io) { runCatching { work(r) } }
            result.onFailure { handleError(it) }
            refresh()
        }
    }

    private fun handleError(e: Throwable) {
        val msg = e.message ?: e.toString()
        wb.log("git: $msg")
        when (e) {
            is GitException.NoUpstream -> confirm = Confirm(
                "The branch '${head?.branch ?: ""}' has no remote branch. Would you like to publish this branch?",
                buttons = listOf("OK" to { publish() }),
            )
            is GitException.Cancelled -> {}
            else -> wb.message = msg
        }
    }

    // ----- resources ---------------------------------------------------------

    fun open(change: ScmChange) {
        if (change.deleted) {
            wb.message = "${change.path} was deleted"
            return
        }
        wb.openFile("$root/${change.path}", preview = true)
        if (!wb.sideBarDocked) wb.sideBarVisible = false
    }

    fun stage(change: ScmChange) = gitOp { it.stage(listOf(change.path)) }
    fun unstage(change: ScmChange) = gitOp { it.unstage(listOf(change.path)) }
    fun stageAll() = gitOp { it.stageAll() }
    fun unstageAll() = gitOp { it.unstageAll() }

    /** "Discard Changes" with VS Code's confirmation (untracked files are deleted). */
    fun discard(change: ScmChange) {
        val name = change.path.substringAfterLast('/')
        confirm = if (change.letter == "U") {
            Confirm(
                "Are you sure you want to DELETE $name?",
                "This is IRREVERSIBLE!\nThis file will be FOREVER LOST if you proceed.",
                listOf("Delete File" to { gitOp { it.discard(listOf(change.path)) } }),
            )
        } else {
            Confirm(
                "Are you sure you want to discard changes in $name?",
                null,
                listOf("Discard File" to { gitOp { it.discard(listOf(change.path)) } }),
            )
        }
    }

    fun discardAll() {
        val n = unstaged.size
        if (n == 0) return
        confirm = Confirm(
            "Are you sure you want to discard ALL changes in $n ${if (n == 1) "file" else "files"}?",
            "This is IRREVERSIBLE!\nYour current working set will be FOREVER LOST if you proceed.",
            listOf("Discard All $n ${if (n == 1) "File" else "Files"}" to { gitOp { it.discardAll() } }),
        )
    }

    // ----- commit ---------------------------------------------------------------

    /** "Commit" (Ctrl+Enter in the message box), with smart commit and the identity prompt. */
    fun commit(amend: Boolean = false) {
        val r = repo ?: return
        val message = commitMessage
        if (message.isBlank()) {
            wb.message = "Please provide a commit message."
            return
        }
        if (!amend && staged.isEmpty() && merge.isEmpty()) {
            if (unstaged.isEmpty()) {
                wb.message = "There are no changes to commit."
                return
            }
            if (!wb.settings.gitEnableSmartCommit) {
                confirm = Confirm(
                    "There are no staged changes to commit.",
                    "Would you like to stage all your changes and commit them directly?",
                    listOf(
                        "Yes" to { doCommit(r, message, amend, stageAll = true) },
                        "Always" to {
                            wb.updateSettings { it.copy(gitEnableSmartCommit = true) }
                            doCommit(r, message, amend, stageAll = true)
                        },
                    ),
                )
                return
            }
        }
        doCommit(r, message, amend, stageAll = wb.settings.gitEnableSmartCommit)
    }

    private fun doCommit(r: ScmRepo, message: String, amend: Boolean, stageAll: Boolean) {
        val s = wb.settings
        val name = s.gitUserName.ifBlank { null }
        val email = s.gitUserEmail.ifBlank { null }
        scope.launch {
            val result = withContext(io) { runCatching { r.commit(message, name, email, amend, stageAll) } }
            result.onSuccess {
                if (commitMessage == message) commitMessage = ""
                wb.log("git: committed ${it.take(7)}")
            }.onFailure { e ->
                if (e is GitException.MissingIdentity) askIdentity { doCommit(r, message, amend, stageAll) } else handleError(e)
            }
            refresh()
        }
    }

    /** Asks for `git.user.name` and `git.user.email`, saves them in settings.json, then runs [then]. */
    private fun askIdentity(then: () -> Unit) {
        inputBox = InputBox(
            prompt = "Your name for commits (saved as \"git.user.name\" in settings.json)",
            placeholder = "Name",
            initial = wb.settings.gitUserName,
            validate = { if (it.isBlank()) "Please provide a name." else null },
        ) { name ->
            inputBox = InputBox(
                prompt = "Your email for commits (saved as \"git.user.email\" in settings.json)",
                placeholder = "Email",
                initial = wb.settings.gitUserEmail,
                validate = { if ('@' !in it) "Please provide an email address." else null },
            ) { email ->
                wb.updateSettings { it.copy(gitUserName = name.trim(), gitUserEmail = email.trim()) }
                then()
            }
        }
    }

    // ----- branches -------------------------------------------------------------

    /** "Git: Checkout to..." (the status bar branch item). */
    fun showBranchPicker() {
        val r = repo ?: return
        scope.launch {
            val branches = withContext(io) { runCatching { r.branches() } }.getOrElse { handleError(it); return@launch }
            val items = buildList {
                add(QuickPickItem("Create new branch...", icon = "add") { promptCreateBranch() })
                val local = branches.filter { !it.remote }
                val remote = branches.filter { it.remote }
                local.forEachIndexed { i, b ->
                    add(
                        QuickPickItem(
                            b.name,
                            description = b.commit + if (b.isHead) " · current" else "",
                            detail = b.summary,
                            icon = "git-branch",
                            section = if (i == 0) "branches" else null,
                        ) { checkout(b.name) },
                    )
                }
                remote.forEachIndexed { i, b ->
                    add(
                        QuickPickItem(
                            b.name,
                            description = "Remote branch at ${b.commit}",
                            detail = b.summary,
                            icon = "cloud",
                            section = if (i == 0) "remote branches" else null,
                        ) { checkout(b.name) },
                    )
                }
            }
            quickPick = QuickPick("Select a branch or tag to checkout", items)
        }
    }

    fun promptCreateBranch() {
        inputBox = InputBox(
            prompt = "Please provide a new branch name",
            placeholder = "Branch name",
            validate = { name -> if (name.isBlank()) "Please provide a branch name." else null },
        ) { name -> gitOp { it.createBranch(name.trim(), null, true) } }
    }

    fun checkout(name: String) {
        quickPick = null
        val r = repo ?: return
        scope.launch {
            val result = withContext(io) { runCatching { r.checkout(name) } }
            result.onFailure { e ->
                if (e is GitException.WouldOverwrite) {
                    wb.message = "Your local changes would be overwritten by checkout. Commit or discard them first."
                    wb.log("git: ${e.message}")
                } else {
                    handleError(e)
                }
            }
            refresh()
            wb.loadDir(root) // files may have appeared or disappeared
        }
    }

    // ----- repositories --------------------------------------------------------

    /** "Initialize Repository" on the workspace folder. */
    fun initRepository() {
        if (!rootSupported) return
        scope.launch {
            val result = withContext(io) { runCatching { provider.init(root) } }
            result.onSuccess {
                repo = it
                mode = ScmMode.Ready
                refresh()
            }.onFailure { handleError(it) }
        }
    }

    /** "Git: Clone": asks for the URL, clones into Ferride's projects with progress, offers to open it. */
    fun promptClone() {
        val parent = clonesDir ?: run {
            wb.message = "Cloning needs a projects folder"
            return
        }
        inputBox = InputBox(
            prompt = "Provide repository URL",
            placeholder = "https://github.com/owner/repository.git",
            validate = { url ->
                val u = url.trim()
                when {
                    u.isEmpty() -> "Please provide a repository URL."
                    !u.startsWith("https://") && !u.startsWith("http://") -> "Only HTTPS URLs are supported."
                    else -> null
                }
            },
        ) { url -> clone(url.trim(), parent) }
    }

    fun clone(url: String, parent: String) {
        val dest = uniqueChild(parent, repoNameFromUrl(url))
        val cancel = CancelSignal()
        val title = "Cloning git repository '${url.removeSuffix("/")}'..."
        operation = ScmOperation(title, cancel = cancel)
        scope.launch {
            val result = withContext(io) { runCatching { provider.clone(url, dest, callbacks(title, cancel), cancel) } }
            operation = null
            result.onSuccess {
                wb.log("git: cloned $url into $dest")
                val open = openFolder ?: wb.host?.let { host -> { path: String -> openLocalFolder(host, path) } }
                confirm = if (open != null) {
                    Confirm("Would you like to open the cloned repository?", dest, listOf("Open" to { open(dest) }))
                } else {
                    Confirm("Cloned into $dest", null, emptyList())
                }
            }.onFailure { handleError(it) }
        }
    }

    /** Opens a cloned folder as the workspace (#26's folder switching). */
    private fun openLocalFolder(host: dev.ferride.workbench.WorkspaceHost, path: String) {
        scope.launch {
            withContext(io) { runCatching { host.openLocal(path) } }
                .onSuccess { wb.openWorkspace(it) }
                .onFailure { wb.message = "Couldn't open ${File(path).name}: ${it.message}" }
        }
    }

    // ----- sync ----------------------------------------------------------------

    fun pull() = network("Pulling...") { r, cb, c -> r.pull(cb, c) }
    fun fetch() = network("Fetching...") { r, cb, c -> r.fetch(cb, c) }
    fun push() = network("Pushing...") { r, cb, c -> r.push(false, false, cb, c) }
    fun publish() = network("Publishing branch...") { r, cb, c -> r.push(true, false, cb, c) }

    /** "Sync Changes": pull then push, like VS Code's sync button. */
    fun sync() {
        if (head?.upstream == null) return publish()
        network("Synchronizing...") { r, cb, c ->
            r.pull(cb, c)
            if ((r.head().ahead) > 0) r.push(false, false, cb, c)
        }
    }

    private fun network(title: String, work: (ScmRepo, NetworkCallbacks, CancelSignal) -> Unit) {
        val r = repo ?: return
        if (operation != null) return
        val cancel = CancelSignal()
        operation = ScmOperation(title, cancel = cancel)
        scope.launch {
            val result = withContext(io) { runCatching { work(r, callbacks(title, cancel), cancel) } }
            operation = null
            result.onFailure { handleError(it) }
            refresh()
            wb.loadDir(root)
        }
    }

    private fun callbacks(title: String, cancel: CancelSignal) = object : NetworkCallbacks {
        private var askedUser = false

        override fun credentials(url: String): ScmCredential? {
            credentialStore.get(url)?.let { if (!askedUser) return it }
            askedUser = true
            val future = CompletableFuture<ScmCredential?>()
            val prompt = CredentialPrompt(url, future)
            scope.launch { credentialPrompt = prompt }
            val result = runCatching { future.get() }.getOrNull()
            scope.launch { if (credentialPrompt === prompt) credentialPrompt = null }
            if (result == null) {
                cancel.cancel()
                return null
            }
            if (prompt.remember) pendingRemember = url to result
            return result
        }

        private var pendingRemember: Pair<String, ScmCredential>? = null

        override fun approved(url: String) {
            pendingRemember?.let { (u, c) -> credentialStore.put(u, c) }
        }

        override fun rejected(url: String) {
            credentialStore.erase(url)
        }

        override fun progress(phase: String, current: Long, total: Long?) {
            scope.launch {
                if (operation?.cancel === cancel) operation = ScmOperation(title, phase, current, total, cancel)
            }
        }
    }

    fun cancelOperation() {
        operation?.cancel?.cancel()
    }

    // ----- commands ---------------------------------------------------------------

    fun registerCommands() {
        val r = wb.commands
        val ready = { mode == ScmMode.Ready }
        fun cmd(id: String, title: String, enabled: () -> Boolean = ready, run: () -> Unit) =
            r.register(Command(id, title, "Git", enabled = enabled, run = run))
        cmd(GitCommandIds.COMMIT, "Commit") { commit() }
        cmd(GitCommandIds.COMMIT_AMEND, "Commit Staged (Amend)") { commit(amend = true) }
        cmd(GitCommandIds.REFRESH, "Refresh") { refresh() }
        cmd(GitCommandIds.STAGE_ALL, "Stage All Changes") { stageAll() }
        cmd(GitCommandIds.UNSTAGE_ALL, "Unstage All Changes") { unstageAll() }
        cmd(GitCommandIds.CLEAN_ALL, "Discard All Changes") { discardAll() }
        cmd(GitCommandIds.CHECKOUT, "Checkout to...") { showBranchPicker() }
        cmd(GitCommandIds.BRANCH, "Create Branch...") { promptCreateBranch() }
        cmd(GitCommandIds.PULL, "Pull") { pull() }
        cmd(GitCommandIds.PUSH, "Push") { push() }
        cmd(GitCommandIds.SYNC, "Sync") { sync() }
        cmd(GitCommandIds.FETCH, "Fetch") { fetch() }
        cmd(GitCommandIds.PUBLISH, "Publish Branch...") { publish() }
        cmd(GitCommandIds.INIT, "Initialize Repository", enabled = { mode == ScmMode.NoRepository }) { initRepository() }
        cmd(GitCommandIds.CLONE, "Clone", enabled = { clonesDir != null }) { promptClone() }
    }

    companion object {
        /** `<parent>/<name>`, or `<name>-2`, `<name>-3`… if that exists. */
        fun uniqueChild(parent: String, name: String): String {
            val safe = name.replace('/', '_').ifBlank { "repository" }
            var candidate = File(parent, safe)
            var n = 2
            while (candidate.exists()) candidate = File(parent, "$safe-${n++}")
            return candidate.path
        }
    }
}

/** VS Code's git command ids. */
object GitCommandIds {
    const val COMMIT = "git.commit"
    const val COMMIT_AMEND = "git.commitStagedAmend"
    const val REFRESH = "git.refresh"
    const val STAGE_ALL = "git.stageAll"
    const val UNSTAGE_ALL = "git.unstageAll"
    const val CLEAN_ALL = "git.cleanAll"
    const val CHECKOUT = "git.checkout"
    const val BRANCH = "git.branch"
    const val PULL = "git.pull"
    const val PUSH = "git.push"
    const val SYNC = "git.sync"
    const val FETCH = "git.fetch"
    const val PUBLISH = "git.publish"
    const val INIT = "git.init"
    const val CLONE = "git.clone"
}
