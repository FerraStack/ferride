package dev.ferride.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.ferride.core.FerruxStatus
import dev.ferride.theme.LocalWorkbenchColors
import dev.ferride.workbench.PanelTab
import dev.ferride.workbench.TerminalSessionHandle
import dev.ferride.workbench.WorkbenchState
import dev.ferrux.terminal.NativeSessionBackend
import dev.ferrux.terminal.SessionEvent
import dev.ferrux.terminal.TerminalBackend
import dev.ferrux.terminal.TerminalNative
import dev.ferrux.terminal.TerminalView
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bottom panel: Problems, Output, Terminal. */
@Composable
fun Panel(state: WorkbenchState, modifier: Modifier = Modifier) {
    val c = LocalWorkbenchColors.current
    val liveTerminal = state.panelTab == PanelTab.Terminal && state.ferrux is FerruxStatus.Ready
    Column(modifier.fillMaxWidth().background(c.panelBackground)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.panelBorder))
        Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PanelTab.entries.forEach { tab ->
                val active = tab == state.panelTab
                Column(
                    Modifier.fillMaxHeight().clickable { state.panelTab = tab }.padding(horizontal = 10.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tab.title.uppercase(),
                            color = if (active) c.panelTitleActiveForeground else c.panelTitleInactiveForeground,
                            fontSize = 11.sp,
                            letterSpacing = 0.5.sp,
                        )
                        val count = state.diagnostics.values.sumOf { it.size }
                        if (tab == PanelTab.Problems && count > 0) {
                            Spacer(Modifier.width(6.dp))
                            CountBadge(count)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.width(28.dp).height(1.dp).background(if (active) c.panelTitleActiveBorder else c.panelBackground))
                }
            }
            Spacer(Modifier.weight(1f))
            if (liveTerminal && state.terminalLauncher != null) {
                BarIcon(Icons.Outlined.Refresh, "Restart Terminal", c.panelTitleInactiveForeground, size = 36.dp, iconSize = 16.dp) {
                    state.restartTerminal()
                }
            }
            BarIcon(Icons.Default.Close, "Close Panel", c.panelTitleInactiveForeground, size = 36.dp, iconSize = 16.dp) {
                state.panelVisible = false
            }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(
                horizontal = if (liveTerminal) 4.dp else 16.dp,
                vertical = if (liveTerminal) 2.dp else 6.dp,
            ),
        ) {
            when (state.panelTab) {
                PanelTab.Problems -> ProblemsList(state)
                PanelTab.Output -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.output) { line ->
                        Text(line, color = c.editorForeground, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                PanelTab.Terminal -> if (state.ferrux is FerruxStatus.Ready) {
                    TerminalPanel(state)
                } else {
                    TerminalPlaceholder(state.ferrux)
                }
            }
        }
    }
}

@Composable
private fun TerminalPanel(state: WorkbenchState) {
    val c = LocalWorkbenchColors.current
    LaunchedEffect(state.panelVisible, state.panelTab, state.ferrux) {
        state.startTerminalIfNeeded()
    }
    val session = state.terminalSession
    if (session == null) {
        val err = state.terminalError
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (err != null) {
                Text(err, color = c.descriptionForeground, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Retry",
                    color = c.buttonForeground,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .background(c.buttonBackground, RoundedCornerShape(4.dp))
                        .clickable { state.restartTerminal() }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                )
            } else {
                Text(
                    if (state.terminalStarting) "Starting shell…" else "Terminal ready",
                    color = c.descriptionForeground,
                    fontSize = 12.sp,
                )
            }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        val exitCode = state.terminalExitCode
        val err = state.terminalError
        if (exitCode != null || err != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatTerminalStatus(exitCode, err),
                    color = c.descriptionForeground,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Restart",
                    color = c.buttonForeground,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .background(c.buttonBackground, RoundedCornerShape(4.dp))
                        .clickable { state.restartTerminal() }
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
        TerminalViewport(session, state, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun TerminalViewport(session: TerminalSessionHandle, state: WorkbenchState, modifier: Modifier = Modifier) {
    val colors = state.colors
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }

    LaunchedEffect(session.sessionId, colors.isDark) {
        withContext(Dispatchers.IO) {
            runCatching {
                val wantDark = if (colors.isDark) "1" else "0"
                val themeId = TerminalNative.themes()
                    .map { it.split('\t') }
                    .firstOrNull { it.size >= 3 && it[2] == wantDark }
                    ?.firstOrNull()
                if (themeId != null) {
                    TerminalNative.setAppearance(session.sessionId, themeId, 0)
                }
            }
        }
        terminalView?.invalidate()
    }

    DisposableEffect(session.sessionId, terminalView) {
        val view = terminalView ?: return@DisposableEffect onDispose {}
        val running = AtomicBoolean(true)
        val thread = Thread({
            var seq = 0L
            while (running.get()) {
                val next = try {
                    TerminalNative.awaitChange(seq, AWAIT_CHANGE_MS)
                } catch (_: UnsatisfiedLinkError) {
                    break
                }
                if (!running.get()) break
                if (next == seq) continue
                seq = next
                val events = runCatching { TerminalNative.pollEvents(session.sessionId) }.getOrNull()
                if (events != null) {
                    for (raw in events) {
                        when (val ev = SessionEvent.parse(raw)) {
                            is SessionEvent.Title -> state.onTerminalTitle(ev.title)
                            is SessionEvent.Failed -> state.onTerminalNativeError(ev.message)
                            else -> Unit
                        }
                    }
                }
                view.onScreenUpdated()
            }
        }, "ferride-term-redraw-${session.sessionId}").apply {
            isDaemon = true
            start()
        }
        onDispose { running.set(false) }
    }

    AndroidView(
        factory = { context ->
            TerminalView(context).apply {
                textSizeSp = state.settings.fontSize.toFloat().coerceIn(8f, 32f)
                isFocusable = true
                isFocusableInTouchMode = true
            }.also { terminalView = it }
        },
        update = { view ->
            val currentBackend = view.backend
            if (currentBackend !is FerruxTerminalBackend || currentBackend.sessionId != session.sessionId) {
                view.backend = FerruxTerminalBackend(NativeSessionBackend(session.sessionId)) { rows, cols ->
                    state.onTerminalGridResized(rows, cols)
                }
                view.requestFocus()
            }
            val wantSp = state.settings.fontSize.toFloat().coerceIn(8f, 32f)
            if (view.textSizeSp != wantSp) view.textSizeSp = wantSp
        },
        onRelease = { if (terminalView === it) terminalView = null },
        modifier = modifier,
    )
}

private class FerruxTerminalBackend(
    private val inner: NativeSessionBackend,
    private val onResize: (rows: Int, cols: Int) -> Unit,
) : TerminalBackend by inner {
    val sessionId: Long get() = inner.sessionId

    override fun resize(rows: Int, cols: Int) {
        inner.resize(rows, cols)
        onResize(rows, cols)
    }
}

private fun formatTerminalStatus(exitCode: Int?, error: String?): String = when {
    error != null -> error
    exitCode == -9 -> "Process killed by signal 9 (Android phantom-process limit?)"
    exitCode != null && exitCode < 0 -> "Process exited with signal ${-exitCode}"
    exitCode != null -> "Process exited with code $exitCode"
    else -> ""
}

private const val AWAIT_CHANGE_MS = 500

/** The terminal runs in Ferrux; until the Host API exists this explains why it's empty. */
@Composable
fun TerminalPlaceholder(status: FerruxStatus) {
    val c = LocalWorkbenchColors.current
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Terminal, null, tint = c.descriptionForeground, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(8.dp))
        Text("Requires Ferrux", color = c.editorForeground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Terminals, language servers and build tools run in Ferrux, FerraStack's Linux environment for Android.",
            color = c.descriptionForeground,
            fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(ferruxLabel(status), color = c.descriptionForeground, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            "Get Ferrux",
            color = c.buttonForeground,
            fontSize = 13.sp,
            modifier = Modifier
                .background(c.buttonBackground, RoundedCornerShape(4.dp))
                .clickable { uri.openUri("https://github.com/FerraStack/ferrux") }
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/** Problems grouped by file, VS Code style: icon, message, source(code), [Ln, Col]. */
@Composable
private fun ProblemsList(state: WorkbenchState) {
    val c = LocalWorkbenchColors.current
    val byFile = state.diagnostics.filterValues { it.isNotEmpty() }.toSortedMap()
    if (byFile.isEmpty()) {
        Text("No problems have been detected in the workspace.", color = c.descriptionForeground, fontSize = 13.sp)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        byFile.forEach { (path, list) ->
            val rel = path.removePrefix(state.backend.root).trimStart('/')
            item(key = "f:$path") {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    FileIcon(rel.substringAfterLast('/'))
                    Spacer(Modifier.width(4.dp))
                    Text(rel.substringAfterLast('/'), color = c.editorForeground, fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(rel.substringBeforeLast('/', ""), color = c.descriptionForeground, fontSize = 12.sp, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp))
                    CountBadge(list.size)
                }
            }
            items(list.sortedWith(compareBy({ it.line }, { it.startColumn })), key = { "d:$path:${it.line}:${it.startColumn}:${it.message}" }) { d ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { state.openFile(path, preview = true, line = d.line) }
                        .padding(start = 22.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        when (d.severity) { 1 -> Icons.Outlined.ErrorOutline; 2 -> Icons.Outlined.WarningAmber; else -> Icons.Outlined.Info },
                        null,
                        tint = when (d.severity) { 1 -> c.errorSquiggle; 2 -> c.warningSquiggle; else -> c.infoSquiggle },
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        buildString {
                            append(d.message)
                            d.source?.let { append("  $it") }
                            d.code?.let { append("($it)") }
                        },
                        color = c.editorForeground,
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("[Ln ${d.line + 1}, Col ${d.startColumn + 1}]", color = c.descriptionForeground, fontSize = 12.sp)
                }
            }
        }
    }
}
