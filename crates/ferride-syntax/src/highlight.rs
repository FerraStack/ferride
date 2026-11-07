use std::ops::Range;

use ferride_buffer::Buffer;
use ropey::Rope;
use tree_sitter::{Node, QueryCursor, StreamingIterator, Tree};

use crate::language::LanguageConfig;

/// Theme-independent highlight classes. The UI maps each to a color/style.
/// The discriminant values are stable (they cross the FFI as integers).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum HighlightKind {
    Plain = 0,
    Keyword = 1,
    Function = 2,
    Type = 3,
    String = 4,
    Number = 5,
    Comment = 6,
    Constant = 7,
    Variable = 8,
    Property = 9,
    Operator = 10,
    Punctuation = 11,
    Tag = 12,
    Attribute = 13,
    Heading = 14,
    Link = 15,
    Escape = 16,
    Emphasis = 17,
    Label = 18,
    Builtin = 19,
}

impl HighlightKind {
    /// Maps a tree-sitter capture name (`function.method`, `string.special`,
    /// `text.title`, `markup.heading.1`, ...) to a highlight kind.
    pub fn from_capture_name(name: &str) -> Self {
        use HighlightKind::*;
        let mut parts = name.split('.');
        let head = parts.next().unwrap_or("");
        let sub = parts.next().unwrap_or("");
        match (head, sub) {
            ("keyword" | "conditional" | "repeat" | "include" | "exception" | "storageclass", _) => Keyword,
            ("function" | "method", "builtin") => Builtin,
            ("function" | "method", _) => Function,
            ("constructor" | "type" | "module" | "namespace", "builtin") => Builtin,
            ("constructor" | "type" | "module" | "namespace", _) => Type,
            ("string", "escape") | ("escape", _) => Escape,
            ("string", "special") if parts.next() == Some("key") => Property,
            ("string" | "character", _) => String,
            ("number" | "float", _) => Number,
            ("boolean", _) => Constant,
            ("comment", _) => Comment,
            ("constant", _) => Constant,
            ("variable", "builtin") => Builtin,
            ("variable" | "parameter", _) => Variable,
            ("property" | "field", _) => Property,
            ("operator", _) => Operator,
            ("punctuation", _) => Punctuation,
            ("tag", _) => Tag,
            ("attribute", _) => Attribute,
            ("label", _) => Label,
            ("text", "title") | ("markup", "heading") => Heading,
            ("text", "uri" | "reference") | ("markup", "link") => Link,
            ("text", "literal") | ("markup", "raw") => String,
            ("text", "emphasis" | "strong") | ("markup", "italic" | "strong" | "bold") => Emphasis,
            _ => Plain,
        }
    }
}

/// A highlighted byte range of the document.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct HighlightSpan {
    pub start_byte: usize,
    pub end_byte: usize,
    pub kind: HighlightKind,
}

/// A highlighted range within one line, in UTF-16 columns.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LineSpan {
    pub start_col: usize,
    pub end_col: usize,
    pub kind: HighlightKind,
}

/// Runs the highlight query over `range` and flattens nested captures.
///
/// Precedence: an inner (narrower) node wins over the node containing it,
/// and for the same node the pattern that appears *later* in the query wins
/// (so a generic `(identifier) @variable` at the top of a query is refined
/// by more specific patterns below it).
pub(crate) fn highlight(
    config: &LanguageConfig,
    tree: &Tree,
    rope: &Rope,
    range: Range<usize>,
) -> Vec<HighlightSpan> {
    let range = range.start.min(rope.len_bytes())..range.end.min(rope.len_bytes());
    if range.is_empty() {
        return Vec::new();
    }
    let mut cursor = QueryCursor::new();
    cursor.set_byte_range(range.clone());
    let text = |node: Node| {
        let r = node.byte_range();
        rope.byte_slice(r.start.min(rope.len_bytes())..r.end.min(rope.len_bytes()))
            .chunks()
            .map(str::as_bytes)
    };

    // (start, end, pattern, kind)
    let mut caps: Vec<(usize, usize, usize, HighlightKind)> = Vec::new();
    let mut it = cursor.captures(&config.query, tree.root_node(), text);
    while let Some((m, idx)) = it.next() {
        let cap = m.captures()[*idx];
        let kind = config.kinds[cap.index as usize];
        if kind == HighlightKind::Plain {
            continue;
        }
        let r = cap.node.byte_range();
        let (start, end) = (r.start.max(range.start), r.end.min(range.end));
        if start < end {
            caps.push((start, end, m.pattern_index, kind));
        }
    }
    // Outer before inner; for equal ranges, lower pattern index first so the
    // later pattern paints over it.
    caps.sort_by(|a, b| a.0.cmp(&b.0).then(b.1.cmp(&a.1)).then(a.2.cmp(&b.2)));

    let mut paint: Vec<HighlightKind> = vec![HighlightKind::Plain; range.len()];
    for (start, end, _, kind) in caps {
        paint[start - range.start..end - range.start].fill(kind);
    }

    let mut spans: Vec<HighlightSpan> = Vec::new();
    let mut i = 0;
    while i < paint.len() {
        let kind = paint[i];
        let mut j = i + 1;
        while j < paint.len() && paint[j] == kind {
            j += 1;
        }
        if kind != HighlightKind::Plain {
            spans.push(HighlightSpan {
                start_byte: range.start + i,
                end_byte: range.start + j,
                kind,
            });
        }
        i = j;
    }
    spans
}

/// Splits document byte spans into per-line UTF-16 column spans.
pub(crate) fn split_into_lines(
    buffer: &Buffer,
    lines: Range<usize>,
    spans: &[HighlightSpan],
) -> Vec<Vec<LineSpan>> {
    let mut out = Vec::with_capacity(lines.len());
    let mut next = 0;
    for line in lines {
        let line_start = buffer.line_to_byte(line);
        let text = buffer.line_text(line);
        let line_end = line_start + text.len();
        // byte offset within the line -> utf16 column
        let col = |byte_in_line: usize| -> usize {
            text[..byte_in_line.min(text.len())]
                .chars()
                .map(char::len_utf16)
                .sum()
        };
        let mut line_spans = Vec::new();
        // Skip spans that ended before this line.
        while next < spans.len() && spans[next].end_byte <= line_start {
            next += 1;
        }
        let mut k = next;
        while k < spans.len() && spans[k].start_byte < line_end {
            let s = spans[k];
            let a = s.start_byte.max(line_start) - line_start;
            let b = s.end_byte.min(line_end) - line_start;
            if a < b {
                line_spans.push(LineSpan {
                    start_col: col(a),
                    end_col: col(b),
                    kind: s.kind,
                });
            }
            k += 1;
        }
        out.push(line_spans);
    }
    out
}
