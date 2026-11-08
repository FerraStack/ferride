package dev.ferride.editor

import org.json.JSONArray
import org.json.JSONObject

/**
 * A snippet in VS Code's format (`*.code-snippets` / `snippets/<lang>.json`):
 * `{ "Name": { "prefix": "fn", "body": ["fn ${1:name}() {", "\t$0", "}"], "description": "…" } }`.
 */
data class Snippet(val name: String, val prefixes: List<String>, val body: String, val description: String?)

/** A snippet body expanded for one insertion: the text and its tab stops (UTF-16 offsets into [text]). */
data class ExpandedSnippet(val text: String, val stops: List<TabStop>) {
    /** A tab stop: its number (0 = final cursor) and every place it appears (mirrors). */
    data class TabStop(val index: Int, val ranges: List<IntRange>, val choices: List<String> = emptyList())
}

object Snippets {
    /** Parses a VS Code snippets file; malformed entries are skipped. Comments are allowed (JSONC). */
    fun parse(json: String): List<Snippet> {
        val o = JSONObject(stripComments(json))
        return o.keys().asSequence().mapNotNull { name ->
            val e = o.optJSONObject(name) ?: return@mapNotNull null
            val prefixes = when (val p = e.opt("prefix")) {
                is String -> listOf(p)
                is JSONArray -> (0 until p.length()).map { p.optString(it) }
                else -> emptyList()
            }.filter { it.isNotEmpty() }
            val body = when (val b = e.opt("body")) {
                is String -> b
                is JSONArray -> (0 until b.length()).joinToString("\n") { b.optString(it) }
                else -> return@mapNotNull null
            }
            Snippet(name, prefixes, body, e.optString("description").takeIf { it.isNotEmpty() })
        }.toList()
    }

    /**
     * Expands a body: `$1`, `${1}`, `${1:default}` (placeholders may nest),
     * `${1|one,two|}` (choices: the first is inserted), `$0` (final cursor; at
     * the end when absent), variables (`$TM_FILENAME`, `${TM_FILENAME:default}`)
     * from [variables], escapes `\$ \} \\`. [indent] is added after every
     * newline so multi-line snippets follow the current line's indentation, and
     * tabs become [tab] (spaces or a tab, per `editor.insertSpaces`).
     */
    fun expand(body: String, indent: String = "", tab: String = "\t", variables: (String) -> String? = { null }): ExpandedSnippet {
        val out = StringBuilder()
        val stops = LinkedHashMap<Int, MutableList<IntRange>>()
        val choices = HashMap<Int, List<String>>()
        var i = 0

        fun append(text: String) {
            for (ch in text) {
                when (ch) {
                    '\n' -> out.append('\n').append(indent)
                    '\t' -> out.append(tab)
                    else -> out.append(ch)
                }
            }
        }

        fun readNumber(): Int? {
            val start = i
            while (i < body.length && body[i].isDigit()) i++
            return if (i > start) body.substring(start, i).toInt() else null
        }

        fun readName(): String {
            val start = i
            while (i < body.length && (body[i].isLetterOrDigit() || body[i] == '_')) i++
            return body.substring(start, i)
        }

        // Parses until an unescaped '}' (when nested) or the end.
        fun parse(nested: Boolean) {
            while (i < body.length) {
                val ch = body[i]
                when {
                    ch == '\\' && i + 1 < body.length && body[i + 1] in "\$}\\" -> {
                        out.append(body[i + 1])
                        i += 2
                    }
                    nested && ch == '}' -> return
                    ch == '$' && i + 1 < body.length && body[i + 1].isDigit() -> {
                        i++
                        val n = readNumber()!!
                        stops.getOrPut(n) { mutableListOf() } += out.length until out.length
                    }
                    ch == '$' && i + 1 < body.length && body[i + 1] == '{' -> {
                        i += 2
                        val n = readNumber()
                        if (n != null) {
                            val start = out.length
                            when {
                                i < body.length && body[i] == ':' -> {
                                    i++
                                    parse(nested = true)
                                }
                                i < body.length && body[i] == '|' -> {
                                    i++
                                    val end = body.indexOf("|}", i).takeIf { it >= 0 } ?: body.length
                                    val options = body.substring(i, end).split(',')
                                    choices[n] = options
                                    append(options.first())
                                    i = (end + 1).coerceAtMost(body.length)
                                }
                            }
                            if (i < body.length && body[i] == '}') i++
                            stops.getOrPut(n) { mutableListOf() } += start until out.length
                        } else {
                            val name = readName()
                            val value = variables(name)
                            if (i < body.length && body[i] == ':') {
                                i++
                                val start = out.length
                                parse(nested = true)
                                if (value != null) {
                                    out.setLength(start)
                                    append(value)
                                }
                            } else if (value != null) {
                                append(value)
                            }
                            if (i < body.length && body[i] == '}') i++
                        }
                    }
                    ch == '$' && i + 1 < body.length && (body[i + 1].isLetter() || body[i + 1] == '_') -> {
                        i++
                        val name = readName()
                        append(variables(name) ?: "")
                    }
                    else -> {
                        append(ch.toString())
                        i++
                    }
                }
            }
        }
        parse(nested = false)
        if (0 !in stops) stops[0] = mutableListOf(out.length until out.length)
        val ordered = stops.keys.filter { it > 0 }.sorted() + 0
        return ExpandedSnippet(
            out.toString(),
            ordered.map { n -> ExpandedSnippet.TabStop(n, stops.getValue(n), choices[n].orEmpty()) },
        )
    }

    /** JSONC: drop `//` and `/* */` comments outside strings. */
    private fun stripComments(s: String): String {
        val out = StringBuilder()
        var i = 0
        var inString = false
        while (i < s.length) {
            val c = s[i]
            when {
                inString -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < s.length) {
                        out.append(s[i + 1])
                        i++
                    } else if (c == '"') {
                        inString = false
                    }
                }
                c == '"' -> {
                    inString = true
                    out.append(c)
                }
                c == '/' && s.startsWith("//", i) -> while (i < s.length && s[i] != '\n') i++
                c == '/' && s.startsWith("/*", i) -> {
                    i = s.indexOf("*/", i + 2).let { if (it < 0) s.length else it + 1 }
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }
}
