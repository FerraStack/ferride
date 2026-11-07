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

use ferride_buffer::{Buffer, ByteEdit};
use ropey::Rope;
use tree_sitter::{InputEdit, Parser, Point, Tree};

pub use highlight::{HighlightKind, HighlightSpan, LineSpan};
pub use language::LanguageId;

/// Incremental parser + tree for one document.
pub struct Syntax {
    language: LanguageId,
    parser: Parser,
    tree: Option<Tree>,
}

impl std::fmt::Debug for Syntax {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Syntax")
            .field("language", &self.language)
            .field("has_tree", &self.tree.is_some())
            .finish()
    }
}

impl Syntax {
    /// Creates a parser for `language` and parses `rope` fully.
    pub fn new(language: LanguageId, rope: &Rope) -> Self {
        let mut parser = Parser::new();
        parser
            .set_language(&language.config().language)
            .expect("bundled grammars match the tree-sitter ABI");
        let mut syntax = Self {
            language,
            parser,
            tree: None,
        };
        syntax.reparse(rope);
        syntax
    }

    pub fn language(&self) -> LanguageId {
        self.language
    }

    pub fn tree(&self) -> Option<&Tree> {
        self.tree.as_ref()
    }

    /// Applies buffer edits to the old tree and reparses incrementally.
    pub fn update(&mut self, rope: &Rope, edits: &[ByteEdit]) {
        if let Some(tree) = &mut self.tree {
            for e in edits {
                tree.edit(&to_input_edit(e));
            }
        }
        self.reparse(rope);
    }

    fn reparse(&mut self, rope: &Rope) {
        let len = rope.len_bytes();
        let mut read = |byte: usize, _: Point| -> &[u8] {
            if byte >= len {
                return &[];
            }
            let (chunk, chunk_start, _, _) = rope.chunk_at_byte(byte);
            &chunk.as_bytes()[byte - chunk_start..]
        };
        let new_tree = self
            .parser
            .parse_with_options(&mut read, self.tree.as_ref(), None);
        self.tree = new_tree;
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
