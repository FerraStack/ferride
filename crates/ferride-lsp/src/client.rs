use std::collections::HashMap;
use std::io::{BufReader, Read, Write};
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::mpsc::{self, Sender};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use lsp_types::notification::{self as notif, Notification};
use lsp_types::request::{self as req, Request};
use lsp_types::{
    ClientCapabilities, CompletionClientCapabilities, CompletionItem, CompletionParams, CompletionResponse,
    Diagnostic, DidChangeTextDocumentParams, DidCloseTextDocumentParams, DidOpenTextDocumentParams,
    DidSaveTextDocumentParams, GeneralClientCapabilities, Hover, HoverClientCapabilities, HoverContents,
    HoverParams, InitializeParams, InitializeResult, InitializedParams, MarkedString, MarkupKind,
    MessageType, PartialResultParams, Position, PositionEncodingKind, PublishDiagnosticsClientCapabilities,
    ServerCapabilities, TextDocumentClientCapabilities, TextDocumentContentChangeEvent,
    TextDocumentIdentifier, TextDocumentItem, TextDocumentPositionParams, TextDocumentSyncClientCapabilities,
    Uri, VersionedTextDocumentIdentifier, WindowClientCapabilities, WorkDoneProgressParams, WorkspaceFolder,
};
use serde::Serialize;
use serde::de::DeserializeOwned;
use serde_json::{Value, json};

use crate::codec;

#[derive(Debug, thiserror::Error)]
pub enum LspError {
    #[error("server returned error {code}: {message}")]
    Response { code: i64, message: String },
    #[error("request timed out")]
    Timeout,
    #[error("connection to the language server is closed")]
    Closed,
    #[error("i/o error: {0}")]
    Io(#[from] std::io::Error),
    #[error("protocol error: {0}")]
    Protocol(String),
}

/// Things the server tells us without being asked.
#[derive(Debug, Clone, PartialEq)]
pub enum LspEvent {
    Diagnostics {
        uri: Uri,
        version: Option<i32>,
        diagnostics: Vec<Diagnostic>,
    },
    LogMessage {
        typ: MessageType,
        message: String,
    },
    ShowMessage {
        typ: MessageType,
        message: String,
    },
    /// Any other notification, unparsed.
    Notification {
        method: String,
        params: Value,
    },
    /// The server's output stream ended (it exited or the fd was closed).
    Closed,
}

type EventHandler = Box<dyn Fn(LspEvent) + Send + Sync>;
type Pending = Arc<Mutex<HashMap<i64, Sender<Result<Value, LspError>>>>>;
type SharedWriter = Arc<Mutex<Box<dyn Write + Send>>>;

/// A language-server client over any pair of byte streams.
///
/// The transport is deliberately just `Read` + `Write`: on a desktop it's a
/// child process' stdio, on Android it will be a socket fd handed over by
/// Ferrux's Host API. A background thread reads server messages; requests
/// block the calling thread (call them off the UI thread) until the response
/// arrives or [`LspClient::set_timeout`] elapses.
pub struct LspClient {
    writer: SharedWriter,
    pending: Pending,
    next_id: AtomicI64,
    closed: Arc<AtomicBool>,
    timeout: Mutex<Duration>,
    capabilities: Mutex<Option<ServerCapabilities>>,
}

impl std::fmt::Debug for LspClient {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("LspClient")
            .field("closed", &self.closed.load(Ordering::Relaxed))
            .finish_non_exhaustive()
    }
}

impl LspClient {
    /// Starts the reader thread. `on_event` is called on that thread.
    pub fn start(
        reader: impl Read + Send + 'static,
        writer: impl Write + Send + 'static,
        on_event: impl Fn(LspEvent) + Send + Sync + 'static,
    ) -> Self {
        let writer: SharedWriter = Arc::new(Mutex::new(Box::new(writer)));
        let pending: Pending = Arc::default();
        let closed = Arc::new(AtomicBool::new(false));
        let ctx = ReaderCtx {
            writer: writer.clone(),
            pending: pending.clone(),
            closed: closed.clone(),
            on_event: Box::new(on_event),
        };
        thread::Builder::new()
            .name("ferride-lsp-reader".into())
            .spawn(move || ctx.run(reader))
            .expect("spawn LSP reader thread");
        Self {
            writer,
            pending,
            next_id: AtomicI64::new(1),
            closed,
            timeout: Mutex::new(Duration::from_secs(30)),
            capabilities: Mutex::new(None),
        }
    }

    pub fn set_timeout(&self, timeout: Duration) {
        *self.timeout.lock().unwrap() = timeout;
    }

    pub fn is_closed(&self) -> bool {
        self.closed.load(Ordering::SeqCst)
    }

    /// Capabilities from the `initialize` response.
    pub fn server_capabilities(&self) -> Option<ServerCapabilities> {
        self.capabilities.lock().unwrap().clone()
    }

    // ----- generic plumbing ------------------------------------------------

    /// Sends a typed request and waits for its result.
    pub fn request<R: Request>(&self, params: R::Params) -> Result<R::Result, LspError>
    where
        R::Params: Serialize,
        R::Result: DeserializeOwned,
    {
        let value = self.request_raw(R::METHOD, serde_json::to_value(params).map_err(proto)?)?;
        serde_json::from_value(value).map_err(proto)
    }

    pub fn request_raw(&self, method: &str, params: Value) -> Result<Value, LspError> {
        if self.is_closed() {
            return Err(LspError::Closed);
        }
        let id = self.next_id.fetch_add(1, Ordering::SeqCst);
        let (tx, rx) = mpsc::channel();
        self.pending.lock().unwrap().insert(id, tx);
        let msg = json!({"jsonrpc": "2.0", "id": id, "method": method, "params": params});
        if let Err(e) = send(&self.writer, &msg) {
            self.pending.lock().unwrap().remove(&id);
            return Err(e);
        }
        let timeout = *self.timeout.lock().unwrap();
        match rx.recv_timeout(timeout) {
            Ok(result) => result,
            Err(mpsc::RecvTimeoutError::Timeout) => {
                self.pending.lock().unwrap().remove(&id);
                // Best effort: tell the server we gave up.
                let _ = self.notify_raw("$/cancelRequest", json!({"id": id}));
                Err(LspError::Timeout)
            }
            Err(mpsc::RecvTimeoutError::Disconnected) => Err(LspError::Closed),
        }
    }

    pub fn notify<N: Notification>(&self, params: N::Params) -> Result<(), LspError>
    where
        N::Params: Serialize,
    {
        self.notify_raw(N::METHOD, serde_json::to_value(params).map_err(proto)?)
    }

    pub fn notify_raw(&self, method: &str, params: Value) -> Result<(), LspError> {
        if self.is_closed() {
            return Err(LspError::Closed);
        }
        send(
            &self.writer,
            &json!({"jsonrpc": "2.0", "method": method, "params": params}),
        )
    }

    // ----- lifecycle -------------------------------------------------------

    /// `initialize` + `initialized`. `root` becomes the single workspace folder.
    pub fn initialize(&self, root: Option<Uri>, client_name: &str) -> Result<InitializeResult, LspError> {
        #[allow(deprecated)] // root_uri is still what many servers read.
        let params = InitializeParams {
            process_id: Some(std::process::id()),
            root_uri: root.clone(),
            workspace_folders: root.map(|uri| {
                let name = uri
                    .path()
                    .as_str()
                    .rsplit('/')
                    .next()
                    .unwrap_or("workspace")
                    .to_owned();
                vec![WorkspaceFolder { uri, name }]
            }),
            capabilities: client_capabilities(),
            client_info: Some(lsp_types::ClientInfo {
                name: client_name.to_owned(),
                version: Some(env!("CARGO_PKG_VERSION").to_owned()),
            }),
            ..Default::default()
        };
        let result = self.request::<req::Initialize>(params)?;
        *self.capabilities.lock().unwrap() = Some(result.capabilities.clone());
        self.notify::<notif::Initialized>(InitializedParams {})?;
        Ok(result)
    }

    /// `shutdown` + `exit`.
    pub fn shutdown(&self) -> Result<(), LspError> {
        self.request::<req::Shutdown>(())?;
        self.notify::<notif::Exit>(())
    }

    // ----- text synchronization ------------------------------------------

    pub fn did_open(&self, uri: Uri, language_id: &str, version: i32, text: String) -> Result<(), LspError> {
        self.notify::<notif::DidOpenTextDocument>(DidOpenTextDocumentParams {
            text_document: TextDocumentItem {
                uri,
                language_id: language_id.to_owned(),
                version,
                text,
            },
        })
    }

    /// Sends changes. Each event is either a full replacement (`range: None`)
    /// or an incremental edit with UTF-16 positions.
    pub fn did_change(
        &self,
        uri: Uri,
        version: i32,
        changes: Vec<TextDocumentContentChangeEvent>,
    ) -> Result<(), LspError> {
        self.notify::<notif::DidChangeTextDocument>(DidChangeTextDocumentParams {
            text_document: VersionedTextDocumentIdentifier { uri, version },
            content_changes: changes,
        })
    }

    pub fn did_change_full(&self, uri: Uri, version: i32, text: String) -> Result<(), LspError> {
        self.did_change(
            uri,
            version,
            vec![TextDocumentContentChangeEvent {
                range: None,
                range_length: None,
                text,
            }],
        )
    }

    pub fn did_save(&self, uri: Uri, text: Option<String>) -> Result<(), LspError> {
        self.notify::<notif::DidSaveTextDocument>(DidSaveTextDocumentParams {
            text_document: TextDocumentIdentifier { uri },
            text,
        })
    }

    pub fn did_close(&self, uri: Uri) -> Result<(), LspError> {
        self.notify::<notif::DidCloseTextDocument>(DidCloseTextDocumentParams {
            text_document: TextDocumentIdentifier { uri },
        })
    }

    // ----- language features ---------------------------------------------

    pub fn completion(&self, uri: Uri, position: Position) -> Result<Vec<CompletionItem>, LspError> {
        let resp = self.request::<req::Completion>(CompletionParams {
            text_document_position: TextDocumentPositionParams {
                text_document: TextDocumentIdentifier { uri },
                position,
            },
            work_done_progress_params: WorkDoneProgressParams::default(),
            partial_result_params: PartialResultParams::default(),
            context: None,
        })?;
        Ok(match resp {
            None => Vec::new(),
            Some(CompletionResponse::Array(items)) => items,
            Some(CompletionResponse::List(list)) => list.items,
        })
    }

    /// Hover contents flattened to Markdown/plain text.
    pub fn hover(&self, uri: Uri, position: Position) -> Result<Option<String>, LspError> {
        let resp: Option<Hover> = self.request::<req::HoverRequest>(HoverParams {
            text_document_position_params: TextDocumentPositionParams {
                text_document: TextDocumentIdentifier { uri },
                position,
            },
            work_done_progress_params: WorkDoneProgressParams::default(),
        })?;
        Ok(resp.map(|h| hover_to_text(h.contents)))
    }
}

impl Drop for LspClient {
    fn drop(&mut self) {
        self.closed.store(true, Ordering::SeqCst);
    }
}

fn hover_to_text(contents: HoverContents) -> String {
    let marked = |m: MarkedString| match m {
        MarkedString::String(s) => s,
        MarkedString::LanguageString(ls) => format!("```{}\n{}\n```", ls.language, ls.value),
    };
    match contents {
        HoverContents::Scalar(m) => marked(m),
        HoverContents::Array(v) => v.into_iter().map(marked).collect::<Vec<_>>().join("\n\n"),
        HoverContents::Markup(m) => m.value,
    }
}

fn client_capabilities() -> ClientCapabilities {
    ClientCapabilities {
        general: Some(GeneralClientCapabilities {
            position_encodings: Some(vec![PositionEncodingKind::UTF16]),
            ..Default::default()
        }),
        text_document: Some(TextDocumentClientCapabilities {
            synchronization: Some(TextDocumentSyncClientCapabilities {
                did_save: Some(true),
                ..Default::default()
            }),
            completion: Some(CompletionClientCapabilities::default()),
            hover: Some(HoverClientCapabilities {
                content_format: Some(vec![MarkupKind::Markdown, MarkupKind::PlainText]),
                ..Default::default()
            }),
            publish_diagnostics: Some(PublishDiagnosticsClientCapabilities {
                version_support: Some(true),
                ..Default::default()
            }),
            ..Default::default()
        }),
        window: Some(WindowClientCapabilities {
            work_done_progress: Some(false),
            ..Default::default()
        }),
        ..Default::default()
    }
}

fn proto(e: serde_json::Error) -> LspError {
    LspError::Protocol(e.to_string())
}

fn send(writer: &SharedWriter, msg: &Value) -> Result<(), LspError> {
    let body = serde_json::to_vec(msg).map_err(proto)?;
    let mut w = writer.lock().unwrap();
    codec::write_message(&mut **w, &body).map_err(|e| match e.kind() {
        std::io::ErrorKind::BrokenPipe => LspError::Closed,
        _ => LspError::Io(e),
    })
}

struct ReaderCtx {
    writer: SharedWriter,
    pending: Pending,
    closed: Arc<AtomicBool>,
    on_event: EventHandler,
}

impl ReaderCtx {
    fn run(self, reader: impl Read) {
        let mut reader = BufReader::new(reader);
        loop {
            match codec::read_message(&mut reader) {
                Ok(Some(body)) => match serde_json::from_slice::<Value>(&body) {
                    Ok(msg) => self.dispatch(msg),
                    Err(e) => log::warn!("LSP: dropping unparsable message: {e}"),
                },
                Ok(None) => break,
                Err(e) => {
                    log::warn!("LSP: read error: {e}");
                    break;
                }
            }
        }
        self.closed.store(true, Ordering::SeqCst);
        for (_, tx) in self.pending.lock().unwrap().drain() {
            let _ = tx.send(Err(LspError::Closed));
        }
        (self.on_event)(LspEvent::Closed);
    }

    fn dispatch(&self, mut msg: Value) {
        let method = msg.get("method").and_then(Value::as_str).map(str::to_owned);
        let id = msg.get("id").cloned();
        match (method, id) {
            // Response to one of our requests.
            (None, Some(id)) => {
                let Some(id) = id.as_i64() else { return };
                let Some(tx) = self.pending.lock().unwrap().remove(&id) else {
                    return;
                };
                let result = if let Some(err) = msg.get("error") {
                    Err(LspError::Response {
                        code: err.get("code").and_then(Value::as_i64).unwrap_or(0),
                        message: err
                            .get("message")
                            .and_then(Value::as_str)
                            .unwrap_or_default()
                            .to_owned(),
                    })
                } else {
                    Ok(msg.get_mut("result").map(Value::take).unwrap_or(Value::Null))
                };
                let _ = tx.send(result);
            }
            // Request from the server.
            (Some(method), Some(id)) => {
                let params = msg.get_mut("params").map(Value::take).unwrap_or(Value::Null);
                let reply = match server_request_result(&method, &params) {
                    Some(result) => json!({"jsonrpc": "2.0", "id": id, "result": result}),
                    None => json!({
                        "jsonrpc": "2.0",
                        "id": id,
                        "error": {"code": -32601, "message": format!("unsupported: {method}")}
                    }),
                };
                if let Err(e) = send(&self.writer, &reply) {
                    log::warn!("LSP: failed to answer {method}: {e}");
                }
            }
            // Notification.
            (Some(method), None) => {
                let params = msg.get_mut("params").map(Value::take).unwrap_or(Value::Null);
                (self.on_event)(notification_event(method, params));
            }
            (None, None) => log::warn!("LSP: message without method or id"),
        }
    }
}

/// Default answers for requests servers commonly send to clients.
fn server_request_result(method: &str, params: &Value) -> Option<Value> {
    match method {
        "workspace/configuration" => {
            let n = params.get("items").and_then(Value::as_array).map_or(0, Vec::len);
            Some(Value::Array(vec![Value::Null; n]))
        }
        "window/workDoneProgress/create" | "client/registerCapability" | "client/unregisterCapability" => {
            Some(Value::Null)
        }
        "workspace/workspaceFolders" => Some(Value::Null),
        "workspace/applyEdit" => Some(json!({"applied": false, "failureReason": "not supported yet"})),
        _ => None,
    }
}

fn notification_event(method: String, params: Value) -> LspEvent {
    fn parse<T: DeserializeOwned>(params: &Value) -> Option<T> {
        serde_json::from_value(params.clone()).ok()
    }
    match method.as_str() {
        notif::PublishDiagnostics::METHOD => {
            if let Some(p) = parse::<lsp_types::PublishDiagnosticsParams>(&params) {
                return LspEvent::Diagnostics {
                    uri: p.uri,
                    version: p.version,
                    diagnostics: p.diagnostics,
                };
            }
        }
        notif::LogMessage::METHOD => {
            if let Some(p) = parse::<lsp_types::LogMessageParams>(&params) {
                return LspEvent::LogMessage {
                    typ: p.typ,
                    message: p.message,
                };
            }
        }
        notif::ShowMessage::METHOD => {
            if let Some(p) = parse::<lsp_types::ShowMessageParams>(&params) {
                return LspEvent::ShowMessage {
                    typ: p.typ,
                    message: p.message,
                };
            }
        }
        _ => {}
    }
    LspEvent::Notification { method, params }
}
