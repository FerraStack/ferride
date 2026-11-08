use std::collections::HashMap;
use std::ops::Range;

use ferride_buffer::Buffer;
use ropey::Rope;
use tree_sitter::{Node, QueryCursor, StreamingIterator, Tree};

use crate::LanguageId;
use crate::language::LanguageConfig;
use crate::scope::{self, LayerId, Parent, ScopeId};

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
            ("comment", _) | ("markup", "quote") => Comment,
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
    /// TextMate scope stack of the token (see [`crate::scope_stack`]).
    pub scope: ScopeId,
}

/// A highlighted range within one line, in UTF-16 columns.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LineSpan {
    pub start_col: usize,
    pub end_col: usize,
    pub kind: HighlightKind,
    pub scope: ScopeId,
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
    language: LanguageId,
    layer: LayerId,
    tree: &Tree,
    rope: &Rope,
    window: Range<usize>,
    paint: &mut [ScopeId],
) {
    let config: &LanguageConfig = language.config();
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

    let names = config.query.capture_names();
    struct Cap {
        start: usize,
        end: usize,
        node: Range<usize>,
        pattern: usize,
        kind: HighlightKind,
        token: &'static str,
        /// The token's first and last characters (for quote punctuation).
        ends: (char, char),
    }
    let mut caps: Vec<Cap> = Vec::new();
    let mut it = cursor.captures(&config.query, tree.root_node(), text);
    while let Some((m, idx)) = it.next() {
        let cap = m.captures()[*idx];
        let Some(kind) = config.kinds[cap.index as usize] else {
            continue;
        };
        let r = cap.node.byte_range();
        let (start, end) = (r.start.max(window.start), r.end.min(window.end));
        if start >= end {
            continue;
        }
        let prefix = token_prefix(rope, r.clone());
        let token = if kind == HighlightKind::Plain {
            ""
        } else {
            scope::token_scope(names[cap.index as usize], language, &prefix, cap.node)
        };
        let end_char = rope.byte_to_char(r.end.min(rope.len_bytes()));
        let last = if end_char > 0 {
            rope.char(end_char - 1)
        } else {
            ' '
        };
        caps.push(Cap {
            start,
            end,
            node: r,
            pattern: m.pattern_index,
            kind,
            token,
            ends: (prefix.chars().next().unwrap_or(' '), last),
        });
    }
    // Outer before inner; for equal ranges, the winning pattern last so it
    // paints over the others.
    caps.sort_by(|a, b| {
        let by_pattern = if config.first_wins {
            b.pattern.cmp(&a.pattern)
        } else {
            a.pattern.cmp(&b.pattern)
        };
        a.start.cmp(&b.start).then(b.end.cmp(&a.end)).then(by_pattern)
    });

    // A capture inside another capture's node nests its scope in the outer
    // one's stack (an escape inside a string); a capture of the same node
    // replaces it. Paint that predates this call (a Markdown heading under
    // the inline grammar's emphasis) is an enclosing scope too.
    let mut owner: Vec<usize> = vec![usize::MAX; paint.len()];
    let mut memo: HashMap<(Parent, &'static str, HighlightKind), ScopeId> = HashMap::new();
    let mut id_for = |parent: Parent, token: &'static str, kind: HighlightKind| -> ScopeId {
        *memo
            .entry((parent, token, kind))
            .or_insert_with(|| scope::intern(parent, token, language, kind))
    };
    for (ci, cap) in caps.iter().enumerate() {
        for p in cap.start..cap.end {
            let i = p - window.start;
            if cap.kind == HighlightKind::Plain {
                paint[i] = 0;
                owner[i] = ci;
                continue;
            }
            let current = paint[i];
            let parent = match owner.get(i).copied() {
                _ if current == 0 => Parent::Layer(layer),
                Some(o) if o != usize::MAX && caps[o].node == cap.node => {
                    scope::parent_of(current).unwrap_or(Parent::Layer(layer))
                }
                _ => Parent::Style(current),
            };
            paint[i] = id_for(parent, cap.token, cap.kind);
            owner[i] = ci;
        }
        // printf-style placeholders inside C, C++ and Go strings.
        if cap.token.starts_with("string.quoted")
            && matches!(language, LanguageId::C | LanguageId::Cpp | LanguageId::Go)
        {
            let text = rope.byte_slice(cap.node.clone()).to_string();
            for (a, b) in placeholders(&text) {
                for at in cap.node.start + a..cap.node.start + b {
                    if at >= window.start && at < window.end {
                        let i = at - window.start;
                        paint[i] = id_for(Parent::Style(paint[i]), "constant.other.placeholder", cap.kind);
                    }
                }
            }
        }
        // Quotes are punctuation inside the string, as in TextMate grammars.
        let quote_scopes = match cap.token {
            t if t.starts_with("string.quoted") => Some((
                "punctuation.definition.string.begin",
                "punctuation.definition.string.end",
            )),
            "support.type.property-name" => Some((
                "punctuation.support.type.property-name.begin",
                "punctuation.support.type.property-name.end",
            )),
            _ => None,
        };
        let (open, close) = cap.ends;
        if let Some((begin, end)) = quote_scopes.filter(|_| matches!(open, '"' | '\'' | '`') && close == open)
        {
            let close_at = cap.node.end - close.len_utf8();
            for (at, scope) in [(cap.node.start, begin), (close_at, end)] {
                if at >= window.start && at < window.end && cap.node.end - cap.node.start >= 2 {
                    let i = at - window.start;
                    paint[i] = id_for(Parent::Style(paint[i]), scope, cap.kind);
                }
            }
        }
    }
}

/// Byte ranges of printf/Go format verbs (`%d`, `%-8.2f`, `%zu`, `%v`) in a string literal.
fn placeholders(text: &str) -> Vec<(usize, usize)> {
    let b = text.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < b.len() {
        if b[i] != b'%' {
            i += 1;
            continue;
        }
        let start = i;
        i += 1;
        while i < b.len() && matches!(b[i], b'-' | b'+' | b' ' | b'#' | b'0'..=b'9' | b'.' | b'*') {
            i += 1;
        }
        while i < b.len() && matches!(b[i], b'h' | b'l' | b'L' | b'q' | b'j' | b'z' | b't') {
            i += 1;
        }
        if i < b.len() && b"diouxXeEfFgGaAcspnvTqbtwU%".contains(&b[i]) {
            out.push((start, i + 1));
            i += 1;
        }
    }
    out
}

/// The first few characters of a token: enough to tell `if` from `fn` or
/// `///` from `//`, without copying whole comments or strings.
fn token_prefix(rope: &Rope, bytes: Range<usize>) -> String {
    let len = rope.len_bytes();
    let start = rope.byte_to_char(bytes.start.min(len));
    let end = rope.byte_to_char(bytes.end.min(len));
    rope.slice(start..end.min(start + 16)).to_string()
}

/// Merges a painted window into spans (unhighlighted gaps omitted).
pub(crate) fn spans(paint: &[ScopeId], window_start: usize) -> Vec<HighlightSpan> {
    let mut spans: Vec<HighlightSpan> = Vec::new();
    let mut i = 0;
    while i < paint.len() {
        let id = paint[i];
        let mut j = i + 1;
        while j < paint.len() && paint[j] == id {
            j += 1;
        }
        if id != 0 {
            spans.push(HighlightSpan {
                start_byte: window_start + i,
                end_byte: window_start + j,
                kind: scope::kind_of(id),
                scope: id,
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
                    scope: s.scope,
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
