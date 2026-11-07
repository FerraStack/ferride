//! Syntax highlighting for Ferride.
//!
//! One [`Syntax`] per open document holds a tree-sitter parser and the
//! current tree. After every buffer change the caller passes the
//! [`ByteEdit`]s produced by `ferride-buffer` to [`Syntax::update`], which
//! edits the old tree and reparses incrementally (reading straight from the
//! rope, without copying the document).
//!
//! Highlighting is **viewport-scoped**: [`Syntax::highlight_bytes`] and
//! [`Syntax::line_spans`] run the grammar's highlight query only over the
//! requested range, so cost scales with what is on screen, not file size.

mod highlight;
mod language;

use std::ops::ControlFlow;
use std::time::{Duration, Instant};

use ferride_buffer::{Buffer, ByteEdit};
use ropey::Rope;
use tree_sitter::{InputEdit, ParseOptions, ParseState, Parser, Point, Tree};

pub use highlight::{BracketMark, HighlightKind, HighlightSpan, LineSpan};

/// Time budget for parsing a newly opened document.
pub const INITIAL_PARSE_BUDGET: Duration = Duration::from_millis(1500);
/// Time budget for an incremental reparse after an edit (runs on the UI thread).
pub const EDIT_PARSE_BUDGET: Duration = Duration::from_millis(40);
pub use language::LanguageId;

/// Incremental parser + tree for one document.
pub struct Syntax {
    language: LanguageId,
    parser: Parser,
    tree: Option<Tree>,
    /// The last reparse ran out of time: `tree` is the edited old tree.
    stale: bool,
    /// Bumped whenever `tree` changes (edit or reparse).
    generation: u64,
    /// Bracket tokens of `generation`, collected up to `.1` bytes.
    brackets: Option<(u64, usize, Vec<BracketMark>)>,
}

impl std::fmt::Debug for Syntax {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Syntax")
            .field("language", &self.language)
            .field("has_tree", &self.tree.is_some())
            .field("stale", &self.stale)
            .finish()
    }
}

impl Syntax {
    /// Creates a parser for `language` and parses `rope` fully (within
    /// [`INITIAL_PARSE_BUDGET`]; past that the document stays unhighlighted).
    pub fn new(language: LanguageId, rope: &Rope) -> Self {
        Self::with_budget(language, rope, INITIAL_PARSE_BUDGET)
    }

    pub fn with_budget(language: LanguageId, rope: &Rope, budget: Duration) -> Self {
        let mut parser = Parser::new();
        parser
            .set_language(&language.config().language)
            .expect("bundled grammars match the tree-sitter ABI");
        let mut syntax = Self {
            language,
            parser,
            tree: None,
            stale: false,
            generation: 0,
            brackets: None,
        };
        syntax.reparse(rope, budget);
        syntax
    }

    pub fn language(&self) -> LanguageId {
        self.language
    }

    pub fn tree(&self) -> Option<&Tree> {
        self.tree.as_ref()
    }

    /// True when the last reparse ran out of time and highlighting may lag.
    pub fn is_stale(&self) -> bool {
        self.stale
    }

    /// Applies buffer edits to the old tree and reparses incrementally within
    /// [`EDIT_PARSE_BUDGET`]. If the budget runs out, the edited old tree is
    /// kept (highlights shift with the text but may be stale) and the next
    /// call tries again.
    pub fn update(&mut self, rope: &Rope, edits: &[ByteEdit]) {
        self.update_with_budget(rope, edits, EDIT_PARSE_BUDGET);
    }

    pub fn update_with_budget(&mut self, rope: &Rope, edits: &[ByteEdit], budget: Duration) {
        if let Some(tree) = &mut self.tree {
            for e in edits {
                tree.edit(&to_input_edit(e));
            }
            self.generation += 1;
        }
        self.reparse(rope, budget);
    }

    /// Returns false if parsing was cancelled by the budget.
    fn reparse(&mut self, rope: &Rope, budget: Duration) -> bool {
        let len = rope.len_bytes();
        let mut read = |byte: usize, _: Point| -> &[u8] {
            if byte >= len {
                return &[];
            }
            let (chunk, chunk_start, _, _) = rope.chunk_at_byte(byte);
            &chunk.as_bytes()[byte - chunk_start..]
        };
        let started = Instant::now();
        let mut progress = |_: &ParseState| {
            if started.elapsed() > budget {
                ControlFlow::Break(())
            } else {
                ControlFlow::Continue(())
            }
        };
        let options = ParseOptions::new().progress_callback(&mut progress);
        match self
            .parser
            .parse_with_options(&mut read, self.tree.as_ref(), Some(options))
        {
            Some(tree) => {
                self.tree = Some(tree);
                self.stale = false;
                self.generation += 1;
                true
            }
            None => {
                // Cancelled: forget the partial parse; keep the (edited) old tree.
                self.parser.reset();
                self.stale = true;
                false
            }
        }
    }

    /// Bracket tokens per line for `lines`, as (UTF-16 column, depth).
    pub fn line_brackets(
        &mut self,
        buffer: &Buffer,
        lines: std::ops::Range<usize>,
    ) -> Vec<Vec<(usize, u32)>> {
        let end_line = lines.end.min(buffer.len_lines());
        let start_line = lines.start.min(end_line);
        let Some(tree) = &self.tree else {
            return vec![Vec::new(); end_line - start_line];
        };
        let end_byte = buffer.char_to_byte(buffer.line_end_char(end_line.saturating_sub(1)));
        let fresh =
            matches!(&self.brackets, Some((g, walked, _)) if *g == self.generation && *walked >= end_byte);
        if !fresh {
            let marks = highlight::collect_brackets(tree, end_byte);
            self.brackets = Some((self.generation, end_byte, marks));
        }
        let marks = &self.brackets.as_ref().expect("just filled").2;
        let mut out = Vec::with_capacity(end_line - start_line);
        let first_byte = buffer.line_to_byte(start_line);
        let mut k = marks.partition_point(|m| m.byte < first_byte);
        for line in start_line..end_line {
            let ls = buffer.line_to_byte(line);
            let text = buffer.line_text(line);
            let le = ls + text.len();
            let map = highlight::utf16_columns(&text);
            let mut row = Vec::new();
            while k < marks.len() && marks[k].byte < le {
                if marks[k].byte >= ls {
                    row.push((map[marks[k].byte - ls] as usize, marks[k].depth));
                }
                k += 1;
            }
            out.push(row);
        }
        out
    }

    /// Highlight spans covering `byte_range`, sorted and non-overlapping.
    /// Unhighlighted gaps are omitted.
    pub fn highlight_bytes(&self, rope: &Rope, byte_range: std::ops::Range<usize>) -> Vec<HighlightSpan> {
        match &self.tree {
            Some(tree) => highlight::highlight(self.language.config(), tree, rope, byte_range),
            None => Vec::new(),
        }
    }

    /// Per-line spans for `lines` (clamped to the buffer), with columns in
    /// UTF-16 code units relative to each line start — the unit Android's
    /// text APIs use. Spans never include line terminators.
    pub fn line_spans(&self, buffer: &Buffer, lines: std::ops::Range<usize>) -> Vec<Vec<LineSpan>> {
        let end_line = lines.end.min(buffer.len_lines());
        let start_line = lines.start.min(end_line);
        if start_line == end_line {
            return Vec::new();
        }
        let start_byte = buffer.line_to_byte(start_line);
        let end_byte = buffer.char_to_byte(buffer.line_end_char(end_line - 1));
        let spans = self.highlight_bytes(buffer.rope(), start_byte..end_byte);
        highlight::split_into_lines(buffer, start_line..end_line, &spans)
    }
}

fn to_input_edit(e: &ByteEdit) -> InputEdit {
    let p = |p: ferride_buffer::Point| Point::new(p.row, p.column);
    InputEdit {
        start_byte: e.start_byte,
        old_end_byte: e.old_end_byte,
        new_end_byte: e.new_end_byte,
        start_position: p(e.start_position),
        old_end_position: p(e.old_end_position),
        new_end_position: p(e.new_end_position),
    }
}

#[cfg(test)]
mod tests;
