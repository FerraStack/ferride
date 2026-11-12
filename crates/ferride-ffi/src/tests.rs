use std::sync::Mutex;

use super::*;

#[test]
fn ime_style_editing_with_utf16_offsets() {
    // "a😀" : the emoji is 2 UTF-16 units, so the end is offset 3.
    let doc = Document::new("a😀".into(), Some("notes.txt".into()));
    assert_eq!(doc.language(), None);
    assert_eq!(doc.len_utf16(), 3);
    doc.set_selection(3, 3);

    // Composition: setComposingText("h") → ("hé") → commit("hé ")
    let o = doc.replace(3, 3, "h".into(), true).unwrap();
    assert!(o.changed);
    assert_eq!(o.selection, SelectionRange { anchor: 4, head: 4 });
    doc.replace(3, 4, "hé".into(), true).unwrap();
    let o = doc.replace(3, 5, "hé ".into(), true).unwrap();
    assert_eq!(doc.text(), "a😀hé ");
    assert_eq!(o.selection.head, 6);

    // An offset in the middle of the surrogate pair rounds down to the emoji.
    assert_eq!(doc.text_range(2, 3), "😀");
    assert_eq!(doc.text_range(0, 1), "a");

    // The composition is one undo step.
    let o = doc.undo();
    assert!(o.changed);
    assert_eq!(doc.text(), "a😀");
    assert!(!doc.undo().changed);
    doc.redo();
    assert_eq!(doc.text(), "a😀hé ");
}

#[test]
fn viewport_has_highlight_spans() {
    let doc = Document::new("fn main() {\n    let x = 1;\n}\n".into(), Some("main.rs".into()));
    assert_eq!(doc.language().as_deref(), Some("rust"));
    let lines = doc.viewport(0, 100);
    assert_eq!(lines.len(), 4);
    assert_eq!(lines[1].text, "    let x = 1;");
    assert_eq!(lines[1].start_offset, 12);
    let fn_kw = lines[0].spans.iter().find(|s| s.start == 0).unwrap();
    assert_eq!((fn_kw.end, fn_kw.kind), (2, StyleKind::Keyword));
    assert_eq!(scope_stack(fn_kw.scope), ["source.rust", "keyword.other.rust"]);
    assert!(
        lines[1]
            .spans
            .iter()
            .any(|s| s.kind == StyleKind::Keyword && s.start == 4)
    );

    // Editing reparses: turning `main` into a comment changes its style.
    doc.replace(0, 0, "// ".into(), false).unwrap();
    let lines = doc.viewport(0, 1);
    let spans: Vec<_> = lines[0].spans.iter().map(|s| (s.start, s.end, s.kind)).collect();
    assert_eq!(spans, vec![(0, 14, StyleKind::Comment)]);
    assert!(doc.viewport(50, 10).is_empty());
}

#[test]
fn newline_keeps_indent_and_multi_cursor() {
    let doc = Document::new("    foo\nbar".into(), None);
    doc.set_selection(7, 7);
    doc.newline();
    assert_eq!(doc.text(), "    foo\n    \nbar");
    assert_eq!(doc.selection().head, 12);

    let doc = Document::new("a\nb".into(), None);
    doc.set_selection(1, 1);
    doc.add_selection(3, 3);
    assert_eq!(doc.selections().len(), 2);
    doc.insert("!".into(), true);
    assert_eq!(doc.text(), "a!\nb!");
    doc.backspace();
    assert_eq!(doc.text(), "a\nb");
}

#[test]
fn offsets_and_words() {
    let doc = Document::new("let héllo_1 = 2;\nnext".into(), None);
    assert_eq!(doc.line_count(), 2);
    assert_eq!(doc.line_start_offset(1), 17);
    assert_eq!(doc.line_end_offset(0), 16);
    assert_eq!(doc.line_of_offset(17), 1);
    assert_eq!(doc.offset_at(1, 99), 21);
    assert_eq!(doc.word_at(6), SelectionRange { anchor: 4, head: 11 });
    let w = doc.word_at(12);
    assert_eq!(w.anchor, w.head, "no word at '='");
    assert!(doc.replace(5, 100, "x".into(), false).is_ok(), "clamped");
}

#[test]
fn workspace_round_trip() {
    let dir = tempfile::tempdir().unwrap();
    std::fs::write(dir.path().join("a.rs"), "fn alpha() {}\n").unwrap();
    let ws = WorkspaceHandle::open(dir.path().to_string_lossy().into_owned()).unwrap();
    let root = ws.root();
    let nodes = ws.list_dir(root.clone()).unwrap();
    assert_eq!(nodes.len(), 1);
    assert_eq!(nodes[0].rel_path, "a.rs");
    ws.write_file(format!("{root}/b.py"), "alpha = 1\n".into())
        .unwrap();
    assert_eq!(ws.read_file(format!("{root}/b.py")).unwrap(), "alpha = 1\n");
    let r = ws
        .search_text("alpha".into(), false, false, false, 100, None)
        .unwrap();
    assert!(!r.cancelled);
    let cancel = crate::SearchCancellation::new();
    cancel.cancel();
    assert!(cancel.is_cancelled());
    let stopped = ws
        .search_text("alpha".into(), false, false, false, 100, Some(cancel))
        .unwrap();
    assert!(stopped.cancelled && stopped.hits.is_empty());
    assert_eq!(r.hits.len(), 2);
    assert_eq!(ws.find_files("bpy".into(), 5)[0].rel_path, "b.py");
    assert!(matches!(
        ws.read_file("/etc/hostname".into()),
        Err(FerrideError::Workspace { .. })
    ));
    assert!(WorkspaceHandle::open("/definitely/not/here".into()).is_err());
}

#[test]
fn host_stub_and_languages() {
    assert_eq!(ferrux_status(), FerruxStatus::NotInstalled);
    assert!(supported_languages().contains(&"typescript".to_owned()));
    assert_eq!(language_for_file("x.py".into(), None).as_deref(), Some("python"));
    assert_eq!(language_for_file("a.h".into(), None).as_deref(), Some("cpp"));
    assert_eq!(
        language_for_file("deploy".into(), Some("#!/bin/sh".into())).as_deref(),
        Some("shellscript")
    );
    assert_eq!(language_for_file("deploy".into(), None), None);
    assert_eq!(supported_languages().len(), 18);
    assert!(!core_version().is_empty());
}

#[test]
fn change_language_mode_and_auto_detect() {
    // No extension: the shebang picks the grammar.
    let doc = Document::new(
        "#!/usr/bin/env bash
echo \"hi\"\n"
            .into(),
        Some("deploy".into()),
    );
    assert_eq!(doc.language().as_deref(), Some("shellscript"));
    let kinds =
        |doc: &Document| -> Vec<StyleKind> { doc.viewport(1, 1)[0].spans.iter().map(|s| s.kind).collect() };
    assert!(kinds(&doc).contains(&StyleKind::String));

    // Change Language Mode → Plain Text drops the highlighting…
    doc.set_language(None);
    assert_eq!(doc.language(), None);
    assert!(kinds(&doc).is_empty());
    // …an explicit pick sticks (unknown ids mean plain text)…
    doc.set_language(Some("python".into()));
    assert_eq!(doc.language().as_deref(), Some("python"));
    doc.set_language(Some("cobol".into()));
    assert_eq!(doc.language(), None);
    // …and Auto Detect goes back to the file name / first line.
    assert_eq!(doc.detect_language().as_deref(), Some("shellscript"));
    assert_eq!(doc.language().as_deref(), Some("shellscript"));
    assert!(kinds(&doc).contains(&StyleKind::String));

    // Edits after detection keep the grammar (it's not re-detected while typing).
    doc.replace(0, 0, "x".into(), false).unwrap();
    assert_eq!(doc.language().as_deref(), Some("shellscript"));
}

#[derive(Default)]
struct Recorder {
    events: Mutex<Vec<String>>,
}

impl LspListener for std::sync::Arc<Recorder> {
    fn on_diagnostics(&self, path: String, _version: Option<i32>, diagnostics: Vec<LspDiagnostic>) {
        self.events
            .lock()
            .unwrap()
            .push(format!("diag {path} {}", diagnostics.len()));
    }

    fn on_message(&self, level: i32, message: String, show: bool) {
        self.events
            .lock()
            .unwrap()
            .push(format!("msg {level} {message} {show}"));
    }

    fn on_closed(&self) {
        self.events.lock().unwrap().push("closed".into());
    }
}

#[cfg(unix)]
#[test]
fn lsp_session_over_socket_fd() {
    use std::io::BufReader;
    use std::os::fd::IntoRawFd;
    use std::os::unix::net::UnixStream;

    use ferride_lsp::codec;
    use serde_json::{Value, json};

    let (client_end, server_end) = UnixStream::pair().unwrap();
    let server = std::thread::spawn(move || {
        let mut r = BufReader::new(server_end.try_clone().unwrap());
        let mut w = server_end;
        let mut send = |v: Value| codec::write_message(&mut w, &serde_json::to_vec(&v).unwrap()).unwrap();
        while let Some(body) = codec::read_message(&mut r).unwrap() {
            let m: Value = serde_json::from_slice(&body).unwrap();
            match m["method"].as_str() {
                Some("initialize") => send(json!({"jsonrpc":"2.0","id":m["id"],"result":{
                    "capabilities":{}, "serverInfo":{"name":"mock-ls"}}})),
                Some("textDocument/didOpen") => {
                    send(
                        json!({"jsonrpc":"2.0","method":"window/showMessage","params":{"type":2,"message":"hi"}}),
                    );
                    send(
                        json!({"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{
                        "uri": m["params"]["textDocument"]["uri"], "diagnostics":[{
                            "range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},
                            "message":"bad"}]}}),
                    );
                }
                Some("textDocument/completion") => send(json!({"jsonrpc":"2.0","id":m["id"],
                    "result":[{"label":"foo","detail":"fn foo()","insertText":"foo()"}]})),
                Some("textDocument/hover") => send(json!({"jsonrpc":"2.0","id":m["id"],"result":null})),
                Some("shutdown") => send(json!({"jsonrpc":"2.0","id":m["id"],"result":null})),
                Some("exit") => break,
                _ => {}
            }
        }
    });

    let rec = std::sync::Arc::new(Recorder::default());
    let fd = client_end.into_raw_fd();
    let session = LspSession::connect_fds(fd, fd, Box::new(rec.clone())).unwrap();
    assert_eq!(session.initialize(Some("/w s".into())).unwrap(), "mock-ls");
    session
        .did_open("/w s/a.rs".into(), "rust".into(), 1, "x".into())
        .unwrap();
    let items = session.completion("/w s/a.rs".into(), 0, 1).unwrap();
    assert_eq!(
        items,
        vec![LspCompletion {
            label: "foo".into(),
            detail: Some("fn foo()".into()),
            insert_text: "foo()".into()
        }]
    );
    assert_eq!(session.hover("/w s/a.rs".into(), 0, 0).unwrap(), None);
    session.shutdown().unwrap();
    server.join().unwrap();
    // The reader thread marks the session closed and then calls on_closed;
    // wait for the callback itself, not just the flag (flaked on CI).
    for _ in 0..500 {
        if session.is_closed() && rec.events.lock().unwrap().iter().any(|e| e == "closed") {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    let events = rec.events.lock().unwrap().clone();
    assert_eq!(events, ["msg 2 hi true", "diag /w s/a.rs 1", "closed"]);
    assert!(LspSession::connect_fds(-1, -1, Box::new(rec.clone())).is_err());
}

#[test]
fn crlf_is_never_split_and_eol_is_normalized() {
    let doc = Document::new("ab\r\ncd".into(), None);
    // IME deleteSurroundingText(1, 0) at the start of line 2 targets just the \n.
    doc.replace(3, 4, "".into(), true).unwrap();
    assert_eq!(doc.text(), "abcd");
    // A caret between \r and \n snaps before the \r.
    let doc = Document::new("ab\r\ncd".into(), None);
    doc.set_selection(3, 3);
    assert_eq!(doc.selection().head, 2);
    // Inserted line breaks follow the file's EOL; `end` reflects that.
    let o = doc.replace(0, 0, "x\ny".into(), false).unwrap();
    assert_eq!(doc.text(), "x\r\nyab\r\ncd");
    assert_eq!(o.end, 4);
    doc.set_selection(0, 0);
    doc.insert("1\r2\n".into(), false);
    assert!(doc.text().starts_with("1\r\n2\r\nx"));
}

#[test]
fn viewport_reports_syntactic_brackets() {
    let doc = Document::new("fn f() { let s = \"(\"; }\n".into(), Some("a.rs".into()));
    let lines = doc.viewport(0, 1);
    let cols: Vec<(i32, i32)> = lines[0].brackets.iter().map(|b| (b.column, b.depth)).collect();
    assert_eq!(cols, vec![(4, 0), (5, 0), (7, 0), (22, 0)]);
}

#[test]
fn mark_saved_version_keeps_later_edits_dirty() {
    let doc = Document::new("".into(), None);
    doc.insert("a".into(), false);
    let v = doc.version();
    doc.insert("b".into(), false);
    doc.mark_saved_version(v);
    assert!(doc.is_dirty());
}

#[test]
fn grapheme_navigation_in_utf16() {
    // "a" + family (8 UTF-16 units: three surrogate pairs + two ZWJs) + "b".
    let doc = Document::new("a👨\u{200D}👩\u{200D}👧b".into(), None);
    assert_eq!(doc.grapheme_after(1), 9);
    assert_eq!(doc.grapheme_before(9), 1);
    // From the middle of the sequence (even mid surrogate pair) both ways leave it.
    assert_eq!(doc.grapheme_after(4), 9);
    assert_eq!(doc.grapheme_before(4), 1);
    assert_eq!(doc.grapheme_before(0), 0);
    assert_eq!(doc.grapheme_after(10), 10);
    doc.set_selection(9, 9);
    doc.backspace();
    assert_eq!(doc.text(), "ab");
}

#[test]
fn token_theme_styles_span_scopes() {
    let theme = TokenTheme::new(
        r##"{ "type": "dark", "colors": { "editor.foreground": "#cccccc" },
             "tokenColors": [
               { "scope": "keyword", "settings": { "foreground": "#ff79c6" } },
               { "scope": "comment", "settings": { "foreground": "#6272a4", "fontStyle": "italic" } },
             ] }"##
            .into(),
    )
    .unwrap();
    let doc = Document::new("fn main() {} // hi\n".into(), Some("main.rs".into()));
    let line = &doc.viewport(0, 1)[0];
    let style = |kind| theme.style(line.spans.iter().find(|s| s.kind == kind).unwrap().scope);
    assert_eq!(
        style(StyleKind::Keyword),
        TokenStyle {
            foreground: 0xFFFF79C6,
            font_style: 0
        }
    );
    assert_eq!(
        style(StyleKind::Comment),
        TokenStyle {
            foreground: 0xFF6272A4,
            font_style: 1
        }
    );
    // Unknown ids fall back to the default foreground.
    assert_eq!(theme.style(0).foreground, 0xFFCCCCCC);
    assert!(TokenTheme::new("not json".into()).is_err());
}

#[test]
fn scm_init_stage_commit_log_and_diff() {
    let tmp = tempfile::tempdir().unwrap();
    let root = tmp.path().join("p");
    let repo = scm_init(root.to_string_lossy().into_owned()).unwrap();
    std::fs::write(root.join("a.txt"), "one\ntwo\n").unwrap();
    let status = repo.status().unwrap();
    assert_eq!(status.len(), 1);
    assert_eq!(
        (
            status[0].group,
            status[0].letter.as_str(),
            status[0].tooltip.as_str()
        ),
        (ScmGroup::WorkingTree, "U", "Untracked")
    );

    repo.stage(vec!["a.txt".into()]).unwrap();
    assert!(matches!(
        repo.commit("first".into(), None, None, false, false),
        Err(GitError::MissingIdentity { .. })
    ));
    let id = repo
        .commit(
            "first".into(),
            Some("Ada".into()),
            Some("ada@example.com".into()),
            false,
            false,
        )
        .unwrap();
    let head = repo.head().unwrap();
    assert_eq!(head.label, "main");
    assert_eq!(head.commit.as_deref(), Some(id.as_str()));
    let log = repo.log(None, false, 0, 10).unwrap();
    assert_eq!(log[0].summary, "first");
    assert_eq!(log[0].refs[0].kind, ScmRefKind::LocalBranch);

    std::fs::write(root.join("a.txt"), "one\nTWO\n").unwrap();
    let diff = repo.diff("a.txt".into(), false).unwrap();
    assert_eq!(diff.hunks.len(), 1);
    assert_eq!(
        diff.hunks[0].added[0].words,
        vec![ScmWordRange { start: 0, end: 3 }]
    );
    repo.stage_hunks("a.txt".into(), diff.hunks.clone()).unwrap();
    assert_eq!(repo.status().unwrap()[0].group, ScmGroup::Index);

    let found = scm_discover(root.to_string_lossy().into_owned())
        .unwrap()
        .unwrap();
    assert_eq!(found.branches().unwrap()[0].name, "main");
    assert!(
        scm_discover(tmp.path().to_string_lossy().into_owned())
            .unwrap()
            .is_none()
    );

    let changes = scm_line_changes("a\nb\n".into(), "a\nB\nc\n".into());
    assert_eq!(
        changes,
        vec![ScmLineChange {
            kind: ScmLineChangeKind::Modified,
            start: 1,
            end: 3
        }]
    );
}

#[test]
fn scm_clone_rejects_non_https_urls() {
    struct Silent;
    impl ScmNetworkListener for Silent {
        fn credentials(&self, _url: String) -> Option<ScmCredential> {
            None
        }
        fn credentials_approved(&self, _url: String) {}
        fn credentials_rejected(&self, _url: String) {}
        fn progress(&self, _phase: String, _current: i64, _total: Option<i64>) {}
    }
    let tmp = tempfile::tempdir().unwrap();
    let dest = tmp.path().join("c").to_string_lossy().into_owned();
    let err = scm_clone("file:///etc".into(), dest, Box::new(Silent), ScmCancel::new());
    assert!(matches!(err, Err(GitError::Unsupported { .. })));
}

#[test]
fn poisoned_document_lock_is_recovered_by_reparsing() {
    let doc = Document::new("fn main() {}\n".into(), Some("main.rs".into()));
    let d = doc.clone();
    let panicked = std::thread::spawn(move || d.poison_for_test()).join();
    assert!(panicked.is_err());
    // The next call recovers (reparse + clear the poison) and highlighting still works.
    assert_eq!(doc.recoveries(), 1);
    let line = &doc.viewport(0, 1)[0];
    assert!(
        line.spans.iter().any(|s| s.kind == StyleKind::Keyword),
        "{:?}",
        line.spans
    );
    doc.insert("// ok\n".into(), false);
    assert_eq!(doc.recoveries(), 1, "the poison was cleared: no second recovery");
    assert!(doc.text().starts_with("// ok"));
}

#[test]
fn document_symbols_are_flattened_with_parents() {
    let doc = Document::new(
        "struct S {\n    a: u8,\n}\n\nimpl S {\n    fn é() {}\n}\n".into(),
        Some("lib.rs".into()),
    );
    let syms = doc.symbols();
    let summary: Vec<_> = syms
        .iter()
        .map(|s| (s.name.as_str(), s.kind, s.parent, s.depth))
        .collect();
    assert_eq!(
        summary,
        vec![
            ("S", 23, -1, 0),
            ("a", 8, 0, 1),
            ("impl S", 19, -1, 0),
            ("é", 6, 2, 1)
        ]
    );
    let method = &syms[3];
    assert_eq!((method.start_line, method.end_line), (5, 5));
    assert_eq!(doc.text_range(method.name_start, method.name_end), "é");
    assert!(
        Document::new("plain".into(), Some("notes.txt".into()))
            .symbols()
            .is_empty()
    );
}
