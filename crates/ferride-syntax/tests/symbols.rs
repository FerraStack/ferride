//! Outline golden tests: the symbols of every file in `samples/`, as an
//! indented tree (`kind name  [line]`), compared with
//! `tests/golden/<file>.symbols`. Regenerate with
//! `FERRIDE_BLESS=1 cargo test -p ferride-syntax --test symbols`.

use std::fmt::Write as _;
use std::path::{Path, PathBuf};

use ferride_buffer::Buffer;
use ferride_syntax::{LanguageId, Symbol, Syntax};

fn render(out: &mut String, buf: &Buffer, symbols: &[Symbol], depth: usize) {
    for s in symbols {
        let line = buf.byte_to_line(s.range.start) + 1;
        let end = buf.byte_to_line(s.range.end.saturating_sub(1).max(s.range.start)) + 1;
        writeln!(
            out,
            "{}{:?} {}  [{line}-{end}]",
            "  ".repeat(depth),
            s.kind,
            s.name
        )
        .unwrap();
        render(out, buf, &s.children, depth + 1);
    }
}

#[test]
fn every_sample_outline_matches_its_golden_file() {
    let dir = Path::new(env!("CARGO_MANIFEST_DIR"));
    let bless = std::env::var_os("FERRIDE_BLESS").is_some();
    let mut samples: Vec<PathBuf> = std::fs::read_dir(dir.join("samples"))
        .unwrap()
        .map(|e| e.unwrap().path())
        .collect();
    samples.sort();
    let mut failures = Vec::new();
    for path in samples {
        let name = path.file_name().unwrap().to_str().unwrap().to_owned();
        let text = std::fs::read_to_string(&path).unwrap();
        let language = LanguageId::detect(&path, text.lines().next()).unwrap();
        let buf = Buffer::new(&text);
        let syntax = Syntax::new(language, buf.rope());
        let symbols = syntax.symbols(buf.rope());
        assert!(!symbols.is_empty(), "no symbols in samples/{name}");
        let mut actual = String::new();
        render(&mut actual, &buf, &symbols, 0);
        let golden = dir.join(format!("tests/golden/{name}.symbols"));
        if bless {
            std::fs::write(&golden, &actual).unwrap();
        } else if std::fs::read_to_string(&golden).unwrap_or_default() != actual {
            failures.push(format!("{name}:\n{actual}"));
        }
    }
    assert!(
        failures.is_empty(),
        "outline mismatch (FERRIDE_BLESS=1 to update):\n{}",
        failures.join("\n")
    );
}
