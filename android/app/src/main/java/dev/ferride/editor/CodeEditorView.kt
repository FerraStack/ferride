package dev.ferride.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorBoundsInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Magnifier
import android.widget.OverScroller
import dev.ferride.core.StyleKind
import dev.ferride.core.ViewLine
import java.text.BreakIterator
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The code editor surface: a plain Android [View] that draws the visible lines
 * of an [EditorModel] (gutter, current line, selection, find matches,
 * diagnostics, highlighted text, indent guides, bracket pair colors, caret,
 * scrollbars) and talks to the soft keyboard through [EditorInputConnection].
 * Hosted in Compose with `AndroidView`.
 *
 * With `editor.wordWrap` on, each logical line is split into visual rows
 * (breaking after whitespace when possible); continuation rows are indented
 * like the line itself, as in VS Code's `wrappingIndent: "same"`.
 */
class CodeEditorView(context: Context) : View(context), EditorActions {

    var model: EditorModel? = null
        private set

    /** The model unless it has been closed (IME callbacks can race a tab close). */
    val liveModel: EditorModel? get() = model?.takeIf { !it.isClosed }

    /** Focus changes, for keybinding contexts (VS Code's `editorTextFocus`). */
    var onFocus: ((Boolean) -> Unit)? = null

    /** Called after edits (`true`) and selection-only changes (`false`). */
    var onChange: ((contentChanged: Boolean) -> Unit)? = null

    /**
     * The floating selection toolbar should show at [SelectionUi] (or hide, on
     * `null`). The host draws it (Compose) and calls [onSelectionToolbarAction].
     */
    var onSelectionUi: ((SelectionUi?) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(lastSelectionUi)
        }

    var style: EditorStyle = EditorStyle(
        background = 0xFF1F1F1F.toInt(), foreground = 0xFFD4D4D4.toInt(), lineHighlight = 0xFF282828.toInt(),
        selection = 0xFF264F78.toInt(), cursor = 0xFFAEAFAD.toInt(), lineNumber = 0xFF6E7681.toInt(),
        lineNumberActive = 0xFFCCCCCC.toInt(), indentGuide = 0xFF404040.toInt(), tokens = emptyMap(),
    )
        set(value) {
            val metricsChanged = field.fontSizeSp != value.fontSizeSp || field.tabSize != value.tabSize
            val wrapChanged = field.wordWrap != value.wordWrap
            field = value
            if (metricsChanged) updateMetrics()
            if (metricsChanged || wrapChanged) {
                wrap = null
                if (value.wordWrap) sx = 0f
            }
            invalidateLines()
        }

    /** Find-widget matches (UTF-16 [start, end) pairs) and the current one. */
    var findMatches: List<IntRange> = emptyList()
        set(value) {
            field = value
            invalidate()
        }
    var currentMatch: Int = -1
        set(value) {
            field = value
            invalidate()
        }

    /** Diagnostics drawn as squiggly underlines. */
    var squiggles: List<Squiggle> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.RIGHT
    }
    private val fillPaint = Paint()
    private val squigglePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val squigglePath = Path()

    private var lineHeight = 1f
    private var baseline = 0f
    private var charWidth = 1f
    private var gutterWidth = 0f
    private val textPad get() = charWidth
    private val density get() = resources.displayMetrics.density

    private var sx = 0f
    private var sy = 0f
    private var maxLineWidth = 0f
    private val scroller = OverScroller(context)
    private val scrollMemory = HashMap<EditorModel, Pair<Float, Float>>()

    // Cached viewport lines for the current model version.
    private var cache: List<ViewLine> = emptyList()
    private var cacheFirst = -1
    private var cacheCount = 0
    private var cacheVersion = -1L

    private var composingStart = -1
    private var composingEnd = -1
    private var inputConnection: EditorInputConnection? = null
    private var preferredX = -1f

    private var caretVisible = true
    private val blink = object : Runnable {
        override fun run() {
            caretVisible = !caretVisible
            invalidate()
            postDelayed(this, BLINK_MS)
        }
    }

    private val imm get() = context.getSystemService(InputMethodManager::class.java)

    /** Where selection/extracted-text/cursor-anchor updates go (a recorder in tests). */
    var imeSink: ImeSink = ImmSink { imm }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        updateMetrics()
    }

    fun setModel(newModel: EditorModel?) {
        if (newModel === model) return
        liveModel?.let {
            scrollMemory[it] = sx to sy
            it.sealUndoGroup()
        }
        scrollMemory.keys.removeAll { it.isClosed }
        model = newModel
        hideSelectionControls()
        val (x, y) = newModel?.let { scrollMemory[it] } ?: (0f to 0f)
        sx = x
        sy = y
        maxLineWidth = 0f
        wrap = null
        clearComposing()
        invalidateLines()
        if (hasFocus()) imeSink.restartInput(this)
    }

    private fun updateMetrics() {
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, style.fontSizeSp, resources.displayMetrics)
        textPaint.textSize = px
        gutterPaint.textSize = px
        val fm = textPaint.fontMetrics
        lineHeight = ceil((fm.descent - fm.ascent) * 1.35f)
        baseline = (lineHeight - (fm.descent - fm.ascent)) / 2f - fm.ascent
        charWidth = textPaint.measureText("M").coerceAtLeast(1f)
        requestLayout()
    }

    private fun invalidateLines() {
        cacheVersion = -1
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) wrap = null
        if (h != oldh) post { ensureCaretVisible() }
        if (w != oldw || h != oldh) {
            post {
                publishSelectionUi()
                if (cursorAnchorMonitor) publishCursorAnchorInfo()
            }
        }
    }

    // ----- layout: tabs and wrapping -----------------------------------------------

    /**
     * Display form of a line: tabs expanded. `map[col]` = display column,
     * `xs[dcol]` = x of a display column (prefix widths, so x lookups are O(1)).
     */
    private class Layout(val text: String, val display: String, val map: IntArray, val xs: FloatArray) {
        val width: Float get() = xs.last()
    }

    private val layoutCache = HashMap<String, Layout>()
    private var layoutCacheKey = 0f to 0

    private fun layout(text: String): Layout {
        val key = textPaint.textSize to style.tabSize
        if (key != layoutCacheKey || layoutCache.size > 2048) {
            layoutCache.clear()
            layoutCacheKey = key
        }
        return layoutCache.getOrPut(text) { buildLayout(text) }
    }

    private fun withWidths(text: String, display: String, map: IntArray): Layout {
        val widths = FloatArray(display.length)
        if (display.isNotEmpty()) textPaint.getTextWidths(display, widths)
        val xs = FloatArray(display.length + 1)
        for (i in widths.indices) xs[i + 1] = xs[i] + widths[i]
        return Layout(text, display, map, xs)
    }

    private fun buildLayout(text: String): Layout {
        if ('\t' !in text) return withWidths(text, text, IntArray(text.length + 1) { it })
        val sb = StringBuilder()
        val map = IntArray(text.length + 1)
        for (i in text.indices) {
            map[i] = sb.length
            if (text[i] == '\t') {
                val n = style.tabSize - (sb.length % style.tabSize)
                repeat(n) { sb.append(' ') }
            } else {
                sb.append(text[i])
            }
        }
        map[text.length] = sb.length
        return withWidths(text, sb.toString(), map)
    }

    private fun xOf(layout: Layout, col: Int): Float = layout.xs[layout.map[col.coerceIn(0, layout.map.size - 1)]]

    /** Nearest column boundary to [x] (binary search over prefix widths). */
    private fun colAtX(layout: Layout, x: Float): Int {
        val map = layout.map
        var lo = 0
        var hi = map.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (xOf(layout, mid + 1) <= x) lo = mid + 1 else hi = mid
        }
        if (lo < map.size - 1) {
            val left = xOf(layout, lo)
            val right = xOf(layout, lo + 1)
            if (x - left > right - x) lo++
        }
        // Never land inside a grapheme cluster (surrogate pair, emoji sequence, e + accent).
        return snapToGrapheme(layout, lo, x)
    }

    private val graphemes: BreakIterator = BreakIterator.getCharacterInstance()

    /** Is [col] a grapheme boundary of [text]? Cheap for the common case (no marks/joiners/surrogates). */
    private fun isGraphemeBoundary(text: String, col: Int): Boolean {
        if (col <= 0 || col >= text.length) return true
        if (text[col].code < 0x300 && !Character.isHighSurrogate(text[col - 1])) return true
        graphemes.setText(text)
        return graphemes.isBoundary(col)
    }

    /** The grapheme boundary nearest to [x] around [col] (hit testing). */
    private fun snapToGrapheme(layout: Layout, col: Int, x: Float): Int {
        val text = layout.text
        if (isGraphemeBoundary(text, col)) return col
        val before = graphemes.preceding(col).coerceAtLeast(0)
        val after = graphemes.following(col).let { if (it == BreakIterator.DONE) text.length else it }
        return if (x - xOf(layout, before) <= xOf(layout, after) - x) before else after
    }

    /**
     * Visual rows for the whole document (only when wrapping). `rowStart[line]`
     * is the first visual row of a line; `breaks[line]` are the columns where its
     * rows start (always beginning with 0); `indent[line]` is the continuation
     * indent in px.
     */
    private class Wrap(
        val version: Long,
        val width: Float,
        val rowStart: IntArray,
        val breaks: Array<IntArray>,
        val indent: FloatArray,
        val texts: List<String>,
    )

    private var wrap: Wrap? = null
    private val wrapping get() = style.wordWrap && (liveModel?.length ?: 0) <= MAX_WRAP_CHARS

    private val textAreaWidth get() = max(charWidth * 8, textRight - textLeft - charWidth)

    private fun wrapInfo(): Wrap? {
        val m = liveModel ?: return null
        if (!wrapping || width == 0) return null
        val w = textAreaWidth
        wrap?.let { if (it.version == m.version && it.width == w) return it }
        // Lines end at \n only (a CR before it is part of the terminator), like the core.
        val texts = m.text().split('\n').map { it.removeSuffix("\r") }
        val rowStart = IntArray(texts.size + 1)
        val breaks = arrayOfNulls<IntArray>(texts.size)
        val indent = FloatArray(texts.size)
        var row = 0
        texts.forEachIndexed { i, t ->
            rowStart[i] = row
            val (b, ind) = computeBreaks(t, w)
            breaks[i] = b
            indent[i] = ind
            row += b.size
        }
        rowStart[texts.size] = row
        @Suppress("UNCHECKED_CAST")
        return Wrap(m.version, w, rowStart, breaks as Array<IntArray>, indent, texts).also { wrap = it }
    }

    private fun computeBreaks(text: String, width: Float): Pair<IntArray, Float> {
        val lay = layout(text)
        if (text.isEmpty() || lay.width <= width) return intArrayOf(0) to 0f
        val leading = text.indexOfFirst { it != ' ' && it != '\t' }.let { if (it < 0) 0 else it }
        val indentPx = xOf(lay, leading).let { if (it > width / 2) 0f else it }
        val breaks = mutableListOf(0)
        var start = 0
        var avail = width
        while (true) {
            val dStart = lay.map[start]
            val fit = textPaint.breakText(lay.display, dStart, lay.display.length, true, avail, null)
            val dEnd = dStart + max(1, fit)
            if (dEnd >= lay.display.length) break
            var col = start
            while (col < text.length && lay.map[col + 1] <= dEnd) col++
            if (col <= start) col = start + 1
            // Wrap between grapheme clusters, never inside one.
            if (!isGraphemeBoundary(text, col)) {
                val before = graphemes.preceding(col)
                col = if (before > start) before else graphemes.following(col).let { if (it == BreakIterator.DONE) text.length else it }
            }
            // Prefer breaking after whitespace.
            val space = text.lastIndexOf(' ', col - 1)
            if (space >= start + 1 && space + 1 < col) col = space + 1
            if (col <= start) col = start + 1
            breaks += col
            start = col
            avail = width - indentPx
        }
        return breaks.toIntArray() to indentPx
    }

    private fun lineCount() = liveModel?.lineCount ?: 1
    private fun totalRows(): Int = wrapInfo()?.rowStart?.last() ?: lineCount()
    private fun rowStartOf(line: Int): Int = wrapInfo()?.rowStart?.getOrNull(line) ?: line
    private fun breaksOf(line: Int): IntArray = wrapInfo()?.breaks?.getOrNull(line) ?: NO_BREAKS
    private fun indentOf(line: Int): Float = wrapInfo()?.indent?.getOrNull(line) ?: 0f

    private fun lineOfRow(row: Int): Int {
        val w = wrapInfo() ?: return row.coerceIn(0, lineCount() - 1)
        val rs = w.rowStart
        var lo = 0
        var hi = rs.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (rs[mid] <= row) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun lineText(line: Int): String {
        wrapInfo()?.texts?.getOrNull(line)?.let { return it }
        val m = liveModel ?: return ""
        return m.textRange(m.lineStart(line), m.lineEnd(line))
    }

    /** Row index within its line for a column (a caret at a break belongs to the next row). */
    private fun rowIn(breaks: IntArray, col: Int): Int {
        var r = 0
        while (r + 1 < breaks.size && breaks[r + 1] <= col) r++
        return r
    }

    // ----- geometry -----------------------------------------------------------------

    private fun computeGutter() {
        gutterWidth = if (style.lineNumbers) {
            val digits = max(3, lineCount().toString().length)
            charWidth * (digits + 3)
        } else {
            charWidth * 1.5f
        }
    }

    private val textLeft get() = gutterWidth + textPad
    private val maxScrollY get() = max(0f, (totalRows() - 1) * lineHeight)
    private val maxScrollX get() = if (wrapping) 0f else max(0f, maxLineWidth + charWidth * 4 - (textRight - textLeft))

    private fun lines(first: Int, count: Int): List<ViewLine> {
        val m = liveModel ?: return emptyList()
        if (m.version != cacheVersion || first != cacheFirst || count != cacheCount) {
            cache = m.viewport(first, count)
            cacheFirst = first
            cacheCount = count
            cacheVersion = m.version
        }
        return cache
    }

    /** Document offset under a view coordinate. */
    private fun offsetAt(x: Float, y: Float): Int {
        val m = liveModel ?: return 0
        val row = ((y + sy) / lineHeight).toInt().coerceIn(0, max(0, totalRows() - 1))
        val line = lineOfRow(row).coerceIn(0, m.lineCount - 1)
        val text = lineText(line)
        val lay = layout(text)
        val breaks = breaksOf(line)
        val r = (row - rowStartOf(line)).coerceIn(0, breaks.size - 1)
        val a = breaks[r]
        val b = if (r + 1 < breaks.size) breaks[r + 1] else text.length
        val xIn = x - textLeft + sx - (if (r > 0) indentOf(line) else 0f) + xOf(lay, a)
        val col = colAtX(lay, xIn).coerceIn(a, if (r + 1 < breaks.size) b - 1 else b)
        return m.lineStart(line) + col
    }

    /** Caret position in content coordinates (x relative to the text origin, y = row top). */
    private fun caretPoint(offset: Int): Pair<Float, Float> {
        val m = liveModel ?: return 0f to 0f
        val line = m.lineOfOffset(offset)
        val col = offset - m.lineStart(line)
        val lay = layout(lineText(line))
        val breaks = breaksOf(line)
        val r = rowIn(breaks, col)
        val x = (if (r > 0) indentOf(line) else 0f) + xOf(lay, col) - xOf(lay, breaks[r])
        return x to (rowStartOf(line) + r) * lineHeight
    }

    // ----- drawing --------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        computeGutter()
        // Fill our bounds only (drawColor ignores view bounds on unclipped canvases).
        fillPaint.color = style.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
        val m = liveModel ?: return
        val firstRow = max(0, (sy / lineHeight).toInt())
        val lastRow = min(totalRows() - 1, ((sy + height) / lineHeight).toInt() + 1)
        val firstLine = lineOfRow(firstRow)
        val lastLine = lineOfRow(lastRow)
        val lines = lines(firstLine, lastLine - firstLine + 1)
        val sel = m.selection
        val caretLine = m.lineOfOffset(sel.head)

        canvas.save()
        canvas.clipRect(gutterWidth, 0f, textRight, height.toFloat())
        for (vl in lines) {
            val layout = layout(vl.text)
            val lineStart = vl.startOffset
            val lineEnd = lineStart + vl.text.length
            val breaks = breaksOf(vl.line)
            val rowBase = rowStartOf(vl.line)
            val indent = indentOf(vl.line)
            val colors = colorsFor(vl)
            for (r in breaks.indices) {
                val a = breaks[r]
                val b = if (r + 1 < breaks.size) breaks[r + 1] else vl.text.length
                val top = (rowBase + r) * lineHeight - sy
                if (top > height || top + lineHeight < 0) continue
                val rowX = textLeft - sx + (if (r > 0) indent else 0f) - xOf(layout, a)
                fun x(col: Int) = rowX + xOf(layout, col)
                if (style.highlightActiveLine && vl.line == caretLine && sel.isEmpty) {
                    fillPaint.color = style.lineHighlight
                    canvas.drawRect(gutterWidth, top, textRight, top + lineHeight, fillPaint)
                }
                // Find matches.
                findMatches.forEachIndexed { i, mr ->
                    val s = max(mr.first, lineStart + a)
                    val e = min(mr.last + 1, lineStart + b)
                    if (s < e) {
                        fillPaint.color = if (i == currentMatch) style.findMatchCurrent else style.findMatch
                        canvas.drawRect(x(s - lineStart), top, x(e - lineStart), top + lineHeight, fillPaint)
                    }
                }
                // Selection.
                if (!sel.isEmpty && sel.end >= lineStart + a && sel.start <= lineStart + b) {
                    val s = max(sel.start, lineStart + a) - lineStart
                    val e = min(sel.end, lineStart + b) - lineStart
                    var x1 = x(e)
                    if (sel.end > lineEnd && r == breaks.size - 1) x1 += charWidth * 0.6f
                    fillPaint.color = style.selection
                    canvas.drawRect(x(s), top, x1, top + lineHeight, fillPaint)
                }
                if (r == 0 && style.indentGuides) drawIndentGuides(canvas, layout, top)
                drawRun(canvas, layout, colors, a, b, rowX, top + baseline)
                drawSquiggles(canvas, lineStart + a, lineStart + b, ::x, top + lineHeight - 2f * density)
                // Composing underline.
                val cs = max(composingStart, lineStart + a)
                val ce = min(composingEnd, lineStart + b)
                if (composingStart >= 0 && cs < ce) {
                    fillPaint.color = style.foreground
                    val y = top + lineHeight - 2f
                    canvas.drawRect(x(cs - lineStart), y - 2f, x(ce - lineStart), y, fillPaint)
                }
            }
            if (!wrapping) maxLineWidth = max(maxLineWidth, layout.width)
        }
        // Caret.
        if (hasFocus() && caretVisible) {
            val (cx, cy) = caretPoint(sel.head)
            fillPaint.color = style.cursor
            val x = textLeft - sx + cx
            canvas.drawRect(x, cy - sy, x + 2f * density, cy - sy + lineHeight, fillPaint)
        }
        canvas.restore()
        drawGutter(canvas, firstRow, lastRow, caretLine)
        drawStickyScroll(canvas, caretLine)
        drawMinimap(canvas)
        drawScrollbars(canvas)
        drawHandles(canvas)
    }

    /**
     * Per-char colors for a line: syntax spans, then bracket pair colors from the
     * core's tree-sitter bracket tokens (so brackets in strings/comments are left alone).
     */
    private fun colorsFor(vl: ViewLine): IntArray {
        val text = vl.text
        val colors = IntArray(text.length) { style.foreground }
        val styles = style.tokenStyles
        fonts = if (fonts.size >= text.length) fonts.also { it.fill(0) } else IntArray(text.length)
        for (s in vl.spans) {
            val a = s.start.coerceIn(0, text.length)
            val b = s.end.coerceIn(a, text.length)
            val themed = if (style.kindColors) null else styles?.color(s.scope)
            val color = themed ?: style.tokens[s.kind] ?: style.foreground
            colors.fill(color, a, b)
            if (styles != null) fonts.fill(styles.fontStyle(s.scope), a, b)
        }
        if (style.bracketPairs) {
            for (mark in vl.brackets) {
                if (mark.column in text.indices) {
                    colors[mark.column] = style.bracketColors[mark.depth.mod(style.bracketColors.size)]
                }
            }
        }
        return colors
    }

    private fun drawIndentGuides(canvas: Canvas, layout: Layout, top: Float) {
        val indent = layout.display.takeWhile { it == ' ' }.length
        fillPaint.color = style.indentGuide
        var col = style.tabSize
        while (col <= indent && col < layout.display.length) {
            val x = textLeft - sx + layout.xs[col - style.tabSize]
            canvas.drawRect(x, top, x + 1f, top + lineHeight, fillPaint)
            col += style.tabSize
        }
    }

    /** Draws columns [a, b) in same-color runs starting at [rowX] (x of column 0). */
    private fun drawRun(canvas: Canvas, layout: Layout, colors: IntArray, a: Int, b: Int, rowX: Float, y: Float) {
        var i = a
        val right = width.toFloat()
        while (i < b) {
            var j = i + 1
            while (j < b && colors[j] == colors[i] && fontAt(j) == fontAt(i)) j++
            val d0 = layout.map[i]
            val d1 = layout.map[j]
            val x0 = rowX + layout.xs[d0]
            if (x0 > right) break
            if (rowX + layout.xs[d1] >= gutterWidth) {
                textPaint.color = colors[i]
                val font = fontAt(i)
                if (font != 0) TokenStyles.apply(textPaint, font)
                canvas.drawText(layout.display, d0, d1, x0, y, textPaint)
                if (font != 0) TokenStyles.apply(textPaint, 0)
            }
            i = j
        }
    }

    /** Theme font style bits (italic/bold/underline/strikethrough) of the line last passed to [colorsFor]. */
    private var fonts = IntArray(0)

    private fun fontAt(col: Int): Int = fonts.getOrElse(col) { 0 }

    private fun drawSquiggles(canvas: Canvas, rowStart: Int, rowEnd: Int, x: (Int) -> Float, y: Float) {
        val m = liveModel ?: return
        if (squiggles.isEmpty()) return
        val lineStart = m.lineStart(m.lineOfOffset(rowStart))
        for (sq in squiggles) {
            val s = max(sq.start, rowStart)
            val e = min(max(sq.end, sq.start + 1), rowEnd)
            if (s >= e) continue
            squigglePaint.color = when (sq.severity) {
                1 -> style.error
                2 -> style.warning
                else -> style.info
            }
            val x0 = x(s - lineStart)
            val x1 = max(x(e - lineStart), x0 + charWidth)
            val amp = 1.6f * density
            val step = 2.5f * density
            squigglePath.reset()
            squigglePath.moveTo(x0, y)
            var px = x0
            var up = true
            while (px < x1) {
                px = min(px + step, x1)
                squigglePath.lineTo(px, if (up) y - amp else y)
                up = !up
            }
            canvas.drawPath(squigglePath, squigglePaint)
        }
    }

    private fun drawGutter(canvas: Canvas, firstRow: Int, lastRow: Int, caretLine: Int) {
        fillPaint.color = style.background
        canvas.drawRect(0f, 0f, gutterWidth, height.toFloat(), fillPaint)
        if (!style.lineNumbers) return
        val x = gutterWidth - charWidth * 1.5f
        // Numbers only on the first visual row of each line.
        var line = lineOfRow(firstRow)
        while (line < lineCount()) {
            val row = rowStartOf(line)
            if (row > lastRow) break
            gutterPaint.color = if (line == caretLine) style.lineNumberActive else style.lineNumber
            canvas.drawText((line + 1).toString(), x, row * lineHeight - sy + baseline, gutterPaint)
            line++
        }
    }

    private fun drawScrollbars(canvas: Canvas) {
        val thickness = 4f * density
        fillPaint.color = style.scrollbar
        val contentH = totalRows() * lineHeight + height * 0.5f
        if (contentH > height) {
            val thumbH = max(24f * density, height * height / contentH)
            val top = (sy / max(1f, maxScrollY)) * (height - thumbH)
            canvas.drawRect(width - thickness, top, width.toFloat(), top + thumbH, fillPaint)
        }
        val maxX = maxScrollX
        if (maxX > 0f) {
            val area = textRight - textLeft
            val thumbW = max(24f * density, area * area / (area + maxX))
            val left = textLeft + (sx / maxX) * (area - thumbW - thickness)
            canvas.drawRect(left, height - thickness, left + thumbW, height.toFloat(), fillPaint)
        }
    }

    // ----- scrolling & touch ------------------------------------------------------------

    private fun scrollToClamped(x: Float, y: Float) {
        sx = x.coerceIn(0f, maxScrollX)
        sy = y.coerceIn(0f, maxScrollY)
        invalidate()
        if (toolbarShown) publishSelectionUi()
        if (cursorAnchorMonitor) publishCursorAnchorInfo()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollToClamped(scroller.currX.toFloat(), scroller.currY.toFloat())
            postInvalidateOnAnimation()
        } else if (restoreToolbar) {
            // A fling hid the toolbar; bring it back once the content settles.
            restoreToolbar = false
            showSelectionControls(toolbar = true)
        }
    }

    private fun ensureCaretVisible() {
        val m = liveModel ?: return
        if (width == 0 || height == 0) return
        computeGutter()
        val (cx, cy) = caretPoint(m.selection.head)
        var nx = sx
        var ny = sy
        if (cy < sy) ny = cy
        if (cy + lineHeight > sy + height) ny = cy + lineHeight - height
        val areaWidth = textRight - textLeft
        if (!wrapping) {
            if (cx < sx) nx = max(0f, cx - charWidth * 4)
            if (cx > sx + areaWidth - charWidth * 2) nx = cx - areaWidth + charWidth * 4
            maxLineWidth = max(maxLineWidth, cx)
        }
        scrollToClamped(nx, ny)
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val m = liveModel ?: return true
            val hadComposing = composingRange() != null
            val offset = offsetAt(e.x, e.y)
            // Tapping the caret again toggles the Paste / Select All toolbar (Android's insertion menu).
            val tappedCaret = handlesShown && m.selection.isEmpty && m.selection.head == offset
            clearComposing()
            m.sealUndoGroup()
            m.selection = Sel.caret(offset)
            preferredX = -1f
            requestFocus()
            if (hadComposing) imeSink.restartInput(this@CodeEditorView)
            showKeyboard()
            afterSelectionChange(fromIme = false)
            restoreToolbar = false
            showSelectionControls(toolbar = tappedCaret && !toolbarShown)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            selectWordAt(e)
            showSelectionControls(toolbar = true)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            selectWordAt(e)
            // Keep the finger down and drag to extend the selection word by word.
            longPressWord = liveModel?.selection
            lastTouchX = e.x
            lastTouchY = e.y
            showSelectionControls(toolbar = false)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (toolbarShown) {
                // Like Android's text toolbar: hidden while the content moves, back afterwards.
                toolbarShown = false
                restoreToolbar = true
                publishSelectionUi()
            }
            // Lock to the dominant axis for steadier scrolling.
            when {
                abs(dy) > abs(dx) * 1.5f -> scrollToClamped(sx, sy + dy)
                abs(dx) > abs(dy) * 1.5f -> scrollToClamped(sx + dx, sy)
                else -> scrollToClamped(sx + dx, sy + dy)
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val horizontal = abs(vx) > abs(vy) * 1.5f
            val vertical = abs(vy) > abs(vx) * 1.5f
            scroller.fling(
                sx.roundToInt(), sy.roundToInt(),
                if (vertical) 0 else -vx.roundToInt(), if (horizontal) 0 else -vy.roundToInt(),
                0, maxScrollX.roundToInt(), 0, maxScrollY.roundToInt(),
            )
            postInvalidateOnAnimation()
            return true
        }
    })

    private fun selectWordAt(e: MotionEvent) {
        val m = liveModel ?: return
        val hadComposing = composingRange() != null
        clearComposing()
        m.sealUndoGroup()
        m.selection = m.wordAt(offsetAt(e.x, e.y))
        requestFocus()
        if (hadComposing) imeSink.restartInput(this)
        restoreToolbar = false
        afterSelectionChange(fromIme = false)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onStickyTouch(event)) return true
        if (onMinimapTouch(event)) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val handle = handleAt(event.x, event.y)
            if (handle != null && liveModel != null) {
                startHandleDrag(handle, event)
                return true
            }
        }
        if (dragHandle != null) {
            onHandleDrag(event)
            return true
        }
        val handled = gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (longPressWord != null) {
                lastTouchX = event.x
                lastTouchY = event.y
                updateLongPressDrag()
                startAutoScroll()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (longPressWord != null) {
                    longPressWord = null
                    if (event.actionMasked == MotionEvent.ACTION_UP) showSelectionControls(toolbar = true)
                } else if (restoreToolbar && scroller.isFinished) {
                    restoreToolbar = false
                    showSelectionControls(toolbar = true)
                }
            }
        }
        return handled || super.onTouchEvent(event)
    }

    // ----- touch selection: handles + floating toolbar ---------------------------------------

    private enum class Handle { Start, End, Caret }

    /** Selection handles (or the caret handle for an empty selection) are visible. */
    private var handlesShown = false
    /** The floating toolbar is requested (the host hides it while the selection is off-screen). */
    private var toolbarShown = false
    /** The toolbar was hidden by a scroll/fling and comes back when it ends. */
    private var restoreToolbar = false
    private var lastSelectionUi: SelectionUi? = null

    private var dragHandle: Handle? = null
    private var dragDx = 0f
    private var dragDy = 0f
    private var dragMoved = false
    private var downX = 0f
    private var downY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    /** The long-pressed word while the finger is still down (drag extends by words). */
    private var longPressWord: Sel? = null
    private var autoScrolling = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val handleRadius get() = 11f * density
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** The platform magnifier while dragging a handle (off in JVM tests, which have no window surface). */
    internal var magnifierEnabled = true
    private val magnifier: Magnifier? by lazy {
        if (magnifierEnabled) runCatching { Magnifier.Builder(this).build() }.getOrNull() else null
    }

    private val hideCaretHandle = Runnable {
        if (handlesShown && !toolbarShown && dragHandle == null && liveModel?.selection?.isEmpty != false) {
            handlesShown = false
            invalidate()
        }
    }

    /** Shows the handles for the current selection and, with [toolbar], the floating toolbar. */
    fun showSelectionControls(toolbar: Boolean) {
        val m = liveModel ?: return
        handlesShown = true
        toolbarShown = toolbar
        removeCallbacks(hideCaretHandle)
        if (m.selection.isEmpty && !toolbar) postDelayed(hideCaretHandle, CARET_HANDLE_MS)
        invalidate()
        publishSelectionUi()
    }

    /** Hides handles and toolbar (typing, keys, IME selection changes, focus loss, tab switch). */
    fun hideSelectionControls() {
        removeCallbacks(hideCaretHandle)
        if (!handlesShown && !toolbarShown && !restoreToolbar) return
        handlesShown = false
        toolbarShown = false
        restoreToolbar = false
        invalidate()
        publishSelectionUi()
    }

    /** Runs a toolbar entry (called by the host's toolbar). */
    fun onSelectionToolbarAction(action: SelectionAction) {
        when (action) {
            SelectionAction.Cut -> cut()
            SelectionAction.Copy -> {
                copy()
                // VS Code keeps the selection after copying; only the menu closes.
                toolbarShown = false
                publishSelectionUi()
            }
            SelectionAction.Paste -> paste()
            SelectionAction.SelectAll -> {
                selectAll()
                showSelectionControls(toolbar = true)
            }
        }
    }

    private fun publishSelectionUi() {
        val m = liveModel
        val ui = if (m == null || !toolbarShown || dragHandle != null || width == 0) null else selectionUi(m)
        if (ui == lastSelectionUi) return
        lastSelectionUi = ui
        onSelectionUi?.invoke(ui)
    }

    private fun selectionUi(m: EditorModel): SelectionUi? {
        computeGutter()
        val s = m.selection
        val (x1, y1) = caretPoint(s.start)
        val (x2, y2) = caretPoint(s.end)
        val top = y1 - sy
        val bottom = y2 - sy + lineHeight + (if (handlesShown) handleRadius * 2.5f else 0f)
        if (bottom < 0f || top > height) return null
        val oneRow = y1 == y2
        return SelectionUi(
            left = if (oneRow) textLeft - sx + x1 else textLeft,
            top = top,
            right = if (oneRow) textLeft - sx + x2 else width.toFloat(),
            bottom = bottom,
            hasSelection = !s.isEmpty,
            canPaste = clipboard?.hasPrimaryClip() == true,
        )
    }

    /** Where a handle points: x of [offset], bottom of its row (view coordinates). */
    private fun handlePoint(offset: Int): PointF {
        val (cx, cy) = caretPoint(offset)
        return PointF(textLeft - sx + cx, cy - sy + lineHeight)
    }

    /** Center of a handle's round body (the part a finger grabs). */
    private fun handleBody(kind: Handle, p: PointF): PointF {
        val r = handleRadius
        return when (kind) {
            Handle.Start -> PointF(p.x - r, p.y + r)
            Handle.End -> PointF(p.x + r, p.y + r)
            Handle.Caret -> PointF(p.x, p.y + r * SQRT2)
        }
    }

    /** Center of the row at [offset], in view coordinates (tests, accessibility). */
    internal fun pointOf(offset: Int): PointF {
        computeGutter()
        val (cx, cy) = caretPoint(offset)
        return PointF(textLeft - sx + cx, cy - sy + lineHeight / 2f)
    }

    /** Where to grab the visible handles: start/end for a selection, one caret handle otherwise (tests). */
    internal fun handleBodies(): List<PointF> {
        computeGutter()
        return visibleHandles().map { (kind, offset) -> handleBody(kind, handlePoint(offset)) }
    }

    private fun visibleHandles(): List<Pair<Handle, Int>> {
        val m = liveModel ?: return emptyList()
        if (!handlesShown) return emptyList()
        val s = m.selection
        return if (s.isEmpty) listOf(Handle.Caret to s.head) else listOf(Handle.Start to s.start, Handle.End to s.end)
    }

    private fun handleAt(x: Float, y: Float): Handle? {
        val reach = max(handleRadius * 2f, 24f * density)
        return visibleHandles()
            .map { (kind, offset) -> kind to handleBody(kind, handlePoint(offset)) }
            .map { (kind, c) -> kind to hypot(x - c.x, y - c.y) }
            .filter { it.second <= reach }
            .minByOrNull { it.second }?.first
    }

    private fun drawHandles(canvas: Canvas) {
        val handles = visibleHandles()
        if (handles.isEmpty()) return
        computeGutter()
        handlePaint.color = style.handle
        val r = handleRadius
        for ((kind, offset) in handles) {
            val p = handlePoint(offset)
            if (p.x < gutterWidth - 1f || p.x > width + r || p.y < 0f || p.y > height) continue
            canvas.save()
            // The caret handle is the start handle turned to point straight up.
            if (kind == Handle.Caret) canvas.rotate(-45f, p.x, p.y)
            if (kind == Handle.End) {
                canvas.drawCircle(p.x + r, p.y + r, r, handlePaint)
                canvas.drawRect(p.x, p.y, p.x + r, p.y + r, handlePaint)
            } else {
                canvas.drawCircle(p.x - r, p.y + r, r, handlePaint)
                canvas.drawRect(p.x - r, p.y, p.x, p.y + r, handlePaint)
            }
            canvas.restore()
        }
    }

    private fun startHandleDrag(handle: Handle, event: MotionEvent) {
        val m = liveModel ?: return
        scroller.forceFinished(true)
        if (composingRange() != null) {
            clearComposing()
            imeSink.restartInput(this)
        }
        m.sealUndoGroup()
        val s = m.selection
        val p = handlePoint(if (handle == Handle.Start) s.start else if (handle == Handle.End) s.end else s.head)
        dragHandle = handle
        dragMoved = false
        dragDx = event.x - p.x
        dragDy = event.y - p.y
        downX = event.x
        downY = event.y
        lastTouchX = event.x
        lastTouchY = event.y
        removeCallbacks(hideCaretHandle)
        restoreToolbar = false
        parent?.requestDisallowInterceptTouchEvent(true)
        publishSelectionUi()
    }

    private fun onHandleDrag(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragMoved && hypot(event.x - downX, event.y - downY) < touchSlop) return
                dragMoved = true
                lastTouchX = event.x
                lastTouchY = event.y
                updateHandleDrag()
                startAutoScroll()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val handle = dragHandle
                val wasTap = !dragMoved
                dragHandle = null
                runCatching { magnifier?.dismiss() }
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    publishSelectionUi()
                } else if (handle == Handle.Caret) {
                    // Tapping the caret handle toggles the insertion menu; dragging it just moves the caret.
                    showSelectionControls(toolbar = wasTap && !toolbarShown)
                } else {
                    showSelectionControls(toolbar = true)
                }
            }
        }
    }

    private fun updateHandleDrag() {
        val m = liveModel ?: return
        val handle = dragHandle ?: return
        val x = lastTouchX - dragDx
        val y = lastTouchY - dragDy - lineHeight / 2f
        val offset = offsetAt(x, y)
        val s = m.selection
        // Handles never cross: the dragged end keeps at least one character selected.
        val next = when (handle) {
            Handle.Caret -> Sel.caret(offset)
            Handle.Start -> Sel(s.end, if (offset < s.end) offset else stepBack(m, s.end))
            Handle.End -> Sel(s.start, if (offset > s.start) offset else stepForward(m, s.start))
        }
        if (next != s) {
            m.selection = next
            performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
            afterSelectionChange(fromIme = false)
        }
        // The magnifier is cosmetic: never let it break a drag (e.g. no window surface yet).
        val p = handlePoint(next.head)
        runCatching { magnifier?.show(x.coerceIn(textLeft, width.toFloat()), p.y - lineHeight / 2f) }
    }

    private fun updateLongPressDrag() {
        val m = liveModel ?: return
        val word = longPressWord ?: return
        val offset = offsetAt(lastTouchX, lastTouchY)
        val next = when {
            offset < word.start -> Sel(word.end, m.wordAt(offset).start)
            offset > word.end -> Sel(word.start, m.wordAt(offset).end)
            else -> word
        }
        if (next != m.selection) {
            m.selection = next
            performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
            afterSelectionChange(fromIme = false)
        }
    }

    /** While a handle (or a long press) is dragged near an edge, scroll toward it. */
    private val autoScroll = object : Runnable {
        override fun run() {
            val dragging = dragHandle != null || longPressWord != null
            val edge = lineHeight
            val dy = when {
                lastTouchY < edge -> -lineHeight / 2f
                lastTouchY > height - edge -> lineHeight / 2f
                else -> 0f
            }
            val dx = when {
                wrapping -> 0f
                lastTouchX < textLeft + charWidth -> -charWidth
                lastTouchX > width - charWidth * 2 -> charWidth
                else -> 0f
            }
            if (!dragging || (dx == 0f && dy == 0f)) {
                autoScrolling = false
                return
            }
            scrollToClamped(sx + dx, sy + dy)
            if (dragHandle != null) updateHandleDrag() else updateLongPressDrag()
            postOnAnimation(this)
        }
    }

    private fun startAutoScroll() {
        if (autoScrolling) return
        autoScrolling = true
        postOnAnimation(autoScroll)
    }

    fun showKeyboard() {
        imm?.showSoftInput(this, 0)
    }

    // ----- focus & blink ------------------------------------------------------------------

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        restartBlink()
        if (!gainFocus) {
            liveModel?.sealUndoGroup()
            hideSelectionControls()
        }
        onFocus?.invoke(gainFocus)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            removeCallbacks(blink)
            caretVisible = true
            invalidate()
        } else if (hasFocus()) {
            restartBlink()
        }
    }

    private fun restartBlink() {
        removeCallbacks(blink)
        caretVisible = true
        if (hasFocus() && hasWindowFocus()) postDelayed(blink, BLINK_MS)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blink)
        removeCallbacks(hideCaretHandle)
        super.onDetachedFromWindow()
    }

    // ----- IME -------------------------------------------------------------------------------

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        liveModel?.selection?.let {
            outAttrs.initialSelStart = it.start
            outAttrs.initialSelEnd = it.end
        }
        // A new connection starts without monitors; the keyboard asks again.
        extractMonitor = null
        cursorAnchorMonitor = false
        return EditorInputConnection(this).also { inputConnection = it }
    }

    // ----- cursor anchor info (#4) --------------------------------------------------------------
    // CJK keyboards place their candidate window from CursorAnchorInfo, stylus
    // handwriting (API 33+) needs the editor and line bounds, and the floating
    // cursor needs the insertion marker. Positions are view-local; the matrix
    // maps them to the screen.

    private var cursorAnchorMonitor = false
    private val anchorBuilder = CursorAnchorInfo.Builder()
    private val anchorMatrix = Matrix()
    private val screenLocation = IntArray(2)

    internal fun requestCursorUpdates(mode: Int): Boolean {
        cursorAnchorMonitor = mode and InputConnection.CURSOR_UPDATE_MONITOR != 0
        if (mode and InputConnection.CURSOR_UPDATE_IMMEDIATE != 0) publishCursorAnchorInfo()
        return true
    }

    private fun publishCursorAnchorInfo() {
        buildCursorAnchorInfo()?.let { imeSink.updateCursorAnchorInfo(this, it) }
    }

    internal fun buildCursorAnchorInfo(): CursorAnchorInfo? {
        val m = liveModel ?: return null
        if (width == 0 || height == 0) return null
        computeGutter()
        val s = m.selection
        val b = anchorBuilder
        b.reset()
        b.setSelectionRange(s.start, s.end)
        getLocationOnScreen(screenLocation)
        anchorMatrix.setTranslate(screenLocation[0].toFloat(), screenLocation[1].toFloat())
        b.setMatrix(anchorMatrix)
        val (cx, cy) = caretPoint(s.head)
        val x = textLeft - sx + cx
        val top = cy - sy
        b.setInsertionMarkerLocation(x, top, top + baseline, top + lineHeight, regionFlags(x, top))
        composingRange()?.let { (cs, ce) ->
            b.setComposingText(cs, m.textRange(cs, ce))
            for (i in cs until min(ce, cs + MAX_ANCHOR_CHARS)) {
                val (lx, ly) = caretPoint(i)
                val (rx, ry) = caretPoint(i + 1)
                val left = textLeft - sx + lx
                // The last char of a wrapped row ends at the row's end, not at the next row's start.
                val right = if (ry == ly) textLeft - sx + rx else left + charWidth
                val charTop = ly - sy
                b.addCharacterBounds(i, left, charTop, max(left, right), charTop + lineHeight, regionFlags(left, charTop))
            }
        }
        val editor = RectF(0f, 0f, width.toFloat(), height.toFloat())
        if (Build.VERSION.SDK_INT >= 33) {
            b.setEditorBoundsInfo(EditorBoundsInfo.Builder().setEditorBounds(editor).setHandwritingBounds(editor).build())
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val firstRow = max(0, (sy / lineHeight).toInt())
            val lastRow = min(totalRows() - 1, ((sy + height) / lineHeight).toInt())
            for (row in firstRow..lastRow) {
                val rowTop = row * lineHeight - sy
                b.addVisibleLineBounds(textLeft, max(0f, rowTop), width.toFloat(), min(height.toFloat(), rowTop + lineHeight))
            }
        }
        return b.build()
    }

    private fun regionFlags(x: Float, top: Float): Int =
        if (x >= gutterWidth && x <= width && top + lineHeight > 0 && top < height) {
            CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION
        } else {
            CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION
        }

    // ----- extracted text (#3) ------------------------------------------------------------------
    // Samsung Keyboard and older SwiftKey read "extracted text" for cursor
    // gestures and replacements. We hand out a window around the selection
    // (the whole document can be megabytes) and, when the keyboard asked to
    // monitor it, push updates after edits and when the selection leaves the
    // window it has.

    private var extractMonitor: ExtractedTextRequest? = null
    private var extractedStart = -1
    private var extractedEnd = -1
    private var extractedStale = false

    internal fun extractedText(request: ExtractedTextRequest?, monitor: Boolean): ExtractedText? {
        val m = liveModel ?: return null
        if (monitor) extractMonitor = request
        return buildExtractedText(m, request).also(::rememberExtracted)
    }

    internal fun stopExtractedTextMonitor() {
        extractMonitor = null
    }

    private fun rememberExtracted(et: ExtractedText) {
        extractedStart = et.startOffset
        extractedEnd = et.startOffset + (et.text?.length ?: 0)
        extractedStale = false
    }

    private fun buildExtractedText(m: EditorModel, request: ExtractedTextRequest?): ExtractedText {
        val s = m.selection
        val hint = request?.hintMaxChars ?: 0
        val half = if (hint > 0) min(EXTRACT_WINDOW, max(1, hint / 2)) else EXTRACT_WINDOW
        var start = max(0, s.start - half)
        var end = min(m.length, s.end + half)
        // Never cut a surrogate pair at either edge.
        if (start > 0 && m.textRange(start, start + 1).firstOrNull()?.isLowSurrogate() == true) start--
        if (end < m.length && m.textRange(end, end + 1).firstOrNull()?.isLowSurrogate() == true) end++
        return ExtractedText().apply {
            text = m.textRange(start, end)
            startOffset = start
            partialStartOffset = -1
            partialEndOffset = -1
            selectionStart = s.start - start
            selectionEnd = s.end - start
            flags = 0
        }
    }

    /** Pushes extracted text to a monitoring keyboard (after edits, or when the selection left its window). */
    private fun updateExtractedText() {
        val request = extractMonitor ?: return
        val m = liveModel ?: return
        val s = m.selection
        if (!extractedStale && s.start >= extractedStart && s.end <= extractedEnd) return
        val et = buildExtractedText(m, request)
        rememberExtracted(et)
        imeSink.updateExtractedText(this, request.token, et)
    }

    fun composingRange(): Pair<Int, Int>? =
        if (composingStart >= 0 && composingEnd > composingStart) composingStart to composingEnd else null

    internal fun setComposing(start: Int, end: Int) {
        composingStart = start
        composingEnd = end
    }

    internal fun clearComposing() {
        composingStart = -1
        composingEnd = -1
    }

    internal fun onImeBatchFinished() {
        updateImeSelection()
    }

    private fun updateImeSelection() {
        val m = liveModel ?: return
        val s = m.selection
        val (cs, ce) = composingRange() ?: (-1 to -1)
        imeSink.updateSelection(this, s.start, s.end, cs, ce)
        updateExtractedText()
        if (cursorAnchorMonitor) publishCursorAnchorInfo()
    }

    /** After any content change: refresh, keep the caret visible, notify. */
    internal fun afterEdit(fromIme: Boolean) {
        hideSelectionControls()
        extractedStale = true
        invalidateLines()
        preferredX = -1f
        ensureCaretVisible()
        restartBlink()
        if (!fromIme || inputConnection?.inBatch != true) updateImeSelection()
        notifyAccessibility(contentChanged = true)
        onChange?.invoke(true)
    }

    internal fun afterSelectionChange(fromIme: Boolean) {
        invalidate()
        ensureCaretVisible()
        restartBlink()
        if (!fromIme || inputConnection?.inBatch != true) updateImeSelection()
        notifyAccessibility(contentChanged = false)
        onChange?.invoke(false)
    }

    // ----- accessibility (#7) -------------------------------------------------------------------
    // TalkBack sees an editable, multi-line text field: the text (up to
    // A11Y_MAX_CHARS), the selection, and the standard actions: set selection,
    // move by character / word / line / paragraph, copy, cut, paste, select
    // all. Edits and cursor moves send the usual text events, and moving to
    // another line announces "Line N".

    /** What TalkBack calls the editor ("main.rs, editor"); set by the host. */
    var accessibilityLabel: String = "Editor"

    private var lastAnnouncedLine = -1

    private fun a11yText(m: EditorModel): String = if (m.length <= A11Y_MAX_CHARS) m.text() else m.textRange(0, A11Y_MAX_CHARS)

    override fun getAccessibilityClassName(): CharSequence = android.widget.EditText::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val m = liveModel ?: return
        info.isEditable = true
        info.isMultiLine = true
        info.isFocusable = true
        info.isLongClickable = true
        info.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        info.text = a11yText(m)
        info.hintText = accessibilityLabel
        info.contentDescription = null
        val sel = m.selection
        val max = min(m.length, A11Y_MAX_CHARS)
        info.setTextSelection(sel.start.coerceAtMost(max), sel.end.coerceAtMost(max))
        info.movementGranularities = A11Y_GRANULARITIES
        listOf(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_AT_MOVEMENT_GRANULARITY,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_COPY,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_CUT,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT,
        ).forEach(info::addAction)
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        val m = liveModel ?: return super.performAccessibilityAction(action, arguments)
        return when (action) {
            AccessibilityNodeInfo.ACTION_SET_SELECTION -> {
                val start = arguments?.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, -1) ?: -1
                val end = arguments?.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, -1) ?: -1
                // Both -1 (or missing) collapses to the caret, like TextView.
                if (start < 0 || end < 0) select(m.selection.head, m.selection.head) else select(start, end)
                true
            }
            AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY,
            AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY,
            -> {
                val granularity = arguments?.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT) ?: 0
                val extend = arguments?.getBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN) ?: false
                val forward = action == AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
                moveByGranularity(m, granularity, forward, extend)
            }
            AccessibilityNodeInfo.ACTION_COPY -> { copy(); true }
            AccessibilityNodeInfo.ACTION_CUT -> { cut(); true }
            AccessibilityNodeInfo.ACTION_PASTE -> { paste(); true }
            AccessibilityNodeInfo.ACTION_SET_TEXT -> {
                val text = arguments?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                replaceRange(0, m.length, text?.toString() ?: "")
                true
            }
            else -> super.performAccessibilityAction(action, arguments)
        }
    }

    /** TalkBack's reading controls: by character, word, line or paragraph. */
    private fun moveByGranularity(m: EditorModel, granularity: Int, forward: Boolean, extend: Boolean): Boolean {
        val sel = m.selection
        val head = sel.head
        val text = a11yText(m)
        val target = when (granularity) {
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER ->
                if (forward) stepForward(m, head) else stepBack(m, head)
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD -> {
                val isWord = { c: Char -> c.isLetterOrDigit() || c == '_' }
                var i = head.coerceAtMost(text.length)
                if (forward) {
                    while (i < text.length && !isWord(text[i])) i++
                    while (i < text.length && isWord(text[i])) i++
                } else {
                    while (i > 0 && !isWord(text[i - 1])) i--
                    while (i > 0 && isWord(text[i - 1])) i--
                }
                i
            }
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE,
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH,
            -> {
                val line = m.lineOfOffset(head)
                when {
                    forward && head < m.lineEnd(line) -> m.lineEnd(line)
                    forward -> if (line + 1 < m.lineCount) m.lineEnd(line + 1) else m.length
                    head > m.lineStart(line) -> m.lineStart(line)
                    else -> if (line > 0) m.lineStart(line - 1) else 0
                }
            }
            else -> return false
        }
        if (target == head) return false
        select(if (extend) sel.anchor else target, target)
        return true
    }

    private fun notifyAccessibility(contentChanged: Boolean) {
        val am = context.getSystemService(AccessibilityManager::class.java) ?: return
        if (!am.isEnabled) return
        val m = liveModel ?: return
        if (contentChanged) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        }
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)
        // Like VS Code's screen reader mode: say the line number when the caret changes line.
        val line = m.lineOfOffset(m.selection.head)
        if (!contentChanged && lastAnnouncedLine >= 0 && line != lastAnnouncedLine) announceForAccessibility("Line ${line + 1}")
        lastAnnouncedLine = line
    }

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        val m = liveModel ?: return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            val max = min(m.length, A11Y_MAX_CHARS)
            event.fromIndex = m.selection.start.coerceAtMost(max)
            event.toIndex = m.selection.end.coerceAtMost(max)
            event.itemCount = max
        }
    }

    /** Edits not coming from the IME must reset its view of the text. */
    private fun afterExternalEdit() {
        clearComposing()
        afterEdit(fromIme = false)
        imeSink.restartInput(this)
    }

    // ----- hardware keys -----------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val m = liveModel ?: return super.onKeyDown(keyCode, event)
        hideSelectionControls()
        val shift = event.isShiftPressed
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> { m.backspace(); afterKeyEdit() }
            KeyEvent.KEYCODE_FORWARD_DEL -> { m.deleteForward(); afterKeyEdit() }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { m.newline(); afterKeyEdit() }
            KeyEvent.KEYCODE_TAB -> if (!event.isCtrlPressed) indent() else return super.onKeyDown(keyCode, event)
            KeyEvent.KEYCODE_ESCAPE -> escape()
            KeyEvent.KEYCODE_DPAD_LEFT -> moveCursor(EditorActions.Direction.Left, shift)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveCursor(EditorActions.Direction.Right, shift)
            KeyEvent.KEYCODE_DPAD_UP -> moveCursor(EditorActions.Direction.Up, shift)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveCursor(EditorActions.Direction.Down, shift)
            KeyEvent.KEYCODE_MOVE_HOME -> moveCursor(EditorActions.Direction.LineStart, shift)
            KeyEvent.KEYCODE_MOVE_END -> moveCursor(EditorActions.Direction.LineEnd, shift)
            KeyEvent.KEYCODE_PAGE_UP -> moveCursor(EditorActions.Direction.PageUp, shift)
            KeyEvent.KEYCODE_PAGE_DOWN -> moveCursor(EditorActions.Direction.PageDown, shift)
            else -> {
                val ch = event.unicodeChar
                if (ch != 0 && !event.isCtrlPressed && !event.isAltPressed) {
                    clearComposing()
                    m.insert(String(Character.toChars(ch)), true)
                    afterKeyEdit()
                } else {
                    return super.onKeyDown(keyCode, event)
                }
            }
        }
        return true
    }

    private fun afterKeyEdit() {
        clearComposing()
        afterEdit(fromIme = false)
    }

    // ----- EditorActions ---------------------------------------------------------------------

    override fun insertText(text: String) {
        val m = liveModel ?: return
        clearComposing()
        m.insert(text, false)
        afterExternalEdit()
    }

    override fun indent() {
        val m = liveModel ?: return
        clearComposing()
        if (style.insertSpaces) {
            val head = m.selection.head
            val col = head - m.lineStart(m.lineOfOffset(head))
            m.insert(" ".repeat(style.tabSize - (col % style.tabSize)), true)
        } else {
            m.insert("\t", true)
        }
        afterExternalEdit()
    }

    override fun moveCursor(direction: EditorActions.Direction, extend: Boolean) {
        val m = liveModel ?: return
        clearComposing()
        hideSelectionControls()
        m.sealUndoGroup()
        val sel = m.selection
        val head = sel.head
        val line = m.lineOfOffset(head)
        // Up/down move by visual rows.
        fun vertical(rows: Int): Int {
            val (cx, cy) = caretPoint(head)
            if (preferredX < 0) preferredX = cx
            val row = (cy / lineHeight).roundToInt()
            val target = row + rows
            if (target < 0) return 0
            if (target >= totalRows()) return m.length
            val y = target * lineHeight - sy + lineHeight / 2
            return offsetAt(textLeft - sx + preferredX, y)
        }
        val pageRows = max(1, (height / lineHeight).toInt() - 1)
        val newHead = when (direction) {
            EditorActions.Direction.Left -> if (!extend && !sel.isEmpty) sel.start else stepBack(m, head)
            EditorActions.Direction.Right -> if (!extend && !sel.isEmpty) sel.end else stepForward(m, head)
            EditorActions.Direction.Up -> vertical(-1)
            EditorActions.Direction.Down -> vertical(1)
            EditorActions.Direction.PageUp -> vertical(-pageRows)
            EditorActions.Direction.PageDown -> vertical(pageRows)
            EditorActions.Direction.LineStart -> {
                // Smart home: first non-whitespace char, then column 0.
                val start = m.lineStart(line)
                val text = m.textRange(start, m.lineEnd(line))
                val firstNonWs = start + text.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) text.length else it }
                if (head == firstNonWs) start else firstNonWs
            }
            EditorActions.Direction.LineEnd -> m.lineEnd(line)
        }
        val verticalMove = direction in VERTICAL
        if (!verticalMove) preferredX = -1f
        m.selection = if (extend) Sel(sel.anchor, newHead) else Sel.caret(newHead)
        val keepX = preferredX
        afterSelectionChange(fromIme = false)
        if (verticalMove) preferredX = keepX
    }

    /** One grapheme cluster left/right (the core knows UAX #29; Java strings alone don't). */
    private fun stepBack(m: EditorModel, pos: Int): Int = if (pos <= 0) 0 else m.graphemeBefore(pos)

    private fun stepForward(m: EditorModel, pos: Int): Int = if (pos >= m.length) m.length else m.graphemeAfter(pos)

    override fun undo() {
        val m = liveModel ?: return
        if (m.undo()) afterExternalEdit()
    }

    override fun redo() {
        val m = liveModel ?: return
        if (m.redo()) afterExternalEdit()
    }

    override fun selectAll() {
        val m = liveModel ?: return
        clearComposing()
        m.selection = Sel(0, m.length)
        afterSelectionChange(fromIme = false)
    }

    override fun selectedText(): String {
        val m = liveModel ?: return ""
        val s = m.selection
        return m.textRange(s.start, s.end)
    }

    override fun escape() {
        val m = liveModel ?: return
        hideSelectionControls()
        if (m.selection.isEmpty) return
        m.selection = Sel.caret(m.selection.head)
        afterSelectionChange(fromIme = false)
    }

    override fun select(start: Int, end: Int) {
        val m = liveModel ?: return
        clearComposing()
        hideSelectionControls()
        m.sealUndoGroup()
        m.selection = Sel(start.coerceIn(0, m.length), end.coerceIn(0, m.length))
        afterSelectionChange(fromIme = false)
    }

    override fun replaceRange(start: Int, end: Int, text: String) {
        val m = liveModel ?: return
        clearComposing()
        m.sealUndoGroup()
        m.replace(start, end, text, false)
        afterExternalEdit()
    }

    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)

    override fun copy() {
        val m = liveModel ?: return
        val s = m.selection
        // Like VS Code: copying with no selection copies the whole line.
        val text = if (s.isEmpty) {
            val line = m.lineOfOffset(s.head)
            m.textRange(m.lineStart(line), m.lineEnd(line)) + "\n"
        } else {
            m.textRange(s.start, s.end)
        }
        clipboard?.setPrimaryClip(ClipData.newPlainText("code", text))
    }

    override fun cut() {
        val m = liveModel ?: return
        val s = m.selection
        copy()
        if (s.isEmpty) {
            val line = m.lineOfOffset(s.head)
            val end = if (line + 1 < m.lineCount) m.lineStart(line + 1) else m.lineEnd(line)
            m.replace(m.lineStart(line), end, "", false)
        } else {
            m.replace(s.start, s.end, "", false)
        }
        afterExternalEdit()
    }

    override fun paste() {
        val clip = clipboard?.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(context).toString()
        if (text.isNotEmpty()) insertText(text)
    }

    // ----- sticky scroll (editor.stickyScroll; logic in StickyScroll) --------------------

    private val sticky = StickyScroll()

    /** Line ranges (0-based, inclusive) of multi-line outline symbols, outer first. */
    var stickyScopes: List<IntRange>
        get() = sticky.scopes
        set(value) {
            if (sticky.scopes != value) {
                sticky.scopes = value
                invalidate()
            }
        }

    /** `editor.stickyScroll.enabled`. */
    var stickyScrollEnabled: Boolean
        get() = sticky.enabled
        set(value) {
            sticky.enabled = value
            invalidate()
        }

    private fun drawStickyScroll(canvas: Canvas, caretLine: Int) {
        val m = liveModel ?: return
        sticky.update(sy, lineHeight, ::lineOfRow)
        sticky.draw(canvas, m, width.toFloat(), lineHeight, density, style.background) { line, vl, top ->
            val end = breaksOf(line).getOrNull(1) ?: vl.text.length
            canvas.save()
            canvas.clipRect(gutterWidth, top, width.toFloat(), top + lineHeight)
            drawRun(canvas, layout(vl.text), colorsFor(vl), 0, end, textLeft - sx, top + baseline)
            canvas.restore()
            if (style.lineNumbers) {
                gutterPaint.color = if (line == caretLine) style.lineNumberActive else style.lineNumber
                canvas.drawText((line + 1).toString(), gutterWidth - charWidth * 1.5f, top + baseline, gutterPaint)
            }
        }
    }

    private fun onStickyTouch(event: MotionEvent): Boolean = sticky.onTouch(event, lineHeight, ::goToLine)

    override fun goToLine(line: Int) {
        val m = liveModel ?: return
        val l = line.coerceIn(0, m.lineCount - 1)
        hideSelectionControls()
        m.sealUndoGroup()
        m.selection = Sel.caret(m.lineStart(l))
        // Center the target line.
        scrollToClamped(0f, rowStartOf(l) * lineHeight - height / 3f)
        requestFocus()
        afterSelectionChange(fromIme = false)
    }

    // ----- minimap ---------------------------------------------------------------------------
    // VS Code's minimap at scale 1: each line 2 dp tall, each column 1 dp wide,
    // colored runs instead of glyphs, up to 120 columns, with a slider marking
    // the visible part. Rendered into a cached bitmap (rebuilt when the text or
    // the minimap's first line changes, at most every 100 ms while typing).
    // Tap or drag on it to scroll.

    private val mmLineH get() = 2f * density
    private val mmCharW get() = 1f * density
    private val minimapWidth: Float
        get() = if (!style.minimap || width == 0) 0f else min(MINIMAP_COLUMNS * mmCharW, width * 0.18f).let { floor(it) }
    /** Room for the vertical scrollbar to the right of the minimap. */
    private val minimapGap get() = 6f * density
    private val minimapLeft: Float get() = width - minimapWidth - minimapGap
    /** Right edge of the text area (the minimap sits to its right). */
    private val textRight: Float get() = if (minimapWidth > 0f) minimapLeft else width.toFloat()

    /** How far the minimap content is scrolled: proportional to the editor when it doesn't fit. */
    private val minimapScroll: Float
        get() {
            val content = lineCount() * mmLineH
            return if (content <= height) 0f else (sy / max(1f, maxScrollY)) * (content - height)
        }

    private var minimapBitmap: android.graphics.Bitmap? = null
    private var minimapKey: List<Any?>? = null
    private var minimapFirst = 0
    private var minimapRenderedAt = 0L
    private var minimapDragging = false
    private val minimapPaint = Paint()

    private fun drawMinimap(canvas: Canvas) {
        val w = minimapWidth
        val m = liveModel ?: return
        if (w <= 0f) return
        val scroll = minimapScroll
        val first = (scroll / mmLineH).toInt()
        val count = (height / mmLineH).toInt() + 2
        val key = listOf(m.version, first, count, w.toInt(), style)
        if (key != minimapKey) {
            val now = android.os.SystemClock.uptimeMillis()
            val sameLines = minimapKey?.let { it[1] == first && it[2] == count && it[3] == w.toInt() && it[4] == style } == true
            if (sameLines && minimapBitmap != null && now - minimapRenderedAt < MINIMAP_THROTTLE_MS) {
                // Typing: keep the last picture a moment and redraw once things settle.
                postInvalidateDelayed(MINIMAP_THROTTLE_MS)
            } else {
                renderMinimap(m, first, count, w)
                minimapKey = key
                minimapFirst = first
                minimapRenderedAt = now
            }
        }
        val left = minimapLeft
        minimapBitmap?.let { canvas.drawBitmap(it, left, minimapFirst * mmLineH - scroll, null) }
        // The slider: the part of the file on screen.
        val top = sy / lineHeight * mmLineH - scroll
        fillPaint.color = style.minimapSlider
        canvas.drawRect(left, top, left + w, top + height / lineHeight * mmLineH, fillPaint)
    }

    private fun renderMinimap(m: EditorModel, first: Int, count: Int, w: Float) {
        val bw = w.toInt().coerceAtLeast(1)
        val bh = ceil(count * mmLineH).toInt().coerceAtLeast(1)
        val bmp = minimapBitmap?.takeIf { it.width == bw && it.height == bh }
            ?: android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888).also {
                minimapBitmap?.recycle()
                minimapBitmap = it
            }
        bmp.eraseColor(style.background)
        val c = Canvas(bmp)
        val cw = mmCharW
        val barH = mmLineH * 0.75f
        for (vl in m.viewport(first, count)) {
            val y = (vl.line - first) * mmLineH
            val colors = colorsFor(vl)
            val text = vl.text
            var dcol = 0
            var i = 0
            while (i < text.length && dcol * cw < bw) {
                val ch = text[i]
                if (ch == ' ' || ch == '\t') {
                    dcol += if (ch == '\t') style.tabSize - (dcol % style.tabSize) else 1
                    i++
                    continue
                }
                // A run of non-blank chars with one color is one bar.
                val color = colors.getOrElse(i) { style.foreground }
                val start = dcol
                while (i < text.length && text[i] != ' ' && text[i] != '\t' && colors.getOrElse(i) { style.foreground } == color) {
                    if (!Character.isLowSurrogate(text[i])) dcol++
                    i++
                }
                minimapPaint.color = color
                minimapPaint.alpha = 0xB0
                c.drawRect(start * cw, y, min(dcol * cw, bw.toFloat()), y + barH, minimapPaint)
            }
        }
    }

    /** Tap or drag on the minimap: scroll so that line is centered. */
    private fun onMinimapTouch(event: MotionEvent): Boolean {
        if (minimapWidth <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.x < minimapLeft) return false
                minimapDragging = true
                scroller.forceFinished(true)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (!minimapDragging) return false
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!minimapDragging) return false
                minimapDragging = false
                return true
            }
            else -> return minimapDragging
        }
        val line = ((event.y + minimapScroll) / mmLineH).toInt().coerceIn(0, lineCount() - 1)
        scrollToClamped(sx, rowStartOf(line) * lineHeight - height / 2f)
        return true
    }

    companion object {
        private const val BLINK_MS = 530L
        /** VS Code's `editor.minimap.maxColumn`. */
        private const val MINIMAP_COLUMNS = 120
        private const val MINIMAP_THROTTLE_MS = 100L
        /** Larger documents expose only their beginning to accessibility services (Binder size). */
        private const val A11Y_MAX_CHARS = 100_000
        private const val A11Y_GRANULARITIES =
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER or
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD or
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE or
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH
        /** Android hides an idle insertion (caret) handle after a few seconds. */
        private const val CARET_HANDLE_MS = 4000L
        private const val SQRT2 = 1.4142135f
        /** Chars of context on each side of the selection in extracted text. */
        internal const val EXTRACT_WINDOW = 2000
        /** Character bounds reported for at most this many composing chars. */
        private const val MAX_ANCHOR_CHARS = 64
        private const val MAX_WRAP_CHARS = 2_000_000
        private val NO_BREAKS = intArrayOf(0)
        private val VERTICAL = setOf(
            EditorActions.Direction.Up, EditorActions.Direction.Down,
            EditorActions.Direction.PageUp, EditorActions.Direction.PageDown,
        )
    }
}
