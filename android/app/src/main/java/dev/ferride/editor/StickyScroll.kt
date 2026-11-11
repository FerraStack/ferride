package dev.ferride.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import dev.ferride.core.ViewLine

/**
 * VS Code's sticky scroll (`editor.stickyScroll`), kept out of
 * [CodeEditorView]: which lines stick at the top, their cached view lines,
 * the band's background and shadow, and taps on them. The view supplies its
 * metrics and draws each line's text and number.
 */
class StickyScroll(private val maxLines: Int = MAX_LINES) {
    /** Line ranges (0-based, inclusive) of multi-line outline symbols, outer first. */
    var scopes: List<IntRange> = emptyList()
    var enabled: Boolean = true

    /** Lines pinned at the top since the last [update]. */
    var lines: List<Int> = emptyList()
        private set

    private val cache = HashMap<Int, ViewLine>()
    private var cacheVersion = -1L
    private var gesture = false
    private val paint = Paint()

    /**
     * VS Code's rule: a scope sticks while its first line has scrolled above
     * the line just below the sticky stack and its last line hasn't, up to
     * [maxLines] lines, outermost first.
     */
    fun update(scrollY: Float, lineHeight: Float, lineOfRow: (Int) -> Int): List<Int> {
        lines = if (!enabled || scopes.isEmpty() || scrollY <= 0f || lineHeight <= 0f) {
            emptyList()
        } else {
            val out = ArrayList<Int>(maxLines)
            for (scope in scopes) {
                if (out.size >= maxLines) break
                val top = lineOfRow(((scrollY + out.size * lineHeight) / lineHeight).toInt().coerceAtLeast(0))
                if (scope.first < top && scope.last >= top && scope.last > scope.first &&
                    (out.isEmpty() || scope.first > out.last())
                ) {
                    out += scope.first
                }
            }
            out
        }
        return lines
    }

    /** The view line of a sticky [line], cached per document version. */
    fun viewLine(model: EditorModel, line: Int): ViewLine? {
        if (model.version != cacheVersion) {
            cache.clear()
            cacheVersion = model.version
        }
        return cache[line] ?: model.viewport(line, 1).firstOrNull()?.also { cache[line] = it }
    }

    /** Background band, each line via [drawLine], then VS Code's bottom shadow. */
    fun draw(
        canvas: Canvas,
        model: EditorModel,
        width: Float,
        lineHeight: Float,
        density: Float,
        background: Int,
        drawLine: (line: Int, viewLine: ViewLine, top: Float) -> Unit,
    ) {
        if (lines.isEmpty()) return
        val bottom = lines.size * lineHeight
        paint.color = background
        canvas.drawRect(0f, 0f, width, bottom, paint)
        lines.forEachIndexed { i, line ->
            viewLine(model, line)?.let { drawLine(line, it, i * lineHeight) }
        }
        paint.color = SHADOW
        canvas.drawRect(0f, bottom, width, bottom + 2f * density, paint)
    }

    /** A tap on a sticky line reveals it; gestures starting on the band don't scroll the editor. */
    fun onTouch(event: MotionEvent, lineHeight: Float, reveal: (Int) -> Unit): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            gesture = lines.isNotEmpty() && event.y < lines.size * lineHeight
        }
        if (!gesture) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                gesture = false
                lines.getOrNull((event.y / lineHeight).toInt())?.let(reveal)
            }
            MotionEvent.ACTION_CANCEL -> gesture = false
        }
        return true
    }

    companion object {
        /** VS Code's default `editor.stickyScroll.maxLineCount`. */
        const val MAX_LINES = 5
        /** `scrollbar.shadow`-like band shadow. */
        private const val SHADOW = 0x55000000
    }
}
