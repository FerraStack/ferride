//! Golden highlight tests: every file in `samples/` is highlighted and the
//! result is compared with `tests/golden/<file>.golden`.
//!
//! The golden format lists each source line followed by its highlighted
//! tokens, so a diff shows exactly which token changed class:
//!
//! ```text
//!    4 | fn word_counts(text: &str) -> HashMap<&str, usize> {
//!      |   keyword      "fn"             keyword.other.rust
//!      |   function     "word_counts"    entity.name.function.rust
//! ```
//!
//! After an intended change (new grammar version, query tweak), regenerate with
//! `FERRIDE_BLESS=1 cargo test -p ferride-syntax --test golden` and review the diff.

use std::fmt::Write as _;
use std::path::{Path, PathBuf};

use ferride_buffer::Buffer;
use ferride_syntax::{LanguageId, Syntax, scope_stack};

fn samples_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("samples")
}

fn golden_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/golden")
}

/// Renders the highlights of `text` in the golden format.
fn render(language: LanguageId, text: &str) -> String {
    let buf = Buffer::new(text);
    let syntax = Syntax::new(language, buf.rope());
    assert!(syntax.tree().is_some(), "{language:?} failed to parse");
    let lines = syntax.line_spans(&buf, 0..buf.len_lines());
    let mut out = String::new();
    for (i, spans) in lines.iter().enumerate() {
        let line = buf.line_text(i);
        if line.is_empty() && i + 1 == lines.len() {
            break;
        }
        writeln!(out, "{:4} | {line}", i + 1).unwrap();
        let utf16: Vec<u16> = line.encode_utf16().collect();
        for s in spans {
            let token = String::from_utf16_lossy(&utf16[s.start_col..s.end_col]);
            let kind = format!("{:?}", s.kind).to_lowercase();
            let scope = scope_stack(s.scope).pop().unwrap_or_default();
            writeln!(out, "     |   {kind:<12} {:<16} {scope}", format!("{token:?}")).unwrap();
        }
    }
    out
}

#[test]
fn every_sample_matches_its_golden_file() {
    let bless = std::env::var_os("FERRIDE_BLESS").is_some();
    let mut samples: Vec<PathBuf> = std::fs::read_dir(samples_dir())
        .unwrap()
        .map(|e| e.unwrap().path())
        .collect();
    samples.sort();
    let mut covered = Vec::new();
    let mut failures = Vec::new();
    for path in samples {
        let name = path.file_name().unwrap().to_str().unwrap().to_owned();
        let text = std::fs::read_to_string(&path).unwrap();
        let first_line = text.lines().next();
        let language = LanguageId::detect(&path, first_line)
            .unwrap_or_else(|| panic!("no language detected for samples/{name}"));
        covered.push(language);
        let actual = render(language, &text);
        let golden = golden_dir().join(format!("{name}.golden"));
        if bless {
            std::fs::create_dir_all(golden_dir()).unwrap();
            std::fs::write(&golden, &actual).unwrap();
            continue;
        }
        let expected = std::fs::read_to_string(&golden).unwrap_or_default();
        if expected != actual {
            failures.push(first_difference(&name, &expected, &actual));
        }
    }
    for lang in LanguageId::ALL {
        assert!(covered.contains(&lang), "no sample for {lang:?} in samples/");
    }
    assert!(
        failures.is_empty(),
        "golden mismatch (rerun with FERRIDE_BLESS=1 to update):\n{}",
        failures.join("\n")
    );
}

fn first_difference(name: &str, expected: &str, actual: &str) -> String {
    let mut e = expected.lines();
    let mut a = actual.lines();
    let mut n = 1;
    loop {
        match (e.next(), a.next()) {
            (Some(x), Some(y)) if x == y => n += 1,
            (None, None) => return format!("{name}: differs only in trailing newlines"),
            (x, y) => {
                return format!(
                    "{name}.golden line {n}:\n  expected: {}\n  actual:   {}",
                    x.unwrap_or("<end>"),
                    y.unwrap_or("<end>")
                );
            }
        }
    }
}
