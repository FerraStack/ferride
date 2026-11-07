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
    assert!(lines[0].spans.contains(&StyleSpan {
        start: 0,
        end: 2,
        kind: StyleKind::Keyword
    }));
    assert!(
        lines[1]
            .spans
            .iter()
            .any(|s| s.kind == StyleKind::Keyword && s.start == 4)
    );

    // Editing reparses: turning `main` into a comment changes its style.
    doc.replace(0, 0, "// ".into(), false).unwrap();
    let lines = doc.viewport(0, 1);
    assert_eq!(
        lines[0].spans,
        vec![StyleSpan {
            start: 0,
            end: 14,
            kind: StyleKind::Comment
        }]
    );
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
    let r = ws.search_text("alpha".into(), false, false, false, 100).unwrap();
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
    assert_eq!(language_for_file("x.py".into()).as_deref(), Some("python"));
    assert!(!core_version().is_empty());
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
    for _ in 0..100 {
        if session.is_closed() {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    let events = rec.events.lock().unwrap().clone();
    assert_eq!(events, ["msg 2 hi true", "diag /w s/a.rs 1", "closed"]);
    assert!(LspSession::connect_fds(-1, -1, Box::new(rec.clone())).is_err());
}
