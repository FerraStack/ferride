package main

import (
	"fmt"
	"sort"
	"strings"
)

// WordCount is a word and how often it appears.
type WordCount struct {
	Word  string
	Count int
}

func countWords(text string) []WordCount {
	counts := make(map[string]int)
	for _, w := range strings.Fields(strings.ToLower(text)) {
		counts[w]++
	}
	out := make([]WordCount, 0, len(counts))
	for w, c := range counts {
		out = append(out, WordCount{Word: w, Count: c})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Count > out[j].Count })
	return out
}

func main() {
	const text = "the quick brown fox jumps over the lazy dog"
	for i, wc := range countWords(text) {
		if i >= 3 {
			break
		}
		fmt.Printf("%s: %d\n", wc.Word, wc.Count)
	}
}
