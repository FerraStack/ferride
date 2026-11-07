//! Undo/redo history.
//!
//! Typing coalesces into one undo step per **word**: a run of typing edits
//! merges while it continues the same stretch of text, and a new step starts
//! when
//! - a word character is typed right after a non-word one (`hello |world`:
//!   undo removes `world`, then `hello `),
//! - the user pauses for more than [`TYPING_PAUSE`] between keystrokes,
//! - a newline is typed, the cursor moves, or the group is sealed explicitly.
//!
//! IME composition updates (replacing the text being composed) always merge,
//! however slowly the user types, so one composed word is one undo step.

use std::time::{Duration, Instant};

use crate::selection::Selections;

/// A typing pause longer than this starts a new undo step.
pub const TYPING_PAUSE: Duration = Duration::from_millis(500);

/// One primitive replacement, in char indices of the rope state *at the time
/// it was applied*.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Change {
    pub start: usize,
    pub removed: String,
    pub inserted: String,
}

impl Change {
    pub fn removed_len(&self) -> usize {
        self.removed.chars().count()
    }

    pub fn inserted_len(&self) -> usize {
        self.inserted.chars().count()
    }
}

/// A group of changes undone/redone as one step. `changes` are stored in
/// application order.
#[derive(Debug, Clone)]
pub struct Transaction {
    pub changes: Vec<Change>,
    pub selections_before: Selections,
    pub selections_after: Selections,
    pub(crate) mergeable: bool,
}

#[derive(Debug, Default)]
pub(crate) struct History {
    undo: Vec<Transaction>,
    redo: Vec<Transaction>,
    /// When the last transaction was recorded (for pause-based grouping).
    last_recorded: Option<Instant>,
}

impl History {
    pub fn can_undo(&self) -> bool {
        !self.undo.is_empty()
    }

    pub fn can_redo(&self) -> bool {
        !self.redo.is_empty()
    }

    /// Records a transaction at time `now`, coalescing it into the previous
    /// one when both are single-change typing edits that continue the same
    /// word (see the module docs).
    pub fn record(&mut self, tx: Transaction, now: Instant) {
        self.redo.clear();
        let paused = self
            .last_recorded
            .is_some_and(|t| now.saturating_duration_since(t) > TYPING_PAUSE);
        self.last_recorded = Some(now);
        if tx.mergeable
            && let Some(last) = self.undo.last_mut()
            && last.mergeable
            && try_merge(last, &tx, paused)
        {
            last.selections_after = tx.selections_after;
            return;
        }
        self.undo.push(tx);
    }

    /// Stops the current typing run from absorbing further edits.
    pub fn seal(&mut self) {
        if let Some(last) = self.undo.last_mut() {
            last.mergeable = false;
        }
    }

    pub fn pop_undo(&mut self) -> Option<Transaction> {
        let tx = self.undo.pop()?;
        Some(tx)
    }

    pub fn push_redo(&mut self, mut tx: Transaction) {
        tx.mergeable = false;
        self.redo.push(tx);
    }

    pub fn pop_redo(&mut self) -> Option<Transaction> {
        self.redo.pop()
    }

    pub fn push_undo_from_redo(&mut self, mut tx: Transaction) {
        tx.mergeable = false;
        self.undo.push(tx);
    }
}

/// Letters, digits and `_`: the same notion of a word as double-tap selection.
fn is_word_char(c: char) -> bool {
    c.is_alphanumeric() || c == '_'
}

fn try_merge(last: &mut Transaction, next: &Transaction, paused: bool) -> bool {
    let ([l], [n]) = (last.changes.as_mut_slice(), next.changes.as_slice()) else {
        return false;
    };
    if n.inserted.contains('\n') || l.inserted.contains('\n') {
        return false;
    }
    let l_ins_end = l.start + l.inserted_len();
    let n_end = n.start + n.removed_len();
    let pure_insert = n.removed.is_empty();
    let pure_delete = n.inserted.is_empty();

    // A pause ends the run, except for IME composition updates (they replace
    // the text being composed and must stay one step however slow the user is).
    if paused && (pure_insert || pure_delete) {
        return false;
    }
    // Typing a word char right after a non-word char starts the next word's step.
    if pure_insert
        && n.start == l_ins_end
        && let (Some(prev), Some(first)) = (l.inserted.chars().last(), n.inserted.chars().next())
        && !is_word_char(prev)
        && is_word_char(first)
    {
        return false;
    }

    // The new change lies within (or at the edge of) the text the last change
    // inserted: typing, IME composition updates, backspacing typed text.
    if n.start >= l.start && n_end <= l_ins_end {
        let mut chars: Vec<char> = l.inserted.chars().collect();
        let a = n.start - l.start;
        let b = n_end - l.start;
        chars.splice(a..b, n.inserted.chars());
        l.inserted = chars.into_iter().collect();
        return true;
    }
    // Consecutive pure deletions (backspace / forward delete runs).
    if l.inserted.is_empty() && n.inserted.is_empty() {
        if n_end == l.start {
            l.start = n.start;
            l.removed.insert_str(0, &n.removed);
            return true;
        }
        if n.start == l.start {
            l.removed.push_str(&n.removed);
            return true;
        }
    }
    false
}
