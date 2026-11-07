//! Extended grapheme cluster boundaries on a rope (UAX #29), so cursor
//! movement and deletion never land inside `👨‍👩‍👧`, a flag, `e` + combining
//! accent or `\r\n`.
//!
//! The cursor walks the rope's chunks directly (no copy), following ropey's
//! documented `GraphemeCursor` recipe.

use ropey::RopeSlice;
use ropey::str_utils::byte_to_char_idx;
use unicode_segmentation::{GraphemeCursor, GraphemeIncomplete};

/// Char index of the grapheme boundary before `char_idx` (0 at the start).
pub fn prev_boundary(slice: RopeSlice<'_>, char_idx: usize) -> usize {
    let char_idx = char_idx.min(slice.len_chars());
    if char_idx == 0 {
        return 0;
    }
    let byte_idx = slice.char_to_byte(char_idx);
    let (mut chunk, mut chunk_byte, mut chunk_char, _) = slice.chunk_at_byte(byte_idx);
    let mut cursor = GraphemeCursor::new(byte_idx, slice.len_bytes(), true);
    loop {
        match cursor.prev_boundary(chunk, chunk_byte) {
            Ok(None) => return 0,
            Ok(Some(b)) => return chunk_char + byte_to_char_idx(chunk, b - chunk_byte),
            Err(GraphemeIncomplete::PrevChunk) => {
                let (c, cb, cc, _) = slice.chunk_at_byte(chunk_byte - 1);
                chunk = c;
                chunk_byte = cb;
                chunk_char = cc;
            }
            Err(GraphemeIncomplete::PreContext(n)) => {
                let ctx = slice.chunk_at_byte(n - 1).0;
                cursor.provide_context(ctx, n - ctx.len());
            }
            // Not produced by prev_boundary; fall back to one char.
            Err(_) => return char_idx - 1,
        }
    }
}

/// Char index of the grapheme boundary after `char_idx` (the length at the end).
pub fn next_boundary(slice: RopeSlice<'_>, char_idx: usize) -> usize {
    let len = slice.len_chars();
    let char_idx = char_idx.min(len);
    if char_idx == len {
        return len;
    }
    let byte_idx = slice.char_to_byte(char_idx);
    let (mut chunk, mut chunk_byte, mut chunk_char, _) = slice.chunk_at_byte(byte_idx);
    let mut cursor = GraphemeCursor::new(byte_idx, slice.len_bytes(), true);
    loop {
        match cursor.next_boundary(chunk, chunk_byte) {
            Ok(None) => return len,
            Ok(Some(b)) => return chunk_char + byte_to_char_idx(chunk, b - chunk_byte),
            Err(GraphemeIncomplete::NextChunk) => {
                chunk_byte += chunk.len();
                let (c, _, cc, _) = slice.chunk_at_byte(chunk_byte);
                chunk = c;
                chunk_char = cc;
            }
            Err(GraphemeIncomplete::PreContext(n)) => {
                let ctx = slice.chunk_at_byte(n - 1).0;
                cursor.provide_context(ctx, n - ctx.len());
            }
            Err(_) => return char_idx + 1,
        }
    }
}

/// Chars that glue an emoji sequence together or modify it: deleting just one
/// of them would leave a different (or broken) emoji behind.
fn is_emoji_component(c: char) -> bool {
    matches!(c,
        '\u{200D}'                    // zero-width joiner (👨‍👩‍👧)
        | '\u{FE0E}' | '\u{FE0F}'     // text / emoji presentation selectors
        | '\u{20E3}'                  // combining enclosing keycap (1️⃣)
        | '\u{1F1E6}'..='\u{1F1FF}'   // regional indicators (flags)
        | '\u{1F3FB}'..='\u{1F3FF}'   // skin-tone modifiers
        | '\u{E0020}'..='\u{E007F}'   // tag sequences (subdivision flags)
    )
}

/// Where Backspace at `char_idx` starts deleting, like VS Code's `deleteLeft`:
/// an emoji sequence (or `\r\n`) goes as a whole; anything else loses one code
/// point, so `e` + accent or an Indic vowel sign can be corrected piecewise.
pub fn backspace_start(slice: RopeSlice<'_>, char_idx: usize) -> usize {
    let char_idx = char_idx.min(slice.len_chars());
    if char_idx == 0 {
        return 0;
    }
    let prev = prev_boundary(slice, char_idx);
    let cluster = slice.slice(prev..char_idx);
    if cluster.len_chars() > 1 && cluster.chars().any(|c| c == '\r' || is_emoji_component(c)) {
        prev
    } else {
        char_idx - 1
    }
}
