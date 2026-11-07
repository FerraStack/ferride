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
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ferride.core.FerruxStatus
import dev.ferride.theme.LocalWorkbenchColors
import dev.ferride.workbench.PanelTab
import dev.ferride.workbench.WorkbenchState

/** Bottom panel: Problems, Output, Terminal. */
@Composable
fun Panel(state: WorkbenchState, modifier: Modifier = Modifier) {
    val c = LocalWorkbenchColors.current
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
                    Text(
                        tab.title.uppercase(),
                        color = if (active) c.panelTitleActiveForeground else c.panelTitleInactiveForeground,
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.width(28.dp).height(1.dp).background(if (active) c.panelTitleActiveBorder else c.panelBackground))
                }
            }
            Spacer(Modifier.weight(1f))
            BarIcon(Icons.Default.Close, "Close Panel", c.panelTitleInactiveForeground, size = 36.dp, iconSize = 16.dp) {
                state.panelVisible = false
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            when (state.panelTab) {
                PanelTab.Problems -> Text(
                    "No problems have been detected in the workspace.",
                    color = c.descriptionForeground,
                    fontSize = 13.sp,
                )
                PanelTab.Output -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.output) { line ->
                        Text(line, color = c.editorForeground, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                PanelTab.Terminal -> TerminalPlaceholder(state.ferrux)
            }
        }
    }
}

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
