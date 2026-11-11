//! Workspace symbols (`#` in Quick Open, VS Code's "Go to Symbol in
//! Workspace"): every file with a bundled grammar is parsed with tree-sitter
//! and its outline symbols are indexed. Re-indexing only touches files whose
//! size or modification time changed.

use std::collections::HashMap;
use std::path::PathBuf;
use std::time::{Duration, Instant, SystemTime};

use ferride_buffer::Buffer;
use ferride_syntax::{LanguageId, Symbol, SymbolKind, Syntax};

use crate::Workspace;
use crate::fuzzy::fuzzy_score;

/// Files above this size aren't indexed (generated code, bundles).
const MAX_FILE_BYTES: u64 = 512 * 1024;
/// At most this many files are indexed.
const MAX_FILES: usize = 5000;
/// Parse budget per file.
const PARSE_BUDGET: Duration = Duration::from_millis(250);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspaceSymbol {
    pub name: String,
    pub kind: SymbolKind,
    /// Name of the enclosing symbol, if any.
    pub container: Option<String>,
    pub path: PathBuf,
    pub rel_path: String,
    /// 0-based line and UTF-16 column of the symbol's name.
    pub line: usize,
    pub column: usize,
    pub score: i64,
    /// Char indices of `name` that matched the query.
    pub positions: Vec<usize>,
}

#[derive(Debug, Clone)]
struct Indexed {
    name: String,
    kind: SymbolKind,
    container: Option<String>,
    line: usize,
    column: usize,
}

#[derive(Debug)]
struct IndexedFile {
    modified: Option<SystemTime>,
    len: u64,
    rel_path: String,
    symbols: Vec<Indexed>,
}

/// The index; keep one per workspace and call [`SymbolIndex::query`].
#[derive(Debug, Default)]
pub struct SymbolIndex {
    files: HashMap<PathBuf, IndexedFile>,
}

impl SymbolIndex {
    pub fn new() -> Self {
        Self::default()
    }

    /// Number of indexed symbols.
    pub fn len(&self) -> usize {
        self.files.values().map(|f| f.symbols.len()).sum()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Brings the index up to date with the files on disk; returns how many
    /// files were (re)parsed.
    pub fn update(&mut self, ws: &Workspace) -> usize {
        let mut seen = std::collections::HashSet::new();
        let mut parsed = 0;
        for entry in ws.walker().filter_map(Result::ok) {
            if seen.len() >= MAX_FILES {
                break;
            }
            if !entry.file_type().is_some_and(|t| t.is_file()) {
                continue;
            }
            let path = entry.path();
            let Ok(meta) = entry.metadata() else { continue };
            if meta.len() > MAX_FILE_BYTES || LanguageId::from_path(path).is_none() {
                continue;
            }
            seen.insert(path.to_path_buf());
            let modified = meta.modified().ok();
            if self
                .files
                .get(path)
                .is_some_and(|f| f.len == meta.len() && f.modified == modified)
            {
                continue;
            }
            let Ok(text) = std::fs::read_to_string(path) else {
                continue;
            };
            let language = LanguageId::detect(path, text.lines().next());
            let symbols = language.map(|l| index_text(l, &text)).unwrap_or_default();
            parsed += 1;
            self.files.insert(
                path.to_path_buf(),
                IndexedFile {
                    modified,
                    len: meta.len(),
                    rel_path: ws.rel_path(path),
                    symbols,
                },
            );
        }
        self.files.retain(|p, _| seen.contains(p));
        parsed
    }

    /// Symbols whose name fuzzy-matches `query`, best first.
    pub fn query(&self, query: &str, limit: usize) -> Vec<WorkspaceSymbol> {
        let mut out: Vec<WorkspaceSymbol> = Vec::new();
        for (path, file) in &self.files {
            for s in &file.symbols {
                let Some((score, positions)) = fuzzy_score(query, &s.name) else {
                    continue;
                };
                out.push(WorkspaceSymbol {
                    name: s.name.clone(),
                    kind: s.kind,
                    container: s.container.clone(),
                    path: path.clone(),
                    rel_path: file.rel_path.clone(),
                    line: s.line,
                    column: s.column,
                    score,
                    positions,
                });
            }
        }
        out.sort_by(|a, b| {
            b.score
                .cmp(&a.score)
                .then_with(|| a.name.len().cmp(&b.name.len()))
                .then_with(|| a.rel_path.cmp(&b.rel_path))
                .then(a.line.cmp(&b.line))
        });
        out.truncate(limit);
        out
    }
}

fn index_text(language: LanguageId, text: &str) -> Vec<Indexed> {
    let buf = Buffer::new(text);
    let syntax = Syntax::with_budget(language, buf.rope(), PARSE_BUDGET);
    let mut out = Vec::new();
    fn walk(buf: &Buffer, symbols: &[Symbol], container: Option<&str>, out: &mut Vec<Indexed>) {
        for s in symbols {
            let ch = buf.byte_to_char(s.selection.start);
            let line = buf.char_to_line(ch);
            let column = buf.char_to_utf16(ch) - buf.line_to_utf16(line);
            out.push(Indexed {
                name: s.name.clone(),
                kind: s.kind,
                container: container.map(str::to_owned),
                line,
                column,
            });
            walk(buf, &s.children, Some(&s.name), out);
        }
    }
    walk(&buf, &syntax.symbols(buf.rope()), None, &mut out);
    out
}

/// How long an index refresh may be skipped after the previous one.
pub const REFRESH_INTERVAL: Duration = Duration::from_secs(5);

/// A [`SymbolIndex`] with a refresh throttle, for interactive queries.
#[derive(Debug, Default)]
pub struct ThrottledSymbolIndex {
    index: SymbolIndex,
    last_update: Option<Instant>,
}

impl ThrottledSymbolIndex {
    /// Queries, refreshing the index first unless it was refreshed within
    /// [`REFRESH_INTERVAL`] (or `force`).
    pub fn query(&mut self, ws: &Workspace, query: &str, limit: usize, force: bool) -> Vec<WorkspaceSymbol> {
        if force || self.last_update.is_none_or(|t| t.elapsed() > REFRESH_INTERVAL) {
            self.index.update(ws);
            self.last_update = Some(Instant::now());
        }
        self.index.query(query, limit)
    }
}
