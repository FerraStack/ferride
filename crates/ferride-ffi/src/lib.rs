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
mod host;
mod lsp;
mod scm;
mod theme;
mod workspace;

pub use document::{
    Document, DocumentListener, DocumentSymbol, EditOutcome, LspTextChange, SelectionRange, StyleKind,
    StyleSpan, ViewLine,
};
pub use host::{
    BridgeSpawnSpec, EnvEntry, HostBridge, HostBridgeError, SpawnedFds, TerminalSession, register_host_bridge,
};
pub use lsp::{LspCancellation, LspCompletion, LspDiagnostic, LspListener, LspSession, LspTextEdit};
pub use scm::{
    GitError, ScmBranch, ScmCancel, ScmChange, ScmCommit, ScmCredential, ScmDiffLine, ScmFileDiff, ScmGroup,
    ScmHead, ScmHunk, ScmIdentity, ScmLineChange, ScmLineChangeKind, ScmNetworkListener, ScmRefKind,
    ScmRefLabel, ScmRepository, ScmWordRange, scm_clone, scm_discover, scm_init, scm_line_changes,
};
pub use theme::{TokenStyle, TokenTheme, scope_stack};
pub use workspace::{
    EncodingInfo, FerruxEntry, FerruxError, FerruxTree, FileHit, FileNode, MatchRange, OpenedText, SafDoc,
    SafError, SafTree, SearchCancellation, SearchHit, SearchReport, SymbolHit, WorkspaceHandle,
    WorkspaceKind, supported_encodings,
};

use ferride_host::{HostError, HostStatus};

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

impl From<HostError> for FerrideError {
    fn from(e: HostError) -> Self {
        match e {
            HostError::Io(io) => FerrideError::Io { msg: io.to_string() },
            other => FerrideError::HostUnavailable {
                msg: other.to_string(),
            },
        }
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

#[uniffi::export]
pub fn ferrux_status() -> FerruxStatus {
    host::current_host_status().into()
}

/// Binds `dev.ferrux.terminal.TerminalNative` methods when Android loads
/// `libferride_ffi.so`, so only one copy of `ferrux-term`'s `SessionManager`
/// exists in the process.
///
/// # Safety
/// `vm` must be the pointer passed by the JVM to `JNI_OnLoad`.
#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "system" fn JNI_OnLoad(
    vm: *mut ferrux_jni_core::jni::sys::JavaVM,
    _reserved: *mut std::ffi::c_void,
) -> ferrux_jni_core::jni::sys::jint {
    // SAFETY: forwarded from the JVM on `System.loadLibrary("ferride_ffi")`.
    unsafe {
        ferrux_jni_core::on_load(vm, "ferride_ffi", |env| {
            ferrux_jni_core::register_terminal_natives(env, ferrux_jni_core::TERMINAL_CLASS)
        })
    }
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

/// Language id for a file name (well-known names, then the extension), or
/// else for a `#!` `first_line`, if a grammar is bundled for it.
#[uniffi::export]
pub fn language_for_file(file_name: String, first_line: Option<String>) -> Option<String> {
    ferride_syntax::LanguageId::detect(file_name, first_line.as_deref()).map(|l| l.name().to_owned())
}

#[cfg(test)]
mod tests;
