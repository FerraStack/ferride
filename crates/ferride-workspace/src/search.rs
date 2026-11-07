use std::path::PathBuf;

use grep_matcher::Matcher;
use grep_regex::RegexMatcherBuilder;
use grep_searcher::sinks::UTF8;
use grep_searcher::{BinaryDetection, SearcherBuilder};

use crate::{MAX_FILE_SIZE, Result, Workspace, WorkspaceError};

#[derive(Debug, Clone)]
pub struct SearchOptions {
    /// Treat the query as a regular expression (otherwise a literal).
    pub regex: bool,
    pub case_sensitive: bool,
    pub whole_word: bool,
    /// Stop after this many matching lines.
    pub max_results: usize,
}

impl Default for SearchOptions {
    fn default() -> Self {
        Self {
            regex: false,
            case_sensitive: false,
            whole_word: false,
            max_results: 2000,
        }
    }
}

/// One matching line.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TextMatch {
    pub path: PathBuf,
    pub rel_path: String,
    /// 0-based line number.
    pub line: u64,
    /// The line without its terminator (truncated to ~500 chars).
    pub line_text: String,
    /// Match ranges within `line_text`, in UTF-16 columns.
    pub ranges: Vec<(usize, usize)>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SearchResults {
    pub matches: Vec<TextMatch>,
    pub files_searched: usize,
    /// `max_results` was hit.
    pub truncated: bool,
}

const MAX_LINE_CHARS: usize = 500;

pub(crate) fn search(ws: &Workspace, query: &str, opts: &SearchOptions) -> Result<SearchResults> {
    let mut results = SearchResults::default();
    if query.is_empty() {
        return Ok(results);
    }
    let matcher = RegexMatcherBuilder::new()
        .case_insensitive(!opts.case_sensitive)
        .fixed_strings(!opts.regex)
        .word(opts.whole_word)
        .line_terminator(Some(b'\n'))
        .build(query)
        .map_err(|e| WorkspaceError::BadPattern(e.to_string()))?;
    let mut searcher = SearcherBuilder::new()
        .line_number(true)
        .binary_detection(BinaryDetection::quit(0))
        .build();

    for entry in ws.walker().filter_map(|e| e.ok()) {
        if !entry.file_type().is_some_and(|t| t.is_file()) {
            continue;
        }
        if entry.metadata().is_ok_and(|m| m.len() > MAX_FILE_SIZE) {
            continue;
        }
        results.files_searched += 1;
        let path = entry.path();
        let rel = ws.rel_path(path);
        let mut stop = false;
        let sink = UTF8(|line_number, line| {
            let text = line.trim_end_matches(['\n', '\r']);
            let mut ranges = Vec::new();
            matcher
                .find_iter(text.as_bytes(), |m| {
                    ranges.push((utf16_col(text, m.start()), utf16_col(text, m.end())));
                    true
                })
                .ok();
            let line_text: String = text.chars().take(MAX_LINE_CHARS).collect();
            let limit = line_text.encode_utf16().count();
            ranges.retain(|&(a, _)| a < limit);
            for r in &mut ranges {
                r.1 = r.1.min(limit);
            }
            results.matches.push(TextMatch {
                path: path.to_owned(),
                rel_path: rel.clone(),
                line: line_number.saturating_sub(1),
                line_text,
                ranges,
            });
            if results.matches.len() >= opts.max_results {
                stop = true;
                return Ok(false);
            }
            Ok(true)
        });
        // Unreadable files and invalid UTF-8 are skipped, not fatal.
        let _ = searcher.search_path(&matcher, path, sink);
        if stop {
            results.truncated = true;
            break;
        }
    }
    Ok(results)
}

fn utf16_col(text: &str, byte: usize) -> usize {
    let mut b = byte.min(text.len());
    while !text.is_char_boundary(b) {
        b -= 1;
    }
    text[..b].encode_utf16().count()
}
