use std::path::Path;
use std::sync::OnceLock;

use tree_sitter::{Language, Query};

use crate::highlight::HighlightKind;

/// Languages with a bundled grammar.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum LanguageId {
    Rust,
    Python,
    JavaScript,
    TypeScript,
    Tsx,
    Json,
    Markdown,
}

pub(crate) struct LanguageConfig {
    pub language: Language,
    pub query: Query,
    /// Capture index → highlight kind.
    pub kinds: Vec<HighlightKind>,
}

impl LanguageId {
    pub const ALL: [LanguageId; 7] = [
        LanguageId::Rust,
        LanguageId::Python,
        LanguageId::JavaScript,
        LanguageId::TypeScript,
        LanguageId::Tsx,
        LanguageId::Json,
        LanguageId::Markdown,
    ];

    /// Picks a language from a file name/path (by extension).
    pub fn from_path(path: impl AsRef<Path>) -> Option<Self> {
        let ext = path.as_ref().extension()?.to_str()?.to_ascii_lowercase();
        Self::from_extension(&ext)
    }

    pub fn from_extension(ext: &str) -> Option<Self> {
        Some(match ext {
            "rs" => Self::Rust,
            "py" | "pyi" | "pyw" => Self::Python,
            "js" | "mjs" | "cjs" | "jsx" => Self::JavaScript,
            "ts" | "mts" | "cts" => Self::TypeScript,
            "tsx" => Self::Tsx,
            "json" | "jsonc" | "json5" => Self::Json,
            "md" | "markdown" | "mdx" => Self::Markdown,
            _ => return None,
        })
    }

    /// Stable lowercase identifier (also the LSP `languageId`).
    pub fn name(self) -> &'static str {
        match self {
            Self::Rust => "rust",
            Self::Python => "python",
            Self::JavaScript => "javascript",
            Self::TypeScript => "typescript",
            Self::Tsx => "typescriptreact",
            Self::Json => "json",
            Self::Markdown => "markdown",
        }
    }

    pub fn from_name(name: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|l| l.name() == name)
    }

    /// Grammar + compiled highlight query, built once per process.
    pub(crate) fn config(self) -> &'static LanguageConfig {
        static CONFIGS: [OnceLock<LanguageConfig>; 7] = [const { OnceLock::new() }; 7];
        CONFIGS[self as usize].get_or_init(|| self.build_config())
    }

    fn build_config(self) -> LanguageConfig {
        let (language, sources): (Language, Vec<&str>) = match self {
            Self::Rust => (
                tree_sitter_rust::LANGUAGE.into(),
                vec![tree_sitter_rust::HIGHLIGHTS_QUERY],
            ),
            Self::Python => (
                tree_sitter_python::LANGUAGE.into(),
                vec![tree_sitter_python::HIGHLIGHTS_QUERY],
            ),
            Self::JavaScript => (
                tree_sitter_javascript::LANGUAGE.into(),
                vec![
                    tree_sitter_javascript::HIGHLIGHT_QUERY,
                    tree_sitter_javascript::JSX_HIGHLIGHT_QUERY,
                ],
            ),
            Self::TypeScript => (
                tree_sitter_typescript::LANGUAGE_TYPESCRIPT.into(),
                vec![
                    tree_sitter_javascript::HIGHLIGHT_QUERY,
                    tree_sitter_typescript::HIGHLIGHTS_QUERY,
                ],
            ),
            Self::Tsx => (
                tree_sitter_typescript::LANGUAGE_TSX.into(),
                vec![
                    tree_sitter_javascript::HIGHLIGHT_QUERY,
                    tree_sitter_javascript::JSX_HIGHLIGHT_QUERY,
                    tree_sitter_typescript::HIGHLIGHTS_QUERY,
                ],
            ),
            Self::Json => (
                tree_sitter_json::LANGUAGE.into(),
                vec![tree_sitter_json::HIGHLIGHTS_QUERY],
            ),
            Self::Markdown => (
                tree_sitter_md::LANGUAGE.into(),
                vec![tree_sitter_md::HIGHLIGHT_QUERY_BLOCK],
            ),
        };
        let source = sources.join("\n");
        let query = Query::new(&language, &source)
            .unwrap_or_else(|e| panic!("bundled highlight query for {self:?} is invalid: {e}"));
        let kinds = query
            .capture_names()
            .iter()
            .map(|n| HighlightKind::from_capture_name(n))
            .collect();
        LanguageConfig {
            language,
            query,
            kinds,
        }
    }
}
