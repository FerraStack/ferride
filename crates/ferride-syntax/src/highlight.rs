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
            ("type", "qualifier") => Keyword,
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
            ("punctuation" | "delimiter", _) => Punctuation,
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

/// Runs the highlight query over `window` and paints the captures into
/// `paint` (`paint[0]` is the byte at `window.start`), flattening nested
/// captures.
///
/// Precedence: an inner (narrower) node wins over the node containing it,
/// and for the same node the pattern that appears *later* in the query wins
/// (so a generic `(identifier) @variable` at the top of a query is refined
/// by more specific patterns below it), unless the language's query is
/// written the other way round ([`LanguageConfig::first_wins`]). A `@none`
/// capture paints plain text over its node; other captures without a
/// highlight class are ignored.
pub(crate) fn paint(
    config: &LanguageConfig,
    tree: &Tree,
    rope: &Rope,
    window: Range<usize>,
    paint: &mut [HighlightKind],
) {
    if window.is_empty() {
        return;
    }
    let mut cursor = QueryCursor::new();
    cursor.set_byte_range(window.clone());
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
        let Some(kind) = config.kinds[cap.index as usize] else {
            continue;
        };
        let r = cap.node.byte_range();
        let (start, end) = (r.start.max(window.start), r.end.min(window.end));
        if start < end {
            caps.push((start, end, m.pattern_index, kind));
        }
    }
    // Outer before inner; for equal ranges, the winning pattern last so it
    // paints over the others.
    caps.sort_by(|a, b| {
        let by_pattern = if config.first_wins {
            b.2.cmp(&a.2)
        } else {
            a.2.cmp(&b.2)
        };
        a.0.cmp(&b.0).then(b.1.cmp(&a.1)).then(by_pattern)
    });
    for (start, end, _, kind) in caps {
        paint[start - window.start..end - window.start].fill(kind);
    }
}

/// Merges a painted window into spans (unhighlighted gaps omitted).
pub(crate) fn spans(paint: &[HighlightKind], window_start: usize) -> Vec<HighlightSpan> {
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
                start_byte: window_start + i,
                end_byte: window_start + j,
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
        let map = utf16_columns(&text);
        let col = |byte_in_line: usize| -> usize { map[byte_in_line.min(text.len())] as usize };
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

/// `map[byte]` = UTF-16 column of that byte offset within `text` (bytes inside a
/// multi-byte char map to the char's column). Built once per line so span
/// conversion is O(1) per span instead of O(line length).
pub(crate) fn utf16_columns(text: &str) -> Vec<u32> {
    let mut map = vec![0u32; text.len() + 1];
    let mut col = 0u32;
    for (i, c) in text.char_indices() {
        for b in 0..c.len_utf8() {
            map[i + b] = col;
        }
        col += c.len_utf16() as u32;
    }
    map[text.len()] = col;
    map
}

/// A syntactic bracket (an anonymous `(`/`[`/`{`/`${` or closing token) and
/// its nesting depth: for an opener, the depth before it; for a closer, the
/// depth after it, so a matched pair shares a depth.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BracketMark {
    pub byte: usize,
    pub depth: u32,
}

/// Walks the tree in document order up to `end_byte`, collecting bracket
/// tokens. Brackets inside strings and comments aren't tokens, so they're
/// skipped naturally.
pub(crate) fn collect_brackets(tree: &Tree, end_byte: usize) -> Vec<BracketMark> {
    let mut out = Vec::new();
    let mut depth: u32 = 0;
    let mut cursor = tree.walk();
    'walk: loop {
        let node = cursor.node();
        if node.start_byte() >= end_byte {
            break;
        }
        if node.child_count() == 0 {
            if !node.is_named() {
                match node.kind() {
                    "(" | "[" | "{" | "${" => {
                        out.push(BracketMark {
                            byte: node.start_byte(),
                            depth,
                        });
                        depth += 1;
                    }
                    ")" | "]" | "}" => {
                        depth = depth.saturating_sub(1);
                        out.push(BracketMark {
                            byte: node.start_byte(),
                            depth,
                        });
                    }
                    _ => {}
                }
            }
        } else if cursor.goto_first_child() {
            continue;
        }
        while !cursor.goto_next_sibling() {
            if !cursor.goto_parent() {
                break 'walk;
            }
        }
    }
    out
}
