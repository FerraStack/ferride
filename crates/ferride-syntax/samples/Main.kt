package dev.ferride.sample

import kotlin.math.max

/** A word and how often it appears. */
data class WordCount(val word: String, val count: Int)

private const val TOP = 3

fun countWords(text: String): List<WordCount> =
    text.lowercase()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .groupingBy { it }
        .eachCount()
        .map { (word, count) -> WordCount(word, count) }
        .sortedByDescending { it.count }

sealed interface Result {
    object Empty : Result
    data class Found(val words: List<WordCount>) : Result
}

fun main(args: Array<String>) {
    val text = args.joinToString(" ").ifEmpty { "the quick brown fox jumps over the lazy dog" }
    val result = countWords(text).take(max(TOP, 1))
    when {
        result.isEmpty() -> println("no words")
        else -> result.forEach { println("${it.word}: ${it.count}") }
    }
    val enabled = true && text != null
}
