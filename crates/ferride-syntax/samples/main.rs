use std::collections::HashMap;

/// Counts words in a line of text.
fn word_counts(text: &str) -> HashMap<&str, usize> {
    let mut counts = HashMap::new();
    for word in text.split_whitespace() {
        *counts.entry(word).or_insert(0) += 1;
    }
    counts
}

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

fn main() {
    let text = "the quick brown fox jumps over the lazy dog";
    let mut words: Vec<_> = word_counts(text).into_iter().collect();
    words.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(b.0)));
    for (word, n) in words.iter().take(3) {
        println!("{word}: {n}"); // top three
    }
}
