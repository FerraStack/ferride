#include <stdio.h>
#include <stdlib.h>

#define MAX_WORDS 128

/* A word and how often it appears. */
typedef struct {
    const char *text;
    unsigned int count;
} Word;

static int compare(const void *a, const void *b) {
    const Word *x = a, *y = b;
    return (int)y->count - (int)x->count;
}

int main(int argc, char **argv) {
    Word words[MAX_WORDS] = {0};
    size_t n = 0;
    for (int i = 1; i < argc && n < MAX_WORDS; i++) {
        words[n++] = (Word){ .text = argv[i], .count = 1 };
    }
    qsort(words, n, sizeof(Word), compare);
    printf("%zu words\n", n); // done
    return EXIT_SUCCESS;
}
