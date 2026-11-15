package dev.ferride.host

import dev.ferride.core.BridgeSpawnSpec
import dev.ferride.core.EnvEntry
import dev.ferride.core.FerruxEntry
import dev.ferride.core.FerruxException as CoreFerruxException
import dev.ferride.core.FerruxStatus as CoreFerruxStatus
import dev.ferride.core.HostBridgeException
import dev.ferrux.api.FerruxInfo
import dev.ferrux.api.FileStat
import dev.ferrux.api.IFerruxApi
import dev.ferrux.client.ConnectionState
import dev.ferrux.client.ExitStatus
import dev.ferrux.client.FerruxException as ClientFerruxException
import dev.ferrux.client.FerruxStatus as ClientFerruxStatus
import dev.ferrux.client.ProcessSpec
import dev.ferrux.client.RawFds
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FerruxConnectorTest {
    private class FakeProcess(
        override val handle: Long,
        override val pid: Int = 4242,
        var rawFds: RawFds = RawFds(stdin = -1, stdout = -1, stderr = -1, pty = 99, ptyIsSocket = false),
        var exitStatus: ExitStatus = ExitStatus(exitCode = 0, signal = 0),
    ) : FerruxProcessHandle {
        val resizes = mutableListOf<Pair<Int, Int>>()
        val signals = mutableListOf<Int>()
        var killed = false
        var closed = false

        override fun detachFds(): RawFds = rawFds

        override suspend fun resize(rows: Int, cols: Int) {
            resizes += rows to cols
        }

        override suspend fun signal(signo: Int) {
            signals += signo
        }

        override suspend fun kill() {
            killed = true
        }

        override suspend fun await(): ExitStatus = exitStatus

        override fun close() {
            closed = true
        }
    }

    private class FakeFiles : FerruxFilesHandle {
        private val files = linkedMapOf<String, ByteArray>()
        private val dirs = linkedSetOf("")

        fun putDir(rel: String) {
            dirs += rel.trimEnd('/')
        }

        fun putFile(rel: String, bytes: ByteArray) {
            val parent = rel.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) putDir(parent)
            files[rel] = bytes
        }

        override suspend fun list(path: String): List<FileStat> {
            val prefix = path.trimEnd('/')
            if (prefix !in dirs) throw ClientFerruxException.Api(IFerruxApi.ERR_NOT_FOUND, "missing $path")
            val entries = mutableListOf<FileStat>()
            for (d in dirs) {
                if (d.isEmpty() || d == prefix) continue
                val parent = d.substringBeforeLast('/', "")
                if (parent == prefix) {
                    entries += statObj(d, d.substringAfterLast('/'), FileStat.TYPE_DIR, 0L)
                }
            }
            for ((f, data) in files) {
                val parent = f.substringBeforeLast('/', "")
                if (parent == prefix) {
                    entries += statObj(f, f.substringAfterLast('/'), FileStat.TYPE_FILE, data.size.toLong())
                }
            }
            return entries.sortedBy { it.name }
        }

        override suspend fun stat(path: String): FileStat {
            val norm = path.trimEnd('/')
            files[norm]?.let { return statObj(norm, norm.substringAfterLast('/'), FileStat.TYPE_FILE, it.size.toLong()) }
            if (norm in dirs) return statObj(norm, norm.substringAfterLast('/'), FileStat.TYPE_DIR, 0L)
            throw ClientFerruxException.Api(IFerruxApi.ERR_NOT_FOUND, "missing $path")
        }

        override suspend fun readBytes(path: String): ByteArray =
            files[path] ?: throw ClientFerruxException.Api(IFerruxApi.ERR_NOT_FOUND, "missing $path")

        override suspend fun writeAtomically(path: String, data: ByteArray) {
            putFile(path, data)
        }

        override suspend fun mkdirs(path: String) {
            var cur = ""
            for (part in path.trim('/').split('/')) {
                if (part.isEmpty()) continue
                cur = if (cur.isEmpty()) part else "$cur/$part"
                dirs += cur
            }
        }

        override suspend fun rename(from: String, to: String) {
            val bytes = files.remove(from) ?: throw ClientFerruxException.Api(IFerruxApi.ERR_NOT_FOUND, "missing $from")
            putFile(to, bytes)
        }

        override suspend fun delete(path: String, recursive: Boolean) {
            val norm = path.trimEnd('/')
            if (files.remove(norm) != null) return
            if (dirs.remove(norm)) {
                if (recursive) {
                    files.keys.removeAll { it.startsWith("$norm/") }
                    dirs.removeAll { it.startsWith("$norm/") }
                }
                return
            }
            throw ClientFerruxException.Api(IFerruxApi.ERR_NOT_FOUND, "missing $path")
        }

        private fun statObj(path: String, name: String, type: Int, size: Long) = FileStat().apply {
            this.path = path
            this.name = name
            this.type = type
            this.size = size
            this.modifiedMillis = 1234L
            this.mode = 0b110100100
        }
    }

    private class FakeKeepAlive(val reason: String) : Closeable {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private class FakeGateway(
        override val info: FerruxInfo = defaultInfo(),
        override val files: FerruxFilesHandle = FakeFiles(),
    ) : FerruxGateway {
        val stateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        override val state: StateFlow<ConnectionState> get() = stateFlow

        val spawnedSpecs = mutableListOf<ProcessSpec>()
        val spawnedProcesses = mutableListOf<FakeProcess>()
        val keepAlives = mutableListOf<FakeKeepAlive>()
        var spawnError: ClientFerruxException? = null
        var nextProcessFactory: (Long, ProcessSpec) -> FakeProcess = { handle, _ -> FakeProcess(handle) }
        var closed = false
        private var nextHandle = 1L

        override suspend fun spawn(spec: ProcessSpec): FerruxProcessHandle {
            spawnError?.let { throw it }
            spawnedSpecs += spec
            val proc = nextProcessFactory(nextHandle++, spec)
            spawnedProcesses += proc
            return proc
        }

        override suspend fun keepAlive(reason: String): Closeable =
            FakeKeepAlive(reason).also { keepAlives += it }

        override fun close() {
            closed = true
        }
    }

    private class FakeClientApi : FerruxClientApi {
        var currentStatus: ClientFerruxStatus = ClientFerruxStatus.Ready(1)
        var connectCalls = 0
        var connectImpl: () -> FerruxGateway = { FakeGateway() }

        override fun status(): ClientFerruxStatus = currentStatus

        override suspend fun connect(): FerruxGateway {
            connectCalls++
            return connectImpl()
        }
    }

    private fun connector(
        client: FakeClientApi = FakeClientApi(),
        onMainThread: () -> Boolean = { false },
    ) = FerruxConnector(
        client = client,
        ioDispatcher = Dispatchers.Unconfined,
        mainThreadCheck = onMainThread,
        reconnectDelayMs = 0L,
    )

    @Test
    fun statusMapsAllClientStatesAndReflectsConnectPermissionError() = runBlocking {
        val client = FakeClientApi()
        val conn = connector(client)

        client.currentStatus = ClientFerruxStatus.NotInstalled
        assertEquals(CoreFerruxStatus.NotInstalled, conn.status())

        client.currentStatus = ClientFerruxStatus.NotPermitted
        assertEquals(CoreFerruxStatus.NotPaired, conn.status())

        client.currentStatus = ClientFerruxStatus.Disabled
        assertEquals(CoreFerruxStatus.Unavailable("Ferrux is disabled"), conn.status())

        client.currentStatus = ClientFerruxStatus.Outdated(installed = 0, required = 1)
        assertEquals(
            CoreFerruxStatus.Unavailable("Ferrux API 0 is older than required 1"),
            conn.status(),
        )

        client.currentStatus = ClientFerruxStatus.Ready(apiVersion = 1)
        assertEquals(CoreFerruxStatus.Ready("Ferrux (API v1)"), conn.status())

        val gw = FakeGateway(defaultInfo(versionName = "0.2.0", apiVersion = 1))
        client.connectImpl = { gw }
        assertTrue(conn.connect())
        assertEquals(CoreFerruxStatus.Ready("Ferrux 0.2.0 (API v1)"), conn.status())

        // Disconnect clears cached gateway, and a NotPermitted connect error surfaces as NotPaired.
        gw.stateFlow.value = ConnectionState.Disconnected
        client.connectImpl = { throw ClientFerruxException.NotPermitted("not paired") }
        assertFalse(conn.connect())
        assertEquals(CoreFerruxStatus.NotPaired, conn.status())
        conn.close()
    }

    @Test
    fun spawnDetachesFdsAndForwardsControlAndExitSignals() {
        val gw = FakeGateway().apply {
            nextProcessFactory = { handle, _ ->
                FakeProcess(
                    handle = handle,
                    rawFds = RawFds(stdin = 10, stdout = 11, stderr = 12, pty = -1, ptyIsSocket = false),
                    exitStatus = ExitStatus(exitCode = -1, signal = 9),
                )
            }
        }
        val client = FakeClientApi().apply { connectImpl = { gw } }
        val conn = connector(client)

        val spawned = conn.spawn(
            BridgeSpawnSpec(
                argv = listOf("/system/bin/sh", "-c", "echo hi"),
                env = listOf(EnvEntry("TERM", "xterm-256color")),
                cwd = "/data/user/0/dev.ferrux/files/home",
                ptyRows = 24,
                ptyCols = 80,
                mergeStderr = true,
            ),
        )
        assertEquals(1L, spawned.handle)
        assertEquals(10, spawned.stdinFd)
        assertEquals(11, spawned.stdoutFd)
        assertEquals(12, spawned.stderrFd)
        assertEquals(-1, spawned.ptyFd)
        assertFalse(spawned.ptyIsSocket)

        val spec = gw.spawnedSpecs.single()
        assertEquals(listOf("/system/bin/sh", "-c", "echo hi"), spec.argv)
        assertEquals(mapOf("TERM" to "xterm-256color"), spec.env)
        assertEquals("/data/user/0/dev.ferrux/files/home", spec.cwd)
        assertEquals(24, spec.pty?.rows)
        assertEquals(80, spec.pty?.cols)
        assertTrue(spec.mergeStderr)

        conn.resize(spawned.handle, 40, 120)
        conn.signal(spawned.handle, 2)
        conn.kill(spawned.handle)
        assertEquals(-9, conn.wait(spawned.handle))

        val proc = gw.spawnedProcesses.single()
        assertEquals(listOf(40 to 120), proc.resizes)
        assertEquals(listOf(2), proc.signals)
        assertTrue(proc.killed)

        conn.close(spawned.handle)
        assertTrue(proc.closed)
        conn.close()
    }

    @Test
    fun bootstrapNotReadyInPrefixAndApiErrorThrowBootstrapSetupMessage() {
        val gw = FakeGateway(defaultInfo(bootstrapState = FerruxInfo.BOOTSTRAP_NOT_INSTALLED))
        val client = FakeClientApi().apply { connectImpl = { gw } }
        val conn = connector(client)

        // System shell works even before bootstrap finishes.
        val sh = conn.spawn(
            BridgeSpawnSpec(
                argv = listOf("/system/bin/sh"),
                env = emptyList(),
                cwd = null,
                ptyRows = 24,
                ptyCols = 80,
                mergeStderr = false,
            ),
        )
        conn.close(sh.handle)

        // Program inside $PREFIX fails fast with the actionable bootstrap prompt.
        try {
            conn.spawn(
                BridgeSpawnSpec(
                    argv = listOf("/data/user/0/dev.ferrux/files/usr/bin/rust-analyzer"),
                    env = emptyList(),
                    cwd = null,
                    ptyRows = null,
                    ptyCols = null,
                    mergeStderr = false,
                ),
            )
            fail("expected Unavailable for unbootstrapped \$PREFIX binary")
        } catch (e: HostBridgeException.Unavailable) {
            assertTrue(e.message!!.contains(FerruxConnector.BOOTSTRAP_SETUP_MESSAGE))
        }

        // ERR_BOOTSTRAP_REQUIRED from Ferrux maps to the same message.
        gw.spawnError = ClientFerruxException.Api(IFerruxApi.ERR_BOOTSTRAP_REQUIRED, "bootstrap not installed")
        try {
            conn.spawn(
                BridgeSpawnSpec(
                    argv = listOf("/system/bin/sh"),
                    env = emptyList(),
                    cwd = null,
                    ptyRows = null,
                    ptyCols = null,
                    mergeStderr = false,
                ),
            )
            fail("expected Unavailable for ERR_BOOTSTRAP_REQUIRED")
        } catch (e: HostBridgeException.Unavailable) {
            assertTrue(e.message!!.contains(FerruxConnector.BOOTSTRAP_SETUP_MESSAGE))
        }
        conn.close()
    }

    @Test
    fun keepAliveHeldOnlyWhileVisibleAndWorkspaceOpenAndReleasedOnBackground() {
        val gw = FakeGateway()
        val client = FakeClientApi().apply { connectImpl = { gw } }
        val conn = connector(client)

        conn.setVisible(true)
        assertTrue("keep-alive stays inactive until workspace is open", gw.keepAlives.isEmpty())

        conn.setWorkspaceOpen(true)
        assertEquals(1, gw.keepAlives.size)
        assertEquals(FerruxConnector.KEEPALIVE_REASON, gw.keepAlives[0].reason)
        assertFalse(gw.keepAlives[0].closed)

        conn.setVisible(false)
        assertTrue("keep-alive must release on background", gw.keepAlives[0].closed)

        conn.setVisible(true)
        assertEquals(2, gw.keepAlives.size)
        assertFalse(gw.keepAlives[1].closed)

        conn.close()
        assertTrue(gw.keepAlives[1].closed)
        assertTrue(gw.closed)
    }

    @Test
    fun disconnectClearsProcessesReleasesKeepAliveAndReconnectsWhenVisible() {
        val gw1 = FakeGateway(defaultInfo(home = "/home/one"))
        val gw2 = FakeGateway(defaultInfo(home = "/home/two"))
        val queue = ArrayDeque(listOf(gw1, gw2))
        val client = FakeClientApi().apply { connectImpl = { queue.removeFirst() } }
        val conn = connector(client)
        var statusChanges = 0
        conn.onStatusChanged = { statusChanges++ }

        conn.setVisible(true)
        conn.setWorkspaceOpen(true)
        val spawned = conn.spawn(
            BridgeSpawnSpec(listOf("/system/bin/sh"), emptyList(), null, 24, 80, false),
        )
        assertEquals("/home/one", conn.homeDir)
        assertEquals(1, gw1.keepAlives.size)

        // Simulate Ferrux service binder death while Ferride is visible in foreground.
        gw1.stateFlow.value = ConnectionState.Disconnected

        assertTrue("old process handle closed on disconnect", gw1.spawnedProcesses.single().closed)
        assertTrue("old keep-alive closed on disconnect", gw1.keepAlives.single().closed)
        assertTrue("old gateway closed on disconnect", gw1.closed)
        assertEquals(2, client.connectCalls)
        assertEquals("/home/two", conn.homeDir)
        assertTrue("reconnect acquires fresh keep-alive", gw2.keepAlives.singleOrNull()?.closed == false)
        assertTrue(statusChanges >= 2)

        // Stale handle from gw1 is no longer tracked.
        try {
            conn.kill(spawned.handle)
            fail("expected Failed for stale handle after disconnect")
        } catch (_: HostBridgeException.Failed) {
        }
        conn.close()
    }

    @Test
    fun mainThreadGuardRejectsBlockingBridgeCalls() {
        val client = FakeClientApi()
        val conn = connector(client, onMainThread = { true })
        try {
            conn.spawn(BridgeSpawnSpec(listOf("/system/bin/sh"), emptyList(), null, 24, 80, false))
            fail("expected Failed when called on main thread")
        } catch (e: HostBridgeException.Failed) {
            assertTrue(e.message!!.contains("main thread"))
        }
        conn.close()
    }

    @Test
    fun ferruxFilesTreeMapsStatListReadWriteCreateRenameDelete() {
        val files = FakeFiles()
        files.putFile("README.md", "hello".toByteArray())
        files.putDir("src")
        val tree = FerruxConnector.FerruxFilesTree(files, Dispatchers.Unconfined)

        assertEquals(
            listOf(
                FerruxEntry("README.md", isDir = false, size = 5, modifiedMs = 1234L),
                FerruxEntry("src", isDir = true, size = 0, modifiedMs = 1234L),
            ),
            tree.list(""),
        )
        assertEquals("hello", tree.read("README.md").decodeToString())

        tree.createFile("src/main.rs")
        tree.write("src/main.rs", "fn main() {}\n".toByteArray())
        assertArrayEquals("fn main() {}\n".toByteArray(), tree.read("src/main.rs"))
        assertEquals(13L, tree.stat("src/main.rs").size)

        tree.rename("src/main.rs", "src/lib.rs")
        assertEquals("fn main() {}\n", tree.read("src/lib.rs").decodeToString())

        tree.createDir("docs/notes")
        assertTrue(tree.stat("docs/notes").isDir)

        try {
            tree.createFile("src/lib.rs")
            fail("expected Failed for existing file")
        } catch (_: CoreFerruxException.Failed) {
        }

        tree.delete("src")
        assertNull(runCatching { tree.stat("src/lib.rs") }.getOrNull())
    }

    companion object {
        private fun defaultInfo(
            versionName: String = "0.1.0",
            apiVersion: Int = 1,
            bootstrapState: Int = FerruxInfo.BOOTSTRAP_READY,
            prefix: String = "/data/user/0/dev.ferrux/files/usr",
            home: String = "/data/user/0/dev.ferrux/files/home",
        ) = FerruxInfo().apply {
            this.apiVersion = apiVersion
            this.versionName = versionName
            this.versionCode = 1L
            this.packageName = "dev.ferrux"
            this.prefix = prefix
            this.home = home
            this.tmpDir = "$prefix/tmp"
            this.bootstrapState = bootstrapState
            this.capabilities = arrayOf("pipes", "pty", "files", "keepalive")
            this.androidApi = 35
            this.abi = "arm64-v8a"
            this.liveProcesses = 0
            this.clientProcessLimit = 16
        }
    }
}
