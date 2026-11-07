package dev.ferride.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ferride.theme.LocalWorkbenchColors
import dev.ferride.workbench.WorkbenchState
import kotlinx.coroutines.delay

/** Window size classes, by width (Material breakpoints). */
enum class WidthClass { Compact, Medium, Expanded }

/**
 * The VS Code-style workbench: Activity Bar → Side Bar → editor area (tabs,
 * breadcrumbs, editor) → Panel → Status Bar.
 *
 * - **Compact** (phones, portrait): command-center title bar on top, Activity
 *   Bar at the bottom, Side Bar as a drawer, Panel above the status bar, a
 *   symbols row above the keyboard.
 * - **Medium**: Activity Bar becomes a left rail; Side Bar is still a drawer.
 * - **Expanded** (tablets, landscape, DeX): desktop layout with a docked Side Bar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FerrideWorkbench(state: WorkbenchState, modifier: Modifier = Modifier, imeVisible: Boolean? = null) {
    val colors = state.colors
    val scheme = if (colors.isDark) {
        darkColorScheme(
            primary = colors.focusBorder,
            background = colors.editorBackground,
            surface = colors.sideBarBackground,
            onSurface = colors.sideBarForeground,
        )
    } else {
        lightColorScheme(
            primary = colors.focusBorder,
            background = colors.editorBackground,
            surface = colors.sideBarBackground,
            onSurface = colors.sideBarForeground,
        )
    }
    CompositionLocalProvider(LocalWorkbenchColors provides colors) {
        MaterialTheme(colorScheme = scheme) {
            BoxWithConstraints(modifier.fillMaxSize().background(colors.activityBarBackground)) {
                val widthClass = when {
                    maxWidth >= 840.dp -> WidthClass.Expanded
                    maxWidth >= 600.dp -> WidthClass.Medium
                    else -> WidthClass.Compact
                }
                val keyboardUp = imeVisible ?: WindowInsets.isImeVisible
                LaunchedEffect(widthClass) {
                    state.sideBarDocked = widthClass == WidthClass.Expanded
                    if (state.sideBarDocked) state.sideBarVisible = true
                }
                val panelHeight = if (widthClass == WidthClass.Expanded) 220.dp else maxHeight * 0.38f

                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    if (widthClass != WidthClass.Expanded) TitleBar(state)
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
                    if (widthClass != WidthClass.Expanded && state.settings.extraKeys && state.activeTab != null) {
                        SymbolsRow(state)
                    }
                    StatusBar(state, compact = widthClass == WidthClass.Compact)
                    if (widthClass == WidthClass.Compact && !keyboardUp) ActivityBar(state, vertical = false)
                }

                if (widthClass != WidthClass.Expanded && state.sideBarVisible) {
                    // Side Bar as a drawer over the editor.
                    Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(
                            interactionSource = MutableInteractionSource(),
                            indication = null,
                        ) { state.sideBarVisible = false },
                    )
                    SideBar(
                        state,
                        Modifier.safeDrawingPadding().fillMaxHeight().width(minOf(340.dp, maxWidth * 0.86f)),
                    )
                    BackHandler { state.sideBarVisible = false }
                }

                if (state.paletteOpen) {
                    CommandPalette(state, maxWidth)
                    BackHandler { state.paletteOpen = false }
                }

                state.message?.let { msg ->
                    LaunchedEffect(msg) {
                        delay(3500)
                        if (state.message == msg) state.message = null
                    }
                    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(bottom = 72.dp), contentAlignment = Alignment.BottomCenter) {
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

@Composable
internal fun HSpacer(width: androidx.compose.ui.unit.Dp) = Spacer(Modifier.width(width))
