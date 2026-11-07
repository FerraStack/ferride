//! UniFFI surface of the Ferride core, consumed by the Kotlin app.
//!
//! Conventions:
//! - **All text offsets crossing the FFI are UTF-16 code units**, because
//!   that's what `String`, `Editable` and `InputConnection` use on Android.
//!   Conversion to/from the rope's char indices happens here.
//! - Objects are internally synchronized (`Mutex`), so Kotlin may call them
//!   from any thread. Document calls are cheap and made from the UI thread;
//!   workspace search and LSP requests block and belong on `Dispatchers.IO`.

mod document;
mod lsp;
mod workspace;

pub use document::{Document, EditOutcome, SelectionRange, StyleKind, StyleSpan, ViewLine};
pub use lsp::{LspCompletion, LspDiagnostic, LspListener, LspSession};
pub use workspace::{FileHit, FileNode, MatchRange, SearchHit, SearchReport, WorkspaceHandle};

use ferride_host::{HostStatus, ProcessHost, UnavailableHost};

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum FerrideError {
    #[error("{msg}")]
    Io { msg: String },
    #[error("{msg}")]
    Workspace { msg: String },
    #[error("{msg}")]
    Lsp { msg: String },
    #[error("{msg}")]
    InvalidArgument { msg: String },
    #[error("{msg}")]
    HostUnavailable { msg: String },
}

impl From<ferride_workspace::WorkspaceError> for FerrideError {
    fn from(e: ferride_workspace::WorkspaceError) -> Self {
        FerrideError::Workspace { msg: e.to_string() }
    }
}

impl From<ferride_lsp::LspError> for FerrideError {
    fn from(e: ferride_lsp::LspError) -> Self {
        FerrideError::Lsp { msg: e.to_string() }
    }
}

/// Availability of Ferrux, the process host (terminal, language servers).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum FerruxStatus {
    NotInstalled,
    NotPaired,
    Unavailable { reason: String },
    Ready { description: String },
}

impl From<HostStatus> for FerruxStatus {
    fn from(s: HostStatus) -> Self {
        match s {
            HostStatus::NotInstalled => FerruxStatus::NotInstalled,
            HostStatus::NotPaired => FerruxStatus::NotPaired,
            HostStatus::Unavailable { reason } => FerruxStatus::Unavailable { reason },
            HostStatus::Ready { description } => FerruxStatus::Ready { description },
        }
    }
}

/// The process host in use. Until the Ferrux Host API exists this is the
/// [`UnavailableHost`] stub, so the terminal panel reports "Requires Ferrux".
fn process_host() -> &'static dyn ProcessHost {
    static HOST: UnavailableHost = UnavailableHost;
    &HOST
}

#[uniffi::export]
pub fn ferrux_status() -> FerruxStatus {
    process_host().status().into()
}

#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_owned()
}

/// Languages with bundled grammars, as LSP language ids.
#[uniffi::export]
pub fn supported_languages() -> Vec<String> {
    ferride_syntax::LanguageId::ALL
        .iter()
        .map(|l| l.name().to_owned())
        .collect()
}

/// Language id for a file name, if a grammar is bundled for it.
#[uniffi::export]
pub fn language_for_file(file_name: String) -> Option<String> {
    ferride_syntax::LanguageId::from_path(file_name).map(|l| l.name().to_owned())
}

#[cfg(test)]
mod tests;
