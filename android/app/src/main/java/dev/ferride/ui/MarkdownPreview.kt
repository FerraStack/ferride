package dev.ferride.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ferride.core.MdAlign
import dev.ferride.core.MdBlock
import dev.ferride.core.MdKind
import dev.ferride.core.MdSpan
import dev.ferride.core.StyleKind
import dev.ferride.core.markdownBlocks
import dev.ferride.theme.LocalWorkbenchColors
import dev.ferride.theme.WorkbenchColors
import dev.ferride.workbench.EditorTab
import dev.ferride.workbench.WorkbenchState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Markdown: Open Preview — the active Markdown file rendered like VS Code's
 * preview (its `markdown.css` sizes: 14 sp body, 2 em / 1.5 em headings with a
 * rule under h1/h2, code blocks on `textCodeBlock`, inline code in
 * `textPreformat`, quotes with a left bar, GFM tables and task lists). It
 * follows the source editor's edits; parsing (Rust, `pulldown-cmark`) runs off
 * the main thread.
 */
@Composable
fun MarkdownPreview(state: WorkbenchState, tab: EditorTab, modifier: Modifier = Modifier) {
    val c = LocalWorkbenchColors.current
    val source = state.previewSourceModel(tab)
    // Re-read when the source editor changes.
    val revision = state.tabs.firstOrNull { it.model === source && source != null }?.revision ?: 0
    val blocks by produceState(emptyList<MdBlock>(), source, revision) {
        val text = source?.takeIf { !it.isClosed }?.text()
        value = withContext(Dispatchers.Default) {
            runCatching { markdownBlocks(text ?: state.readPreviewSource(tab)) }.getOrDefault(emptyList())
        }
    }
    LazyColumn(
        modifier.fillMaxSize().background(c.editorBackground).padding(horizontal = 26.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item { Spacer(Modifier.height(16.dp)) }
        itemsIndexed(blocks) { i, block -> MarkdownBlock(block, previous = blocks.getOrNull(i - 1)) }
        item { Spacer(Modifier.height(48.dp)) }
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock, previous: MdBlock?) {
    val c = LocalWorkbenchColors.current
    val quote = block.quoteDepth.toInt()
    val indent = (block.listDepth.toInt() * 20).dp
    Row(Modifier.fillMaxWidth()) {
        repeat(quote) {
            // VS Code: textBlockQuote.border bar with a tinted background.
            Box(Modifier.width(5.dp).heightIn(min = 22.dp).background(c.descriptionForeground.copy(alpha = 0.5f)))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f).padding(start = indent)) {
            when (val k = block.kind) {
                is MdKind.Heading -> Heading(block, k.level.toInt(), first = previous == null)
                is MdKind.Paragraph -> Paragraph(block.spans, top = 0.dp)
                is MdKind.ListItem -> ListItem(block, k)
                is MdKind.Code -> CodeBlock(block)
                is MdKind.Rule -> Box(
                    Modifier.padding(vertical = 14.dp).fillMaxWidth().height(2.dp).background(c.panelBorder),
                )
                is MdKind.TableRow -> TableRow(k)
                is MdKind.Image -> Text(
                    "🖼 " + block.spans.joinToString("") { it.text }.ifEmpty { k.source },
                    color = c.descriptionForeground,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun Heading(block: MdBlock, level: Int, first: Boolean) {
    val c = LocalWorkbenchColors.current
    val size = when (level) {
        1 -> 28.sp
        2 -> 21.sp
        3 -> 17.5.sp
        4 -> 14.sp
        5 -> 12.sp
        else -> 11.sp
    }
    Column(Modifier.padding(top = if (first) 0.dp else 18.dp, bottom = 10.dp)) {
        Text(
            inline(block.spans, c),
            color = c.editorForeground,
            fontSize = size,
            fontWeight = FontWeight.SemiBold,
            lineHeight = size * 1.25f,
        )
        if (level <= 2) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.panelBorder))
        }
    }
}

@Composable
private fun Paragraph(spans: List<MdSpan>, top: androidx.compose.ui.unit.Dp) {
    val c = LocalWorkbenchColors.current
    Text(
        inline(spans, c),
        color = c.editorForeground,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        modifier = Modifier.padding(top = top, bottom = 12.dp),
    )
}

@Composable
private fun ListItem(block: MdBlock, k: MdKind.ListItem) {
    val c = LocalWorkbenchColors.current
    Row(Modifier.padding(bottom = 4.dp)) {
        val marker = when {
            k.checked == true -> "☑"
            k.checked == false -> "☐"
            k.ordered -> "${k.number}."
            block.listDepth.toInt() % 3 == 0 -> "•"
            block.listDepth.toInt() % 3 == 1 -> "◦"
            else -> "▪"
        }
        Text(marker, color = c.editorForeground, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.widthIn(min = 22.dp))
        Text(inline(block.spans, c), color = c.editorForeground, fontSize = 14.sp, lineHeight = 22.sp)
    }
}

@Composable
private fun CodeBlock(block: MdBlock) {
    val c = LocalWorkbenchColors.current
    val code = block.spans.joinToString("") { it.text }
    Box(
        Modifier
            .padding(bottom = 14.dp)
            .fillMaxWidth()
            .background(codeBlockBackground(c), RoundedCornerShape(3.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(code, color = c.editorForeground, fontSize = 13.sp, lineHeight = 19.sp, fontFamily = FontFamily.Monospace, softWrap = false)
    }
}

@Composable
private fun TableRow(k: MdKind.TableRow) {
    val c = LocalWorkbenchColors.current
    Row(Modifier.fillMaxWidth().border(0.5.dp, c.panelBorder)) {
        k.cells.forEachIndexed { i, cell ->
            val align = when (k.align.getOrNull(i)) {
                MdAlign.CENTER -> TextAlign.Center
                MdAlign.RIGHT -> TextAlign.End
                else -> TextAlign.Start
            }
            Text(
                inline(cell, c),
                color = c.editorForeground,
                fontSize = 14.sp,
                fontWeight = if (k.header) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = align,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** VS Code's `textCodeBlock.background` in Dark/Light Modern. */
private fun codeBlockBackground(c: WorkbenchColors) = if (c.isDark) Color(0xFF2B2B2B) else Color(0xFFF2F2F2)

/** Inline spans: bold/italic/strike, `code` in `textPreformat` colors, links in `textLink`. */
@Composable
private fun inline(spans: List<MdSpan>, c: WorkbenchColors): AnnotatedString {
    val uri = LocalUriHandler.current
    val link = c.textLinkForeground
    val preformat = c.token(StyleKind.ESCAPE)
    return buildAnnotatedString {
        for (s in spans) {
            val style = SpanStyle(
                fontWeight = if (s.bold) FontWeight.Bold else null,
                fontStyle = if (s.italic) FontStyle.Italic else null,
                textDecoration = if (s.strike) TextDecoration.LineThrough else null,
                fontFamily = if (s.code) FontFamily.Monospace else null,
                color = if (s.code) preformat else Color.Unspecified,
                background = if (s.code) codeBlockBackground(c) else Color.Unspecified,
            )
            val target = s.link
            if (target != null && (target.startsWith("http://") || target.startsWith("https://"))) {
                withLink(
                    LinkAnnotation.Url(target, TextLinkStyles(SpanStyle(color = link))) { runCatching { uri.openUri(target) } },
                ) { withStyle(style) { append(s.text) } }
            } else if (target != null) {
                withStyle(style.merge(SpanStyle(color = link))) { append(s.text) }
            } else {
                withStyle(style) { append(s.text) }
            }
        }
    }
}
