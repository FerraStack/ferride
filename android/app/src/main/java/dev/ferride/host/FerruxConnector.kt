package dev.ferride.host

import android.content.Context
import android.os.Looper
import dev.ferride.core.BridgeSpawnSpec
import dev.ferride.core.FerruxEntry
import dev.ferride.core.FerruxException as CoreFerruxException
import dev.ferride.core.FerruxStatus as CoreFerruxStatus
import dev.ferride.core.FerruxTree
import dev.ferride.core.HostBridge
import dev.ferride.core.HostBridgeException
import dev.ferride.core.SpawnedFds
import dev.ferride.core.registerHostBridge
import dev.ferrux.api.FerruxInfo
import dev.ferrux.api.FileStat
import dev.ferrux.client.ConnectionState
import dev.ferrux.client.ExitStatus
import dev.ferrux.client.FerruxClient
import dev.ferrux.client.FerruxConnection
import dev.ferrux.client.FerruxException as ClientFerruxException
import dev.ferrux.client.FerruxStatus as ClientFerruxStatus
import dev.ferrux.client.ProcessSpec
import dev.ferrux.client.PtySize
import dev.ferrux.client.RawFds
import dev.ferrux.terminal.TerminalNative
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface FerruxClientApi {
    fun status(): ClientFerruxStatus
    suspend fun connect(): FerruxGateway
}

internal interface FerruxGateway : Closeable {
    val info: FerruxInfo
    val state: StateFlow<ConnectionState>
    suspend fun spawn(spec: ProcessSpec): FerruxProcessHandle
    suspend fun keepAlive(reason: String): Closeable
    val files: FerruxFilesHandle
}

internal interface FerruxProcessHandle : Closeable {
    val handle: Long
    val pid: Int
    fun detachFds(): RawFds
    suspend fun resize(rows: Int, cols: Int)
    suspend fun signal(signo: Int)
    suspend fun kill()
    suspend fun await(): ExitStatus
}

internal interface FerruxFilesHandle {
    suspend fun list(path: String): List<FileStat>
    suspend fun stat(path: String): FileStat
    suspend fun readBytes(path: String): ByteArray
    suspend fun writeAtomically(path: String, data: ByteArray)
    suspend fun mkdirs(path: String)
    suspend fun rename(from: String, to: String)
    suspend fun delete(path: String, recursive: Boolean)
}

private class RealFerruxClientApi(private val context: Context) : FerruxClientApi {
    override fun status(): ClientFerruxStatus = FerruxClient.status(context)
    override suspend fun connect(): FerruxGateway = RealFerruxGateway(FerruxClient.connect(context))
}

private class RealFerruxGateway(private val conn: FerruxConnection) : FerruxGateway {
    override val info: FerruxInfo get() = conn.info
    override val state: StateFlow<ConnectionState> get() = conn.state
    override suspend fun spawn(spec: ProcessSpec): FerruxProcessHandle = RealFerruxProcessHandle(conn.spawn(spec))
    override suspend fun keepAlive(reason: String): Closeable = conn.keepAlive(reason)
    override val files: FerruxFilesHandle = object : FerruxFilesHandle {
        override suspend fun list(path: String) = conn.files.list(path)
        override suspend fun stat(path: String) = conn.files.stat(path)
        override suspend fun readBytes(path: String) = conn.files.readBytes(path)
        override suspend fun writeAtomically(path: String, data: ByteArray) = conn.files.writeAtomically(path, data)
        override suspend fun mkdirs(path: String) = conn.files.mkdirs(path)
        override suspend fun rename(from: String, to: String) = conn.files.rename(from, to)
        override suspend fun delete(path: String, recursive: Boolean) = conn.files.delete(path, recursive)
    }

    override fun close() = conn.close()
}

private class RealFerruxProcessHandle(private val proc: dev.ferrux.client.FerruxProcess) : FerruxProcessHandle {
    override val handle: Long get() = proc.handle
    override val pid: Int get() = proc.pid
    override fun detachFds(): RawFds = proc.detachFds()
    override suspend fun resize(rows: Int, cols: Int) = proc.resize(rows, cols)
    override suspend fun signal(signo: Int) = proc.signal(signo)
    override suspend fun kill() = proc.kill()
    override suspend fun await(): ExitStatus = proc.await()
    override fun close() = proc.close()
}

/**
 * Bridges Ferride's Rust core ([HostBridge] and [FerruxTree]) to Ferrux API v1
 * (`dev.ferrux:ferrux-client`). Manages one [FerruxGateway] connection per app
 * process, tracks keep-alive while Ferride is foreground with a workspace open,
 * and reconnects automatically after [ConnectionState.Disconnected].
 */
class FerruxConnector internal constructor(
    private val client: FerruxClientApi,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainThreadCheck: () -> Boolean = ::isMainThread,
    private val reconnectDelayMs: Long = DEFAULT_RECONNECT_DELAY_MS,
) : HostBridge, Closeable {

    constructor(context: Context) : this(RealFerruxClientApi(context.applicationContext))

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val connectMutex = Mutex()
    private val keepAliveMutex = Mutex()
    private val processes = ConcurrentHashMap<Long, FerruxProcessHandle>()
    private val closed = AtomicBoolean(false)

    @Volatile private var connection: FerruxGateway? = null
    @Volatile private var disconnectWatcher: Job? = null
    @Volatile private var lastConnectError: ClientFerruxException? = null
    @Volatile private var keepAliveHandle: Closeable? = null
    @Volatile private var visible = false
    @Volatile private var workspaceOpen = false
    @Volatile private var reconnectAttempts = 0

    /** Invoked (possibly from a background thread) when Ferrux availability changes. */
    var onStatusChanged: (() -> Unit)? = null

    /** Registers this connector as Ferride's process host in the Rust core. */
    fun register() {
        ensureNativeLoaded()
        registerHostBridge(this)
    }

    /** Cached `$HOME` path when connected, or null. */
    val homeDir: String?
        get() = connection?.takeIf { it.state.value == ConnectionState.Connected }?.info?.home

    /** Cached Ferrux bootstrap state (`FerruxInfo.BOOTSTRAP_*`) when connected, or null. */
    val bootstrapState: Int?
        get() = connection?.takeIf { it.state.value == ConnectionState.Connected }?.info?.bootstrapState

    /** Updates foreground visibility from the host Activity lifecycle. */
    fun setVisible(isVisible: Boolean) {
        if (closed.get()) return
        visible = isVisible
        if (isVisible) {
            reconnectAttempts = 0
            onStatusChanged?.invoke()
            scope.launch {
                if (client.status() is ClientFerruxStatus.Ready) {
                    runCatching { ensureConnected() }
                }
                syncKeepAlive()
            }
        } else {
            scope.launch { syncKeepAlive() }
        }
    }

    /** Updates whether a workspace is open so keep-alive is held while visible. */
    fun setWorkspaceOpen(open: Boolean) {
        if (closed.get()) return
        workspaceOpen = open
        scope.launch { syncKeepAlive() }
    }

    /** Ensures an active connection (must not be called on the main thread if not yet connected). */
    suspend fun connect(): Boolean = runCatching { ensureConnected() }.isSuccess

    /** Returns a [FerruxTree] rooted at Ferrux's `$HOME` if connected (connecting if needed). */
    suspend fun openHomeTree(): Pair<String, FerruxTree>? {
        val conn = runCatching { ensureConnected() }.getOrNull() ?: return null
        return conn.info.home to FerruxFilesTree(conn.files)
    }

    override fun status(): CoreFerruxStatus {
        return when (val s = client.status()) {
            ClientFerruxStatus.NotInstalled -> CoreFerruxStatus.NotInstalled
            ClientFerruxStatus.NotPermitted -> CoreFerruxStatus.NotPaired
            ClientFerruxStatus.Disabled -> CoreFerruxStatus.Unavailable("Ferrux is disabled")
            is ClientFerruxStatus.Outdated ->
                CoreFerruxStatus.Unavailable("Ferrux API ${s.installed} is older than required ${s.required}")
            is ClientFerruxStatus.Ready -> {
                if (lastConnectError is ClientFerruxException.NotPermitted) {
                    return CoreFerruxStatus.NotPaired
                }
                val live = connection?.takeIf { it.state.value == ConnectionState.Connected }
                val desc = live?.info?.let { info ->
                    val prefix = if (info.versionName.isNotBlank()) "Ferrux ${info.versionName}" else "Ferrux"
                    "$prefix (API v${info.apiVersion})"
                } ?: "Ferrux (API v${s.apiVersion})"
                CoreFerruxStatus.Ready(desc)
            }
        }
    }

    override fun spawn(spec: BridgeSpawnSpec): SpawnedFds {
        if (closed.get()) throw HostBridgeException.Unavailable("Ferrux connector is closed")
        val conn = blockingIo { ensureConnected() }
        val pty = if (spec.ptyRows != null && spec.ptyCols != null) {
            PtySize(spec.ptyRows!!, spec.ptyCols!!)
        } else {
            null
        }
        val program = spec.argv.firstOrNull().orEmpty()
        val info = conn.info
        if (info.bootstrapState != FerruxInfo.BOOTSTRAP_READY && programLooksInPrefix(program, info.prefix)) {
            throw HostBridgeException.Unavailable(BOOTSTRAP_SETUP_MESSAGE)
        }
        val processSpec = ProcessSpec(
            argv = spec.argv,
            env = spec.env.associate { it.key to it.value },
            cwd = spec.cwd,
            pty = pty,
            mergeStderr = spec.mergeStderr,
        )
        val proc = blockingIo {
            try {
                conn.spawn(processSpec)
            } catch (e: ClientFerruxException) {
                throw mapBridgeException(e)
            }
        }
        val fds = try {
            proc.detachFds()
        } catch (t: Throwable) {
            runCatching { proc.close() }
            throw HostBridgeException.Failed(t.message ?: "failed to detach file descriptors")
        }
        processes[proc.handle] = proc
        return SpawnedFds(
            handle = proc.handle,
            stdinFd = fds.stdin,
            stdoutFd = fds.stdout,
            stderrFd = fds.stderr,
            ptyFd = fds.pty,
            ptyIsSocket = fds.ptyIsSocket,
        )
    }

    override fun resize(handle: Long, rows: Int, cols: Int) {
        val proc = processes[handle] ?: return
        blockingIo {
            try {
                proc.resize(rows, cols)
            } catch (e: ClientFerruxException) {
                throw mapBridgeException(e)
            }
        }
    }

    override fun signal(handle: Long, signo: Int) {
        val proc = processes[handle] ?: throw HostBridgeException.Failed("unknown process handle $handle")
        blockingIo {
            try {
                proc.signal(signo)
            } catch (e: ClientFerruxException) {
                throw mapBridgeException(e)
            }
        }
    }

    override fun kill(handle: Long) {
        val proc = processes[handle] ?: throw HostBridgeException.Failed("unknown process handle $handle")
        blockingIo {
            try {
                proc.kill()
            } catch (e: ClientFerruxException) {
                throw mapBridgeException(e)
            }
        }
    }

    override fun close(handle: Long) {
        processes.remove(handle)?.let { runCatching { it.close() } }
    }

    override fun wait(handle: Long): Int {
        val proc = processes[handle] ?: throw HostBridgeException.Failed("unknown process handle $handle")
        val exit = blockingIo {
            try {
                proc.await()
            } catch (e: ClientFerruxException) {
                throw mapBridgeException(e)
            }
        }
        return if (exit.signaled) -exit.signal else exit.exitCode
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        disconnectWatcher?.cancel()
        disconnectWatcher = null
        releaseKeepAliveImmediate()
        clearProcesses()
        connection?.let { runCatching { it.close() } }
        connection = null
        scope.cancel()
    }

    private suspend fun ensureConnected(): FerruxGateway {
        if (closed.get()) throw HostBridgeException.Unavailable("Ferrux connector is closed")
        connection?.takeIf { it.state.value == ConnectionState.Connected }?.let { return it }
        return connectMutex.withLock {
            connection?.takeIf { it.state.value == ConnectionState.Connected }?.let { return@withLock it }
            if (closed.get()) throw HostBridgeException.Unavailable("Ferrux connector is closed")
            when (val s = client.status()) {
                ClientFerruxStatus.NotInstalled -> throw HostBridgeException.NotInstalled("Ferrux is not installed")
                ClientFerruxStatus.NotPermitted -> throw HostBridgeException.NotPaired("Ferrux API permission denied")
                ClientFerruxStatus.Disabled -> throw HostBridgeException.Unavailable("Ferrux is disabled")
                is ClientFerruxStatus.Outdated ->
                    throw HostBridgeException.Unavailable("Ferrux API ${s.installed} is older than required ${s.required}")
                is ClientFerruxStatus.Ready -> Unit
            }
            val conn = try {
                client.connect()
            } catch (e: ClientFerruxException) {
                lastConnectError = e
                onStatusChanged?.invoke()
                throw mapBridgeException(e)
            }
            lastConnectError = null
            reconnectAttempts = 0
            connection?.takeIf { it !== conn }?.let { runCatching { it.close() } }
            connection = conn
            watchDisconnect(conn)
            onStatusChanged?.invoke()
            syncKeepAliveLocked(conn)
            conn
        }
    }

    private fun watchDisconnect(conn: FerruxGateway) {
        disconnectWatcher?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            conn.state.filter { it == ConnectionState.Disconnected }.first()
            disconnectWatcher = null
            handleDisconnected(conn)
        }
        disconnectWatcher = job
        job.start()
    }

    private suspend fun handleDisconnected(conn: FerruxGateway) {
        if (closed.get()) return
        if (connection === conn) {
            connection = null
            runCatching { conn.close() }
        }
        releaseKeepAliveImmediate()
        clearProcesses()
        onStatusChanged?.invoke()
        if (visible && !closed.get() && reconnectAttempts < MAX_RECONNECT_ATTEMPTS &&
            client.status() is ClientFerruxStatus.Ready
        ) {
            reconnectAttempts++
            delay(reconnectDelayMs)
            if (visible && !closed.get()) {
                runCatching { ensureConnected() }
            }
        }
    }

    private suspend fun syncKeepAlive() {
        keepAliveMutex.withLock {
            syncKeepAliveLocked(connection)
        }
    }

    private suspend fun syncKeepAliveLocked(conn: FerruxGateway?) {
        val want = visible && workspaceOpen && !closed.get() &&
            conn != null && conn.state.value == ConnectionState.Connected
        if (!want) {
            keepAliveHandle?.let { runCatching { it.close() } }
            keepAliveHandle = null
            return
        }
        if (keepAliveHandle != null) return
        keepAliveHandle = runCatching { conn!!.keepAlive(KEEPALIVE_REASON) }.getOrNull()
    }

    private fun releaseKeepAliveImmediate() {
        keepAliveHandle?.let { runCatching { it.close() } }
        keepAliveHandle = null
    }

    private fun clearProcesses() {
        val entries = processes.entries.toList()
        for ((handle, proc) in entries) {
            processes.remove(handle, proc)
            runCatching { proc.close() }
        }
    }

    private fun <T> blockingIo(block: suspend () -> T): T {
        if (mainThreadCheck()) {
            throw HostBridgeException.Failed("Ferrux bridge calls must not run on the main thread")
        }
        return runBlocking(ioDispatcher) { block() }
    }

    private fun mapBridgeException(e: ClientFerruxException): HostBridgeException = when (e) {
        is ClientFerruxException.NotInstalled ->
            HostBridgeException.NotInstalled(e.message ?: "Ferrux is not installed")
        is ClientFerruxException.NotPermitted ->
            HostBridgeException.NotPaired(e.message ?: "Ferrux API permission denied")
        is ClientFerruxException.Outdated ->
            HostBridgeException.Unavailable(e.message ?: "Ferrux is outdated")
        is ClientFerruxException.Disconnected ->
            HostBridgeException.Unavailable(e.message ?: "Ferrux disconnected")
        is ClientFerruxException.Unsupported ->
            HostBridgeException.Unsupported(e.message ?: "Unsupported Ferrux operation")
        is ClientFerruxException.Api -> when {
            e.isBootstrapRequired -> HostBridgeException.Unavailable(BOOTSTRAP_SETUP_MESSAGE)
            e.isLimit -> HostBridgeException.Unavailable(e.message ?: "Ferrux process limit reached")
            else -> HostBridgeException.Failed(e.message ?: "Ferrux API error ${e.code}")
        }
    }

    internal class FerruxFilesTree(
        private val files: FerruxFilesHandle,
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : FerruxTree {
        override fun list(rel: String): List<FerruxEntry> = treeCall {
            files.list(rel).map { it.toEntry() }
        }

        override fun stat(rel: String): FerruxEntry = treeCall {
            files.stat(rel).toEntry()
        }

        override fun read(rel: String): ByteArray = treeCall {
            files.readBytes(rel)
        }

        override fun write(rel: String, bytes: ByteArray) = treeCall {
            files.writeAtomically(rel, bytes)
        }

        override fun createFile(rel: String) = treeCall {
            if (exists(rel)) throw CoreFerruxException.Failed("$rel already exists")
            val parent = rel.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) files.mkdirs(parent)
            files.writeAtomically(rel, ByteArray(0))
        }

        override fun createDir(rel: String) = treeCall {
            if (exists(rel)) throw CoreFerruxException.Failed("$rel already exists")
            files.mkdirs(rel)
        }

        override fun rename(fromRel: String, toRel: String) = treeCall {
            if (exists(toRel)) throw CoreFerruxException.Failed("$toRel already exists")
            val parent = toRel.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) files.mkdirs(parent)
            files.rename(fromRel, toRel)
        }

        override fun delete(rel: String) = treeCall {
            files.delete(rel, recursive = true)
        }

        private suspend fun exists(rel: String): Boolean = try {
            files.stat(rel)
            true
        } catch (e: ClientFerruxException.Api) {
            if (e.isNotFound) false else throw e
        }

        private fun <T> treeCall(block: suspend () -> T): T = try {
            runBlocking(ioDispatcher) { block() }
        } catch (e: CoreFerruxException) {
            throw e
        } catch (e: Exception) {
            throw CoreFerruxException.Failed(e.message ?: e.javaClass.simpleName)
        }

        private fun FileStat.toEntry() = FerruxEntry(
            name = name,
            isDir = type == FileStat.TYPE_DIR,
            size = size,
            modifiedMs = modifiedMillis,
        )
    }

    companion object {
        const val KEEPALIVE_REASON = "Ferride workspace"
        const val BOOTSTRAP_SETUP_MESSAGE = "Open Ferrux to finish setup"
        private const val DEFAULT_RECONNECT_DELAY_MS = 500L
        private const val MAX_RECONNECT_ATTEMPTS = 2

        /**
         * Loads `libferride_ffi.so` via [TerminalNative.load] so both UniFFI and
         * `ferrux-jni-core` share the same process-wide `SessionManager`. Must be
         * called before any UniFFI type or [TerminalNative] is touched.
         */
        fun ensureNativeLoaded(): Throwable? = TerminalNative.load("ferride_ffi")

        internal fun programLooksInPrefix(program: String, prefix: String): Boolean {
            if (program.isEmpty() || prefix.isEmpty()) return false
            val normPrefix = prefix.trimEnd('/')
            return program == normPrefix || program.startsWith("$normPrefix/")
        }

        private fun isMainThread(): Boolean =
            runCatching { Looper.getMainLooper().isCurrentThread }.getOrDefault(false)
    }
}
