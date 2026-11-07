package dev.ferride.workbench

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** State of the editor's find/replace widget (`actions.find`, `editor.action.startFindReplaceAction`). */
class FindState {
    var visible by mutableStateOf(false)
    var replaceVisible by mutableStateOf(false)
    var query by mutableStateOf("")
    var replacement by mutableStateOf("")
    var caseSensitive by mutableStateOf(false)
    var wholeWord by mutableStateOf(false)
    var regex by mutableStateOf(false)
    /** Index of the current match, or -1. */
    var current by mutableIntStateOf(-1)

    fun matches(text: String): List<IntRange> = findMatches(text, query, caseSensitive, wholeWord, regex)
}

/**
 * All matches of [query] in [text] as UTF-16 index ranges (inclusive end), capped
 * at [limit]. Invalid regexes and empty matches yield nothing.
 */
fun findMatches(
    text: String,
    query: String,
    caseSensitive: Boolean,
    wholeWord: Boolean,
    regex: Boolean,
    limit: Int = 5000,
): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    var pattern = if (regex) query else Regex.escape(query)
    if (wholeWord) pattern = "\\b(?:$pattern)\\b"
    val options = buildSet {
        if (!caseSensitive) add(RegexOption.IGNORE_CASE)
        add(RegexOption.MULTILINE)
    }
    val re = runCatching { Regex(pattern, options) }.getOrNull() ?: return emptyList()
    return re.findAll(text).filter { !it.range.isEmpty() }.take(limit).map { it.range }.toList()
}

/** A problem reported for a file (LSP diagnostics later; fakes in tests). Columns are UTF-16, 0-based. */
data class Diagnostic(
    val line: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    /** 1 = error, 2 = warning, 3 = information, 4 = hint (LSP numbering). */
    val severity: Int,
    val message: String,
    val source: String? = null,
    val code: String? = null,
)
