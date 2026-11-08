//! Token colors of VS Code color themes, resolved the way VS Code does.
//!
//! A theme's `tokenColors` are TextMate scope selectors (`keyword.control`,
//! `source.js comment`, `meta.tag > string`) with a foreground and a
//! `fontStyle` (italic, bold, underline, strikethrough). This crate is a port
//! of the theme part of [vscode-textmate](https://github.com/microsoft/vscode-textmate)
//! (MIT, © Microsoft), so the same token gets the same style as in VS Code:
//!
//! - rules live in a trie keyed by scope segments; a scope matches the
//!   deepest trie node along its segments (`entity.name.function.rust` →
//!   `entity.name.function`), whose rules inherit unset properties from
//!   shallower ones;
//! - rules with parent scopes (`source.js comment`) only apply when those
//!   scopes appear, in order, among the token's ancestors (`>` requires the
//!   direct parent);
//! - candidates are ordered by specificity: deeper scope match first, then
//!   longer parent scopes, then more parent scopes; for equal selectors the
//!   later rule in the file wins;
//! - a token's style is built scope by scope from the root (`source.rust`)
//!   down, each matched rule overriding only the properties it sets.

mod jsonc;

use std::cmp::Ordering;
use std::collections::HashMap;

pub use jsonc::strip_jsonc;

/// `fontStyle` bits, as in VS Code.
pub mod font_style {
    pub const NONE: u8 = 0;
    pub const ITALIC: u8 = 1;
    pub const BOLD: u8 = 2;
    pub const UNDERLINE: u8 = 4;
    pub const STRIKETHROUGH: u8 = 8;
}

/// Resolved style of a token: ARGB foreground and [`font_style`] bits.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TokenStyle {
    pub foreground: u32,
    pub font_style: u8,
}

#[derive(Debug, thiserror::Error)]
pub enum ThemeError {
    #[error("invalid theme JSON: {0}")]
    Json(#[from] serde_json::Error),
}

/// A parsed `tokenColors` rule for one selector.
#[derive(Debug, Clone)]
struct ParsedRule {
    scope: String,
    /// Innermost first (reversed from the selector text), `None` if none.
    parents: Option<Vec<String>>,
    index: usize,
    /// -1 = not set.
    font_style: i8,
    foreground: Option<u32>,
}

#[derive(Debug, Clone)]
struct TrieRule {
    depth: u32,
    parents: Vec<String>,
    font_style: i8,
    foreground: Option<u32>,
}

impl TrieRule {
    fn accept_overwrite(&mut self, depth: u32, font_style: i8, foreground: Option<u32>) {
        self.depth = self.depth.max(depth);
        if font_style != -1 {
            self.font_style = font_style;
        }
        if foreground.is_some() {
            self.foreground = foreground;
        }
    }
}

#[derive(Debug, Clone)]
struct TrieNode {
    main: TrieRule,
    with_parents: Vec<TrieRule>,
    children: HashMap<String, usize>,
}

/// The token rules of a color theme.
#[derive(Debug, Clone)]
pub struct TokenTheme {
    default_foreground: u32,
    default_font_style: u8,
    nodes: Vec<TrieNode>,
}

impl TokenTheme {
    /// Parses a VS Code color theme file (JSON with comments and trailing
    /// commas allowed). The default foreground is `colors["editor.foreground"]`,
    /// overridden by a scope-less `tokenColors` entry, like VS Code.
    pub fn from_theme_json(json: &str) -> Result<Self, ThemeError> {
        let value: serde_json::Value = serde_json::from_str(&strip_jsonc(json))?;
        let light = value
            .get("type")
            .and_then(|t| t.as_str())
            .is_some_and(|t| t.starts_with("light"));
        let editor_fg = value
            .pointer("/colors/editor.foreground")
            .and_then(|v| v.as_str())
            .and_then(parse_color)
            .unwrap_or(if light { 0xFF00_0000 } else { 0xFFD4_D4D4 });
        let empty = Vec::new();
        let rules = value
            .get("tokenColors")
            .and_then(|v| v.as_array())
            .unwrap_or(&empty);
        Ok(Self::from_rules(editor_fg, rules))
    }

    /// Builds a theme from `tokenColors` entries (`{ scope, settings }`).
    pub fn from_rules(default_foreground: u32, rules: &[serde_json::Value]) -> Self {
        let mut parsed = parse_rules(rules);
        parsed.sort_by(|a, b| {
            a.scope
                .cmp(&b.scope)
                .then_with(|| cmp_parents(a.parents.as_deref(), b.parents.as_deref()))
                .then(a.index.cmp(&b.index))
        });
        let mut theme = Self {
            default_foreground,
            default_font_style: 0,
            nodes: vec![TrieNode {
                main: TrieRule {
                    depth: 0,
                    parents: Vec::new(),
                    font_style: -1,
                    foreground: None,
                },
                with_parents: Vec::new(),
                children: HashMap::new(),
            }],
        };
        let mut rest = parsed.as_slice();
        while let Some((first, tail)) = rest.split_first() {
            if !first.scope.is_empty() {
                break;
            }
            if first.font_style != -1 {
                theme.default_font_style = first.font_style as u8;
            }
            if let Some(fg) = first.foreground {
                theme.default_foreground = fg;
            }
            rest = tail;
        }
        for rule in rest {
            theme.insert(rule);
        }
        theme
    }

    pub fn default_foreground(&self) -> u32 {
        self.default_foreground
    }

    fn insert(&mut self, rule: &ParsedRule) {
        let mut node = 0;
        let mut depth = 0;
        for segment in rule.scope.split('.') {
            depth += 1;
            node = match self.nodes[node].children.get(segment) {
                Some(&child) => child,
                None => {
                    let child = TrieNode {
                        main: self.nodes[node].main.clone(),
                        with_parents: self.nodes[node].with_parents.clone(),
                        children: HashMap::new(),
                    };
                    self.nodes.push(child);
                    let id = self.nodes.len() - 1;
                    self.nodes[node].children.insert(segment.to_owned(), id);
                    id
                }
            };
        }
        let n = &mut self.nodes[node];
        match &rule.parents {
            None => n.main.accept_overwrite(depth, rule.font_style, rule.foreground),
            Some(parents) => {
                if let Some(existing) = n.with_parents.iter_mut().find(|r| &r.parents == parents) {
                    existing.accept_overwrite(depth, rule.font_style, rule.foreground);
                    return;
                }
                let font_style = if rule.font_style == -1 {
                    n.main.font_style
                } else {
                    rule.font_style
                };
                let foreground = rule.foreground.or(n.main.foreground);
                n.with_parents.push(TrieRule {
                    depth,
                    parents: parents.clone(),
                    font_style,
                    foreground,
                });
            }
        }
    }

    /// Candidate rules for `scope`, most specific first.
    fn candidates(&self, scope: &str) -> Vec<&TrieRule> {
        let mut node = 0;
        for segment in scope.split('.') {
            match self.nodes[node].children.get(segment) {
                Some(&child) => node = child,
                None => break,
            }
        }
        let n = &self.nodes[node];
        let mut rules: Vec<&TrieRule> = n.with_parents.iter().chain(std::iter::once(&n.main)).collect();
        rules.sort_by(|a, b| cmp_specificity(a, b));
        rules
    }

    /// The style of a token whose scope stack is `scopes` (outermost first,
    /// e.g. `["source.rust", "meta.function.rust", "entity.name.function.rust"]`).
    pub fn style<S: AsRef<str>>(&self, scopes: &[S]) -> TokenStyle {
        let mut style = TokenStyle {
            foreground: self.default_foreground,
            font_style: self.default_font_style,
        };
        for i in 0..scopes.len() {
            let ancestors = &scopes[..i];
            let found = self
                .candidates(scopes[i].as_ref())
                .into_iter()
                .find(|r| parents_match(ancestors, &r.parents));
            if let Some(rule) = found {
                if rule.font_style != -1 {
                    style.font_style = rule.font_style as u8;
                }
                if let Some(fg) = rule.foreground {
                    style.foreground = fg;
                }
            }
        }
        style
    }
}

/// `pattern` matches `scope` exactly or as a dotted prefix.
fn matches_scope(scope: &str, pattern: &str) -> bool {
    scope == pattern || (scope.starts_with(pattern) && scope.as_bytes().get(pattern.len()) == Some(&b'.'))
}

/// Whether `parents` (innermost first, `>` = direct parent) match the token's
/// ancestors (outermost first).
fn parents_match<S: AsRef<str>>(ancestors: &[S], parents: &[String]) -> bool {
    let mut path = ancestors.iter().rev().map(|s| s.as_ref());
    let mut i = 0;
    while i < parents.len() {
        let mut pattern = parents[i].as_str();
        let mut direct = false;
        if pattern == ">" {
            if i + 1 == parents.len() {
                return false;
            }
            i += 1;
            pattern = parents[i].as_str();
            direct = true;
        }
        loop {
            match path.next() {
                Some(scope) if matches_scope(scope, pattern) => break,
                Some(_) if !direct => continue,
                _ => return false,
            }
        }
        i += 1;
    }
    true
}

fn cmp_specificity(a: &TrieRule, b: &TrieRule) -> Ordering {
    if a.depth != b.depth {
        return b.depth.cmp(&a.depth);
    }
    let (mut i, mut j) = (0, 0);
    loop {
        if a.parents.get(i).is_some_and(|p| p == ">") {
            i += 1;
        }
        if b.parents.get(j).is_some_and(|p| p == ">") {
            j += 1;
        }
        if i >= a.parents.len() || j >= b.parents.len() {
            break;
        }
        let diff = b.parents[j].len().cmp(&a.parents[i].len());
        if diff != Ordering::Equal {
            return diff;
        }
        i += 1;
        j += 1;
    }
    b.parents.len().cmp(&a.parents.len())
}

/// vscode-textmate's `strArrCmp`: no parents first, then by length, then
/// element-wise.
fn cmp_parents(a: Option<&[String]>, b: Option<&[String]>) -> Ordering {
    match (a, b) {
        (None, None) => Ordering::Equal,
        (None, _) => Ordering::Less,
        (_, None) => Ordering::Greater,
        (Some(a), Some(b)) => a.len().cmp(&b.len()).then_with(|| a.cmp(b)),
    }
}

fn parse_rules(rules: &[serde_json::Value]) -> Vec<ParsedRule> {
    let mut out = Vec::new();
    for (index, entry) in rules.iter().enumerate() {
        let Some(settings) = entry.get("settings").and_then(|s| s.as_object()) else {
            continue;
        };
        let selectors: Vec<String> = match entry.get("scope") {
            Some(serde_json::Value::String(s)) => s.trim_matches(',').split(',').map(str::to_owned).collect(),
            Some(serde_json::Value::Array(a)) => {
                a.iter().filter_map(|v| v.as_str().map(str::to_owned)).collect()
            }
            _ => vec![String::new()],
        };
        let font_style = match settings.get("fontStyle").and_then(|f| f.as_str()) {
            Some(f) => f.split(' ').fold(0i8, |acc, w| {
                acc | match w {
                    "italic" => font_style::ITALIC as i8,
                    "bold" => font_style::BOLD as i8,
                    "underline" => font_style::UNDERLINE as i8,
                    "strikethrough" => font_style::STRIKETHROUGH as i8,
                    _ => 0,
                }
            }),
            None => -1,
        };
        let foreground = settings
            .get("foreground")
            .and_then(|f| f.as_str())
            .and_then(parse_color);
        for selector in selectors {
            let segments: Vec<&str> = selector.trim().split(' ').collect();
            let scope = segments.last().copied().unwrap_or("").to_owned();
            let parents = (segments.len() > 1).then(|| {
                segments[..segments.len() - 1]
                    .iter()
                    .rev()
                    .map(|s| (*s).to_owned())
                    .collect()
            });
            out.push(ParsedRule {
                scope,
                parents,
                index,
                font_style,
                foreground,
            });
        }
    }
    out
}

/// `#RGB`, `#RGBA`, `#RRGGBB`, `#RRGGBBAA` → ARGB.
pub fn parse_color(s: &str) -> Option<u32> {
    let hex = s.strip_prefix('#')?;
    if !hex.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    let full: String = match hex.len() {
        3 | 4 => hex.chars().flat_map(|c| [c, c]).collect(),
        6 | 8 => hex.to_owned(),
        _ => return None,
    };
    let v = u32::from_str_radix(&full, 16).ok()?;
    Some(if full.len() == 8 {
        (v >> 8) | ((v & 0xFF) << 24)
    } else {
        0xFF00_0000 | v
    })
}

#[cfg(test)]
mod tests;
