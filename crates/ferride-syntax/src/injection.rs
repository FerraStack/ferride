//! Language injections: regions of a document parsed with another grammar
//! (Markdown inline syntax inside paragraphs, fenced code blocks, `<script>`
//! and `<style>` in HTML, `sql`/`css`/`html` tagged templates in JS, Rust
//! macro arguments).
//!
//! Injections follow tree-sitter's `injections.scm` conventions:
//! `@injection.content` marks the region, `@injection.language` (a node whose
//! text names the language) or `#set! injection.language "x"` picks the
//! grammar, `#set! injection.combined` parses all matches of a pattern as one
//! document, and `#set! injection.include-children` keeps the content node's
//! children (by default they are cut out, e.g. Markdown's `> ` markers).
//!
//! Injected trees are parsed lazily for the highlighted range only and cached
//! until the host tree changes, so cost scales with what is on screen.

use std::collections::HashMap;
use std::ops::{ControlFlow, Range};
use std::time::{Duration, Instant};

use ropey::Rope;
use tree_sitter::{
    Language, Node, ParseOptions, ParseState, Parser, Query, QueryCursor, QueryError, Range as TsRange,
    StreamingIterator, Tree,
};

use crate::LanguageId;

/// Injections inside injections (HTML → `<script>` → `html` template) stop here.
pub(crate) const MAX_DEPTH: usize = 3;

/// Time budget for parsing one injected region (on the UI thread).
const PARSE_BUDGET: Duration = Duration::from_millis(20);

/// Keeps the cache from growing without bound in a long session on one tree.
const MAX_CACHED: usize = 512;

/// A compiled `injections.scm`.
pub(crate) struct InjectionQuery {
    query: Query,
    content: Option<u32>,
    language: Option<u32>,
    patterns: Vec<PatternSettings>,
}

#[derive(Default)]
struct PatternSettings {
    language: Option<String>,
    combined: bool,
    include_children: bool,
}

impl InjectionQuery {
    pub fn new(language: &Language, source: &str) -> Result<Self, QueryError> {
        let query = Query::new(language, source)?;
        let patterns = (0..query.pattern_count())
            .map(|i| {
                let mut s = PatternSettings::default();
                for prop in query.property_settings(i) {
                    match &*prop.key {
                        "injection.language" => s.language = prop.value.as_deref().map(str::to_owned),
                        "injection.combined" => s.combined = true,
                        "injection.include-children" => s.include_children = true,
                        _ => {}
                    }
                }
                s
            })
            .collect();
        Ok(Self {
            content: query.capture_index_for_name("injection.content"),
            language: query.capture_index_for_name("injection.language"),
            query,
            patterns,
        })
    }
}

/// One region to parse with `language`: sorted, non-overlapping ranges.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Injection {
    pub language: LanguageId,
    pub ranges: Vec<TsRange>,
}

fn node_text(rope: &Rope, node: Node) -> String {
    let r = node.byte_range();
    let len = rope.len_bytes();
    rope.byte_slice(r.start.min(len)..r.end.min(len)).to_string()
}

/// Content ranges of `node`: the whole node, or the node minus its named
/// children (Markdown's `> ` block continuations inside a quoted code block).
/// Anonymous children stay: the Markdown block grammar has punctuation tokens
/// inside `inline` and code content that belong to the injected text.
fn content_ranges(node: Node, include_children: bool, out: &mut Vec<TsRange>) {
    if include_children || node.named_child_count() == 0 {
        out.push(node.range());
        return;
    }
    let mut start_byte = node.start_byte();
    let mut start_point = node.start_position();
    let mut cursor = node.walk();
    for child in node.named_children(&mut cursor) {
        if child.start_byte() > start_byte {
            out.push(TsRange {
                start_byte,
                end_byte: child.start_byte(),
                start_point,
                end_point: child.start_position(),
            });
        }
        start_byte = child.end_byte();
        start_point = child.end_position();
    }
    if node.end_byte() > start_byte {
        out.push(TsRange {
            start_byte,
            end_byte: node.end_byte(),
            start_point,
            end_point: node.end_position(),
        });
    }
}

/// Injections of `tree` that intersect `range`.
pub(crate) fn find(config: &InjectionQuery, tree: &Tree, rope: &Rope, range: Range<usize>) -> Vec<Injection> {
    let Some(content_ix) = config.content else {
        return Vec::new();
    };
    let mut cursor = QueryCursor::new();
    cursor.set_byte_range(range);
    let text = |node: Node| {
        let r = node.byte_range();
        let len = rope.len_bytes();
        rope.byte_slice(r.start.min(len)..r.end.min(len))
            .chunks()
            .map(str::as_bytes)
    };
    let mut single: Vec<Injection> = Vec::new();
    // (pattern, language) → combined ranges.
    let mut combined: Vec<((usize, LanguageId), Vec<TsRange>)> = Vec::new();
    let mut matches = cursor.matches(&config.query, tree.root_node(), text);
    while let Some(m) = matches.next() {
        let settings = &config.patterns[m.pattern_index];
        let mut name = settings.language.clone();
        let mut nodes = Vec::new();
        for cap in m.captures() {
            if Some(cap.index) == config.language {
                name = Some(node_text(rope, cap.node));
            } else if cap.index == content_ix {
                nodes.push(cap.node);
            }
        }
        let Some(language) = name.as_deref().and_then(LanguageId::from_injection_name) else {
            continue;
        };
        let mut ranges = Vec::new();
        for node in nodes {
            content_ranges(node, settings.include_children, &mut ranges);
        }
        if settings.combined {
            match combined
                .iter_mut()
                .find(|(k, _)| *k == (m.pattern_index, language))
            {
                Some((_, r)) => r.extend(ranges),
                None => combined.push(((m.pattern_index, language), ranges)),
            }
        } else {
            single.push(Injection { language, ranges });
        }
    }
    single.extend(
        combined
            .into_iter()
            .map(|((_, language), ranges)| Injection { language, ranges }),
    );
    for inj in &mut single {
        normalize(&mut inj.ranges);
    }
    single.retain(|i| !i.ranges.is_empty());
    single
}

/// Sorts ranges and drops empty or overlapping ones (tree-sitter requires
/// included ranges in order and disjoint).
fn normalize(ranges: &mut Vec<TsRange>) {
    ranges.sort_by_key(|r| (r.start_byte, r.end_byte));
    let mut end = 0;
    ranges.retain(|r| {
        let keep = r.end_byte > r.start_byte && r.start_byte >= end;
        if keep {
            end = r.end_byte;
        }
        keep
    });
}

/// An injected language and the byte ranges it was parsed from.
type LayerKey = (LanguageId, Vec<(usize, usize)>);

/// Parsed injection trees for one generation of the host tree.
pub(crate) struct InjectionCache {
    generation: u64,
    parser: Parser,
    trees: HashMap<LayerKey, Option<Tree>>,
}

impl std::fmt::Debug for InjectionCache {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("InjectionCache")
            .field("generation", &self.generation)
            .field("trees", &self.trees.len())
            .finish()
    }
}

impl InjectionCache {
    pub fn new() -> Self {
        Self {
            generation: u64::MAX,
            parser: Parser::new(),
            trees: HashMap::new(),
        }
    }

    #[cfg(test)]
    pub fn len(&self) -> usize {
        self.trees.len()
    }

    /// Drops trees parsed for an older host tree.
    pub fn sync(&mut self, generation: u64) {
        if self.generation != generation || self.trees.len() > MAX_CACHED {
            self.trees.clear();
            self.generation = generation;
        }
    }

    /// The tree of `injection`, parsed on first use (`None` if it didn't parse
    /// within the budget).
    pub fn tree(&mut self, injection: &Injection, rope: &Rope) -> Option<Tree> {
        let key: LayerKey = (
            injection.language,
            injection
                .ranges
                .iter()
                .map(|r| (r.start_byte, r.end_byte))
                .collect::<Vec<_>>(),
        );
        if let Some(tree) = self.trees.get(&key) {
            return tree.clone();
        }
        let tree = self.parse(injection, rope);
        self.trees.insert(key, tree.clone());
        tree
    }

    fn parse(&mut self, injection: &Injection, rope: &Rope) -> Option<Tree> {
        self.parser
            .set_language(&injection.language.config().language)
            .ok()?;
        self.parser.set_included_ranges(&injection.ranges).ok()?;
        let len = rope.len_bytes();
        let mut read = |byte: usize, _: tree_sitter::Point| -> &[u8] {
            if byte >= len {
                return &[];
            }
            let (chunk, chunk_start, _, _) = rope.chunk_at_byte(byte);
            &chunk.as_bytes()[byte - chunk_start..]
        };
        let started = Instant::now();
        let mut progress = |_: &ParseState| {
            if started.elapsed() > PARSE_BUDGET {
                ControlFlow::Break(())
            } else {
                ControlFlow::Continue(())
            }
        };
        let options = ParseOptions::new().progress_callback(&mut progress);
        let tree = self.parser.parse_with_options(&mut read, None, Some(options));
        if tree.is_none() {
            self.parser.reset();
        }
        tree
    }
}
