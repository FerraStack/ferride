-- Ferride sample: the most common words per document
CREATE TABLE IF NOT EXISTS words (
    id INTEGER PRIMARY KEY,
    document_id INTEGER NOT NULL REFERENCES documents (id),
    word TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO words (document_id, word) VALUES (1, 'quick'), (1, 'fox');

SELECT d.title,
       w.word,
       COUNT(*) AS occurrences,
       ROUND(AVG(LENGTH(w.word)), 2) AS avg_length
FROM words AS w
JOIN documents AS d ON d.id = w.document_id
WHERE w.word <> '' AND d.archived = FALSE
GROUP BY d.title, w.word
HAVING COUNT(*) > 1
ORDER BY occurrences DESC
LIMIT 10;
