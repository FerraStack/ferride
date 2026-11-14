//! Language Server Protocol client for Ferride.
//!
//! - [`codec`]: `Content-Length` framing.
//! - [`LspClient`]: request/response correlation, server→client requests,
//!   notifications as [`LspEvent`]s, and typed helpers for the features the
//!   editor uses (text sync, completion, hover, diagnostics).
//!
//! The transport is any `Read` + `Write` pair, so the same client runs over a
//! child process' stdio (desktop, tests) or a socket fd obtained from Ferrux.
//! Positions use UTF-16 code units (LSP's default encoding), which
//! `ferride-buffer` converts to and from.

mod client;
pub mod codec;

pub use client::{CancelToken, LspClient, LspError, LspEvent};
pub use lsp_types;

use lsp_types::Uri;

/// Builds a `file://` URI for an absolute path, percent-encoding everything
/// outside the unreserved set (and keeping `/`).
pub fn file_uri(path: &str) -> Result<Uri, String> {
    if !path.starts_with('/') {
        return Err(format!("not an absolute path: {path}"));
    }
    let mut s = String::from("file://");
    for b in path.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' | b'/' => s.push(b as char),
            _ => s.push_str(&format!("%{b:02X}")),
        }
    }
    s.parse().map_err(|e| format!("{e:?}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn file_uris() {
        assert_eq!(file_uri("/a/b.rs").unwrap().as_str(), "file:///a/b.rs");
        assert_eq!(
            file_uri("/my dir/ü.rs").unwrap().as_str(),
            "file:///my%20dir/%C3%BC.rs"
        );
        assert!(file_uri("rel/path").is_err());
    }
}
