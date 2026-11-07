package dev.ferride.workbench

import java.io.File

/** The project seeded on first run, so there's something to open and search. */
object SampleProject {
    const val NAME = "hello-ferride"

    val files: Map<String, String> = linkedMapOf(
        "README.md" to """
            # hello-ferride

            A tiny sample project. Open files from the **Explorer**, search with the
            magnifier, or press the command button to open the **Command Palette**.

            - `src/main.rs` — Rust
            - `scripts/stats.py` — Python
            - `web/app.ts` — TypeScript
        """.trimIndent() + "\n",
        "Cargo.toml" to """
            [package]
            name = "hello-ferride"
            version = "0.1.0"
            edition = "2024"
        """.trimIndent() + "\n",
        "src/main.rs" to """
            use std::collections::HashMap;

            /// Counts words in a line of text.
            fn word_counts(text: &str) -> HashMap<&str, usize> {
                let mut counts = HashMap::new();
                for word in text.split_whitespace() {
                    *counts.entry(word).or_insert(0) += 1;
                }
                counts
            }

            fn main() {
                let text = "the quick brown fox jumps over the lazy dog";
                let counts = word_counts(text);
                let mut words: Vec<_> = counts.iter().collect();
                words.sort_by(|a, b| b.1.cmp(a.1).then(a.0.cmp(b.0)));
                for (word, n) in words.iter().take(3) {
                    println!("{word}: {n}");
                }
            }
        """.trimIndent() + "\n",
        "src/lib.rs" to """
            pub mod shapes {
                #[derive(Debug, Clone, Copy)]
                pub struct Circle {
                    pub radius: f64,
                }

                impl Circle {
                    pub const UNIT: Circle = Circle { radius: 1.0 };

                    pub fn area(&self) -> f64 {
                        std::f64::consts::PI * self.radius * self.radius
                    }
                }
            }
        """.trimIndent() + "\n",
        "scripts/stats.py" to """
            from statistics import mean, median


            def summarize(values: list[float]) -> dict[str, float]:
                ""${'"'}Return basic statistics for a list of numbers.""${'"'}
                if not values:
                    raise ValueError("empty input")
                return {"mean": mean(values), "median": median(values), "max": max(values)}


            if __name__ == "__main__":
                print(summarize([3, 1, 4, 1, 5, 9, 2, 6]))
        """.trimIndent() + "\n",
        "web/app.ts" to """
            interface Todo {
              id: number;
              title: string;
              done: boolean;
            }

            export function pending(todos: Todo[]): Todo[] {
              return todos.filter((t) => !t.done);
            }

            const todos: Todo[] = [
              { id: 1, title: "Write the editor", done: true },
              { id: 2, title: "Ship it", done: false },
            ];
            console.log(pending(todos).map((t) => t.title));
        """.trimIndent() + "\n",
        "web/package.json" to """
            {
              "name": "hello-web",
              "version": "1.0.0",
              "private": true,
              "scripts": { "build": "tsc -p ." }
            }
        """.trimIndent() + "\n",
    )

    /** Writes the sample into [parent] once; returns the project directory. */
    fun seed(parent: File): File {
        val dir = File(parent, NAME)
        if (!dir.exists()) {
            files.forEach { (rel, text) ->
                val f = File(dir, rel)
                f.parentFile?.mkdirs()
                f.writeText(text)
            }
        }
        return dir
    }
}
