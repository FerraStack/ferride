use std::path::Path;
use std::sync::OnceLock;

use tree_sitter::{Language, Query};

use crate::highlight::HighlightKind;
use crate::injection::InjectionQuery;

/// Languages with a bundled grammar.
///
/// The discriminants index [`LanguageId::ALL`] and the per-language config
/// cache, so keep them dense and in the same order.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum LanguageId {
    Rust,
    Python,
    JavaScript,
    TypeScript,
    Tsx,
    Json,
    Markdown,
    C,
    Cpp,
    Go,
    Java,
    Kotlin,
    Html,
    Css,
    Yaml,
    Toml,
    Bash,
    Sql,
    /// Markdown's inline grammar (emphasis, code spans, links), only ever
    /// injected into Markdown paragraphs; not a user-selectable language.
    MarkdownInline,
}

/// User-selectable languages ([`LanguageId::ALL`]).
const COUNT: usize = 18;
/// Every grammar, including injection-only ones.
const GRAMMARS: usize = COUNT + 1;

pub(crate) struct LanguageConfig {
    pub language: Language,
    pub query: Query,
    /// Capture index → highlight kind; `None` for captures that don't
    /// highlight (`@spell`, `@embedded`, ...). `@none` is `Some(Plain)`.
    pub kinds: Vec<Option<HighlightKind>>,
    /// Where other grammars are embedded (fenced code, `<script>`, ...).
    pub injections: Option<InjectionQuery>,
    /// When several patterns capture the same node, the *first* one wins
    /// (tree-sitter-highlight's rule, which the Go query is written for:
    /// generic `(identifier) @variable` last). Otherwise the last one
    /// wins (the JS/C/Java/Python queries start with the generic patterns).
    pub first_wins: bool,
}

impl LanguageId {
    pub const ALL: [LanguageId; COUNT] = [
        LanguageId::Rust,
        LanguageId::Python,
        LanguageId::JavaScript,
        LanguageId::TypeScript,
        LanguageId::Tsx,
        LanguageId::Json,
        LanguageId::Markdown,
        LanguageId::C,
        LanguageId::Cpp,
        LanguageId::Go,
        LanguageId::Java,
        LanguageId::Kotlin,
        LanguageId::Html,
        LanguageId::Css,
        LanguageId::Yaml,
        LanguageId::Toml,
        LanguageId::Bash,
        LanguageId::Sql,
    ];

    /// Picks a language for a file, like VS Code: an exact file name first
    /// (`Makefile`-style names, dotfiles such as `.bashrc`), then the
    /// extension, then a `#!` line in `first_line`.
    pub fn detect(path: impl AsRef<Path>, first_line: Option<&str>) -> Option<Self> {
        Self::from_path(path).or_else(|| first_line.and_then(Self::from_shebang))
    }

    /// Picks a language from a file name/path (exact name, then extension).
    pub fn from_path(path: impl AsRef<Path>) -> Option<Self> {
        let name = path.as_ref().file_name()?.to_str()?;
        if let Some(lang) = Self::from_file_name(name) {
            return Some(lang);
        }
        let ext = path.as_ref().extension()?.to_str()?.to_ascii_lowercase();
        Self::from_extension(&ext)
    }

    /// Well-known file names that have no (or a misleading) extension.
    pub fn from_file_name(name: &str) -> Option<Self> {
        Some(match name {
            ".bashrc" | ".bash_profile" | ".bash_login" | ".bash_logout" | ".bash_aliases" | ".profile"
            | ".zshrc" | ".zprofile" | ".zshenv" | ".zlogin" | ".zlogout" | ".kshrc" | ".envrc"
            | "PKGBUILD" | "APKBUILD" | "bashrc" | "profile" => Self::Bash,
            "Cargo.lock" | "Pipfile" | "poetry.lock" | "uv.lock" | ".editorconfig.toml" => Self::Toml,
            ".clang-format" | ".clang-tidy" | ".clangd" | "pnpm-lock.yaml" => Self::Yaml,
            ".babelrc" | ".eslintrc" | ".prettierrc" | ".jshintrc" | ".swcrc" => Self::Json,
            _ => return None,
        })
    }

    pub fn from_extension(ext: &str) -> Option<Self> {
        Some(match ext {
            "rs" => Self::Rust,
            "py" | "pyi" | "pyw" => Self::Python,
            "js" | "mjs" | "cjs" | "jsx" => Self::JavaScript,
            "ts" | "mts" | "cts" => Self::TypeScript,
            "tsx" => Self::Tsx,
            "json" | "jsonc" | "json5" | "code-workspace" | "webmanifest" => Self::Json,
            "md" | "markdown" | "mdx" | "mkd" => Self::Markdown,
            "c" | "i" => Self::C,
            // VS Code opens `.h` as C++ too.
            "cpp" | "cc" | "cxx" | "c++" | "cppm" | "ixx" | "h" | "hpp" | "hh" | "hxx" | "h++" | "inl"
            | "ipp" | "tpp" | "ino" => Self::Cpp,
            "go" => Self::Go,
            "java" | "jav" => Self::Java,
            "kt" | "kts" => Self::Kotlin,
            "html" | "htm" | "xhtml" | "shtml" => Self::Html,
            "css" => Self::Css,
            "yaml" | "yml" => Self::Yaml,
            "toml" => Self::Toml,
            "sh" | "bash" | "zsh" | "ksh" | "ebuild" | "eclass" => Self::Bash,
            "sql" | "dsql" => Self::Sql,
            _ => return None,
        })
    }

    /// Language named by a `#!` interpreter line (`#!/bin/sh`,
    /// `#!/usr/bin/env -S python3 -u`, ...).
    pub fn from_shebang(line: &str) -> Option<Self> {
        let rest = line.strip_prefix("#!")?.trim();
        let mut words = rest.split_whitespace();
        let mut program = words.next()?.rsplit('/').next()?;
        if program == "env" {
            // Skip env's own options (`-S`, `-i`, `-u NAME`) and assignments.
            let mut words = words.skip_while(|w| w.starts_with('-') || w.contains('='));
            program = words.next()?;
        }
        // python3.12 → python, node22 → node.
        let base = program.trim_end_matches(|c: char| c.is_ascii_digit() || c == '.');
        Some(match base {
            "sh" | "bash" | "zsh" | "ksh" | "dash" | "ash" | "mksh" => Self::Bash,
            "python" | "pypy" => Self::Python,
            "node" | "nodejs" | "deno" | "bun" => Self::JavaScript,
            "ts-node" | "tsx" => Self::TypeScript,
            "kotlin" | "kscript" => Self::Kotlin,
            _ => return None,
        })
    }

    /// Stable lowercase identifier: VS Code's language id (also used as the
    /// LSP `languageId`).
    pub fn name(self) -> &'static str {
        match self {
            Self::Rust => "rust",
            Self::Python => "python",
            Self::JavaScript => "javascript",
            Self::TypeScript => "typescript",
            Self::Tsx => "typescriptreact",
            Self::Json => "json",
            Self::Markdown => "markdown",
            Self::C => "c",
            Self::Cpp => "cpp",
            Self::Go => "go",
            Self::Java => "java",
            Self::Kotlin => "kotlin",
            Self::Html => "html",
            Self::Css => "css",
            Self::Yaml => "yaml",
            Self::Toml => "toml",
            Self::Bash => "shellscript",
            Self::Sql => "sql",
            Self::MarkdownInline => "markdown_inline",
        }
    }

    /// The language an injection names: a VS Code id, a Markdown fence info
    /// string (` ```rs `, ` ```sh `, ` ```c++ `) or a JS template tag
    /// (`` sql`...` ``, `` css`...` ``).
    pub fn from_injection_name(name: &str) -> Option<Self> {
        let name = name.trim().to_ascii_lowercase();
        if name == "markdown_inline" {
            return Some(Self::MarkdownInline);
        }
        if let Some(lang) = Self::from_name(&name) {
            return Some(lang);
        }
        Some(match name.as_str() {
            "shell" | "sh" | "bash" | "zsh" | "ksh" | "shellsession" => Self::Bash,
            "js" | "jsx" | "node" | "javascriptreact" => Self::JavaScript,
            "ts" => Self::TypeScript,
            "tsx" => Self::Tsx,
            "py" | "python3" | "py3" => Self::Python,
            "rs" => Self::Rust,
            "golang" => Self::Go,
            "c++" | "cxx" => Self::Cpp,
            "kt" | "kts" => Self::Kotlin,
            "yml" => Self::Yaml,
            "md" => Self::Markdown,
            "jsonc" | "json5" => Self::Json,
            "postgres" | "postgresql" | "psql" | "sqlite" | "mysql" | "plsql" => Self::Sql,
            other => return Self::from_extension(other),
        })
    }

    pub fn from_name(name: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|l| l.name() == name)
    }

    /// Grammar + compiled highlight query, built once per process on first
    /// use (so only languages that are actually opened pay for query
    /// compilation).
    pub(crate) fn config(self) -> &'static LanguageConfig {
        static CONFIGS: [OnceLock<LanguageConfig>; GRAMMARS] = [const { OnceLock::new() }; GRAMMARS];
        CONFIGS[self as usize].get_or_init(|| self.build_config())
    }

    fn injection_query(self) -> Option<&'static str> {
        Some(match self {
            Self::Markdown => tree_sitter_md::INJECTION_QUERY_BLOCK,
            Self::MarkdownInline => tree_sitter_md::INJECTION_QUERY_INLINE,
            Self::Html => tree_sitter_html::INJECTIONS_QUERY,
            // Tagged templates (sql`…`, css`…`, html`…`); regex/JSDoc have no grammar here.
            Self::JavaScript | Self::TypeScript | Self::Tsx => tree_sitter_javascript::INJECTIONS_QUERY,
            // Macro arguments (`println!`, `vec!`) are highlighted as Rust.
            Self::Rust => tree_sitter_rust::INJECTIONS_QUERY,
            _ => return None,
        })
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
            Self::MarkdownInline => (
                tree_sitter_md::INLINE_LANGUAGE.into(),
                vec![tree_sitter_md::HIGHLIGHT_QUERY_INLINE],
            ),
            Self::C => (
                tree_sitter_c::LANGUAGE.into(),
                vec![tree_sitter_c::HIGHLIGHT_QUERY],
            ),
            // tree-sitter-cpp's query refines C's (its tree-sitter.json lists both).
            Self::Cpp => (
                tree_sitter_cpp::LANGUAGE.into(),
                vec![tree_sitter_c::HIGHLIGHT_QUERY, tree_sitter_cpp::HIGHLIGHT_QUERY],
            ),
            Self::Go => (
                tree_sitter_go::LANGUAGE.into(),
                vec![tree_sitter_go::HIGHLIGHTS_QUERY],
            ),
            Self::Java => (
                tree_sitter_java::LANGUAGE.into(),
                vec![tree_sitter_java::HIGHLIGHTS_QUERY],
            ),
            Self::Kotlin => (
                tree_sitter_kotlin_ng::LANGUAGE.into(),
                vec![include_str!("../queries/kotlin/highlights.scm")],
            ),
            Self::Html => (
                tree_sitter_html::LANGUAGE.into(),
                vec![tree_sitter_html::HIGHLIGHTS_QUERY],
            ),
            Self::Css => (
                tree_sitter_css::LANGUAGE.into(),
                vec![
                    tree_sitter_css::HIGHLIGHTS_QUERY,
                    include_str!("../queries/css/highlights.scm"),
                ],
            ),
            Self::Yaml => (
                tree_sitter_yaml::LANGUAGE.into(),
                vec![tree_sitter_yaml::HIGHLIGHTS_QUERY],
            ),
            // tree-sitter-toml-ng's query relies on Neovim painting a later
            // capture of a whole `pair` over its key; ours captures tokens.
            Self::Toml => (
                tree_sitter_toml_ng::LANGUAGE.into(),
                vec![include_str!("../queries/toml/highlights.scm")],
            ),
            Self::Bash => (
                tree_sitter_bash::LANGUAGE.into(),
                vec![tree_sitter_bash::HIGHLIGHT_QUERY],
            ),
            Self::Sql => (
                tree_sitter_sequel::LANGUAGE.into(),
                vec![
                    tree_sitter_sequel::HIGHLIGHTS_QUERY,
                    include_str!("../queries/sql/highlights.scm"),
                ],
            ),
        };
        let source = sources.join("\n");
        let query = Query::new(&language, &source)
            .unwrap_or_else(|e| panic!("bundled highlight query for {self:?} is invalid: {e}"));
        let kinds = query
            .capture_names()
            .iter()
            .map(|n| match HighlightKind::from_capture_name(n) {
                _ if *n == "none" => Some(HighlightKind::Plain),
                HighlightKind::Plain => None,
                kind => Some(kind),
            })
            .collect();
        let injections = self.injection_query().map(|source| {
            InjectionQuery::new(&language, source)
                .unwrap_or_else(|e| panic!("bundled injection query for {self:?} is invalid: {e}"))
        });
        LanguageConfig {
            language,
            query,
            kinds,
            injections,
            first_wins: matches!(self, Self::Go),
        }
    }
}
