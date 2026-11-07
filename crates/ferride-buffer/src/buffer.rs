use std::ops::Range;

use ropey::Rope;

use crate::grapheme;
use crate::history::{Change, History, Transaction};
use crate::selection::{Selection, Selections};

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum BufferError {
    #[error("range {start}..{end} is out of bounds (len {len})")]
    OutOfBounds { start: usize, end: usize, len: usize },
    #[error("edits overlap")]
    OverlappingEdits,
}

/// A replacement of the char range `range` with `text`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Edit {
    pub range: Range<usize>,
    pub text: String,
}

impl Edit {
    pub fn new(range: Range<usize>, text: impl Into<String>) -> Self {
        Self {
            range,
            text: text.into(),
        }
    }

    pub fn insert(at: usize, text: impl Into<String>) -> Self {
        Self::new(at..at, text)
    }

    pub fn delete(range: Range<usize>) -> Self {
        Self::new(range, "")
    }
}

/// How an edit should interact with undo grouping.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EditKind {
    /// Character typing / IME composition: coalesced with the previous typing
    /// edit while it continues the same run.
    Typing,
    /// Anything else (paste, cut, indent, replace-all...): its own undo step.
    Other,
}

/// A row/column point where the column is in **bytes** (tree-sitter's model).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Point {
    pub row: usize,
    pub column: usize,
}

/// A primitive edit expressed in bytes, in the order it was applied. This is
/// exactly what `tree_sitter::Tree::edit` needs.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ByteEdit {
    pub start_byte: usize,
    pub old_end_byte: usize,
    pub new_end_byte: usize,
    pub start_position: Point,
    pub old_end_position: Point,
    pub new_end_position: Point,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LineEnding {
    Lf,
    CrLf,
}

impl LineEnding {
    pub fn as_str(self) -> &'static str {
        match self {
            LineEnding::Lf => "\n",
            LineEnding::CrLf => "\r\n",
        }
    }
}

/// A text document's content plus selections and history.
#[derive(Debug)]
pub struct Buffer {
    rope: Rope,
    selections: Selections,
    history: History,
    version: u64,
    saved_version: u64,
    line_ending: LineEnding,
}

impl Default for Buffer {
    fn default() -> Self {
        Self::new("")
    }
}

impl Buffer {
    pub fn new(text: &str) -> Self {
        let line_ending = if text.contains("\r\n") {
            LineEnding::CrLf
        } else {
            LineEnding::Lf
        };
        Self {
            rope: Rope::from_str(text),
            selections: Selections::default(),
            history: History::default(),
            version: 0,
            saved_version: 0,
            line_ending,
        }
    }

    // ----- content -------------------------------------------------------

    pub fn rope(&self) -> &Rope {
        &self.rope
    }

    pub fn text(&self) -> String {
        self.rope.to_string()
    }

    pub fn len_chars(&self) -> usize {
        self.rope.len_chars()
    }

    pub fn len_bytes(&self) -> usize {
        self.rope.len_bytes()
    }

    pub fn len_utf16(&self) -> usize {
        self.rope.len_utf16_cu()
    }

    /// Number of lines. A trailing newline starts a final, empty line.
    pub fn len_lines(&self) -> usize {
        self.rope.len_lines()
    }

    pub fn line_ending(&self) -> LineEnding {
        self.line_ending
    }

    /// Text of `line` without its line terminator.
    pub fn line_text(&self, line: usize) -> String {
        if line >= self.len_lines() {
            return String::new();
        }
        let mut s = self.rope.line(line).to_string();
        while s.ends_with('\n') || s.ends_with('\r') {
            s.pop();
        }
        s
    }

    /// Length of `line` in chars, without its terminator.
    pub fn line_len_chars(&self, line: usize) -> usize {
        self.line_end_char(line) - self.line_to_char(line)
    }

    /// Char index of the end of `line`, before its terminator.
    pub fn line_end_char(&self, line: usize) -> usize {
        if line >= self.len_lines() {
            return self.len_chars();
        }
        let slice = self.rope.line(line);
        let mut len = slice.len_chars();
        while len > 0 {
            let c = slice.char(len - 1);
            if c == '\n' || c == '\r' {
                len -= 1;
            } else {
                break;
            }
        }
        self.rope.line_to_char(line) + len
    }

    pub fn slice_to_string(&self, range: Range<usize>) -> String {
        let len = self.len_chars();
        let start = range.start.min(len);
        let end = range.end.clamp(start, len);
        self.rope.slice(start..end).to_string()
    }

    pub fn char_at(&self, idx: usize) -> Option<char> {
        (idx < self.len_chars()).then(|| self.rope.char(idx))
    }

    // ----- grapheme clusters ----------------------------------------------

    /// The grapheme cluster boundary before `char_idx` (cursor Left).
    pub fn prev_grapheme_boundary(&self, char_idx: usize) -> usize {
        grapheme::prev_boundary(self.rope.slice(..), char_idx)
    }

    /// The grapheme cluster boundary after `char_idx` (cursor Right, Delete).
    pub fn next_grapheme_boundary(&self, char_idx: usize) -> usize {
        grapheme::next_boundary(self.rope.slice(..), char_idx)
    }

    // ----- offset conversions ---------------------------------------------
    // All take/return clamped values so callers from the UI side can't panic
    // the core with a stale offset.

    pub fn char_to_byte(&self, char_idx: usize) -> usize {
        self.rope.char_to_byte(char_idx.min(self.len_chars()))
    }

    /// Byte offset → char index. A byte inside a multi-byte char maps to that
    /// char's index.
    pub fn byte_to_char(&self, byte_idx: usize) -> usize {
        self.rope.byte_to_char(byte_idx.min(self.len_bytes()))
    }

    pub fn char_to_utf16(&self, char_idx: usize) -> usize {
        self.rope.char_to_utf16_cu(char_idx.min(self.len_chars()))
    }

    /// UTF-16 offset → char index. An offset in the middle of a surrogate
    /// pair maps to the char that pair encodes (i.e. rounds down).
    pub fn utf16_to_char(&self, utf16_idx: usize) -> usize {
        self.rope.utf16_cu_to_char(utf16_idx.min(self.len_utf16()))
    }

    pub fn char_to_line(&self, char_idx: usize) -> usize {
        self.rope.char_to_line(char_idx.min(self.len_chars()))
    }

    pub fn line_to_char(&self, line: usize) -> usize {
        if line >= self.len_lines() {
            return self.len_chars();
        }
        self.rope.line_to_char(line)
    }

    pub fn line_to_byte(&self, line: usize) -> usize {
        self.char_to_byte(self.line_to_char(line))
    }

    pub fn line_to_utf16(&self, line: usize) -> usize {
        self.char_to_utf16(self.line_to_char(line))
    }

    pub fn byte_to_line(&self, byte_idx: usize) -> usize {
        self.char_to_line(self.byte_to_char(byte_idx))
    }

    pub fn utf16_to_line(&self, utf16_idx: usize) -> usize {
        self.char_to_line(self.utf16_to_char(utf16_idx))
    }

    /// (line, column in chars) for a char index.
    pub fn char_to_line_col(&self, char_idx: usize) -> (usize, usize) {
        let char_idx = char_idx.min(self.len_chars());
        let line = self.char_to_line(char_idx);
        (line, char_idx - self.line_to_char(line))
    }

    /// (line, column in UTF-16 code units) for a char index. This is LSP's
    /// default `Position` encoding.
    pub fn char_to_line_col_utf16(&self, char_idx: usize) -> (usize, usize) {
        let char_idx = char_idx.min(self.len_chars());
        let line = self.char_to_line(char_idx);
        (line, self.char_to_utf16(char_idx) - self.line_to_utf16(line))
    }

    /// (row, column in bytes) for a char index (tree-sitter's `Point`).
    pub fn char_to_point(&self, char_idx: usize) -> Point {
        let char_idx = char_idx.min(self.len_chars());
        let row = self.char_to_line(char_idx);
        Point {
            row,
            column: self.char_to_byte(char_idx) - self.line_to_byte(row),
        }
    }

    /// Char index for (line, column in chars); the column is clamped to the
    /// line's content.
    pub fn line_col_to_char(&self, line: usize, col: usize) -> usize {
        if line >= self.len_lines() {
            return self.len_chars();
        }
        (self.line_to_char(line) + col).min(self.line_end_char(line))
    }

    /// Char index for (line, column in UTF-16 units); clamped to the line.
    pub fn line_col_utf16_to_char(&self, line: usize, col_utf16: usize) -> usize {
        if line >= self.len_lines() {
            return self.len_chars();
        }
        let start = self.line_to_utf16(line);
        let end = self.char_to_utf16(self.line_end_char(line));
        self.utf16_to_char((start + col_utf16).min(end))
    }

    // ----- selections ---------------------------------------------------

    pub fn selections(&self) -> &Selections {
        &self.selections
    }

    pub fn set_selections(&mut self, mut selections: Selections) {
        selections.clamp(self.len_chars());
        if selections != self.selections {
            self.history.seal();
        }
        self.selections = selections;
    }

    pub fn set_selection(&mut self, sel: Selection) {
        self.set_selections(Selections::single(sel));
    }

    // ----- versions ------------------------------------------------------

    /// Monotonic counter bumped by every change (including undo/redo).
    pub fn version(&self) -> u64 {
        self.version
    }

    pub fn is_dirty(&self) -> bool {
        self.version != self.saved_version
    }

    pub fn mark_saved(&mut self) {
        self.mark_saved_at(self.version);
    }

    /// Marks the content as of `version` as saved (an async save captures the
    /// version it wrote; edits made meanwhile stay dirty).
    pub fn mark_saved_at(&mut self, version: u64) {
        self.saved_version = version;
        self.history.seal();
    }

    // ----- line-ending hygiene -------------------------------------------

    /// Moves a char index that sits between `\r` and `\n` to before the `\r`,
    /// so edits and carets never split a CRLF pair.
    pub fn snap_out_of_crlf(&self, idx: usize) -> usize {
        let idx = idx.min(self.len_chars());
        if idx > 0 && idx < self.len_chars() && self.rope.char(idx - 1) == '\r' && self.rope.char(idx) == '\n'
        {
            idx - 1
        } else {
            idx
        }
    }

    /// Widens a range so that it doesn't split a CRLF pair at either end.
    pub fn widen_crlf(&self, range: Range<usize>) -> Range<usize> {
        let start = self.snap_out_of_crlf(range.start);
        if range.end <= range.start {
            return start..start;
        }
        let mut end = range.end.clamp(start, self.len_chars());
        if end > 0 && end < self.len_chars() && self.rope.char(end - 1) == '\r' && self.rope.char(end) == '\n'
        {
            end += 1;
        }
        start..end
    }

    /// Converts any `\r\n`, lone `\r` or `\n` in `text` to this buffer's line ending.
    pub fn normalize_eol(&self, text: &str) -> String {
        if !text.contains(['\r', '\n']) {
            return text.to_owned();
        }
        let lf = text.replace("\r\n", "\n").replace('\r', "\n");
        match self.line_ending {
            LineEnding::Lf => lf,
            LineEnding::CrLf => lf.replace('\n', "\r\n"),
        }
    }

    // ----- editing -------------------------------------------------------

    /// Applies a batch of non-overlapping edits as one user action.
    ///
    /// Positions in `edits` refer to the text *before* the batch. Selections
    /// are mapped through the change: a cursor inside or at the start of a
    /// replaced range ends up after the inserted text.
    pub fn apply(&mut self, edits: Vec<Edit>, kind: EditKind) -> Result<Vec<ByteEdit>, BufferError> {
        let mut edits = self.validate(edits)?;
        if edits.is_empty() {
            return Ok(Vec::new());
        }
        let before = self.selections.clone();
        // Map selections on the pre-edit coordinates.
        let deltas: Vec<(usize, usize, usize)> = edits
            .iter()
            .map(|e| (e.range.start, e.range.end, e.text.chars().count()))
            .collect();
        let mut after = before.clone();
        after.map(|pos| map_pos(pos, &deltas));

        let mut changes = Vec::with_capacity(edits.len());
        let mut byte_edits = Vec::with_capacity(edits.len());
        // Back to front so earlier offsets stay valid.
        edits.reverse();
        for e in edits {
            let (change, be) = self.apply_raw(e.range.start, e.range.end, &e.text);
            changes.push(change);
            byte_edits.push(be);
        }
        after.clamp(self.len_chars());
        self.selections = after.clone();
        self.history.record(Transaction {
            changes,
            selections_before: before,
            selections_after: after,
            mergeable: kind == EditKind::Typing,
        });
        Ok(byte_edits)
    }

    /// Replaces each selection with `text` (multi-cursor typing / paste).
    pub fn insert_at_selections(&mut self, text: &str, kind: EditKind) -> Vec<ByteEdit> {
        let edits = self
            .selections
            .ranges()
            .iter()
            .map(|s| Edit::new(s.start()..s.end(), text))
            .collect();
        self.apply(edits, kind).expect("selections are always valid")
    }

    /// Backspace: deletes each selection, or before each caret: a whole emoji
    /// sequence or `\r\n`, otherwise one code point (VS Code's `deleteLeft`;
    /// see [`grapheme::backspace_start`]).
    pub fn delete_backward(&mut self) -> Vec<ByteEdit> {
        let edits: Vec<Edit> = self
            .selections
            .ranges()
            .iter()
            .filter_map(|s| {
                if !s.is_empty() {
                    return Some(Edit::delete(s.start()..s.end()));
                }
                let pos = s.head;
                if pos == 0 {
                    return None;
                }
                let start = grapheme::backspace_start(self.rope.slice(..), pos);
                Some(Edit::delete(start..pos))
            })
            .collect();
        self.apply(edits, EditKind::Typing).unwrap_or_default()
    }

    /// Forward delete: deletes each selection, or the grapheme cluster after
    /// each caret (`\r\n` and emoji sequences included).
    pub fn delete_forward(&mut self) -> Vec<ByteEdit> {
        let len = self.len_chars();
        let edits: Vec<Edit> = self
            .selections
            .ranges()
            .iter()
            .filter_map(|s| {
                if !s.is_empty() {
                    return Some(Edit::delete(s.start()..s.end()));
                }
                let pos = s.head;
                if pos >= len {
                    return None;
                }
                Some(Edit::delete(pos..self.next_grapheme_boundary(pos)))
            })
            .collect();
        self.apply(edits, EditKind::Typing).unwrap_or_default()
    }

    /// Ends the current typing run: the next edit starts a new undo step.
    pub fn seal_undo_group(&mut self) {
        self.history.seal();
    }

    pub fn can_undo(&self) -> bool {
        self.history.can_undo()
    }

    pub fn can_redo(&self) -> bool {
        self.history.can_redo()
    }

    /// Undoes the last transaction. Returns the byte edits that were applied
    /// (for syntax trees), or `None` if there was nothing to undo.
    pub fn undo(&mut self) -> Option<Vec<ByteEdit>> {
        let tx = self.history.pop_undo()?;
        let mut byte_edits = Vec::with_capacity(tx.changes.len());
        for c in tx.changes.iter().rev() {
            let end = c.start + c.inserted_len();
            let (_, be) = self.apply_raw(c.start, end, &c.removed);
            byte_edits.push(be);
        }
        let mut sel = tx.selections_before.clone();
        sel.clamp(self.len_chars());
        self.selections = sel;
        self.history.push_redo(tx);
        Some(byte_edits)
    }

    pub fn redo(&mut self) -> Option<Vec<ByteEdit>> {
        let tx = self.history.pop_redo()?;
        let mut byte_edits = Vec::with_capacity(tx.changes.len());
        for c in &tx.changes {
            let end = c.start + c.removed_len();
            let (_, be) = self.apply_raw(c.start, end, &c.inserted);
            byte_edits.push(be);
        }
        let mut sel = tx.selections_after.clone();
        sel.clamp(self.len_chars());
        self.selections = sel;
        self.history.push_undo_from_redo(tx);
        Some(byte_edits)
    }

    fn validate(&self, mut edits: Vec<Edit>) -> Result<Vec<Edit>, BufferError> {
        let len = self.len_chars();
        for e in &edits {
            if e.range.start > e.range.end || e.range.end > len {
                return Err(BufferError::OutOfBounds {
                    start: e.range.start,
                    end: e.range.end,
                    len,
                });
            }
        }
        edits.retain(|e| !(e.range.is_empty() && e.text.is_empty()));
        edits.sort_by_key(|e| (e.range.start, e.range.end));
        for w in edits.windows(2) {
            if w[1].range.start < w[0].range.end
                || (w[1].range.start == w[0].range.start && w[0].range.is_empty() && w[1].range.is_empty())
            {
                return Err(BufferError::OverlappingEdits);
            }
        }
        Ok(edits)
    }

    /// Replaces chars `start..end` with `text` without touching history.
    fn apply_raw(&mut self, start: usize, end: usize, text: &str) -> (Change, ByteEdit) {
        let start_byte = self.rope.char_to_byte(start);
        let old_end_byte = self.rope.char_to_byte(end);
        let start_position = self.char_to_point(start);
        let old_end_position = self.char_to_point(end);
        let removed = self.rope.slice(start..end).to_string();
        if end > start {
            self.rope.remove(start..end);
        }
        if !text.is_empty() {
            self.rope.insert(start, text);
        }
        let new_end_char = start + text.chars().count();
        let be = ByteEdit {
            start_byte,
            old_end_byte,
            new_end_byte: start_byte + text.len(),
            start_position,
            old_end_position,
            new_end_position: self.char_to_point(new_end_char),
        };
        self.version += 1;
        (
            Change {
                start,
                removed,
                inserted: text.to_owned(),
            },
            be,
        )
    }
}

/// Maps a pre-edit position through sorted, non-overlapping edits given as
/// (start, end, inserted_len).
fn map_pos(pos: usize, edits: &[(usize, usize, usize)]) -> usize {
    let mut delta: isize = 0;
    for &(start, end, ins) in edits {
        if pos < start {
            break;
        }
        if pos <= end {
            return (start as isize + delta) as usize + ins;
        }
        delta += ins as isize - (end - start) as isize;
    }
    (pos as isize + delta) as usize
}

#[cfg(test)]
mod tests;
