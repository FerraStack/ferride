use std::path::PathBuf;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileMatch {
    pub path: PathBuf,
    pub rel_path: String,
    pub score: i64,
    /// Char indices in `rel_path` that matched (for bolding in the UI).
    pub positions: Vec<usize>,
}

/// Subsequence fuzzy match (case-insensitive). Returns `None` when `query`
/// isn't a subsequence of `candidate`. Higher scores are better: matches in
/// the file name, at word starts and in consecutive runs score more; long
/// paths score slightly less. An empty query matches everything.
pub fn fuzzy_score(query: &str, candidate: &str) -> Option<(i64, Vec<usize>)> {
    let cand: Vec<char> = candidate.chars().collect();
    let name_start = cand.iter().rposition(|&c| c == '/').map_or(0, |i| i + 1);
    let q: Vec<char> = query.chars().filter(|c| !c.is_whitespace()).collect();
    if q.is_empty() {
        return Some((-(cand.len() as i64), Vec::new()));
    }

    // Prefer matching inside the file name: try a greedy match that starts at
    // the name, fall back to the whole path.
    let attempt = |from: usize| -> Option<Vec<usize>> {
        let mut positions = Vec::with_capacity(q.len());
        let mut qi = 0;
        for (i, &c) in cand.iter().enumerate().skip(from) {
            if qi < q.len() && eq_ci(c, q[qi]) {
                positions.push(i);
                qi += 1;
            }
        }
        (qi == q.len()).then_some(positions)
    };
    let positions = attempt(name_start).or_else(|| attempt(0))?;

    let mut score: i64 = 0;
    let mut prev: Option<usize> = None;
    for &p in &positions {
        score += 1;
        if p >= name_start {
            score += 4;
        }
        let at_word_start = p == 0
            || matches!(cand[p - 1], '/' | '_' | '-' | '.' | ' ')
            || (cand[p].is_uppercase() && cand[p - 1].is_lowercase());
        if at_word_start {
            score += 6;
        }
        if prev.is_some_and(|q| q + 1 == p) {
            score += 5;
        }
        prev = Some(p);
    }
    if cand[name_start..].iter().collect::<String>().to_lowercase() == query.to_lowercase() {
        score += 50;
    }
    score -= cand.len() as i64 / 4;
    Some((score, positions))
}

fn eq_ci(a: char, b: char) -> bool {
    a == b || a.to_lowercase().eq(b.to_lowercase())
}
