package dev.ferride.workbench

/**
 * Minimal JSONC (JSON with comments and trailing commas) support for
 * `settings.json` and VS Code themes: a tokenizer, comment/trailing-comma
 * stripping, and comment-preserving edits of top-level properties (in the
 * spirit of VS Code's `jsonc-parser` `modify`).
 */
object Jsonc {
    enum class Type { LBrace, RBrace, LBracket, RBracket, Colon, Comma, Str, Literal }

    data class Token(val type: Type, val start: Int, val end: Int)

    class ParseException(message: String) : IllegalArgumentException(message)

    /** Tokens of [src], skipping whitespace and comments. Throws on unterminated strings/comments. */
    fun tokenize(src: String): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c.isWhitespace() -> i++
                c == '/' && src.getOrNull(i + 1) == '/' -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                c == '/' && src.getOrNull(i + 1) == '*' -> {
                    val end = src.indexOf("*/", i + 2)
                    if (end < 0) throw ParseException("unterminated comment")
                    i = end + 2
                }
                c == '"' -> {
                    var j = i + 1
                    while (j < src.length && src[j] != '"') {
                        if (src[j] == '\\') j++
                        j++
                    }
                    if (j >= src.length) throw ParseException("unterminated string")
                    out += Token(Type.Str, i, j + 1)
                    i = j + 1
                }
                c in "{}[]:," -> {
                    val type = when (c) {
                        '{' -> Type.LBrace
                        '}' -> Type.RBrace
                        '[' -> Type.LBracket
                        ']' -> Type.RBracket
                        ':' -> Type.Colon
                        else -> Type.Comma
                    }
                    out += Token(type, i, i + 1)
                    i++
                }
                else -> {
                    var j = i
                    while (j < src.length && !src[j].isWhitespace() && src[j] !in "{}[]:,\"/") j++
                    if (j == i) throw ParseException("unexpected '${src[i]}' at $i")
                    out += Token(Type.Literal, i, j)
                    i = j
                }
            }
        }
        return out
    }

    /** Plain JSON: comments removed, trailing commas dropped (string contents untouched). */
    fun strip(src: String): String {
        val tokens = tokenize(src)
        val sb = StringBuilder(src.length)
        tokens.forEachIndexed { k, t ->
            if (t.type == Type.Comma) {
                val next = tokens.getOrNull(k + 1)?.type
                if (next == Type.RBrace || next == Type.RBracket) return@forEachIndexed
            }
            sb.append(src, t.start, t.end).append(' ')
        }
        return sb.toString()
    }

    /** JSON string literal for [s]. */
    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    fun unquote(literal: String): String =
        literal.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")

    /**
     * Sets top-level property [key] to the JSON text [value] (or removes it when
     * null), keeping all other text — comments included — as it was.
     */
    fun setProperty(src: String, key: String, value: String?): String {
        val text = src.ifBlank { "{}" }
        val tokens = tokenize(text)
        if (tokens.firstOrNull()?.type != Type.LBrace) throw ParseException("settings must be a JSON object")
        // Find top-level properties: (keyToken, valueStart, valueEnd, index of token after value).
        data class Prop(val keyTok: Token, val valueStart: Int, val valueEnd: Int, val after: Int)
        val props = mutableListOf<Prop>()
        var k = 1
        var closeIndex = -1
        while (k < tokens.size) {
            val t = tokens[k]
            if (t.type == Type.RBrace) {
                closeIndex = k
                break
            }
            if (t.type == Type.Comma) {
                k++
                continue
            }
            if (t.type != Type.Str || tokens.getOrNull(k + 1)?.type != Type.Colon) {
                throw ParseException("expected a property name at ${t.start}")
            }
            var v = k + 2
            val vStart = tokens.getOrNull(v) ?: throw ParseException("missing value")
            var depth = 0
            while (v < tokens.size) {
                when (tokens[v].type) {
                    Type.LBrace, Type.LBracket -> depth++
                    Type.RBrace, Type.RBracket -> depth--
                    else -> {}
                }
                if (depth <= 0) break
                v++
            }
            if (v >= tokens.size) throw ParseException("unterminated value")
            props += Prop(t, vStart.start, tokens[v].end, v + 1)
            k = v + 1
        }
        if (closeIndex < 0) throw ParseException("missing closing brace")
        val existing = props.firstOrNull { unquote(text.substring(it.keyTok.start, it.keyTok.end)) == key }
        if (existing != null) {
            if (value != null) return text.replaceRange(existing.valueStart, existing.valueEnd, value)
            // Remove "key": value plus one adjacent comma.
            val next = tokens.getOrNull(existing.after)
            return if (next?.type == Type.Comma) {
                val end = tokens.getOrNull(existing.after + 1)?.start ?: next.end
                text.removeRange(existing.keyTok.start, end)
            } else {
                val prevComma = tokens.indexOfFirst { it.end > existing.keyTok.start }.let { idx ->
                    tokens.getOrNull(idx - 1)?.takeIf { it.type == Type.Comma }
                }
                val start = prevComma?.start ?: existing.keyTok.start
                text.removeRange(start, existing.valueEnd)
            }
        }
        if (value == null) return text
        val entry = "${quote(key)}: $value"
        val close = tokens[closeIndex]
        val last = props.lastOrNull()
        if (last == null) {
            return text.substring(0, close.start).trimEnd() + "\n  $entry\n" + text.substring(close.start)
        }
        val trailingComma = tokens.getOrNull(last.after)?.type == Type.Comma
        val insertAt = if (trailingComma) tokens[last.after].end else last.valueEnd
        val prefix = if (trailingComma) "\n  " else ",\n  "
        return text.substring(0, insertAt) + prefix + entry + text.substring(insertAt)
    }
}
