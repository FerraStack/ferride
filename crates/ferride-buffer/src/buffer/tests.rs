use super::*;
use crate::TYPING_PAUSE;

// "a😀b\n" — 😀 is U+1F600: 1 char, 4 UTF-8 bytes, 2 UTF-16 units (surrogate pair).
const EMOJI: &str = "a😀b\nñx\n";

#[test]
fn offsets_ascii() {
    let b = Buffer::new("hello\nworld");
    assert_eq!(b.len_lines(), 2);
    assert_eq!(b.line_to_char(1), 6);
    assert_eq!(b.char_to_line_col(8), (1, 2));
    assert_eq!(b.line_col_to_char(1, 99), 11, "column clamps to line end");
    assert_eq!(b.line_text(0), "hello");
    assert_eq!(b.line_text(1), "world");
}

#[test]
fn offsets_emoji_and_surrogates() {
    let b = Buffer::new(EMOJI);
    assert_eq!(b.len_chars(), 7);
    assert_eq!(b.len_bytes(), 1 + 4 + 1 + 1 + 2 + 1 + 1);
    assert_eq!(b.len_utf16(), 1 + 2 + 1 + 1 + 1 + 1 + 1);

    // char -> byte / utf16
    assert_eq!(b.char_to_byte(1), 1);
    assert_eq!(b.char_to_byte(2), 5);
    assert_eq!(b.char_to_utf16(2), 3);
    assert_eq!(b.char_to_utf16(3), 4);

    // utf16 -> char: inside the surrogate pair rounds down to the emoji.
    assert_eq!(b.utf16_to_char(1), 1);
    assert_eq!(b.utf16_to_char(2), 1);
    assert_eq!(b.utf16_to_char(3), 2);

    // byte -> char: inside the emoji rounds down.
    for byte in 1..5 {
        assert_eq!(b.byte_to_char(byte), 1, "byte {byte}");
    }

    // Line 1 starts after "a😀b\n".
    assert_eq!(b.line_to_char(1), 4);
    assert_eq!(b.line_to_byte(1), 7);
    assert_eq!(b.line_to_utf16(1), 5);
    // 'x' on line 1: char col 1, utf16 col 1, byte col 2 (ñ is 2 bytes).
    let x = 5;
    assert_eq!(b.char_to_line_col(x), (1, 1));
    assert_eq!(b.char_to_line_col_utf16(x), (1, 1));
    assert_eq!(b.char_to_point(x), Point { row: 1, column: 2 });
    // 'b' on line 0: char col 2, utf16 col 3.
    assert_eq!(b.char_to_line_col_utf16(2), (0, 3));
    assert_eq!(b.line_col_utf16_to_char(0, 3), 2);
    assert_eq!(b.line_col_utf16_to_char(0, 2), 1, "mid-surrogate");
    assert_eq!(b.utf16_to_line(5), 1);
    assert_eq!(b.byte_to_line(7), 1);
}

#[test]
fn offsets_are_clamped() {
    let b = Buffer::new("ab");
    assert_eq!(b.char_to_byte(100), 2);
    assert_eq!(b.utf16_to_char(100), 2);
    assert_eq!(b.line_to_char(9), 2);
    assert_eq!(b.line_text(9), "");
}

#[test]
fn crlf_lines() {
    let b = Buffer::new("one\r\ntwo\r\n");
    assert_eq!(b.line_ending(), LineEnding::CrLf);
    assert_eq!(b.len_lines(), 3);
    assert_eq!(b.line_text(0), "one");
    assert_eq!(b.line_end_char(0), 3);
    assert_eq!(b.line_len_chars(1), 3);
}

#[test]
fn insert_and_byte_edits() {
    let mut b = Buffer::new("a😀b");
    let be = b.apply(vec![Edit::insert(2, "ñ\n")], EditKind::Other).unwrap();
    assert_eq!(b.text(), "a😀ñ\nb");
    assert_eq!(
        be,
        vec![ByteEdit {
            start_byte: 5,
            old_end_byte: 5,
            new_end_byte: 8,
            start_position: Point { row: 0, column: 5 },
            old_end_position: Point { row: 0, column: 5 },
            new_end_position: Point { row: 1, column: 0 },
        }]
    );
    assert_eq!(b.version(), 1);
    assert!(b.is_dirty());
    b.mark_saved();
    assert!(!b.is_dirty());
}

#[test]
fn rejects_bad_edits() {
    let mut b = Buffer::new("abc");
    assert_eq!(
        b.apply(vec![Edit::delete(2..9)], EditKind::Other),
        Err(BufferError::OutOfBounds {
            start: 2,
            end: 9,
            len: 3
        })
    );
    assert_eq!(
        b.apply(vec![Edit::delete(0..2), Edit::delete(1..3)], EditKind::Other),
        Err(BufferError::OverlappingEdits)
    );
    assert_eq!(b.text(), "abc");
}

#[test]
fn multi_cursor_typing_and_undo() {
    let mut b = Buffer::new("foo\nbar\nbaz");
    b.set_selections(
        Selections::new(
            vec![Selection::caret(0), Selection::caret(4), Selection::caret(8)],
            0,
        )
        .unwrap(),
    );
    b.insert_at_selections("> ", EditKind::Other);
    assert_eq!(b.text(), "> foo\n> bar\n> baz");
    let carets: Vec<usize> = b.selections().ranges().iter().map(|s| s.head).collect();
    assert_eq!(carets, vec![2, 8, 14]);

    b.delete_backward();
    assert_eq!(b.text(), ">foo\n>bar\n>baz");

    b.undo().unwrap();
    assert_eq!(b.text(), "> foo\n> bar\n> baz");
    b.undo().unwrap();
    assert_eq!(b.text(), "foo\nbar\nbaz");
    let carets: Vec<usize> = b.selections().ranges().iter().map(|s| s.head).collect();
    assert_eq!(carets, vec![0, 4, 8]);
    assert!(b.undo().is_none());

    b.redo().unwrap();
    b.redo().unwrap();
    assert_eq!(b.text(), ">foo\n>bar\n>baz");
    assert!(b.redo().is_none());
}

#[test]
fn typing_is_coalesced_and_newline_breaks_group() {
    let mut b = Buffer::new("");
    for c in ["h", "e", "l", "l", "o"] {
        b.insert_at_selections(c, EditKind::Typing);
    }
    b.insert_at_selections("\n", EditKind::Typing);
    for c in ["w", "o"] {
        b.insert_at_selections(c, EditKind::Typing);
    }
    assert_eq!(b.text(), "hello\nwo");
    b.undo().unwrap();
    assert_eq!(b.text(), "hello\n", "the typing run after the newline is undone");
    b.undo().unwrap();
    assert_eq!(b.text(), "hello");
    b.undo().unwrap();
    assert_eq!(b.text(), "");
}

#[test]
fn ime_composition_is_one_undo_step() {
    // setComposingText("w") -> ("wo") -> ("wor") -> commit("word ")
    let mut b = Buffer::new("x ");
    b.set_selection(Selection::caret(2));
    b.apply(vec![Edit::insert(2, "w")], EditKind::Typing).unwrap();
    b.apply(vec![Edit::new(2..3, "wo")], EditKind::Typing).unwrap();
    b.apply(vec![Edit::new(2..4, "wor")], EditKind::Typing).unwrap();
    b.apply(vec![Edit::new(2..5, "word ")], EditKind::Typing).unwrap();
    assert_eq!(b.text(), "x word ");
    b.undo().unwrap();
    assert_eq!(b.text(), "x ");
}

#[test]
fn seal_breaks_typing_run() {
    let mut b = Buffer::new("");
    b.insert_at_selections("a", EditKind::Typing);
    b.seal_undo_group();
    b.insert_at_selections("b", EditKind::Typing);
    b.undo().unwrap();
    assert_eq!(b.text(), "a");
}

#[test]
fn moving_the_cursor_breaks_typing_run() {
    let mut b = Buffer::new("");
    b.insert_at_selections("ab", EditKind::Typing);
    b.set_selection(Selection::caret(0));
    b.insert_at_selections("c", EditKind::Typing);
    assert_eq!(b.text(), "cab");
    b.undo().unwrap();
    assert_eq!(b.text(), "ab");
}

#[test]
fn backspace_run_is_coalesced() {
    let mut b = Buffer::new("abcdef");
    b.set_selection(Selection::caret(6));
    b.delete_backward();
    b.delete_backward();
    b.delete_backward();
    assert_eq!(b.text(), "abc");
    b.undo().unwrap();
    assert_eq!(b.text(), "abcdef");
    assert_eq!(b.selections().primary(), Selection::caret(6));
}

#[test]
fn backspace_over_emoji_and_crlf() {
    let mut b = Buffer::new("a😀\r\n");
    b.set_selection(Selection::caret(b.len_chars()));
    b.delete_backward();
    assert_eq!(b.text(), "a😀");
    b.delete_backward();
    assert_eq!(b.text(), "a");
    b.delete_forward(); // at end: no-op
    assert_eq!(b.text(), "a");
}

#[test]
fn selection_replacement() {
    let mut b = Buffer::new("hello world");
    b.set_selection(Selection::new(0, 5));
    b.insert_at_selections("bye", EditKind::Other);
    assert_eq!(b.text(), "bye world");
    assert_eq!(b.selections().primary(), Selection::caret(3));
    b.undo().unwrap();
    assert_eq!(b.selections().primary(), Selection::new(0, 5));
}

#[test]
fn redo_is_cleared_by_new_edit() {
    let mut b = Buffer::new("");
    b.insert_at_selections("a", EditKind::Other);
    b.undo().unwrap();
    assert!(b.can_redo());
    b.insert_at_selections("b", EditKind::Other);
    assert!(!b.can_redo());
}

#[test]
fn map_pos_shifts_later_positions() {
    // replace 2..4 with 3 chars, insert 1 char at 6
    let edits = [(2, 4, 3), (6, 6, 1)];
    assert_eq!(map_pos(0, &edits), 0);
    assert_eq!(map_pos(2, &edits), 5);
    assert_eq!(map_pos(3, &edits), 5);
    assert_eq!(map_pos(5, &edits), 6);
    assert_eq!(map_pos(6, &edits), 8);
    assert_eq!(map_pos(10, &edits), 12);
}

#[test]
fn crlf_hygiene() {
    let b = Buffer::new("a\r\nb");
    // Index 2 is between \r and \n.
    assert_eq!(b.snap_out_of_crlf(2), 1);
    assert_eq!(b.snap_out_of_crlf(3), 3);
    assert_eq!(b.widen_crlf(2..2), 1..1);
    assert_eq!(b.widen_crlf(0..2), 0..3);
    assert_eq!(b.normalize_eol("x\ny\rz\r\n"), "x\r\ny\r\nz\r\n");
    let lf = Buffer::new("a\nb");
    assert_eq!(lf.normalize_eol("x\r\ny"), "x\ny");
    assert_eq!(lf.normalize_eol("plain"), "plain");
}

#[test]
fn lone_cr_is_not_a_line_break() {
    // Tree-sitter counts rows by \n only; the buffer must agree.
    let b = Buffer::new("a\rb\nc");
    assert_eq!(b.len_lines(), 2);
    assert_eq!(b.char_to_point(4), Point { row: 1, column: 0 });
}

#[test]
fn mark_saved_at_older_version_stays_dirty() {
    let mut b = Buffer::new("");
    b.insert_at_selections("a", EditKind::Other);
    let v = b.version();
    b.insert_at_selections("b", EditKind::Other);
    b.mark_saved_at(v);
    assert!(b.is_dirty());
    b.mark_saved_at(b.version());
    assert!(!b.is_dirty());
}

// ----- grapheme clusters (#6) ---------------------------------------------

const FAMILY: &str = "👨\u{200D}👩\u{200D}👧"; // 5 chars, one cluster
const FLAG: &str = "🇺🇦"; // two regional indicators
const E_ACUTE: &str = "e\u{0301}"; // e + combining acute

fn chars(s: &str) -> usize {
    s.chars().count()
}

#[test]
fn grapheme_boundaries_skip_whole_clusters() {
    let text = format!("a{FAMILY}b{FLAG}{E_ACUTE}\r\nz");
    let b = Buffer::new(&text);
    let fam_end = 1 + chars(FAMILY);
    let flag_end = fam_end + 1 + chars(FLAG);
    let e_end = flag_end + chars(E_ACUTE);
    // Forward walk visits only cluster boundaries.
    let mut stops = vec![0];
    let mut pos = 0;
    while pos < b.len_chars() {
        pos = b.next_grapheme_boundary(pos);
        stops.push(pos);
    }
    assert_eq!(
        stops,
        vec![0, 1, fam_end, fam_end + 1, flag_end, e_end, e_end + 2, e_end + 3]
    );
    // Backward walk is the mirror image.
    let mut back = vec![b.len_chars()];
    let mut pos = b.len_chars();
    while pos > 0 {
        pos = b.prev_grapheme_boundary(pos);
        back.push(pos);
    }
    back.reverse();
    assert_eq!(back, stops);
    // From inside a cluster, both directions leave it.
    assert_eq!(b.next_grapheme_boundary(3), fam_end);
    assert_eq!(b.prev_grapheme_boundary(3), 1);
    assert_eq!(b.next_grapheme_boundary(b.len_chars() + 5), b.len_chars());
}

#[test]
fn grapheme_boundaries_across_rope_chunks() {
    // Long enough that the rope splits into several chunks around the emoji.
    for pad in [900, 1000, 1021, 1022, 1023, 1024, 2000] {
        let text = format!("{}{FAMILY}{}", "x".repeat(pad), "y".repeat(pad));
        let b = Buffer::new(&text);
        assert_eq!(b.next_grapheme_boundary(pad), pad + chars(FAMILY), "pad {pad}");
        assert_eq!(b.prev_grapheme_boundary(pad + chars(FAMILY)), pad, "pad {pad}");
        assert_eq!(b.prev_grapheme_boundary(pad + 2), pad, "pad {pad}");
    }
}

#[test]
fn backspace_deletes_emoji_sequences_whole() {
    for emoji in [FAMILY, FLAG, "👍🏽", "1\u{FE0F}\u{20E3}", "❤\u{FE0F}"] {
        let mut b = Buffer::new(&format!("a{emoji}"));
        b.set_selection(Selection::caret(b.len_chars()));
        b.delete_backward();
        assert_eq!(b.text(), "a", "{emoji:?}");
    }
}

#[test]
fn backspace_removes_one_code_point_of_a_combining_sequence() {
    // VS Code's deleteLeft: the accent goes first, the base letter stays.
    let mut b = Buffer::new(&format!("x{E_ACUTE}"));
    b.set_selection(Selection::caret(b.len_chars()));
    b.delete_backward();
    assert_eq!(b.text(), "xe");
    b.delete_backward();
    assert_eq!(b.text(), "x");
}

#[test]
fn backspace_and_delete_keep_crlf_together() {
    let mut b = Buffer::new("a\r\nb");
    b.set_selection(Selection::caret(3));
    b.delete_backward();
    assert_eq!(b.text(), "ab");
    let mut b = Buffer::new("a\r\nb");
    b.set_selection(Selection::caret(1));
    b.delete_forward();
    assert_eq!(b.text(), "ab");
}

#[test]
fn delete_forward_removes_a_whole_cluster() {
    for cluster in [FAMILY, FLAG, E_ACUTE] {
        let mut b = Buffer::new(&format!("{cluster}z"));
        b.set_selection(Selection::caret(0));
        b.delete_forward();
        assert_eq!(b.text(), "z", "{cluster:?}");
    }
}

// ----- undo grouping by word and pause (#2) ----------------------------------

fn type_chars(b: &mut Buffer, text: &str) {
    for c in text.chars() {
        b.insert_at_selections(&c.to_string(), EditKind::Typing);
    }
}

#[test]
fn undo_steps_are_words() {
    let mut b = Buffer::new("");
    b.set_clock(Some(std::time::Instant::now()));
    type_chars(&mut b, "let total = a.b(c);");
    let mut steps = vec![b.text()];
    while b.undo().is_some() {
        steps.push(b.text());
    }
    assert_eq!(
        steps,
        vec![
            "let total = a.b(c);",
            "let total = a.b(",
            "let total = a.",
            "let total = ",
            "let ",
            ""
        ]
    );
}

#[test]
fn a_typing_pause_starts_a_new_step() {
    let t0 = std::time::Instant::now();
    let mut b = Buffer::new("");
    b.set_clock(Some(t0));
    type_chars(&mut b, "inter");
    b.set_clock(Some(t0 + TYPING_PAUSE + std::time::Duration::from_millis(1)));
    type_chars(&mut b, "national");
    b.undo().unwrap();
    assert_eq!(b.text(), "inter");
    b.undo().unwrap();
    assert_eq!(b.text(), "");
}

#[test]
fn backspace_run_after_a_pause_is_its_own_step() {
    let t0 = std::time::Instant::now();
    let mut b = Buffer::new("abcdef");
    b.set_clock(Some(t0));
    b.set_selection(Selection::caret(6));
    b.delete_backward();
    b.set_clock(Some(t0 + TYPING_PAUSE * 2));
    b.delete_backward();
    b.undo().unwrap();
    assert_eq!(b.text(), "abcde");
}

#[test]
fn slow_ime_composition_is_still_one_step() {
    // Composing "word" with long pauses between updates, then committing.
    let t0 = std::time::Instant::now();
    let mut b = Buffer::new("x ");
    b.set_selection(Selection::caret(2));
    let steps = [(2..2, "w"), (2..3, "wo"), (2..4, "wor"), (2..5, "word")];
    for (i, (range, text)) in steps.into_iter().enumerate() {
        b.set_clock(Some(t0 + TYPING_PAUSE * 3 * i as u32));
        b.apply(vec![Edit::new(range, text)], EditKind::Typing).unwrap();
    }
    assert_eq!(b.text(), "x word");
    b.undo().unwrap();
    assert_eq!(b.text(), "x ");
}

/// Replays recorded changes on a plain string the way an LSP server would.
fn replay(text: &str, changes: &[TextChange]) -> String {
    let mut s = text.to_owned();
    for c in changes {
        let offset = |line: usize, col: usize| {
            let line_start: usize = s.split('\n').take(line).map(|l| l.len() + 1).sum();
            let line_text = s[line_start..].split('\n').next().unwrap_or("");
            let mut units = 0;
            let mut bytes = 0;
            for ch in line_text.chars() {
                if units >= col {
                    break;
                }
                units += ch.len_utf16();
                bytes += ch.len_utf8();
            }
            line_start + bytes
        };
        let a = offset(c.start_line, c.start_col);
        let b = offset(c.end_line, c.end_col);
        s.replace_range(a..b, &c.text);
    }
    s
}

#[test]
fn change_log_replays_to_the_same_text() {
    let start = "fn main() {\n    println!(\"😀\");\n}\n";
    let mut b = Buffer::new(start);
    b.set_change_log(true);
    // Multi-cursor typing, a backspace, an undo and a redo.
    let sels = Selections::new(vec![Selection::caret(3), Selection::caret(15)], 0).unwrap();
    b.set_selections(sels);
    b.insert_at_selections("xy", EditKind::Typing);
    b.delete_backward();
    b.apply(vec![Edit::new(0..2, "pub fn")], EditKind::Other).unwrap();
    b.undo().unwrap();
    b.redo().unwrap();
    let changes = b.take_changes();
    assert!(changes.len() >= 6);
    assert_eq!(replay(start, &changes), b.text());
    assert!(b.take_changes().is_empty(), "taken once");
    b.set_change_log(false);
    b.insert_at_selections("z", EditKind::Typing);
    assert!(b.take_changes().is_empty(), "off: nothing recorded");
}
