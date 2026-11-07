//! Undo/redo history.

use crate::selection::Selections;

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
}

impl History {
    pub fn can_undo(&self) -> bool {
        !self.undo.is_empty()
    }

    pub fn can_redo(&self) -> bool {
        !self.redo.is_empty()
    }

    /// Records a transaction, coalescing it into the previous one when both
    /// are single-change typing edits that touch the same run of text.
    pub fn record(&mut self, tx: Transaction) {
        self.redo.clear();
        if tx.mergeable
            && let Some(last) = self.undo.last_mut()
            && last.mergeable
            && try_merge(last, &tx)
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

fn try_merge(last: &mut Transaction, next: &Transaction) -> bool {
    let ([l], [n]) = (last.changes.as_mut_slice(), next.changes.as_slice()) else {
        return false;
    };
    if n.inserted.contains('\n') || l.inserted.contains('\n') {
        return false;
    }
    let l_ins_end = l.start + l.inserted_len();
    let n_end = n.start + n.removed_len();

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
