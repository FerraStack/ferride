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
mod injection;
mod language;
mod scope;
mod symbols;

use std::cell::RefCell;
use std::ops::ControlFlow;
use std::sync::atomic::{AtomicBool, Ordering};
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
pub use scope::{ScopeId, root_scope, scope_stack};
pub use symbols::{Symbol, SymbolKind};

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
    /// Injected trees (Markdown inline, fenced code, `<script>`, ...) parsed
    /// for highlighting, valid for `generation`.
    injections: RefCell<injection::InjectionCache>,
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


/// An O(1) snapshot of a document's rope and syntax tree for off-thread parsing.
pub struct ParseSnapshot {
    language: LanguageId,
    rope: Rope,
    old_tree: Option<Tree>,
}

impl ParseSnapshot {
    /// Parses the snapshotted rope incrementally against the old tree.
    ///
    /// Stops early and returns `None` if `cancel` becomes `true`.
    pub fn parse(self, cancel: &AtomicBool) -> Option<Tree> {
        let mut parser = Parser::new();
        parser
            .set_language(&self.language.config().language)
            .expect("bundled grammars match the tree-sitter ABI");
        parse_rope(&mut parser, &self.rope, self.old_tree.as_ref(), || {
            cancel.load(Ordering::Relaxed)
        })
    }
}

fn parse_rope(
    parser: &mut Parser,
    rope: &Rope,
    old_tree: Option<&Tree>,
    mut should_cancel: impl FnMut() -> bool,
) -> Option<Tree> {
    let len = rope.len_bytes();
    let mut read = |byte: usize, _: Point| -> &[u8] {
        if byte >= len {
            return &[];
        }
        let (chunk, chunk_start, _, _) = rope.chunk_at_byte(byte);
        &chunk.as_bytes()[byte - chunk_start..]
    };
    let mut progress = |_: &ParseState| {
        if should_cancel() {
            ControlFlow::Break(())
        } else {
            ControlFlow::Continue(())
        }
    };
    let options = ParseOptions::new().progress_callback(&mut progress);
    parser.parse_with_options(&mut read, old_tree, Some(options))
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
            injections: RefCell::new(injection::InjectionCache::new()),
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

    /// Monotonic counter bumped whenever the tree changes (edit, reparse, or
    /// background tree publication).
    pub fn generation(&self) -> u64 {
        self.generation
    }

    /// Captures an O(1) snapshot (`Rope::clone` + `Tree::clone`) for parsing on
    /// a background thread after a synchronous parse exceeds its budget.
    pub fn snapshot(&self, rope: &Rope) -> ParseSnapshot {
        ParseSnapshot {
            language: self.language,
            rope: rope.clone(),
            old_tree: self.tree.clone(),
        }
    }

    /// Publishes a tree produced by [`ParseSnapshot::parse`], applying any
    /// `pending_edits` recorded while the background parse was running.
    pub fn apply_background_tree(&mut self, mut tree: Tree, pending_edits: &[ByteEdit]) {
        for e in pending_edits {
            tree.edit(&to_input_edit(e));
        }
        self.tree = Some(tree);
        self.stale = !pending_edits.is_empty();
        self.generation += 1;
    }

    /// Applies buffer edits to the old tree and reparses incrementally within
    /// [`EDIT_PARSE_BUDGET`]. If the budget runs out, the edited old tree is
    /// kept (highlights shift with the text but may be stale) and the caller
    /// can finish parsing off-thread via [`Syntax::snapshot`].
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
        let started = Instant::now();
        match parse_rope(&mut self.parser, rope, self.tree.as_ref(), || {
            started.elapsed() > budget
        }) {
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

    /// Highlight spans covering `byte_range`, sorted and non-overlapping,
    /// including injected languages (their tokens replace the host's inside
    /// the injected region). Unhighlighted gaps are omitted.
    pub fn highlight_bytes(&self, rope: &Rope, byte_range: std::ops::Range<usize>) -> Vec<HighlightSpan> {
        let Some(tree) = &self.tree else {
            return Vec::new();
        };
        let len = rope.len_bytes();
        let window = byte_range.start.min(len)..byte_range.end.min(len);
        if window.is_empty() {
            return Vec::new();
        }
        let mut paint: Vec<ScopeId> = vec![0; window.len()];
        let mut cache = self.injections.borrow_mut();
        cache.sync(self.generation);
        let layer = scope::root_layer(self.language);
        paint_layer(
            self.language,
            layer,
            tree,
            rope,
            &window,
            &mut paint,
            &mut cache,
            0,
        );
        highlight::spans(&paint, window.start)
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

/// Paints `tree`'s highlights over `window`, then each injection on top.
#[allow(clippy::too_many_arguments)]
fn paint_layer(
    language: LanguageId,
    layer: scope::LayerId,
    tree: &Tree,
    rope: &Rope,
    window: &std::ops::Range<usize>,
    paint: &mut [ScopeId],
    cache: &mut injection::InjectionCache,
    depth: usize,
) {
    let config = language.config();
    highlight::paint(language, layer, tree, rope, window.clone(), paint);
    let Some(query) = &config.injections else {
        return;
    };
    if depth >= injection::MAX_DEPTH {
        return;
    }
    for inj in injection::find(query, tree, rope, window.clone()) {
        let Some(child) = cache.tree(&inj, rope) else {
            continue;
        };
        // An embedded language owns its region: host styling (e.g. a fenced
        // block's literal color) doesn't leak into plain embedded tokens.
        // Markdown's inline grammar only refines its host (a heading's text
        // stays a heading, with emphasis on top), so it paints over it.
        let refines_host = inj.language == LanguageId::MarkdownInline;
        for r in inj.ranges.iter().filter(|_| !refines_host) {
            let (a, b) = (r.start_byte.max(window.start), r.end_byte.min(window.end));
            if a < b {
                paint[a - window.start..b - window.start].fill(0);
            }
        }
        let child_layer = scope::embedded_layer(layer, inj.language);
        paint_layer(
            inj.language,
            child_layer,
            &child,
            rope,
            window,
            paint,
            cache,
            depth + 1,
        );
    }
}

impl Syntax {
    /// Outline symbols (byte ranges), nested by containment.
    pub fn symbols(&self, rope: &Rope) -> Vec<Symbol> {
        match &self.tree {
            Some(tree) => symbols::symbols(self.language, tree, rope),
            None => Vec::new(),
        }
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
