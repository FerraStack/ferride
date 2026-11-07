//! Drives `LspClient` against an in-process mock language server connected
//! through a Unix socketpair (the same kind of fd Ferrux will hand over).
#![cfg(unix)]

use std::io::BufReader;
use std::os::unix::net::UnixStream;
use std::sync::mpsc;
use std::thread;
use std::time::Duration;

use ferride_lsp::lsp_types::{DiagnosticSeverity, Position};
use ferride_lsp::{LspClient, LspError, LspEvent, codec, file_uri};
use serde_json::{Value, json};

/// A scripted server. Records every message it receives.
fn spawn_mock(stream: UnixStream) -> thread::JoinHandle<Vec<Value>> {
    thread::spawn(move || {
        let mut reader = BufReader::new(stream.try_clone().unwrap());
        let mut writer = stream;
        let mut seen = Vec::new();
        let send =
            |w: &mut UnixStream, v: Value| codec::write_message(w, &serde_json::to_vec(&v).unwrap()).unwrap();
        while let Some(body) = codec::read_message(&mut reader).unwrap() {
            let msg: Value = serde_json::from_slice(&body).unwrap();
            seen.push(msg.clone());
            let id = msg.get("id").cloned();
            match msg.get("method").and_then(Value::as_str) {
                Some("initialize") => send(
                    &mut writer,
                    json!({"jsonrpc": "2.0", "id": id, "result": {
                        "capabilities": {
                            "textDocumentSync": 2,
                            "completionProvider": {"triggerCharacters": ["."]},
                            "hoverProvider": true
                        },
                        "serverInfo": {"name": "mock", "version": "1"}
                    }}),
                ),
                Some("textDocument/didOpen") => {
                    let uri = msg["params"]["textDocument"]["uri"].clone();
                    // A server→client request the client must answer.
                    send(
                        &mut writer,
                        json!({"jsonrpc": "2.0", "id": "cfg-1", "method": "workspace/configuration",
                               "params": {"items": [{"section": "mock"}, {"section": "other"}]}}),
                    );
                    send(
                        &mut writer,
                        json!({"jsonrpc": "2.0", "method": "window/logMessage",
                               "params": {"type": 3, "message": "opened"}}),
                    );
                    send(
                        &mut writer,
                        json!({"jsonrpc": "2.0", "method": "textDocument/publishDiagnostics",
                        "params": {"uri": uri, "version": 1, "diagnostics": [{
                            "range": {"start": {"line": 0, "character": 3},
                                      "end": {"line": 0, "character": 7}},
                            "severity": 1,
                            "message": "unknown name"
                        }]}}),
                    );
                }
                Some("textDocument/completion") => {
                    let line = msg["params"]["position"]["line"].as_u64().unwrap();
                    send(
                        &mut writer,
                        json!({"jsonrpc": "2.0", "id": id, "result": {
                            "isIncomplete": false,
                            "items": [{"label": format!("item{line}")}, {"label": "println!"}]
                        }}),
                    );
                }
                Some("textDocument/hover") => send(
                    &mut writer,
                    json!({"jsonrpc": "2.0", "id": id, "result": {
                        "contents": {"kind": "markdown", "value": "**fn** main()"}
                    }}),
                ),
                Some("broken") => send(
                    &mut writer,
                    json!({"jsonrpc": "2.0", "id": id, "error": {"code": -32603, "message": "boom"}}),
                ),
                Some("never") => {} // no reply → client times out
                Some("shutdown") => send(&mut writer, json!({"jsonrpc": "2.0", "id": id, "result": null})),
                Some("exit") => break,
                _ => {}
            }
        }
        seen
    })
}

#[test]
fn full_session_against_mock_server() {
    let (client_end, server_end) = UnixStream::pair().unwrap();
    let server = spawn_mock(server_end);
    let (ev_tx, ev_rx) = mpsc::channel();
    let client = LspClient::start(client_end.try_clone().unwrap(), client_end, move |e| {
        let _ = ev_tx.send(e);
    });
    client.set_timeout(Duration::from_secs(5));

    let root = file_uri("/work/proj").unwrap();
    let init = client.initialize(Some(root), "ferride-test").unwrap();
    assert_eq!(init.server_info.unwrap().name, "mock");
    assert!(client.server_capabilities().unwrap().hover_provider.is_some());

    let uri = file_uri("/work/proj/main.rs").unwrap();
    client
        .did_open(uri.clone(), "rust", 1, "fn main() {}".into())
        .unwrap();

    // Events arrive in order: log, then diagnostics.
    let log = ev_rx.recv_timeout(Duration::from_secs(5)).unwrap();
    assert!(matches!(log, LspEvent::LogMessage { ref message, .. } if message == "opened"));
    match ev_rx.recv_timeout(Duration::from_secs(5)).unwrap() {
        LspEvent::Diagnostics {
            uri: u,
            version,
            diagnostics,
        } => {
            assert_eq!(u, uri);
            assert_eq!(version, Some(1));
            assert_eq!(diagnostics.len(), 1);
            assert_eq!(diagnostics[0].severity, Some(DiagnosticSeverity::ERROR));
            assert_eq!(diagnostics[0].range.start, Position::new(0, 3));
        }
        other => panic!("expected diagnostics, got {other:?}"),
    }

    client
        .did_change_full(uri.clone(), 2, "fn main() { x }".into())
        .unwrap();

    let items = client.completion(uri.clone(), Position::new(4, 2)).unwrap();
    let labels: Vec<_> = items.iter().map(|i| i.label.as_str()).collect();
    assert_eq!(labels, ["item4", "println!"]);

    let hover = client.hover(uri.clone(), Position::new(0, 4)).unwrap();
    assert_eq!(hover.as_deref(), Some("**fn** main()"));

    match client.request_raw("broken", Value::Null) {
        Err(LspError::Response { code, message }) => {
            assert_eq!(code, -32603);
            assert_eq!(message, "boom");
        }
        other => panic!("expected error response, got {other:?}"),
    }

    client.set_timeout(Duration::from_millis(100));
    assert!(matches!(
        client.request_raw("never", Value::Null),
        Err(LspError::Timeout)
    ));
    client.set_timeout(Duration::from_secs(5));

    client.shutdown().unwrap();
    let seen = server.join().unwrap();

    // The client answered the server's configuration request with one entry per item.
    let cfg_reply = seen
        .iter()
        .find(|m| m.get("id") == Some(&json!("cfg-1")))
        .expect("client answered workspace/configuration");
    assert_eq!(cfg_reply["result"], json!([null, null]));

    // Text sync and capability negotiation went over the wire as expected.
    let methods: Vec<&str> = seen.iter().filter_map(|m| m["method"].as_str()).collect();
    assert_eq!(
        methods,
        [
            "initialize",
            "initialized",
            "textDocument/didOpen",
            "textDocument/didChange",
            "textDocument/completion",
            "textDocument/hover",
            "broken",
            "never",
            "$/cancelRequest",
            "shutdown",
            "exit"
        ]
    );
    let init_params = &seen[0]["params"];
    assert_eq!(
        init_params["capabilities"]["general"]["positionEncodings"],
        json!(["utf-16"])
    );
    assert_eq!(init_params["workspaceFolders"][0]["name"], json!("proj"));
    let did_change = seen
        .iter()
        .find(|m| m["method"] == "textDocument/didChange")
        .unwrap();
    assert_eq!(
        did_change["params"]["contentChanges"][0]["text"],
        json!("fn main() { x }")
    );
    assert_eq!(did_change["params"]["textDocument"]["version"], json!(2));

    // Server hung up → Closed event, and requests fail fast.
    let closed = ev_rx.iter().find(|e| matches!(e, LspEvent::Closed)).is_some();
    assert!(closed);
    assert!(client.is_closed());
    assert!(matches!(
        client.request_raw("x", Value::Null),
        Err(LspError::Closed)
    ));
}

#[test]
fn pending_request_fails_when_server_dies() {
    let (client_end, server_end) = UnixStream::pair().unwrap();
    let client = LspClient::start(client_end.try_clone().unwrap(), client_end, |_| {});
    let killer = thread::spawn(move || {
        // Read the request, then die without answering.
        let mut r = BufReader::new(server_end.try_clone().unwrap());
        codec::read_message(&mut r).unwrap();
        server_end.shutdown(std::net::Shutdown::Both).unwrap();
    });
    let res = client.request_raw("initialize", json!({}));
    killer.join().unwrap();
    assert!(matches!(res, Err(LspError::Closed)), "{res:?}");
}
