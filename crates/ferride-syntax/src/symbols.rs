//! Document symbols (Outline, breadcrumbs, Go to Symbol, sticky scroll)
//! from per-language tree-sitter queries in `queries/<lang>/symbols.scm`.
//!
//! Convention: `@symbol.<kind>` captures the whole definition (its range is
//! what folds, scrolls sticky and contains children) and `@name` its name.
//! `<kind>` is an LSP `SymbolKind` name in lower case. When `@name` is the
//! definition itself (an `impl` block, `@media`), the name is the
//! definition's header: its text up to `{` or the end of the first line.
//! Symbols nest by containment; when two patterns capture the same node,
//! the later one wins (`function` refined to `method` inside a class).

use std::ops::Range;
use std::sync::OnceLock;

use ropey::Rope;
use tree_sitter::{Node, Query, QueryCursor, StreamingIterator, Tree};

use crate::LanguageId;

/// LSP's `SymbolKind` (same order and values).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum SymbolKind {
    File = 1,
    Module = 2,
    Namespace = 3,
    Package = 4,
    Class = 5,
    Method = 6,
    Property = 7,
    Field = 8,
    Constructor = 9,
    Enum = 10,
    Interface = 11,
    Function = 12,
    Variable = 13,
    Constant = 14,
    String = 15,
    Number = 16,
    Boolean = 17,
    Array = 18,
    Object = 19,
    Key = 20,
    Null = 21,
    EnumMember = 22,
    Struct = 23,
    Event = 24,
    Operator = 25,
    TypeParameter = 26,
}

impl SymbolKind {
    fn from_capture(name: &str) -> Option<Self> {
        use SymbolKind::*;
        Some(match name.strip_prefix("symbol.")? {
            "file" => File,
            "module" => Module,
            "namespace" => Namespace,
            "package" => Package,
            "class" => Class,
            "method" => Method,
            "property" => Property,
            "field" => Field,
            "constructor" => Constructor,
            "enum" => Enum,
            "interface" => Interface,
            "function" => Function,
            "variable" => Variable,
            "constant" => Constant,
            "string" => String,
            "number" => Number,
            "boolean" => Boolean,
            "array" => Array,
            "object" => Object,
            "key" => Key,
            "null" => Null,
            "enummember" => EnumMember,
            "struct" => Struct,
            "event" => Event,
            "operator" => Operator,
            "typeparameter" => TypeParameter,
            _ => return None,
        })
    }
}

/// A symbol with byte ranges: `range` is the whole definition, `selection`
/// its name.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Symbol {
    pub name: String,
    pub kind: SymbolKind,
    pub range: Range<usize>,
    pub selection: Range<usize>,
    pub children: Vec<Symbol>,
}

impl LanguageId {
    /// The compiled symbols query, built on first use.
    pub(crate) fn symbols_query(self) -> Option<&'static Query> {
        static QUERIES: [OnceLock<Option<Query>>; LanguageId::ALL.len() + 1] =
            [const { OnceLock::new() }; LanguageId::ALL.len() + 1];
        QUERIES[self as usize]
            .get_or_init(|| {
                let source = self.symbols_source()?;
                let query = Query::new(&self.config().language, &source)
                    .unwrap_or_else(|e| panic!("bundled symbols query for {self:?} is invalid: {e}"));
                Some(query)
            })
            .as_ref()
    }

    fn symbols_source(self) -> Option<String> {
        let js = include_str!("../queries/javascript/symbols.scm");
        let ts = include_str!("../queries/typescript/symbols.scm");
        Some(match self {
            Self::Rust => include_str!("../queries/rust/symbols.scm").into(),
            Self::Python => include_str!("../queries/python/symbols.scm").into(),
            // TypeScript's grammar calls class fields `public_field_definition`.
            Self::JavaScript => format!("{js}\n(field_definition property: (_) @name) @symbol.property"),
            Self::TypeScript | Self::Tsx => format!("{js}\n{ts}"),
            Self::Json => include_str!("../queries/json/symbols.scm").into(),
            Self::Markdown => include_str!("../queries/markdown/symbols.scm").into(),
            Self::C => include_str!("../queries/c/symbols.scm").into(),
            // C++ adds classes, namespaces and methods to C's definitions.
            Self::Cpp => format!(
                "{}\n{}",
                include_str!("../queries/c/symbols.scm"),
                include_str!("../queries/cpp/symbols.scm")
            ),
            Self::Go => include_str!("../queries/go/symbols.scm").into(),
            Self::Java => include_str!("../queries/java/symbols.scm").into(),
            Self::Kotlin => include_str!("../queries/kotlin/symbols.scm").into(),
            Self::Html => include_str!("../queries/html/symbols.scm").into(),
            Self::Css => include_str!("../queries/css/symbols.scm").into(),
            Self::Yaml => include_str!("../queries/yaml/symbols.scm").into(),
            Self::Toml => include_str!("../queries/toml/symbols.scm").into(),
            Self::Bash => include_str!("../queries/bash/symbols.scm").into(),
            Self::Sql => include_str!("../queries/sql/symbols.scm").into(),
            Self::MarkdownInline => return None,
        })
    }
}

fn text(rope: &Rope, r: Range<usize>) -> String {
    let len = rope.len_bytes();
    rope.byte_slice(r.start.min(len)..r.end.min(len)).to_string()
}

/// Whitespace collapsed, at most 80 characters.
fn tidy(s: &str) -> String {
    let mut out: String = s.split_whitespace().collect::<Vec<_>>().join(" ");
    if out.chars().count() > 80 {
        out = out.chars().take(79).collect::<String>() + "…";
    }
    out
}

/// A definition's header: its text up to `{` or the end of its first line.
fn header(rope: &Rope, node: Node) -> String {
    let t = text(rope, node.byte_range());
    let end = t.find(['{', '\n']).unwrap_or(t.len());
    tidy(t[..end].trim_end_matches([':', ' ']))
}

/// VS Code's HTML outline names: `div#app.main.wide`.
fn html_name(rope: &Rope, tag_name: Node) -> String {
    let mut name = text(rope, tag_name.byte_range());
    let Some(start_tag) = tag_name.parent() else {
        return name;
    };
    let mut cursor = start_tag.walk();
    for attr in start_tag
        .named_children(&mut cursor)
        .filter(|n| n.kind() == "attribute")
    {
        let Some(key) = attr.named_child(0) else { continue };
        let value = attr
            .named_child(1)
            .map(|v| text(rope, v.byte_range()))
            .unwrap_or_default();
        let value = value.trim_matches(['"', '\'']);
        match text(rope, key.byte_range()).as_str() {
            "id" => name.push_str(&format!("#{value}")),
            "class" => value
                .split_whitespace()
                .for_each(|c| name.push_str(&format!(".{c}"))),
            _ => {}
        }
    }
    name
}

/// The document's symbols, nested.
pub(crate) fn symbols(language: LanguageId, tree: &Tree, rope: &Rope) -> Vec<Symbol> {
    let Some(query) = language.symbols_query() else {
        return Vec::new();
    };
    let names = query.capture_names();
    let name_ix = query.capture_index_for_name("name");
    let source = |node: Node| {
        let r = node.byte_range();
        let len = rope.len_bytes();
        rope.byte_slice(r.start.min(len)..r.end.min(len))
            .chunks()
            .map(str::as_bytes)
    };
    // (symbol, pattern)
    let mut flat: Vec<(Symbol, usize)> = Vec::new();
    let mut cursor = QueryCursor::new();
    let mut matches = cursor.matches(query, tree.root_node(), source);
    while let Some(m) = matches.next() {
        let mut def: Option<(Node, SymbolKind)> = None;
        let mut name_node = None;
        for cap in m.captures() {
            if Some(cap.index) == name_ix {
                name_node = Some(cap.node);
            }
            if let Some(kind) = SymbolKind::from_capture(names[cap.index as usize]) {
                def = Some((cap.node, kind));
            }
        }
        let (Some((node, kind)), Some(name_node)) = (def, name_node) else {
            continue;
        };
        let name = if name_node == node {
            header(rope, node)
        } else if language == LanguageId::Html {
            html_name(rope, name_node)
        } else {
            tidy(&text(rope, name_node.byte_range()))
        };
        if name.is_empty() {
            continue;
        }
        flat.push((
            Symbol {
                name,
                kind,
                range: node.byte_range(),
                selection: name_node.byte_range(),
                children: Vec::new(),
            },
            m.pattern_index,
        ));
    }
    // Same node captured by several patterns: the later pattern wins.
    flat.sort_by(|a, b| {
        a.0.range
            .start
            .cmp(&b.0.range.start)
            .then(b.0.range.end.cmp(&a.0.range.end))
            .then(b.1.cmp(&a.1))
    });
    flat.dedup_by(|later, earlier| later.0.range == earlier.0.range);
    nest(flat.into_iter().map(|(s, _)| s).collect())
}

/// Builds the tree from symbols sorted by start (outer first).
fn nest(sorted: Vec<Symbol>) -> Vec<Symbol> {
    let mut roots: Vec<Symbol> = Vec::new();
    let mut stack: Vec<Symbol> = Vec::new();
    let close = |stack: &mut Vec<Symbol>, roots: &mut Vec<Symbol>| {
        let done = stack.pop().expect("non-empty");
        match stack.last_mut() {
            Some(parent) => parent.children.push(done),
            None => roots.push(done),
        }
    };
    for sym in sorted {
        while stack
            .last()
            .is_some_and(|top| !(top.range.start <= sym.range.start && sym.range.end <= top.range.end))
        {
            close(&mut stack, &mut roots);
        }
        stack.push(sym);
    }
    while !stack.is_empty() {
        close(&mut stack, &mut roots);
    }
    roots
}
