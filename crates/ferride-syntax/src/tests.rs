use ferride_buffer::{Buffer, Edit, EditKind};

use super::*;

fn kind_at(syntax: &Syntax, buf: &Buffer, needle: &str) -> HighlightKind {
    let text = buf.text();
    let start = text
        .find(needle)
        .unwrap_or_else(|| panic!("{needle:?} not in text"));
    let spans = syntax.highlight_bytes(buf.rope(), 0..buf.len_bytes());
    spans
        .iter()
        .find(|s| s.start_byte <= start && start < s.end_byte)
        .map_or(HighlightKind::Plain, |s| s.kind)
}

#[test]
fn every_bundled_query_compiles() {
    for lang in LanguageId::ALL {
        let buf = Buffer::new("");
        let s = Syntax::new(lang, buf.rope());
        assert!(s.tree().is_some(), "{lang:?}");
        assert_eq!(LanguageId::from_name(lang.name()), Some(lang));
    }
}

#[test]
fn language_detection() {
    assert_eq!(LanguageId::from_path("src/main.rs"), Some(LanguageId::Rust));
    assert_eq!(LanguageId::from_path("a/b/App.TSX"), Some(LanguageId::Tsx));
    assert_eq!(LanguageId::from_path("README.md"), Some(LanguageId::Markdown));
    assert_eq!(LanguageId::from_path("Makefile"), None);
    // New grammars, VS Code's choices (`.h` is C++).
    for (path, lang) in [
        ("a.c", LanguageId::C),
        ("include/a.h", LanguageId::Cpp),
        ("a.cc", LanguageId::Cpp),
        ("main.go", LanguageId::Go),
        ("Main.java", LanguageId::Java),
        ("build.gradle.kts", LanguageId::Kotlin),
        ("index.HTML", LanguageId::Html),
        ("style.css", LanguageId::Css),
        (".github/workflows/ci.yml", LanguageId::Yaml),
        ("Cargo.toml", LanguageId::Toml),
        ("Cargo.lock", LanguageId::Toml),
        ("run.sh", LanguageId::Bash),
        ("/home/me/.bashrc", LanguageId::Bash),
        ("PKGBUILD", LanguageId::Bash),
        ("schema.sql", LanguageId::Sql),
        (".clang-format", LanguageId::Yaml),
    ] {
        assert_eq!(LanguageId::from_path(path), Some(lang), "{path}");
    }
}

#[test]
fn shebang_detection() {
    use LanguageId as L;
    for (line, lang) in [
        ("#!/bin/sh", Some(L::Bash)),
        ("#!/bin/bash -e", Some(L::Bash)),
        ("#!/usr/bin/env bash", Some(L::Bash)),
        ("#! /usr/bin/env zsh", Some(L::Bash)),
        ("#!/usr/bin/env -S python3 -u", Some(L::Python)),
        ("#!/usr/bin/python3.12", Some(L::Python)),
        ("#!/usr/bin/env node", Some(L::JavaScript)),
        ("#!/usr/bin/env -i FOO=1 deno run", Some(L::JavaScript)),
        ("#!/usr/bin/env perl", None),
        ("# just a comment", None),
        ("", None),
    ] {
        assert_eq!(L::from_shebang(line), lang, "{line:?}");
    }
    // Name and extension win over the shebang; the shebang covers bare names.
    assert_eq!(L::detect("bin/deploy", Some("#!/bin/bash")), Some(L::Bash));
    assert_eq!(L::detect("tool.py", Some("#!/bin/bash")), Some(L::Python));
    assert_eq!(L::detect("notes", Some("hello")), None);
}

#[test]
fn rust_highlights() {
    let buf = Buffer::new("// hi\nfn main() {\n    let s = \"str\";\n    println!(\"{}\", 42);\n}\n");
    let syn = Syntax::new(LanguageId::Rust, buf.rope());
    assert_eq!(kind_at(&syn, &buf, "// hi"), HighlightKind::Comment);
    assert_eq!(kind_at(&syn, &buf, "fn"), HighlightKind::Keyword);
    assert_eq!(kind_at(&syn, &buf, "main"), HighlightKind::Function);
    assert_eq!(kind_at(&syn, &buf, "let"), HighlightKind::Keyword);
    assert_eq!(kind_at(&syn, &buf, "\"str\""), HighlightKind::String);
    // tree-sitter-rust tags literals as `@constant.builtin`.
    assert_eq!(kind_at(&syn, &buf, "42"), HighlightKind::Constant);
}

#[test]
fn later_patterns_refine_generic_ones() {
    // JS's query starts with `(identifier) @variable`; the function name must
    // still come out as a function.
    let buf = Buffer::new("function greet(name) { return name; }\n");
    let syn = Syntax::new(LanguageId::JavaScript, buf.rope());
    assert_eq!(kind_at(&syn, &buf, "greet"), HighlightKind::Function);
    assert_eq!(kind_at(&syn, &buf, "function"), HighlightKind::Keyword);
}

#[test]
fn python_typescript_json_markdown() {
    let py = Buffer::new("def f(x):\n    return 'a'\n");
    let s = Syntax::new(LanguageId::Python, py.rope());
    assert_eq!(kind_at(&s, &py, "def"), HighlightKind::Keyword);
    assert_eq!(kind_at(&s, &py, "'a'"), HighlightKind::String);

    let ts = Buffer::new("interface P { x: number }\nconst a: P = { x: 1 };\n");
    let s = Syntax::new(LanguageId::TypeScript, ts.rope());
    assert_eq!(kind_at(&s, &ts, "interface"), HighlightKind::Keyword);
    assert_eq!(kind_at(&s, &ts, "number"), HighlightKind::Builtin);

    let json = Buffer::new("{\"k\": [1, true, null]}");
    let s = Syntax::new(LanguageId::Json, json.rope());
    assert_eq!(kind_at(&s, &json, "1"), HighlightKind::Number);
    assert_eq!(kind_at(&s, &json, "true"), HighlightKind::Constant);

    let md = Buffer::new("# Title\n\ntext\n");
    let s = Syntax::new(LanguageId::Markdown, md.rope());
    assert_eq!(kind_at(&s, &md, "Title"), HighlightKind::Heading);
}

#[test]
fn line_spans_use_utf16_columns() {
    // The emoji takes 2 UTF-16 units, so the string after it starts at col 9.
    let buf = Buffer::new("let 😀 = 1;\nlet s = \"é\";\n");
    let syn = Syntax::new(LanguageId::Rust, buf.rope());
    let lines = syn.line_spans(&buf, 0..2);
    assert_eq!(lines.len(), 2);
    let num = lines[0]
        .iter()
        .find(|s| s.kind == HighlightKind::Constant)
        .unwrap();
    assert_eq!((num.start_col, num.end_col), (9, 10));
    // The string's quotes are nested punctuation scopes, same class.
    let string: Vec<_> = lines[1]
        .iter()
        .filter(|s| s.kind == HighlightKind::String)
        .collect();
    assert_eq!((string[0].start_col, string.last().unwrap().end_col), (8, 11));
    assert_eq!(string.len(), 3);
    // Clamped past the end.
    assert_eq!(syn.line_spans(&buf, 2..50).len(), 1);
    assert!(syn.line_spans(&buf, 10..20).is_empty());
}

#[test]
fn multiline_comment_is_split_per_line() {
    let buf = Buffer::new("/* a\nbb\nc */ x\n");
    let syn = Syntax::new(LanguageId::Rust, buf.rope());
    let lines = syn.line_spans(&buf, 0..3);
    let comment = |l: &Vec<LineSpan>| {
        l.iter()
            .filter(|s| s.kind == HighlightKind::Comment)
            .map(|s| (s.start_col, s.end_col))
            .collect::<Vec<_>>()
    };
    assert_eq!(comment(&lines[0]), vec![(0, 4)]);
    assert_eq!(comment(&lines[1]), vec![(0, 2)]);
    assert_eq!(comment(&lines[2]), vec![(0, 4)]);
}

#[test]
fn incremental_update_matches_full_parse() {
    let mut buf = Buffer::new("fn a() {}\n");
    let mut syn = Syntax::new(LanguageId::Rust, buf.rope());
    let edits = buf
        .apply(vec![Edit::insert(9, "\n// note\nstruct S;")], EditKind::Other)
        .unwrap();
    syn.update(buf.rope(), &edits);
    let edits = buf
        .apply(vec![Edit::new(3..4, "renamed")], EditKind::Other)
        .unwrap();
    syn.update(buf.rope(), &edits);
    assert_eq!(buf.text(), "fn renamed() {}\n// note\nstruct S;\n");

    let fresh = Syntax::new(LanguageId::Rust, buf.rope());
    assert_eq!(
        syn.tree().unwrap().root_node().to_sexp(),
        fresh.tree().unwrap().root_node().to_sexp()
    );
    assert_eq!(kind_at(&syn, &buf, "// note"), HighlightKind::Comment);
    assert_eq!(kind_at(&syn, &buf, "renamed"), HighlightKind::Function);

    // Undo goes through the same path.
    let edits = buf.undo().unwrap();
    syn.update(buf.rope(), &edits);
    assert_eq!(kind_at(&syn, &buf, "a()"), HighlightKind::Function);
}

#[test]
fn capture_name_mapping() {
    use HighlightKind::*;
    for (name, kind) in [
        ("keyword", Keyword),
        ("function.method", Function),
        ("function.builtin", Builtin),
        ("type.builtin", Builtin),
        ("string.special.key", Property),
        ("string.escape", Escape),
        ("text.title", Heading),
        ("punctuation.bracket", Punctuation),
        ("embedded", Plain),
        ("none", Plain),
    ] {
        assert_eq!(HighlightKind::from_capture_name(name), kind, "{name}");
    }
}

#[test]
fn brackets_skip_strings_and_comments() {
    let buf = Buffer::new("fn f() {\n    g(\"(\", [1]); // )\n}\n");
    let mut syn = Syntax::new(LanguageId::Rust, buf.rope());
    let b = syn.line_brackets(&buf, 0..3);
    // Line 0: ( ) at depth 0, { opens at depth 0.
    assert_eq!(b[0], vec![(4, 0), (5, 0), (7, 0)]);
    // Line 1: g( at depth 1, [ at depth 2, ] back to 2, ) back to 1 — the "(" in the
    // string and the ) in the comment are ignored.
    assert_eq!(b[1], vec![(5, 1), (11, 2), (13, 2), (14, 1)]);
    assert_eq!(b[2], vec![(0, 0)]);
    // A later range reuses the cache and agrees.
    assert_eq!(syn.line_brackets(&buf, 2..3), vec![vec![(0, 0)]]);
}

#[test]
fn parse_budget_degrades_gracefully() {
    // A large document with a zero budget: no tree, no panic, plain text.
    let text = "fn a() { let x = [1, 2, 3]; }\n".repeat(20_000);
    let buf = Buffer::new(&text);
    let syn = Syntax::with_budget(LanguageId::Rust, buf.rope(), std::time::Duration::ZERO);
    assert!(syn.tree().is_none());
    assert!(syn.is_stale());
    assert!(syn.line_spans(&buf, 0..2).iter().all(|l| l.is_empty()));

    // An edit whose reparse runs out of time keeps the edited old tree.
    // (Opening a string at the top forces a reparse of the whole document.)
    let mut buf = Buffer::new(&text);
    let big = std::time::Duration::from_secs(30);
    let mut syn = Syntax::with_budget(LanguageId::Rust, buf.rope(), big);
    assert!(!syn.is_stale());
    let edits = buf
        .apply(
            vec![ferride_buffer::Edit::insert(0, "\"")],
            ferride_buffer::EditKind::Other,
        )
        .unwrap();
    syn.update_with_budget(buf.rope(), &edits, std::time::Duration::ZERO);
    assert!(syn.is_stale());
    assert!(syn.tree().is_some());
    // The next edit with enough budget catches up.
    let edits = buf
        .apply(
            vec![ferride_buffer::Edit::insert(0, " ")],
            ferride_buffer::EditKind::Other,
        )
        .unwrap();
    syn.update_with_budget(buf.rope(), &edits, big);
    assert!(!syn.is_stale());
}

#[test]
fn markdown_inline_is_injected_into_paragraphs_and_headings() {
    let buf = Buffer::new("# The *big* title\n\nSome **bold**, `code` and [a link](https://x.dev).\n");
    let syn = Syntax::new(LanguageId::Markdown, buf.rope());
    assert_eq!(kind_at(&syn, &buf, "The"), HighlightKind::Heading);
    assert_eq!(kind_at(&syn, &buf, "big"), HighlightKind::Emphasis);
    assert_eq!(kind_at(&syn, &buf, "bold"), HighlightKind::Emphasis);
    assert_eq!(kind_at(&syn, &buf, "code"), HighlightKind::String);
    assert_eq!(kind_at(&syn, &buf, "a link"), HighlightKind::Link);
    assert_eq!(kind_at(&syn, &buf, "https"), HighlightKind::Link);
    assert_eq!(kind_at(&syn, &buf, "Some"), HighlightKind::Plain);
}

#[test]
fn fenced_code_uses_the_info_string_language() {
    let text =
        "```py\ndef f(): return 'a'\n```\n\n> ```rs\n> let x = 1;\n> ```\n\n```nosuchlang\nlet y\n```\n";
    let buf = Buffer::new(text);
    let syn = Syntax::new(LanguageId::Markdown, buf.rope());
    assert_eq!(kind_at(&syn, &buf, "def"), HighlightKind::Keyword);
    assert_eq!(kind_at(&syn, &buf, "'a'"), HighlightKind::String);
    // Plain embedded tokens don't keep the block's literal color.
    assert_eq!(kind_at(&syn, &buf, "f()"), HighlightKind::Function);
    // Inside a block quote the `> ` markers are cut out of the Rust source.
    assert_eq!(kind_at(&syn, &buf, "let x"), HighlightKind::Keyword);
    assert_eq!(kind_at(&syn, &buf, "1;"), HighlightKind::Constant);
    // Unknown language: the content is plain (upstream's `@none`), fences are punctuation.
    assert_eq!(kind_at(&syn, &buf, "let y"), HighlightKind::Plain);
    assert_eq!(kind_at(&syn, &buf, "```nosuchlang"), HighlightKind::Punctuation);
}

#[test]
fn html_script_style_and_nested_templates() {
    let text = "<style>a { color: red; }</style>\n<script>\nconst q = sql`SELECT 1`;\n</script>\n";
    let buf = Buffer::new(text);
    let syn = Syntax::new(LanguageId::Html, buf.rope());
    assert_eq!(kind_at(&syn, &buf, "color"), HighlightKind::Property);
    assert_eq!(kind_at(&syn, &buf, "const"), HighlightKind::Keyword);
    // HTML → JavaScript → SQL (depth 2).
    assert_eq!(kind_at(&syn, &buf, "SELECT"), HighlightKind::Keyword);
    assert_eq!(kind_at(&syn, &buf, "style"), HighlightKind::Tag);
}

#[test]
fn injections_follow_edits() {
    let mut buf = Buffer::new("Text\n\n```rust\nlet a = 1;\n```\n");
    let mut syn = Syntax::new(LanguageId::Markdown, buf.rope());
    assert_eq!(kind_at(&syn, &buf, "let"), HighlightKind::Keyword);
    // Change the fence language: the cached Rust tree must not be reused.
    let at = buf.text().find("rust").unwrap();
    let edits = buf
        .apply(vec![Edit::new(at..at + 4, "python")], EditKind::Other)
        .unwrap();
    syn.update(buf.rope(), &edits);
    assert_eq!(kind_at(&syn, &buf, "let"), HighlightKind::Variable);
    // Emphasis typed into the paragraph shows up.
    let edits = buf
        .apply(vec![Edit::new(0..4, "*Text*")], EditKind::Other)
        .unwrap();
    syn.update(buf.rope(), &edits);
    assert_eq!(kind_at(&syn, &buf, "Text"), HighlightKind::Emphasis);
}

#[test]
fn injection_language_names() {
    use LanguageId as L;
    for (name, lang) in [
        ("rust", Some(L::Rust)),
        ("rs", Some(L::Rust)),
        ("sh", Some(L::Bash)),
        ("Shell", Some(L::Bash)),
        ("c++", Some(L::Cpp)),
        ("yml", Some(L::Yaml)),
        ("postgresql", Some(L::Sql)),
        ("html", Some(L::Html)),
        ("markdown_inline", Some(L::MarkdownInline)),
        ("regex", None),
        ("jsdoc", None),
    ] {
        assert_eq!(L::from_injection_name(name), lang, "{name}");
    }
    // Injection-only grammars aren't user-selectable languages.
    assert!(!L::ALL.contains(&L::MarkdownInline));
    assert_eq!(L::from_name("markdown_inline"), None);
}

#[test]
fn injections_are_parsed_for_the_viewport_only() {
    // 10k paragraphs; highlighting 40 lines parses only the visible ones.
    let text = "Some *text* here.\n\n".repeat(10_000);
    let buf = Buffer::new(&text);
    let syn = Syntax::new(LanguageId::Markdown, buf.rope());
    let lines = syn.line_spans(&buf, 5_000..5_040);
    assert!(lines[0].iter().any(|s| s.kind == HighlightKind::Emphasis));
    assert!(
        syn.injections.borrow().len() <= 21,
        "{:?}",
        syn.injections.borrow()
    );
}

#[test]
fn background_snapshot_recovers_stale_highlights_and_supports_cancel() {
    use std::sync::atomic::AtomicBool;

    // 20k lines so zero-budget synchronous parse times out; a matching `"` at
    // line 200 turns lines 1..199 into a multi-line string once reparsed.
    let mut text = "fn a() { let x = [1, 2, 3]; }\n".repeat(20_000);
    let close_at = "fn a() { let x = [1, 2, 3]; }\n".len() * 200;
    text.insert(close_at, '"');
    let mut buf = Buffer::new(&text);
    let mut syn = Syntax::with_budget(LanguageId::Rust, buf.rope(), std::time::Duration::from_secs(30));
    assert!(!syn.is_stale());

    let edits = buf.apply(vec![Edit::insert(0, "\"")], EditKind::Other).unwrap();
    syn.update_with_budget(buf.rope(), &edits, Duration::ZERO);
    assert!(syn.is_stale());
    // Stale tree still highlights line 100 as code (`fn` is Keyword).
    let stale_spans = syn.line_spans(&buf, 100..101);
    assert!(
        stale_spans[0].iter().any(|s| s.kind == HighlightKind::Keyword),
        "stale tree should still hold pre-edit highlights: {:?}",
        stale_spans[0]
    );

    // Clone an O(1) snapshot and parse it on a worker thread.
    let snap = syn.snapshot(buf.rope());
    let cancel = AtomicBool::new(false);
    let tree = std::thread::spawn(move || snap.parse(&cancel))
        .join()
        .unwrap()
        .expect("uncancelled background parse should complete");

    let gen_before = syn.generation();
    syn.apply_background_tree(tree, &[]);
    assert!(!syn.is_stale());
    assert_eq!(syn.generation(), gen_before + 1);

    // Line 100 is now inside the multi-line string literal.
    let fresh_spans = syn.line_spans(&buf, 100..101);
    assert!(
        fresh_spans[0].iter().any(|s| s.kind == HighlightKind::String),
        "background parse should update line 100 to String: {:?}",
        fresh_spans[0]
    );
    assert!(
        !fresh_spans[0].iter().any(|s| s.kind == HighlightKind::Keyword),
        "line 100 should no longer be highlighted as Keyword: {:?}",
        fresh_spans[0]
    );

    // Cancelled snapshot aborts early and returns None.
    let snap = syn.snapshot(buf.rope());
    let cancel = AtomicBool::new(true);
    assert!(snap.parse(&cancel).is_none());
}
