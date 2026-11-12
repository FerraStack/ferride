package dev.ferride.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ferride.theme.LocalWorkbenchColors
import dev.ferride.workbench.WorkbenchState
import kotlinx.coroutines.delay

/** Window size classes, by width (Material breakpoints). */
enum class WidthClass { Compact, Medium, Expanded }

/** Dim behind overlays (drawer, palette), like VS Code's modal backdrop. */
const val SCRIM_ALPHA = 0.4f

/**
 * The VS Code-style workbench: title bar (menus + command center) → Activity
 * Bar → Side Bar → editor area (tabs, breadcrumbs, editor) → Panel → Status Bar.
 *
 * - **Compact** (phones, portrait): Activity Bar at the bottom, Side Bar as a
 *   drawer, Panel above the status bar, symbols row above the keyboard.
 * - **Medium**: Activity Bar becomes a left rail; Side Bar is still a drawer.
 * - **Expanded** (tablets, landscape, DeX): desktop layout with a docked Side Bar.
 *
 * [imeVisible] and [hardwareKeyboard] override detection (screenshot tests).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FerrideWorkbench(
    state: WorkbenchState,
    modifier: Modifier = Modifier,
    imeVisible: Boolean? = null,
    hardwareKeyboard: Boolean? = null,
) {
    val colors = state.colors
    val scheme = if (colors.isDark) {
        darkColorScheme(
            primary = colors.focusBorder,
            background = colors.editorBackground,
            surface = colors.quickInputBackground,
            onSurface = colors.inputForeground,
        )
    } else {
        lightColorScheme(
            primary = colors.focusBorder,
            background = colors.editorBackground,
            surface = colors.quickInputBackground,
            onSurface = colors.inputForeground,
        )
    }
    val clipboard = LocalClipboardManager.current
    SideEffect { state.setClipboard = { clipboard.setText(AnnotatedString(it)) } }
    val config = LocalConfiguration.current
    val keyboardUp = imeVisible ?: WindowInsets.isImeVisible
    val hasHardwareKeyboard = hardwareKeyboard ?: (
        config.keyboard != Configuration.KEYBOARD_NOKEYS &&
            config.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES
        )
    CompositionLocalProvider(LocalWorkbenchColors provides colors) {
        MaterialTheme(colorScheme = scheme) {
            BoxWithConstraints(modifier.fillMaxSize().background(colors.activityBarBackground)) {
                val widthClass = when {
                    maxWidth >= 840.dp -> WidthClass.Expanded
                    maxWidth >= 600.dp -> WidthClass.Medium
                    else -> WidthClass.Compact
                }
                LaunchedEffect(widthClass) {
                    state.compactWindow = widthClass == WidthClass.Compact
                    state.sideBarDocked = widthClass == WidthClass.Expanded
                    if (state.sideBarDocked) state.sideBarVisible = true
                }
                val panelHeight = if (widthClass == WidthClass.Expanded) 220.dp else maxHeight * 0.38f
                val showSymbols = state.settings.extraKeys && state.activeModel != null &&
                    widthClass != WidthClass.Expanded && (keyboardUp || !hasHardwareKeyboard)

                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    TitleBar(state, wide = widthClass == WidthClass.Expanded)
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        if (widthClass != WidthClass.Compact) ActivityBar(state, vertical = true)
                        if (widthClass == WidthClass.Expanded && state.sideBarVisible) {
                            SideBar(state, Modifier.width(280.dp).fillMaxHeight())
                            Box(Modifier.width(1.dp).fillMaxHeight().background(colors.panelBorder))
                        }
                        Column(Modifier.weight(1f).fillMaxHeight().background(colors.editorBackground)) {
                            EditorArea(state, Modifier.weight(1f))
                            if (state.panelVisible) Panel(state, Modifier.height(panelHeight))
                        }
                    }
                    if (showSymbols) SymbolsRow(state)
                    StatusBar(state, compact = widthClass == WidthClass.Compact)
                    if (widthClass == WidthClass.Compact && !keyboardUp) ActivityBar(state, vertical = false)
                }

                if (widthClass != WidthClass.Expanded && state.sideBarVisible) {
                    // Side Bar as a drawer over a dimmed editor; tap outside to close.
                    Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = SCRIM_ALPHA)).clickable(
                            interactionSource = MutableInteractionSource(),
                            indication = null,
                            onClickLabel = "Close side bar",
                        ) { state.sideBarVisible = false },
                    )
                    SideBar(
                        state,
                        Modifier.safeDrawingPadding().fillMaxHeight().width(minOf(360.dp, maxWidth * 0.85f)),
                    )
                    BackHandler { state.sideBarVisible = false }
                }

                if (state.paletteOpen) {
                    CommandPalette(state, maxWidth)
                    BackHandler { state.paletteOpen = false }
                }

                state.contextMenu?.let { menu ->
                    ContextMenuOverlay(menu) { state.contextMenu = null }
                }

                state.deleteRequest?.let { node ->
                    AlertDialog(
                        onDismissRequest = { state.deleteRequest = null },
                        containerColor = colors.quickInputBackground,
                        title = {
                            Text(
                                if (node.isDir) {
                                    "Are you sure you want to permanently delete '${node.name}' and its contents?"
                                } else {
                                    "Are you sure you want to permanently delete '${node.name}'?"
                                },
                                color = colors.inputForeground,
                                fontSize = 16.sp,
                            )
                        },
                        text = { Text("This action is irreversible!", color = colors.descriptionForeground, fontSize = 13.sp) },
                        confirmButton = { TextButton({ state.confirmDelete() }) { Text("Delete") } },
                        dismissButton = { TextButton({ state.deleteRequest = null }) { Text("Cancel") } },
                    )
                }

                ScmOverlays(state.scm, maxWidth)

                state.closeRequest?.let { tab ->
                    AlertDialog(
                        onDismissRequest = { state.resolveCloseRequest(null) },
                        containerColor = colors.quickInputBackground,
                        title = { Text("Do you want to save the changes you made to ${tab.name}?", color = colors.inputForeground, fontSize = 16.sp) },
                        text = { Text("Your changes will be lost if you don't save them.", color = colors.descriptionForeground, fontSize = 13.sp) },
                        confirmButton = { TextButton({ state.resolveCloseRequest(true) }) { Text("Save") } },
                        dismissButton = {
                            Row {
                                TextButton({ state.resolveCloseRequest(false) }) { Text("Don't Save") }
                                TextButton({ state.resolveCloseRequest(null) }) { Text("Cancel") }
                            }
                        },
                    )
                }

                state.message?.let { msg ->
                    LaunchedEffect(msg) {
                        delay(4000)
                        if (state.message == msg) state.message = null
                    }
                    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(bottom = 80.dp), contentAlignment = Alignment.BottomCenter) {
                        Text(
                            msg,
                            color = colors.inputForeground,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .background(colors.quickInputBackground, RoundedCornerShape(6.dp))
                                .clickable { state.message = null }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}
