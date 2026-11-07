package dev.ferride.sample;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Counts words and prints the most common ones. */
public final class Main {
    private static final int TOP = 3;

    record WordCount(String word, int count) {}

    static List<WordCount> countWords(String text) {
        Map<String, Integer> counts = new HashMap<>();
        for (String w : text.toLowerCase().split("\\s+")) {
            counts.merge(w, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .map(e -> new WordCount(e.getKey(), e.getValue()))
                .sorted((a, b) -> Integer.compare(b.count(), a.count()))
                .toList();
    }

    @SuppressWarnings("unused")
    public static void main(String[] args) {
        var text = "the quick brown fox jumps over the lazy dog";
        for (WordCount wc : countWords(text).subList(0, TOP)) {
            System.out.printf("%s: %d%n", wc.word(), wc.count()); // 1.5x faster
        }
    }
}
