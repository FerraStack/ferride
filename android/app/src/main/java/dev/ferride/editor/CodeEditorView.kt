package dev.ferride.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import dev.ferride.core.StyleKind
import dev.ferride.core.ViewLine
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The code editor surface: a plain Android [View] that draws the visible lines
 * of an [EditorModel] (gutter, current line, selection, highlighted text,
 * indent guides, bracket pair colors, caret) and talks to the soft keyboard
 * through [EditorInputConnection]. Hosted in Compose with `AndroidView`.
 */
class CodeEditorView(context: Context) : View(context), EditorActions {

    var model: EditorModel? = null
        private set

    /** Called after edits (`true`) and selection-only changes (`false`). */
    var onChange: ((contentChanged: Boolean) -> Unit)? = null

    var style: EditorStyle = EditorStyle(
        background = 0xFF1F1F1F.toInt(), foreground = 0xFFD4D4D4.toInt(), lineHighlight = 0xFF282828.toInt(),
        selection = 0xFF264F78.toInt(), cursor = 0xFFAEAFAD.toInt(), lineNumber = 0xFF6E7681.toInt(),
        lineNumberActive = 0xFFCCCCCC.toInt(), indentGuide = 0xFF404040.toInt(), tokens = emptyMap(),
    )
        set(value) {
            val metricsChanged = field.fontSizeSp != value.fontSizeSp || field.tabSize != value.tabSize
            field = value
            if (metricsChanged) updateMetrics()
            invalidateLines()
        }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.RIGHT
    }
    private val fillPaint = Paint()

    private var lineHeight = 1f
    private var baseline = 0f
    private var charWidth = 1f
    private var gutterWidth = 0f
    private val textPad get() = charWidth

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
    private var bracketDepthCache = Triple(-1L, -1, 0)

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

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        updateMetrics()
    }

    fun setModel(newModel: EditorModel?) {
        if (newModel === model) return
        model?.let { scrollMemory[it] = sx to sy }
        model?.sealUndoGroup()
        model = newModel
        val (x, y) = newModel?.let { scrollMemory[it] } ?: (0f to 0f)
        sx = x
        sy = y
        maxLineWidth = 0f
        clearComposing()
        invalidateLines()
        if (hasFocus()) imm?.restartInput(this)
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

    // ----- geometry ---------------------------------------------------------

    private fun lineCount() = model?.lineCount ?: 1

    private fun computeGutter() {
        gutterWidth = if (style.lineNumbers) {
            val digits = max(3, lineCount().toString().length)
            charWidth * (digits + 3)
        } else {
            charWidth * 1.5f
        }
    }

    private val textLeft get() = gutterWidth + textPad
    private val maxScrollY get() = max(0f, (lineCount() - 1) * lineHeight)
    private val maxScrollX get() = max(0f, maxLineWidth + charWidth * 4 - (width - textLeft))

    private fun visibleLines(): Pair<Int, Int> {
        val first = max(0, (sy / lineHeight).toInt())
        val count = (height / lineHeight).toInt() + 2
        return first to count
    }

    private fun lines(first: Int, count: Int): List<ViewLine> {
        val m = model ?: return emptyList()
        if (m.version != cacheVersion || first != cacheFirst || count != cacheCount) {
            cache = m.viewport(first, count)
            cacheFirst = first
            cacheCount = count
            cacheVersion = m.version
        }
        return cache
    }

    /** Display form of a line: tabs expanded. `map[col]` = display column. */
    private class Layout(val display: String, val map: IntArray)

    private fun layout(text: String): Layout {
        if ('\t' !in text) return Layout(text, IntArray(text.length + 1) { it })
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
        return Layout(sb.toString(), map)
    }

    private fun xOf(layout: Layout, col: Int): Float =
        textPaint.measureText(layout.display, 0, layout.map[col.coerceIn(0, layout.map.size - 1)])

    private fun colAtX(layout: Layout, x: Float): Int {
        val d = layout.display
        val dcol = if (x <= 0f) 0 else textPaint.getOffsetForAdvance(d, 0, d.length, 0, d.length, false, x)
        var col = 0
        while (col < layout.map.size - 1 && layout.map[col + 1] <= dcol) col++
        // Round to the nearer side of a wide (tab) column.
        if (col < layout.map.size - 1 && layout.map[col] < dcol) {
            val left = xOf(layout, col)
            val right = xOf(layout, col + 1)
            if (x - left > right - x) col++
        }
        return col
    }

    /** Document offset under a view coordinate. */
    private fun offsetAt(x: Float, y: Float): Int {
        val m = model ?: return 0
        val line = ((y + sy) / lineHeight).toInt().coerceIn(0, m.lineCount - 1)
        val start = m.lineStart(line)
        val text = m.textRange(start, m.lineEnd(line))
        return start + colAtX(layout(text), x - textLeft + sx)
    }

    private fun caretPoint(offset: Int): Pair<Float, Float> {
        val m = model ?: return 0f to 0f
        val line = m.lineOfOffset(offset)
        val start = m.lineStart(line)
        val text = m.textRange(start, m.lineEnd(line))
        return xOf(layout(text), offset - start) to line * lineHeight
    }

    // ----- drawing -----------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        computeGutter()
        canvas.drawColor(style.background)
        val m = model ?: return
        val (first, count) = visibleLines()
        val lines = lines(first, count)
        val sel = m.selection
        val caretLine = m.lineOfOffset(sel.head)
        val depthAtStart = if (style.bracketPairs) bracketDepthBefore(m, first) else 0
        var depth = depthAtStart

        canvas.save()
        canvas.clipRect(gutterWidth, 0f, width.toFloat(), height.toFloat())
        for (vl in lines) {
            val top = vl.line * lineHeight - sy
            val layout = layout(vl.text)
            val lineStart = vl.startOffset
            val lineEnd = lineStart + vl.text.length
            if (style.highlightActiveLine && vl.line == caretLine && sel.isEmpty) {
                fillPaint.color = style.lineHighlight
                canvas.drawRect(gutterWidth, top, width.toFloat(), top + lineHeight, fillPaint)
            }
            // Selection.
            if (!sel.isEmpty && sel.end >= lineStart && sel.start <= lineEnd) {
                val a = max(sel.start, lineStart) - lineStart
                val b = min(sel.end, lineEnd) - lineStart
                val x0 = textLeft - sx + xOf(layout, a)
                var x1 = textLeft - sx + xOf(layout, b)
                if (sel.end > lineEnd) x1 += charWidth * 0.6f
                fillPaint.color = style.selection
                canvas.drawRect(x0, top, x1, top + lineHeight, fillPaint)
            }
            if (style.indentGuides) drawIndentGuides(canvas, layout, top)
            depth = drawLineText(canvas, vl, layout, top, depth)
            maxLineWidth = max(maxLineWidth, textPaint.measureText(layout.display))
            // Composing underline.
            if (composingStart in 0..lineEnd && composingEnd >= lineStart && composingEnd > composingStart) {
                val a = max(composingStart, lineStart) - lineStart
                val b = min(composingEnd, lineEnd) - lineStart
                fillPaint.color = style.foreground
                val y = top + lineHeight - 2f
                canvas.drawRect(textLeft - sx + xOf(layout, a), y - 2f, textLeft - sx + xOf(layout, b), y, fillPaint)
            }
        }
        // Caret.
        if (hasFocus() && caretVisible) {
            val (cx, cy) = caretPoint(sel.head)
            fillPaint.color = style.cursor
            val x = textLeft - sx + cx
            canvas.drawRect(x, cy - sy, x + 2f * resources.displayMetrics.density, cy - sy + lineHeight, fillPaint)
        }
        canvas.restore()
        drawGutter(canvas, first, count, caretLine)
    }

    private fun drawIndentGuides(canvas: Canvas, layout: Layout, top: Float) {
        val indent = layout.display.takeWhile { it == ' ' }.length
        fillPaint.color = style.indentGuide
        var col = style.tabSize
        while (col <= indent && col < layout.display.length) {
            val x = textLeft - sx + textPaint.measureText(layout.display, 0, col - style.tabSize)
            canvas.drawRect(x, top, x + 1f, top + lineHeight, fillPaint)
            col += style.tabSize
        }
    }

    /** Draws one line in same-color runs. Returns the bracket depth after the line. */
    private fun drawLineText(canvas: Canvas, vl: ViewLine, layout: Layout, top: Float, depthIn: Int): Int {
        val text = vl.text
        if (text.isEmpty()) return depthIn
        val colors = IntArray(text.length) { style.foreground }
        val inert = BooleanArray(text.length)
        for (s in vl.spans) {
            val a = s.start.coerceIn(0, text.length)
            val b = s.end.coerceIn(a, text.length)
            val color = style.tokens[s.kind] ?: style.foreground
            for (i in a until b) {
                colors[i] = color
                inert[i] = s.kind == StyleKind.STRING || s.kind == StyleKind.COMMENT
            }
        }
        var depth = depthIn
        if (style.bracketPairs) {
            for (i in text.indices) {
                if (inert[i]) continue
                when (text[i]) {
                    '(', '[', '{' -> {
                        colors[i] = style.bracketColors[depth.mod(style.bracketColors.size)]
                        depth++
                    }
                    ')', ']', '}' -> {
                        depth = max(0, depth - 1)
                        colors[i] = style.bracketColors[depth.mod(style.bracketColors.size)]
                    }
                }
            }
        }
        val y = top + baseline
        var i = 0
        while (i < text.length) {
            var j = i + 1
            while (j < text.length && colors[j] == colors[i]) j++
            textPaint.color = colors[i]
            val d0 = layout.map[i]
            val d1 = layout.map[j]
            val x = textLeft - sx + textPaint.measureText(layout.display, 0, d0)
            canvas.drawText(layout.display, d0, d1, x, y, textPaint)
            i = j
        }
        return depth
    }

    /** Bracket nesting depth at the start of [line] (cached per version/line). */
    private fun bracketDepthBefore(m: EditorModel, line: Int): Int {
        val (v, l, d) = bracketDepthCache
        if (v == m.version && l == line) return d
        val end = m.lineStart(line)
        val depth = if (end > MAX_BRACKET_SCAN) 0 else {
            var depth = 0
            for (c in m.textRange(0, end)) when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth = max(0, depth - 1)
            }
            depth
        }
        bracketDepthCache = Triple(m.version, line, depth)
        return depth
    }

    private fun drawGutter(canvas: Canvas, first: Int, count: Int, caretLine: Int) {
        fillPaint.color = style.background
        canvas.drawRect(0f, 0f, gutterWidth, height.toFloat(), fillPaint)
        if (!style.lineNumbers) return
        val last = min(lineCount(), first + count)
        val x = gutterWidth - charWidth * 1.5f
        for (line in first until last) {
            gutterPaint.color = if (line == caretLine) style.lineNumberActive else style.lineNumber
            canvas.drawText((line + 1).toString(), x, line * lineHeight - sy + baseline, gutterPaint)
        }
    }

    // ----- scrolling & touch -------------------------------------------------

    private fun scrollToClamped(x: Float, y: Float) {
        sx = x.coerceIn(0f, maxScrollX)
        sy = y.coerceIn(0f, maxScrollY)
        invalidate()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollToClamped(scroller.currX.toFloat(), scroller.currY.toFloat())
            postInvalidateOnAnimation()
        }
    }

    private fun ensureCaretVisible() {
        val m = model ?: return
        if (width == 0 || height == 0) return
        val (cx, cy) = caretPoint(m.selection.head)
        var nx = sx
        var ny = sy
        if (cy < sy) ny = cy
        if (cy + lineHeight > sy + height) ny = cy + lineHeight - height
        val areaWidth = width - textLeft
        if (cx < sx) nx = max(0f, cx - charWidth * 4)
        if (cx > sx + areaWidth - charWidth * 2) nx = cx - areaWidth + charWidth * 4
        maxLineWidth = max(maxLineWidth, cx)
        scrollToClamped(nx, ny)
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val m = model ?: return true
            val hadComposing = composingRange() != null
            clearComposing()
            m.sealUndoGroup()
            m.selection = Sel.caret(offsetAt(e.x, e.y))
            preferredX = -1f
            requestFocus()
            if (hadComposing) imm?.restartInput(this@CodeEditorView)
            showKeyboard()
            afterSelectionChange(fromIme = false)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            selectWordAt(e)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            selectWordAt(e)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            // Lock to the dominant axis for steadier vertical scrolling.
            if (abs(dy) > abs(dx) * 1.5f) scrollToClamped(sx, sy + dy) else scrollToClamped(sx + dx, sy + dy)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(
                sx.roundToInt(), sy.roundToInt(), -vx.roundToInt(), -vy.roundToInt(),
                0, maxScrollX.roundToInt(), 0, maxScrollY.roundToInt(),
            )
            postInvalidateOnAnimation()
            return true
        }
    })

    private fun selectWordAt(e: MotionEvent) {
        val m = model ?: return
        clearComposing()
        m.selection = m.wordAt(offsetAt(e.x, e.y))
        requestFocus()
        imm?.restartInput(this)
        afterSelectionChange(fromIme = false)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event) || super.onTouchEvent(event)

    fun showKeyboard() {
        imm?.showSoftInput(this, 0)
    }

    // ----- focus & blink -----------------------------------------------------

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        restartBlink()
        if (!gainFocus) model?.sealUndoGroup()
    }

    private fun restartBlink() {
        removeCallbacks(blink)
        caretVisible = true
        if (hasFocus()) postDelayed(blink, BLINK_MS)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blink)
        super.onDetachedFromWindow()
    }

    // ----- IME -----------------------------------------------------------------

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        model?.selection?.let {
            outAttrs.initialSelStart = it.start
            outAttrs.initialSelEnd = it.end
        }
        return EditorInputConnection(this).also { inputConnection = it }
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
        val m = model ?: return
        val s = m.selection
        val (cs, ce) = composingRange() ?: (-1 to -1)
        imm?.updateSelection(this, s.start, s.end, cs, ce)
    }

    /** After any content change: refresh, keep the caret visible, notify. */
    internal fun afterEdit(fromIme: Boolean) {
        invalidateLines()
        preferredX = -1f
        ensureCaretVisible()
        restartBlink()
        if (!fromIme || inputConnection?.inBatch != true) updateImeSelection()
        onChange?.invoke(true)
    }

    internal fun afterSelectionChange(fromIme: Boolean) {
        invalidate()
        ensureCaretVisible()
        restartBlink()
        if (!fromIme || inputConnection?.inBatch != true) updateImeSelection()
        onChange?.invoke(false)
    }

    /** Edits not coming from the IME must reset its view of the text. */
    private fun afterExternalEdit() {
        clearComposing()
        afterEdit(fromIme = false)
        imm?.restartInput(this)
    }

    // ----- hardware keys -------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val m = model ?: return super.onKeyDown(keyCode, event)
        val shift = event.isShiftPressed
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> { m.backspace(); afterKeyEdit() }
            KeyEvent.KEYCODE_FORWARD_DEL -> { m.deleteForward(); afterKeyEdit() }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { m.newline(); afterKeyEdit() }
            KeyEvent.KEYCODE_TAB -> if (!event.isCtrlPressed) indent() else return super.onKeyDown(keyCode, event)
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

    // ----- EditorActions --------------------------------------------------------

    override fun insertText(text: String) {
        val m = model ?: return
        clearComposing()
        m.insert(text, false)
        afterExternalEdit()
    }

    override fun indent() {
        val m = model ?: return
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
        val m = model ?: return
        clearComposing()
        m.sealUndoGroup()
        val sel = m.selection
        val head = sel.head
        val line = m.lineOfOffset(head)
        fun vertical(lines: Int): Int {
            val target = (line + lines).coerceIn(0, m.lineCount - 1)
            if (target == line) return if (lines < 0) 0 else m.length
            if (preferredX < 0) preferredX = caretPoint(head).first
            val start = m.lineStart(target)
            val text = m.textRange(start, m.lineEnd(target))
            return start + colAtX(layout(text), preferredX)
        }
        val pageLines = max(1, (height / lineHeight).toInt() - 1)
        val newHead = when (direction) {
            EditorActions.Direction.Left ->
                if (!extend && !sel.isEmpty) sel.start else stepBack(m, head)
            EditorActions.Direction.Right ->
                if (!extend && !sel.isEmpty) sel.end else stepForward(m, head)
            EditorActions.Direction.Up -> vertical(-1)
            EditorActions.Direction.Down -> vertical(1)
            EditorActions.Direction.PageUp -> vertical(-pageLines)
            EditorActions.Direction.PageDown -> vertical(pageLines)
            EditorActions.Direction.LineStart -> {
                // Smart home: first non-whitespace char, then column 0.
                val start = m.lineStart(line)
                val text = m.textRange(start, m.lineEnd(line))
                val firstNonWs = start + text.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) text.length else it }
                if (head == firstNonWs) start else firstNonWs
            }
            EditorActions.Direction.LineEnd -> m.lineEnd(line)
        }
        if (direction != EditorActions.Direction.Up && direction != EditorActions.Direction.Down &&
            direction != EditorActions.Direction.PageUp && direction != EditorActions.Direction.PageDown
        ) preferredX = -1f
        m.selection = if (extend) Sel(sel.anchor, newHead) else Sel.caret(newHead)
        imm?.restartInput(this)
        afterSelectionChange(fromIme = false)
    }

    private fun stepBack(m: EditorModel, pos: Int): Int {
        if (pos <= 0) return 0
        val t = m.textRange(max(0, pos - 2), pos)
        return if (t.length == 2 && Character.isSurrogatePair(t[0], t[1])) pos - 2 else pos - 1
    }

    private fun stepForward(m: EditorModel, pos: Int): Int {
        if (pos >= m.length) return m.length
        val t = m.textRange(pos, min(m.length, pos + 2))
        return if (t.length == 2 && Character.isSurrogatePair(t[0], t[1])) pos + 2 else pos + 1
    }

    override fun undo() {
        val m = model ?: return
        if (m.undo()) afterExternalEdit()
    }

    override fun redo() {
        val m = model ?: return
        if (m.redo()) afterExternalEdit()
    }

    override fun selectAll() {
        val m = model ?: return
        clearComposing()
        m.selection = Sel(0, m.length)
        imm?.restartInput(this)
        afterSelectionChange(fromIme = false)
    }

    override fun selectedText(): String {
        val m = model ?: return ""
        val s = m.selection
        return m.textRange(s.start, s.end)
    }

    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)

    override fun copy() {
        val m = model ?: return
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
        val m = model ?: return
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

    override fun goToLine(line: Int) {
        val m = model ?: return
        val l = line.coerceIn(0, m.lineCount - 1)
        m.sealUndoGroup()
        m.selection = Sel.caret(m.lineStart(l))
        // Center the target line.
        scrollToClamped(0f, l * lineHeight - height / 3f)
        requestFocus()
        imm?.restartInput(this)
        afterSelectionChange(fromIme = false)
    }

    companion object {
        private const val BLINK_MS = 530L
        private const val MAX_BRACKET_SCAN = 200_000
    }
}
