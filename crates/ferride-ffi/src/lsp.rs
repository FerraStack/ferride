use std::sync::Arc;

use ferride_lsp::lsp_types::{
    DiagnosticSeverity, MessageType, Position, Range, TextDocumentContentChangeEvent, TextDocumentSyncKind,
};
use ferride_lsp::{CancelToken, LspClient, LspEvent, file_uri};

use crate::{Document, FerrideError};

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LspDiagnostic {
    pub start_line: i32,
    /// UTF-16 column.
    pub start_column: i32,
    pub end_line: i32,
    pub end_column: i32,
    /// 1 = error, 2 = warning, 3 = information, 4 = hint.
    pub severity: i32,
    pub message: String,
    pub source: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LspCompletion {
    pub label: String,
    pub detail: Option<String>,
    /// Text to insert (falls back to the label).
    pub insert_text: String,
}

/// Receives server-initiated events. Called on a background thread: post to
/// the main thread before touching UI.
#[uniffi::export(callback_interface)]
pub trait LspListener: Send + Sync {
    fn on_diagnostics(&self, path: String, version: Option<i32>, diagnostics: Vec<LspDiagnostic>);
    /// `level`: 1 = error, 2 = warning, 3 = info, 4 = log.
    fn on_message(&self, level: i32, message: String, show: bool);
    fn on_closed(&self);
}

/// A language-server session over a byte stream obtained from the process
/// host (Ferrux). The server itself never runs inside Ferride.
#[derive(uniffi::Object)]
pub struct LspSession {
    client: LspClient,
}

fn uri(path: &str) -> Result<ferride_lsp::lsp_types::Uri, FerrideError> {
    file_uri(path).map_err(|msg| FerrideError::InvalidArgument { msg })
}

fn path_of(uri: &ferride_lsp::lsp_types::Uri) -> String {
    let s = uri.as_str();
    let raw = s.strip_prefix("file://").unwrap_or(s);
    percent_decode(raw)
}

fn percent_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%'
            && i + 2 < bytes.len()
            && let Some(v) = std::str::from_utf8(&bytes[i + 1..i + 3])
                .ok()
                .and_then(|h| u8::from_str_radix(h, 16).ok())
        {
            out.push(v);
            i += 3;
            continue;
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn message_level(t: MessageType) -> i32 {
    match t {
        MessageType::ERROR => 1,
        MessageType::WARNING => 2,
        MessageType::INFO => 3,
        _ => 4,
    }
}

impl LspSession {
    /// Starts a session over arbitrary streams (used by tests and, later, by
    /// the Ferrux host implementation).
    pub fn from_streams(
        reader: impl std::io::Read + Send + 'static,
        writer: impl std::io::Write + Send + 'static,
        listener: Box<dyn LspListener>,
    ) -> Arc<Self> {
        Self::from_streams_with_closer(reader, writer, listener, || {})
    }

    fn from_streams_with_closer(
        reader: impl std::io::Read + Send + 'static,
        writer: impl std::io::Write + Send + 'static,
        listener: Box<dyn LspListener>,
        closer: impl FnOnce() + Send + 'static,
    ) -> Arc<Self> {
        let client = LspClient::start_with_closer(
            reader,
            writer,
            move |event| match event {
                LspEvent::Diagnostics {
                    uri,
                    version,
                    diagnostics,
                } => listener.on_diagnostics(
                    path_of(&uri),
                    version,
                    diagnostics
                        .into_iter()
                        .map(|d| LspDiagnostic {
                            start_line: d.range.start.line as i32,
                            start_column: d.range.start.character as i32,
                            end_line: d.range.end.line as i32,
                            end_column: d.range.end.character as i32,
                            severity: match d.severity {
                                Some(DiagnosticSeverity::ERROR) => 1,
                                Some(DiagnosticSeverity::WARNING) => 2,
                                Some(DiagnosticSeverity::INFORMATION) => 3,
                                _ => 4,
                            },
                            message: d.message,
                            source: d.source,
                        })
                        .collect(),
                ),
                LspEvent::LogMessage { typ, message } => {
                    listener.on_message(message_level(typ), message, false)
                }
                LspEvent::ShowMessage { typ, message } => {
                    listener.on_message(message_level(typ), message, true)
                }
                LspEvent::Notification { .. } => {}
                LspEvent::Closed => listener.on_closed(),
            },
            closer,
        );
        Arc::new(Self { client })
    }
}

#[uniffi::export]
impl LspSession {
    /// Takes ownership of two file descriptors (e.g. from
    /// `ParcelFileDescriptor.detachFd()`): one to read server output from and
    /// one to write requests to. They may be the same socket fd.
    #[uniffi::constructor]
    pub fn connect_fds(
        read_fd: i32,
        write_fd: i32,
        listener: Box<dyn LspListener>,
    ) -> Result<Arc<Self>, FerrideError> {
        use std::fs::File;
        use std::os::fd::{FromRawFd, OwnedFd};
        if read_fd < 0 || write_fd < 0 {
            return Err(FerrideError::InvalidArgument {
                msg: "invalid file descriptor".into(),
            });
        }
        // SAFETY: the caller transfers ownership of open fds to us.
        let read = unsafe { OwnedFd::from_raw_fd(read_fd) };
        let write = if write_fd == read_fd {
            read.try_clone()
                .map_err(|e| FerrideError::Io { msg: e.to_string() })?
        } else {
            // SAFETY: as above.
            unsafe { OwnedFd::from_raw_fd(write_fd) }
        };
        Ok(Self::from_streams(File::from(read), File::from(write), listener))
    }

    /// Initializes the server for a workspace folder. Returns the server name.
    pub fn initialize(&self, root_path: Option<String>) -> Result<String, FerrideError> {
        let root = root_path.as_deref().map(uri).transpose()?;
        let res = self.client.initialize(root, "Ferride")?;
        Ok(res.server_info.map(|i| i.name).unwrap_or_default())
    }

    pub fn did_open(
        &self,
        path: String,
        language_id: String,
        version: i32,
        text: String,
    ) -> Result<(), FerrideError> {
        Ok(self.client.did_open(uri(&path)?, &language_id, version, text)?)
    }

    pub fn did_change_full(&self, path: String, version: i32, text: String) -> Result<(), FerrideError> {
        Ok(self.client.did_change_full(uri(&path)?, version, text)?)
    }

    /// Sends what changed in `document` since the last sync: incremental
    /// ranges when the server supports them (the document must have
    /// [`Document::enable_lsp_sync`] on since `didOpen`), the full text
    /// otherwise. Returns whether it sent incremental changes.
    pub fn did_change_document(
        &self,
        path: String,
        version: i32,
        document: Arc<Document>,
    ) -> Result<bool, FerrideError> {
        let uri = uri(&path)?;
        let changes = document.take_lsp_changes();
        match self.client.sync_kind() {
            TextDocumentSyncKind::INCREMENTAL => {
                if changes.is_empty() {
                    return Ok(true);
                }
                let events = changes
                    .into_iter()
                    .map(|c| TextDocumentContentChangeEvent {
                        range: Some(Range::new(
                            Position::new(c.start_line.max(0) as u32, c.start_column.max(0) as u32),
                            Position::new(c.end_line.max(0) as u32, c.end_column.max(0) as u32),
                        )),
                        range_length: None,
                        text: c.text,
                    })
                    .collect();
                self.client.did_change(uri, version, events)?;
                Ok(true)
            }
            TextDocumentSyncKind::NONE => Ok(false),
            _ => {
                self.client.did_change_full(uri, version, document.text())?;
                Ok(false)
            }
        }
    }

    pub fn did_save(&self, path: String) -> Result<(), FerrideError> {
        Ok(self.client.did_save(uri(&path)?, None)?)
    }

    pub fn did_close(&self, path: String) -> Result<(), FerrideError> {
        Ok(self.client.did_close(uri(&path)?)?)
    }

    /// Blocking; call off the UI thread. `cancel` abandons it (with
    /// `$/cancelRequest`) when a newer request supersedes it.
    pub fn completion(
        &self,
        path: String,
        line: i32,
        column: i32,
        cancel: Option<Arc<LspCancellation>>,
    ) -> Result<Vec<LspCompletion>, FerrideError> {
        let items = self.client.completion_with(
            uri(&path)?,
            Position::new(line.max(0) as u32, column.max(0) as u32),
            cancel.as_ref().map(|c| &c.token),
        )?;
        Ok(items
            .into_iter()
            .map(|i| LspCompletion {
                insert_text: i.insert_text.clone().unwrap_or_else(|| i.label.clone()),
                label: i.label,
                detail: i.detail,
            })
            .collect())
    }

    /// Blocking; call off the UI thread.
    pub fn hover(
        &self,
        path: String,
        line: i32,
        column: i32,
        cancel: Option<Arc<LspCancellation>>,
    ) -> Result<Option<String>, FerrideError> {
        Ok(self.client.hover_with(
            uri(&path)?,
            Position::new(line.max(0) as u32, column.max(0) as u32),
            cancel.as_ref().map(|c| &c.token),
        )?)
    }

    pub fn shutdown(&self) -> Result<(), FerrideError> {
        Ok(self.client.shutdown()?)
    }

    pub fn is_closed(&self) -> bool {
        self.client.is_closed()
    }
}

/// Cancels a pending LSP request from another thread (see [`LspSession::completion`]).
#[derive(Debug, Default, uniffi::Object)]
pub struct LspCancellation {
    token: CancelToken,
}

#[uniffi::export]
impl LspCancellation {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    pub fn cancel(&self) {
        self.token.cancel();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decode_paths() {
        let u = file_uri("/a b/ü.rs").unwrap();
        assert_eq!(path_of(&u), "/a b/ü.rs");
        assert_eq!(percent_decode("%zz%"), "%zz%");
    }
}
