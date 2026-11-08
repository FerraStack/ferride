//! Theme fidelity against VS Code itself.
//!
//! `tests/fixtures/vscode/<theme>/<sample>.tsv` holds what VS Code renders for
//! each token of `ferride-syntax/samples/<sample>`: tokenized by VS Code's own
//! TextMate grammar and styled by vscode-textmate's theme engine (generated
//! with `tests/fixtures/generate.mjs`). Here the same file is highlighted with
//! tree-sitter, each token's scope stack is styled by [`TokenTheme`], and the
//! two are compared character by character.
//!
//! The themes are popular MIT-licensed ones: Dracula, One Dark Pro and Night
//! Owl (`tests/fixtures/themes/`, licenses alongside).
//!
//! Run with `--nocapture` to see the per-file agreement table and the
//! disagreeing tokens (`FERRIDE_PARITY_VERBOSE=1`).

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use ferride_buffer::Buffer;
use ferride_syntax::{LanguageId, Syntax, root_scope, scope_stack};
use ferride_theme::TokenTheme;

const THEMES: [&str; 3] = ["dracula", "one-dark-pro", "night-owl"];

fn fixtures() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures")
}

fn samples() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../ferride-syntax/samples")
}

struct VsToken {
    line: usize,
    start: usize,
    end: usize,
    fg: u32,
    font_style: u8,
    text: String,
    scope: String,
}

fn read_tsv(path: &Path) -> Vec<VsToken> {
    std::fs::read_to_string(path)
        .unwrap()
        .lines()
        .filter(|l| !l.starts_with('#'))
        .map(|l| {
            let c: Vec<&str> = l.split('\t').collect();
            VsToken {
                line: c[0].parse().unwrap(),
                start: c[1].parse().unwrap(),
                end: c[2].parse().unwrap(),
                fg: ferride_theme::parse_color(c[3]).unwrap(),
                font_style: c[4].parse().unwrap(),
                text: serde_json::from_str(c[5]).unwrap(),
                scope: c[6].to_owned(),
            }
        })
        .collect()
}

#[derive(Default, Clone, Copy)]
struct Score {
    chars: usize,
    fg_ok: usize,
    style_ok: usize,
}

impl Score {
    fn add(&mut self, o: Score) {
        self.chars += o.chars;
        self.fg_ok += o.fg_ok;
        self.style_ok += o.style_ok;
    }
    fn fg(&self) -> f64 {
        100.0 * self.fg_ok as f64 / self.chars.max(1) as f64
    }
    fn style(&self) -> f64 {
        100.0 * self.style_ok as f64 / self.chars.max(1) as f64
    }
}

/// Per-UTF-16-column scope stacks of every line, as Ferride highlights it.
fn ferride_stacks(text: &str, language: LanguageId) -> Vec<Vec<Vec<String>>> {
    let buf = Buffer::new(text);
    let syntax = Syntax::new(language, buf.rope());
    let root = vec![root_scope(language).to_owned()];
    syntax
        .line_spans(&buf, 0..buf.len_lines())
        .iter()
        .enumerate()
        .map(|(i, spans)| {
            let width = buf.line_text(i).encode_utf16().count();
            let mut cols = vec![root.clone(); width];
            for s in spans {
                let stack = scope_stack(s.scope);
                for c in &mut cols[s.start_col..s.end_col.min(width)] {
                    c.clone_from(&stack);
                }
            }
            cols
        })
        .collect()
}

#[test]
fn token_colors_match_vscode() {
    let verbose = std::env::var_os("FERRIDE_PARITY_VERBOSE").is_some();
    let mut report = String::new();
    let mut totals: BTreeMap<&str, Score> = BTreeMap::new();
    let mut by_category: BTreeMap<&str, Score> = BTreeMap::new();
    let mut failures = Vec::new();
    for theme_name in THEMES {
        let json = std::fs::read_to_string(fixtures().join(format!("themes/{theme_name}.json"))).unwrap();
        let theme = TokenTheme::from_theme_json(&json).unwrap();
        let mut files: Vec<PathBuf> = std::fs::read_dir(fixtures().join("vscode").join(theme_name))
            .unwrap()
            .map(|e| e.unwrap().path())
            .collect();
        files.sort();
        for tsv in files {
            let sample = tsv.file_stem().unwrap().to_str().unwrap().to_owned(); // "main.rs"
            let text = std::fs::read_to_string(samples().join(&sample)).unwrap();
            let language = LanguageId::detect(&sample, text.lines().next()).unwrap();
            let stacks = ferride_stacks(&text, language);
            let mut score = Score::default();
            for tok in read_tsv(&tsv) {
                let line_text: Vec<u16> = text.lines().nth(tok.line).unwrap().encode_utf16().collect();
                let mut bad = false;
                for col in tok.start..tok.end.min(line_text.len()) {
                    if char::from_u32(line_text[col] as u32).is_some_and(char::is_whitespace) {
                        continue;
                    }
                    let stack = &stacks[tok.line][col];
                    // Ferride injects SQL/CSS/HTML into JS tagged templates; VS Code
                    // needs an extension for that, so these aren't comparable.
                    if tok.scope.starts_with("string.template")
                        && stack.iter().any(|s| s.starts_with("meta.embedded"))
                    {
                        continue;
                    }
                    let style = theme.style(stack);
                    score.chars += 1;
                    let fg_ok = style.foreground == tok.fg;
                    let style_ok = style.font_style == tok.font_style;
                    score.fg_ok += fg_ok as usize;
                    score.style_ok += style_ok as usize;
                    if let Some(cat) = CATEGORIES.iter().find(|c| tok.scope.starts_with(*c)) {
                        by_category.entry(cat).or_default().add(Score {
                            chars: 1,
                            fg_ok: fg_ok as usize,
                            style_ok: style_ok as usize,
                        });
                    }
                    if !(fg_ok && style_ok) && !bad {
                        bad = true;
                        if verbose {
                            println!(
                                "{theme_name} {sample}:{} {:?} vscode {:08X}/{} ({}) ferride {:08X}/{} ({})",
                                tok.line + 1,
                                tok.text,
                                tok.fg,
                                tok.font_style,
                                tok.scope,
                                style.foreground,
                                style.font_style,
                                stacks[tok.line][col].last().unwrap(),
                            );
                        }
                    }
                }
            }
            report.push_str(&format!(
                "{theme_name:<13} {sample:<13} color {:5.1}%  font style {:5.1}%  ({} chars)\n",
                score.fg(),
                score.style(),
                score.chars
            ));
            if score.fg() < min_color_agreement(&sample) {
                failures.push(format!(
                    "{theme_name} {sample}: color agreement {:.1}%",
                    score.fg()
                ));
            }
            totals.entry(theme_name).or_default().add(score);
        }
    }
    for (theme, s) in &totals {
        report.push_str(&format!(
            "{theme:<13} TOTAL         color {:5.1}%  font style {:5.1}%\n",
            s.fg(),
            s.style()
        ));
        if s.fg() < MIN_THEME_COLOR || s.style() < MIN_THEME_FONT_STYLE {
            failures.push(format!(
                "{theme}: total {:.1}% color, {:.1}% font style",
                s.fg(),
                s.style()
            ));
        }
    }
    for (cat, s) in &by_category {
        report.push_str(&format!(
            "{cat:<27} color {:5.1}%  font style {:5.1}%  ({} chars)\n",
            s.fg(),
            s.style(),
            s.chars
        ));
        if s.fg() < MIN_CATEGORY || s.style() < MIN_CATEGORY {
            failures.push(format!(
                "{cat}: {:.1}% color, {:.1}% font style",
                s.fg(),
                s.style()
            ));
        }
    }
    println!("{report}");
    assert!(
        failures.is_empty(),
        "below the agreement floor:\n{}\n\n{report}",
        failures.join("\n")
    );
}

/// Token kinds every theme styles and VS Code scopes the same way in every
/// grammar: these must match (all three themes, all samples).
const CATEGORIES: [&str; 5] = [
    "comment",
    "string.quoted",
    "constant.numeric",
    "keyword.control",
    "entity.name.function",
];
const MIN_CATEGORY: f64 = 90.0;
/// Floors (ratchet them up as the scope mapping improves). The rest of the
/// gap is mostly where VS Code's regex grammars differ from a syntax tree
/// (SQL, shell words, C's unscoped identifiers, meta scopes).
const MIN_THEME_COLOR: f64 = 80.0;
const MIN_THEME_FONT_STYLE: f64 = 90.0;

fn min_color_agreement(_sample: &str) -> f64 {
    70.0
}
