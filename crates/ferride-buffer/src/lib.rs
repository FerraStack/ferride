//! Text buffer for Ferride.
//!
//! The buffer stores text in a [`ropey::Rope`] and keeps every position as a
//! **char index** (Unicode scalar value index) internally. Conversions to byte
//! offsets (tree-sitter, search), UTF-16 code units (Android IME, Java strings,
//! LSP's default position encoding) and line/column pairs live in
//! [`Buffer`]'s offset methods.
//!
//! Edits are applied as [`Edit`] batches (one batch per user action, possibly
//! touching several cursors). Each batch becomes one undo [`Transaction`]
//! unless it is coalesced with the previous one (typing runs, IME composition
//! updates).

mod buffer;
pub mod grapheme;
mod history;
mod selection;

pub use buffer::{Buffer, BufferError, ByteEdit, Edit, EditKind, LineEnding, Point, TextChange};
pub use history::{Change, TYPING_PAUSE, Transaction};
pub use selection::{Selection, Selections};
