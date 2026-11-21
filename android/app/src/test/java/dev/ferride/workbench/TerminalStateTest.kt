package dev.ferride.workbench

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ferride.core.FerruxStatus
import dev.ferride.core.WorkspaceKind
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
class TerminalStateTest {
    private data class SpawnCall(
        val argv: List<String>,
        val env: Map<String, String>,
        val cwd: String?,
        val rows: Int,
        val cols: Int,
    )

    private class FakeSession(
        override val sessionId: Long,
        private val exitGate: CountDownLatch = CountDownLatch(0),
        private val exitCode: Int = 0,
    ) : TerminalSessionHandle {
        val resizes = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, Int>>()
        val resizedLatch = CountDownLatch(1)
        @Volatile var killed = false
        @Volatile var closed = false

        override fun resize(rows: Int, cols: Int) {
            resizes += rows to cols
            resizedLatch.countDown()
        }

        override fun signal(signo: Int) {}

        override fun kill() {
            killed = true
            exitGate.countDown()
        }

        override fun waitForExit(): Int {
            exitGate.await(5, TimeUnit.SECONDS)
            return exitCode
        }

        override fun close() {
            closed = true
            exitGate.countDown()
        }
    }

    private class FakeLauncher(
        private val sessionFactory: (Long) -> FakeSession = { id -> FakeSession(id) },
    ) : TerminalLauncher {
        val calls = java.util.concurrent.CopyOnWriteArrayList<SpawnCall>()
        val sessions = java.util.concurrent.CopyOnWriteArrayList<FakeSession>()
        private val nextId = java.util.concurrent.atomic.AtomicLong(100L)

        override fun spawn(argv: List<String>, env: Map<String, String>, cwd: String?, rows: Int, cols: Int): TerminalSessionHandle {
            calls += SpawnCall(argv, env, cwd, rows, cols)
            val s = sessionFactory(nextId.getAndIncrement())
            sessions += s
            return s
        }
    }

    @Test
    fun ferruxStatusUpdatesOnRefreshAndClosesTerminalWhenUnavailable() {
        var current: FerruxStatus = FerruxStatus.NotInstalled
        val backend = FakeWorkspaceBackend(ferruxProvider = { current })
        val launcher = FakeLauncher()
        val state = WorkbenchState(
            backend = backend,
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
            terminalLauncher = launcher,
        )
        state.start(openFirst = null)
        assertEquals(FerruxStatus.NotInstalled, state.ferrux)

        // Opening terminal before Ferrux is Ready does not spawn anything.
        state.showPanel(PanelTab.Terminal)
        assertTrue(launcher.calls.isEmpty())
        assertNull(state.terminalSession)

        // Transition to Ready while Terminal tab is active auto-starts /system/bin/sh.
        current = FerruxStatus.Ready("Ferrux 0.1.0 (API v1)")
        state.refreshFerruxStatus()
        assertEquals(FerruxStatus.Ready("Ferrux 0.1.0 (API v1)"), state.ferrux)
        assertEquals(1, launcher.calls.size)
        assertEquals(listOf("/system/bin/sh"), launcher.calls.single().argv)
        assertEquals("xterm-256color", launcher.calls.single().env["TERM"])
        assertNull("local workspace leaves terminal cwd null", launcher.calls.single().cwd)
        assertNotNull(state.terminalSession)

        // Transition back to NotInstalled tears down the active terminal session.
        current = FerruxStatus.NotInstalled
        state.refreshFerruxStatus()
        assertNull(state.terminalSession)
        assertTrue(launcher.sessions.single().closed)
    }

    @Test
    fun terminalTracksResizeExitCodeAndRestart() {
        val pool = Executors.newCachedThreadPool()
        val io = pool.asCoroutineDispatcher()
        try {
            val exitGate = CountDownLatch(1)
            val launcher = FakeLauncher { id -> FakeSession(id, exitGate = exitGate, exitCode = -9) }
            val backend = FakeWorkspaceBackend(
                name = "home",
                kind = WorkspaceKind.FERRUX,
                root = "/data/user/0/dev.ferrux/files/home",
                ferruxProvider = { FerruxStatus.Ready("Ferrux (API v1)") },
            )
            val state = WorkbenchState(
                backend = backend,
                scope = CoroutineScope(Dispatchers.Unconfined),
                io = io,
                terminalLauncher = launcher,
            )
            state.start(openFirst = null)
            waitUntil { state.ferrux is FerruxStatus.Ready }

            state.showPanel(PanelTab.Terminal)
            waitUntil { state.terminalSession != null }
            assertEquals("/data/user/0/dev.ferrux/files/home", launcher.calls.single().cwd)

            // Idempotent re-calls while already running do not spawn duplicates.
            state.startTerminalIfNeeded()
            assertEquals(1, launcher.calls.size)

            val first = launcher.sessions.single()
            state.onTerminalGridResized(rows = 42, cols = 110)
            assertTrue(first.resizedLatch.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(42 to 110), first.resizes)

            // Releasing exitGate simulates SIGKILL (-9).
            exitGate.countDown()
            waitUntil {
                state.terminalExitCode == -9 &&
                    synchronized(state.output) { state.output.toList() }.any { it.contains("signal 9") }
            }

            state.restartTerminal()
            waitUntil { launcher.sessions.size == 2 && state.terminalSession?.sessionId == launcher.sessions[1].sessionId }
            assertTrue(first.closed)
            state.closeTerminalNow()
            assertNull(state.terminalSession)
        } finally {
            io.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun openFerruxHomeCommandAvailabilityAndExecution() {
        var ferruxState: FerruxStatus = FerruxStatus.NotInstalled
        val ferruxHomeBackend = FakeWorkspaceBackend(
            name = "home",
            kind = WorkspaceKind.FERRUX,
            root = "/data/user/0/dev.ferrux/files/home",
            ferruxProvider = { FerruxStatus.Ready("Ferrux (API v1)") },
        )
        val host = object : WorkspaceHost {
            override val projectsDir: String = "/tmp/projects"
            override fun pickFolder() {}
            override fun openLocal(path: String): WorkspaceBackend = FakeWorkspaceBackend()
            override fun openFerruxHome(): WorkspaceBackend = ferruxHomeBackend
        }
        val state = WorkbenchState(
            backend = FakeWorkspaceBackend(ferruxProvider = { ferruxState }),
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
            host = host,
        )
        state.start(openFirst = null)
        assertFalse(state.commands.execute(CommandIds.OPEN_FERRUX_HOME))

        ferruxState = FerruxStatus.Ready("Ferrux (API v1)")
        state.refreshFerruxStatus()
        assertTrue(state.commands.execute(CommandIds.OPEN_FERRUX_HOME))
        assertEquals(ferruxHomeBackend, state.backend)
        assertFalse(
            "Ferrux home is not persisted as a local recent folder",
            state.recentFolders.any { it.root == ferruxHomeBackend.root || it.kind == WorkspaceKind.FERRUX },
        )
    }

    private fun waitUntil(timeoutMs: Long = 5000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("condition not met within ${timeoutMs}ms")
            }
            Thread.sleep(10)
        }
    }
}
