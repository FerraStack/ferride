//! Line and word diffs between two texts, independent of git.
//!
//! Lines are compared *with* their terminators, like git, so a changed line ending or a missing final newline
//! shows up as a change. Line numbers are 0-based; word ranges are UTF-16 columns within a line (without the
//! terminator), which is what the Kotlin side indexes strings with.

use gix::diff::blob::{Algorithm, Diff, InternedInput, sources::words};

/// Hunks larger than this many lines on either side get no word-level highlights (VS Code also gives up on
/// very large hunks; it keeps the diff readable and bounded in time).
const MAX_WORD_DIFF_LINES: u32 = 400;

/// A half-open range of UTF-16 columns within one line.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ColumnRange {
    pub start: u32,
    pub end: u32,
}

/// One changed line inside a [`Hunk`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChangedLine {
    /// 0-based line number in the original (for removed lines) or modified text (for added lines).
    pub line: u32,
    /// The line's text without its terminator.
    pub text: String,
    /// Changed words within the line (VS Code's `diffEditor.removedTextBackground`/`insertedTextBackground`).
    pub words: Vec<ColumnRange>,
}

/// A contiguous change: `original_count` lines starting at `original_start` were replaced by `modified_count`
/// lines starting at `modified_start`. Either count may be 0 (pure insertion / deletion); the start of an empty
/// side is the line *before which* the change sits.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Hunk {
    pub original_start: u32,
    pub original_count: u32,
    pub modified_start: u32,
    pub modified_count: u32,
    pub removed: Vec<ChangedLine>,
    pub added: Vec<ChangedLine>,
}

/// Kind of a gutter decoration (VS Code's quick diff: `editorGutter.addedBackground`, …).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LineChangeKind {
    Added,
    Modified,
    /// Lines were deleted *after* `start` (`start` = `end`; VS Code draws a small triangle there).
    Deleted,
}

/// A gutter decoration on lines `start..end` (0-based, in the modified text).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LineChange {
    pub kind: LineChangeKind,
    pub start: u32,
    pub end: u32,
}

/// Splits `text` into lines including their terminators. A trailing newline does not start a new (empty) line.
pub(crate) fn lines_inclusive(text: &str) -> Vec<&str> {
    text.split_inclusive('\n').collect()
}

fn strip_eol(line: &str) -> &str {
    let line = line.strip_suffix('\n').unwrap_or(line);
    line.strip_suffix('\r').unwrap_or(line)
}

fn utf16_len(s: &str) -> u32 {
    s.encode_utf16().count() as u32
}

fn line_diff<'a>(original: &'a str, modified: &'a str) -> (InternedInput<&'a str>, Diff) {
    let input = InternedInput::new(original, modified);
    let mut diff = Diff::compute(Algorithm::Histogram, &input);
    diff.postprocess_lines(&input);
    (input, diff)
}

/// Computes the hunks between `original` and `modified`, with word-level highlights for replaced lines.
pub fn diff_texts(original: &str, modified: &str) -> Vec<Hunk> {
    let (input, diff) = line_diff(original, modified);
    let original_lines = lines_inclusive(original);
    let modified_lines = lines_inclusive(modified);
    let mut word_input = InternedInput::default();
    let mut word_diff = Diff::default();
    let mut hunks = Vec::new();
    for hunk in diff.hunks() {
        let mut removed: Vec<ChangedLine> = hunk
            .before
            .clone()
            .map(|i| changed_line(i, original_lines[i as usize]))
            .collect();
        let mut added: Vec<ChangedLine> = hunk
            .after
            .clone()
            .map(|i| changed_line(i, modified_lines[i as usize]))
            .collect();
        let replaced = !hunk.before.is_empty() && !hunk.after.is_empty();
        if replaced
            && hunk.before.len() as u32 <= MAX_WORD_DIFF_LINES
            && hunk.after.len() as u32 <= MAX_WORD_DIFF_LINES
        {
            hunk.latin_word_diff(&input, &mut word_input, &mut word_diff);
            mark_words(&original_lines, &hunk.before, &mut removed, |t| {
                word_diff.is_removed(t)
            });
            mark_words(&modified_lines, &hunk.after, &mut added, |t| {
                word_diff.is_added(t)
            });
            word_input.clear();
        }
        hunks.push(Hunk {
            original_start: hunk.before.start,
            original_count: hunk.before.len() as u32,
            modified_start: hunk.after.start,
            modified_count: hunk.after.len() as u32,
            removed,
            added,
        });
    }
    hunks
}

fn changed_line(line: u32, text: &str) -> ChangedLine {
    ChangedLine {
        line,
        text: strip_eol(text).to_owned(),
        words: Vec::new(),
    }
}

/// Walks the word tokens of `range`'s lines in the same order `latin_word_diff` produced them and records the
/// UTF-16 column ranges of the changed ones, merging neighbours.
fn mark_words(
    lines: &[&str],
    range: &std::ops::Range<u32>,
    out: &mut [ChangedLine],
    is_changed: impl Fn(u32) -> bool,
) {
    let mut token = 0u32;
    for (slot, line_idx) in range.clone().enumerate() {
        let full = lines[line_idx as usize];
        let visible_len = utf16_len(strip_eol(full));
        let mut col = 0u32;
        let ranges = &mut out[slot].words;
        for word in words(full) {
            let len = utf16_len(word);
            if is_changed(token) {
                let start = col.min(visible_len);
                let end = (col + len).min(visible_len);
                if start < end {
                    match ranges.last_mut() {
                        Some(last) if last.end == start => last.end = end,
                        _ => ranges.push(ColumnRange { start, end }),
                    }
                }
            }
            col += len;
            token += 1;
        }
        // A line whose every visible word changed is highlighted as a whole line already; keep the ranges
        // anyway: the UI decides.
    }
}

/// Gutter decorations for `modified` compared to `original`, the way VS Code's quick diff draws them.
pub fn line_changes(original: &str, modified: &str) -> Vec<LineChange> {
    let (_, diff) = line_diff(original, modified);
    diff.hunks()
        .map(|h| {
            if h.after.is_empty() {
                // Deleted lines are marked on the line before which they were (VS Code marks the previous line).
                let at = h.after.start.saturating_sub(1);
                LineChange {
                    kind: LineChangeKind::Deleted,
                    start: at,
                    end: at,
                }
            } else if h.before.is_empty() {
                LineChange {
                    kind: LineChangeKind::Added,
                    start: h.after.start,
                    end: h.after.end,
                }
            } else {
                LineChange {
                    kind: LineChangeKind::Modified,
                    start: h.after.start,
                    end: h.after.end,
                }
            }
        })
        .collect()
}

/// Identifies a hunk by its line ranges, so a hunk the UI shows can be found again after a recompute.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct HunkId {
    pub original_start: u32,
    pub original_count: u32,
    pub modified_start: u32,
    pub modified_count: u32,
}

impl From<&Hunk> for HunkId {
    fn from(h: &Hunk) -> Self {
        HunkId {
            original_start: h.original_start,
            original_count: h.original_count,
            modified_start: h.modified_start,
            modified_count: h.modified_count,
        }
    }
}

/// Returns `original` with the selected hunks of the `original → modified` diff applied (the others are left
/// as in `original`). Used for "Stage Change" (apply to the index text) and, swapped, for "Revert Change".
///
/// Returns the new text and how many of `selected` matched a current hunk.
pub fn apply_hunks(original: &str, modified: &str, selected: &[HunkId]) -> (String, usize) {
    let (_, diff) = line_diff(original, modified);
    let original_lines = lines_inclusive(original);
    let modified_lines = lines_inclusive(modified);
    let mut out = String::with_capacity(original.len().max(modified.len()));
    let mut pos = 0usize; // next original line to copy
    let mut applied = 0usize;
    for h in diff.hunks() {
        let id = HunkId {
            original_start: h.before.start,
            original_count: h.before.len() as u32,
            modified_start: h.after.start,
            modified_count: h.after.len() as u32,
        };
        if !selected.contains(&id) {
            continue;
        }
        applied += 1;
        let start = h.before.start as usize;
        for line in &original_lines[pos..start] {
            out.push_str(line);
        }
        for line in &modified_lines[h.after.start as usize..h.after.end as usize] {
            out.push_str(line);
        }
        pos = h.before.end as usize;
    }
    for line in &original_lines[pos..] {
        out.push_str(line);
    }
    (out, applied)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identical_texts_have_no_hunks() {
        assert!(diff_texts("a\nb\n", "a\nb\n").is_empty());
        assert!(line_changes("a\nb\n", "a\nb\n").is_empty());
    }

    #[test]
    fn insertion_deletion_and_replacement() {
        let original = "one\ntwo\nthree\nfour\n";
        let modified = "zero\none\nTWO\nthree\n";
        let hunks = diff_texts(original, modified);
        assert_eq!(hunks.len(), 3, "{hunks:#?}");
        assert_eq!(
            (
                hunks[0].original_count,
                hunks[0].modified_start,
                hunks[0].modified_count
            ),
            (0, 0, 1)
        );
        assert_eq!(hunks[0].added[0].text, "zero");
        assert_eq!((hunks[1].original_start, hunks[1].modified_start), (1, 2));
        assert_eq!(hunks[1].removed[0].text, "two");
        assert_eq!(hunks[1].added[0].text, "TWO");
        assert_eq!(
            (
                hunks[2].original_start,
                hunks[2].original_count,
                hunks[2].modified_count
            ),
            (3, 1, 0)
        );

        let gutter = line_changes(original, modified);
        assert_eq!(
            gutter,
            vec![
                LineChange {
                    kind: LineChangeKind::Added,
                    start: 0,
                    end: 1
                },
                LineChange {
                    kind: LineChangeKind::Modified,
                    start: 2,
                    end: 3
                },
                LineChange {
                    kind: LineChangeKind::Deleted,
                    start: 3,
                    end: 3
                },
            ]
        );
    }

    #[test]
    fn word_highlights_are_utf16_columns() {
        let hunks = diff_texts("let x = 1;\n", "let y = 1;\n");
        assert_eq!(hunks.len(), 1);
        assert_eq!(hunks[0].removed[0].words, vec![ColumnRange { start: 4, end: 5 }]);
        assert_eq!(hunks[0].added[0].words, vec![ColumnRange { start: 4, end: 5 }]);

        // "é" is 1 UTF-16 unit, the emoji is 2.
        let hunks = diff_texts("s = \"😀 a\"\n", "s = \"😀 b\"\n");
        assert_eq!(hunks[0].added[0].words, vec![ColumnRange { start: 8, end: 9 }]);
    }

    #[test]
    fn word_highlights_merge_adjacent_tokens() {
        let hunks = diff_texts("fn foo(a: u8)\n", "fn bar_baz(a: u8, b: u16)\n");
        let added = &hunks[0].added[0].words;
        assert_eq!(added[0], ColumnRange { start: 3, end: 10 });
        assert!(added.len() >= 2, "{added:?}");
    }

    #[test]
    fn crlf_is_stripped_from_display_but_compared() {
        let hunks = diff_texts("a\r\nb\r\n", "a\nb\r\n");
        assert_eq!(hunks.len(), 1);
        assert_eq!(hunks[0].removed[0].text, "a");
        assert_eq!(hunks[0].added[0].text, "a");
    }

    #[test]
    fn missing_final_newline_is_a_change() {
        let hunks = diff_texts("a\nb\n", "a\nb");
        assert_eq!(hunks.len(), 1);
        assert_eq!(hunks[0].original_start, 1);
    }

    #[test]
    fn apply_selected_hunks_only() {
        let original = "1\n2\n3\n4\n5\n6\n7\n8\n";
        let modified = "1\nTWO\n3\n4\n5\n6\nSEVEN\n8\nnine\n";
        let hunks = diff_texts(original, modified);
        assert_eq!(hunks.len(), 3);
        let first = HunkId::from(&hunks[0]);
        let last = HunkId::from(&hunks[2]);
        let (text, applied) = apply_hunks(original, modified, &[first]);
        assert_eq!(applied, 1);
        assert_eq!(text, "1\nTWO\n3\n4\n5\n6\n7\n8\n");
        let (text, _) = apply_hunks(original, modified, &[last]);
        assert_eq!(text, "1\n2\n3\n4\n5\n6\n7\n8\nnine\n");
        let all: Vec<HunkId> = hunks.iter().map(HunkId::from).collect();
        assert_eq!(apply_hunks(original, modified, &all).0, modified);
        assert_eq!(apply_hunks(original, modified, &[]).0, original);
    }

    #[test]
    fn revert_is_apply_in_reverse() {
        let index = "a\nb\nc\n";
        let worktree = "a\nB\nc\nd\n";
        // Revert the second hunk only: diff worktree → index, apply the matching reverse hunk.
        let reverse = diff_texts(worktree, index);
        let target = reverse
            .iter()
            .find(|h| h.original_start == 3)
            .map(HunkId::from)
            .unwrap();
        assert_eq!(apply_hunks(worktree, index, &[target]).0, "a\nB\nc\n");
    }
}
