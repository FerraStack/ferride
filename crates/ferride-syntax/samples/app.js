import { readFile } from "node:fs/promises";

const TOP = 3;

/** Counts words and returns the most common ones. */
export async function topWords(path, n = TOP) {
  const text = await readFile(path, "utf8");
  const counts = new Map();
  for (const word of text.toLowerCase().split(/\s+/)) {
    if (word) counts.set(word, (counts.get(word) ?? 0) + 1);
  }
  return [...counts.entries()].sort((a, b) => b[1] - a[1]).slice(0, n);
}

class Counter extends EventTarget {
  #count = 0;
  increment() {
    this.#count++;
    this.dispatchEvent(new Event(`changed:${this.#count}`));
  }
}

const query = sql`SELECT word, COUNT(*) FROM words WHERE length(word) > ${TOP} GROUP BY word`;
const styles = css`
  .counter { color: red; }
`;

topWords(process.argv[2]).then(console.log).catch((err) => console.error(err));
