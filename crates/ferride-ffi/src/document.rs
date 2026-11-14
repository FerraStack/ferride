use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, Weak};
use std::time::Duration;

use ferride_buffer::{Buffer, ByteEdit, Edit, EditKind, Selection, Selections};
use ferride_syntax::{EDIT_PARSE_BUDGET, HighlightKind, LanguageId, ParseSnapshot, Syntax};

use crate::FerrideError;

/// Highlight class of a span; the app's theme maps each to a color.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum StyleKind {
    Plain,
    Keyword,
    Function,
    Type,
    String,
    Number,
    Comment,
    Constant,
    Variable,
    Property,
    Operator,
    Punctuation,
    Tag,
    Attribute,
    Heading,
    Link,
    Escape,
    Emphasis,
    Label,
    Builtin,
}

impl From<HighlightKind> for StyleKind {
    fn from(k: HighlightKind) -> Self {
        use HighlightKind as H;
        match k {
            H::Plain => Self::Plain,
            H::Keyword => Self::Keyword,
            H::Function => Self::Function,
            H::Type => Self::Type,
            H::String => Self::String,
            H::Number => Self::Number,
            H::Comment => Self::Comment,
            H::Constant => Self::Constant,
            H::Variable => Self::Variable,
            H::Property => Self::Property,
            H::Operator => Self::Operator,
            H::Punctuation => Self::Punctuation,
            H::Tag => Self::Tag,
            H::Attribute => Self::Attribute,
            H::Heading => Self::Heading,
            H::Link => Self::Link,
            H::Escape => Self::Escape,
            H::Emphasis => Self::Emphasis,
            H::Label => Self::Label,
            H::Builtin => Self::Builtin,
        }
    }
}

/// A styled range within one line, in UTF-16 columns.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct StyleSpan {
    pub start: i32,
    pub end: i32,
    pub kind: StyleKind,
    /// Interned TextMate scope stack ([`crate::scope_stack`]); a
    /// [`crate::TokenTheme`] turns it into the VS Code theme's style.
    pub scope: i32,
}

/// One line prepared for drawing.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ViewLine {
    pub line: i32,
    /// Text without the line terminator.
    pub text: String,
    /// Document offset (UTF-16) of the line start.
    pub start_offset: i32,
    pub spans: Vec<StyleSpan>,
    /// Syntactic brackets on this line (tree-sitter tokens, so brackets inside
    /// strings/comments are excluded) with their nesting depth, for bracket
    /// pair colorization.
    pub brackets: Vec<BracketMark>,
}

/// A bracket at a UTF-16 column with its nesting depth.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct BracketMark {
    pub column: i32,
    pub depth: i32,
}

/// A selection in UTF-16 document offsets.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct SelectionRange {
    pub anchor: i32,
    pub head: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct EditOutcome {
    /// Bumped by every content change.
    pub version: i64,
    /// Whether the content changed.
    pub changed: bool,
    /// Primary selection after the edit.
    pub selection: SelectionRange,
    /// For `replace`: end (UTF-16) of the inserted text; otherwise the primary head.
    pub end: i32,
}

/// Clamps a (possibly negative) FFI index to `usize`.
fn ix(v: i32) -> usize {
    v.max(0) as usize
}

/// Receives notifications when an asynchronous background reparse finishes.
/// Called on a worker thread: post to the main thread before touching UI.
#[uniffi::export(callback_interface)]
pub trait DocumentListener: Send + Sync {
    fn on_syntax_updated(&self);
}

struct BackgroundState {
    epoch: u64,
    in_flight: bool,
    cancel: Arc<AtomicBool>,
    pending_edits: Vec<ByteEdit>,
}

impl Default for BackgroundState {
    fn default() -> Self {
        Self {
            epoch: 0,
            in_flight: false,
            cancel: Arc::new(AtomicBool::new(false)),
            pending_edits: Vec::new(),
        }
    }
}

struct Inner {
    buffer: Buffer,
    syntax: Option<Syntax>,
    /// Monotonic counter bumped on any syntax change (sync/async parse, language switch).
    syntax_version: u64,
    /// File name the document was opened with (for language auto-detection).
    file_name: Option<String>,
    /// How many times a poisoned lock was recovered (tests, diagnostics).
    recoveries: u32,
    /// Synchronous parse budget per edit (overridable in tests).
    edit_budget: Duration,
    bg: BackgroundState,
}

struct SharedState {
    inner: Mutex<Inner>,
    listener: Mutex<Option<Arc<dyn DocumentListener>>>,
}

/// Language for a file name, falling back to a `#!` line at the top of `buffer`.
fn detect_language(file_name: Option<&str>, buffer: &Buffer) -> Option<LanguageId> {
    let first_line = (buffer.len_lines() > 0).then(|| buffer.line_text(0));
    match file_name {
        Some(name) => LanguageId::detect(name, first_line.as_deref()),
        None => first_line.as_deref().and_then(LanguageId::from_shebang),
    }
}

impl Inner {
    fn invalidate_background(&mut self) {
        self.bg.cancel.store(true, Ordering::Relaxed);
        self.bg.epoch += 1;
        self.bg.in_flight = false;
        self.bg.pending_edits.clear();
    }

    fn spawn_background_parse(&mut self, state: &Arc<SharedState>) {
        let Some(s) = &self.syntax else {
            return;
        };
        self.bg.epoch += 1;
        let epoch = self.bg.epoch;
        let cancel = Arc::new(AtomicBool::new(false));
        self.bg.cancel = cancel.clone();
        self.bg.in_flight = true;
        self.bg.pending_edits.clear();
        let snap = s.snapshot(self.buffer.rope());
        let weak = Arc::downgrade(state);
        if let Err(err) = std::thread::Builder::new()
            .name("ferride-syntax".into())
            .spawn(move || run_background_parse(weak, epoch, cancel, snap))
        {
            log::warn!("failed to spawn background syntax worker: {err}");
            self.bg.in_flight = false;
        }
    }

    /// Rebuilds the syntax tree from the rope after a panic left it suspect.
    fn recover(&mut self) {
        self.invalidate_background();
        if let Some(lang) = self.syntax.as_ref().map(|s| s.language()) {
            log::warn!(
                "document lock was poisoned; reparsing {} from scratch",
                lang.name()
            );
            self.syntax = Some(Syntax::new(lang, self.buffer.rope()));
            self.syntax_version += 1;
        }
        self.recoveries += 1;
    }

    fn after_edit(&mut self, edits: &[ByteEdit], state: &Arc<SharedState>) {
        if edits.is_empty() {
            return;
        }
        let budget = self.edit_budget;
        let Some(s) = &mut self.syntax else {
            return;
        };
        s.update_with_budget(self.buffer.rope(), edits, budget);
        self.syntax_version += 1;
        if s.is_stale() {
            if self.bg.in_flight {
                self.bg.pending_edits.extend_from_slice(edits);
            } else {
                self.spawn_background_parse(state);
            }
        } else if self.bg.in_flight {
            // Synchronous fast path produced an up-to-date tree: supersede older worker.
            self.invalidate_background();
        }
    }

    fn sel_to_utf16(&self, s: Selection) -> SelectionRange {
        SelectionRange {
            anchor: self.buffer.char_to_utf16(s.anchor) as i32,
            head: self.buffer.char_to_utf16(s.head) as i32,
        }
    }

    /// UTF-16 offset → char index, never between `\r` and `\n`.
    fn snap(&self, off: i32) -> usize {
        self.buffer.snap_out_of_crlf(self.utf16(off))
    }

    fn utf16(&self, off: i32) -> usize {
        self.buffer
            .utf16_to_char(usize::try_from(off).unwrap_or(usize::MAX))
    }

    fn outcome(&self, before: i64) -> EditOutcome {
        EditOutcome {
            version: self.buffer.version() as i64,
            changed: self.buffer.version() as i64 != before,
            selection: self.sel_to_utf16(self.buffer.selections().primary()),
            end: self.buffer.char_to_utf16(self.buffer.selections().primary().head) as i32,
        }
    }
}

fn run_background_parse(
    weak: Weak<SharedState>,
    epoch: u64,
    cancel: Arc<AtomicBool>,
    mut snap: ParseSnapshot,
) {
    loop {
        if cancel.load(Ordering::Relaxed) {
            return;
        }
        let Some(tree) = snap.parse(&cancel) else {
            return;
        };
        let Some(state) = weak.upgrade() else {
            return;
        };
        let next_snap = {
            let mut inner = match state.inner.lock() {
                Ok(g) => g,
                Err(poisoned) => {
                    let mut g = poisoned.into_inner();
                    g.recover();
                    state.inner.clear_poison();
                    g
                }
            };
            if inner.bg.epoch != epoch || inner.bg.cancel.load(Ordering::Relaxed) {
                return;
            }
            let Inner {
                buffer,
                syntax,
                syntax_version,
                bg,
                ..
            } = &mut *inner;
            let pending = std::mem::take(&mut bg.pending_edits);
            let Some(s) = syntax else {
                bg.in_flight = false;
                return;
            };
            s.apply_background_tree(tree, &pending);
            *syntax_version += 1;
            if pending.is_empty() {
                bg.in_flight = false;
                None
            } else {
                Some(s.snapshot(buffer.rope()))
            }
        };
        if next_snap.is_none() {
            let listener = state
                .listener
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clone();
            if let Some(l) = listener {
                l.on_syntax_updated();
            }
            return;
        }
        snap = next_snap.unwrap();
    }
}

/// An open text document: buffer + undo history + selections + syntax tree.
#[derive(uniffi::Object)]
pub struct Document {
    state: Arc<SharedState>,
}

impl Drop for Document {
    fn drop(&mut self) {
        let mut inner = self
            .state
            .inner
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        inner.invalidate_background();
    }
}

impl Document {
    /// Locks the document. If a previous call panicked while holding the lock
    /// (say, inside a grammar during `Syntax::update`), the syntax tree may be
    /// half-updated: drop it and reparse from the rope, which is always
    /// consistent (edits are applied to the rope before the tree), then clear
    /// the poison so later calls take the fast path again.
    fn lock(&self) -> MutexGuard<'_, Inner> {
        match self.state.inner.lock() {
            Ok(guard) => guard,
            Err(poisoned) => {
                let mut guard = poisoned.into_inner();
                guard.recover();
                self.state.inner.clear_poison();
                guard
            }
        }
    }

    /// How many times a poisoned lock was recovered (tests).
    #[cfg(test)]
    pub(crate) fn recoveries(&self) -> u32 {
        self.lock().recoveries
    }

    /// Panics while holding the lock (tests of the recovery path).
    #[cfg(test)]
    pub(crate) fn poison_for_test(&self) {
        let _guard = self.state.inner.lock();
        panic!("simulated panic inside the document lock");
    }

    /// Overrides the synchronous edit parse budget (tests).
    #[cfg(test)]
    pub(crate) fn set_edit_budget_for_test(&self, budget: Duration) {
        self.lock().edit_budget = budget;
    }
}

#[uniffi::export]
impl Document {
    /// Creates a document. The grammar is picked from `file_name` (well-known
    /// names, then the extension) or else a `#!` first line.
    #[uniffi::constructor]
    pub fn new(text: String, file_name: Option<String>) -> Arc<Self> {
        let buffer = Buffer::new(&text);
        let syntax = detect_language(file_name.as_deref(), &buffer).map(|l| Syntax::new(l, buffer.rope()));
        let doc = Arc::new(Self {
            state: Arc::new(SharedState {
                inner: Mutex::new(Inner {
                    buffer,
                    syntax,
                    syntax_version: 0,
                    file_name,
                    recoveries: 0,
                    edit_budget: EDIT_PARSE_BUDGET,
                    bg: BackgroundState::default(),
                }),
                listener: Mutex::new(None),
            }),
        });
        {
            let mut inner = doc.lock();
            if inner.syntax.as_ref().is_some_and(Syntax::is_stale) {
                inner.spawn_background_parse(&doc.state);
            }
        }
        doc
    }

    /// Registers a listener for background syntax parse completion.
    pub fn set_listener(&self, listener: Box<dyn DocumentListener>) {
        let mut guard = self.state.listener.lock().unwrap_or_else(|e| e.into_inner());
        *guard = Some(Arc::from(listener));
    }

    /// Clears the syntax update listener (call when closing the editor/tab).
    pub fn clear_listener(&self) {
        let mut guard = self.state.listener.lock().unwrap_or_else(|e| e.into_inner());
        *guard = None;
    }

    /// Monotonic counter bumped whenever syntax highlighting or symbols change
    /// (including asynchronous background reparses).
    pub fn syntax_version(&self) -> i64 {
        self.lock().syntax_version as i64
    }

    /// True when the active syntax tree lagged behind the latest edit and a
    /// background reparse is pending.
    pub fn is_syntax_stale(&self) -> bool {
        self.lock().syntax.as_ref().is_some_and(Syntax::is_stale)
    }

    /// Re-runs language detection on the current file name and first line
    /// ("Auto Detect" in Change Language Mode) and returns the language id.
    pub fn detect_language(&self) -> Option<String> {
        let mut inner = self.lock();
        let lang = detect_language(inner.file_name.as_deref(), &inner.buffer);
        if inner.syntax.as_ref().map(Syntax::language) != lang {
            inner.invalidate_background();
            inner.syntax = lang.map(|l| Syntax::new(l, inner.buffer.rope()));
            inner.syntax_version += 1;
            if inner.syntax.as_ref().is_some_and(Syntax::is_stale) {
                inner.spawn_background_parse(&self.state);
            }
        }
        lang.map(|l| l.name().to_owned())
    }

    /// LSP language id of the active grammar, if any.
    pub fn language(&self) -> Option<String> {
        self.lock()
            .syntax
            .as_ref()
            .map(|s| s.language().name().to_owned())
    }

    /// Switches grammar by LSP language id (`None`/unknown → plain text).
    pub fn set_language(&self, language: Option<String>) {
        let mut inner = self.lock();
        let lang = language.as_deref().and_then(LanguageId::from_name);
        if inner.syntax.as_ref().map(Syntax::language) != lang {
            inner.invalidate_background();
            inner.syntax = lang.map(|l| Syntax::new(l, inner.buffer.rope()));
            inner.syntax_version += 1;
            if inner.syntax.as_ref().is_some_and(Syntax::is_stale) {
                inner.spawn_background_parse(&self.state);
            }
        }
    }

    pub fn text(&self) -> String {
        self.lock().buffer.text()
    }

    pub fn version(&self) -> i64 {
        self.lock().buffer.version() as i64
    }

    pub fn is_dirty(&self) -> bool {
        self.lock().buffer.is_dirty()
    }

    pub fn mark_saved(&self) {
        self.lock().buffer.mark_saved();
    }

    /// Marks `version` as the saved one (use the version captured before an
    /// async write, so edits typed during the write stay dirty).
    pub fn mark_saved_version(&self, version: i64) {
        self.lock().buffer.mark_saved_at(version.max(0) as u64);
    }

    pub fn len_utf16(&self) -> i32 {
        self.lock().buffer.len_utf16() as i32
    }

    pub fn line_count(&self) -> i32 {
        self.lock().buffer.len_lines() as i32
    }

    pub fn line_text(&self, line: i32) -> String {
        self.lock().buffer.line_text(ix(line))
    }

    /// Lines `first_line .. first_line + count` with highlight spans.
    pub fn viewport(&self, first_line: i32, count: i32) -> Vec<ViewLine> {
        let mut guard = self.lock();
        let Inner { buffer, syntax, .. } = &mut *guard;
        let b = &*buffer;
        let first = ix(first_line).min(b.len_lines());
        let end = (first + ix(count)).min(b.len_lines());
        let spans = syntax
            .as_ref()
            .map(|s| s.line_spans(b, first..end))
            .unwrap_or_default();
        let brackets = syntax
            .as_mut()
            .map(|s| s.line_brackets(b, first..end))
            .unwrap_or_default();
        (first..end)
            .enumerate()
            .map(|(i, line)| ViewLine {
                line: line as i32,
                text: b.line_text(line),
                start_offset: b.line_to_utf16(line) as i32,
                spans: spans
                    .get(i)
                    .map(|v| {
                        v.iter()
                            .map(|s| StyleSpan {
                                start: s.start_col as i32,
                                end: s.end_col as i32,
                                kind: s.kind.into(),
                                scope: s.scope as i32,
                            })
                            .collect()
                    })
                    .unwrap_or_default(),
                brackets: brackets
                    .get(i)
                    .map(|v| {
                        v.iter()
                            .map(|&(column, depth)| BracketMark {
                                column: column as i32,
                                depth: depth as i32,
                            })
                            .collect()
                    })
                    .unwrap_or_default(),
            })
            .collect()
    }

    // ----- offsets (UTF-16) ---------------------------------------------

    pub fn line_of_offset(&self, offset: i32) -> i32 {
        let inner = self.lock();
        inner.buffer.char_to_line(inner.utf16(offset)) as i32
    }

    pub fn line_start_offset(&self, line: i32) -> i32 {
        self.lock().buffer.line_to_utf16(ix(line)) as i32
    }

    /// Offset of the end of `line`, before its terminator.
    pub fn line_end_offset(&self, line: i32) -> i32 {
        let inner = self.lock();
        let b = &inner.buffer;
        b.char_to_utf16(b.line_end_char(ix(line))) as i32
    }

    /// Offset for (line, UTF-16 column), clamped to the line.
    pub fn offset_at(&self, line: i32, column: i32) -> i32 {
        let inner = self.lock();
        let b = &inner.buffer;
        b.char_to_utf16(b.line_col_utf16_to_char(ix(line), ix(column))) as i32
    }

    /// Text between two UTF-16 offsets (clamped, order-insensitive).
    pub fn text_range(&self, start: i32, end: i32) -> String {
        let inner = self.lock();
        let (a, b) = (inner.utf16(start.min(end)), inner.utf16(start.max(end)));
        inner.buffer.slice_to_string(a..b)
    }

    /// Range of the word (letters, digits, `_`) at `offset`; empty if none.
    pub fn word_at(&self, offset: i32) -> SelectionRange {
        let inner = self.lock();
        let b = &inner.buffer;
        let pos = inner.utf16(offset);
        let is_word = |c: char| c.is_alphanumeric() || c == '_';
        let mut start = pos;
        while start > 0 && b.char_at(start - 1).is_some_and(is_word) {
            start -= 1;
        }
        let mut end = pos;
        while b.char_at(end).is_some_and(is_word) {
            end += 1;
        }
        inner.sel_to_utf16(Selection::new(start, end))
    }

    /// Start of the grapheme cluster before `offset` (cursor Left): never
    /// lands inside an emoji sequence, a flag, `e` + accent or `\r\n`.
    pub fn grapheme_before(&self, offset: i32) -> i32 {
        let inner = self.lock();
        let b = &inner.buffer;
        b.char_to_utf16(b.prev_grapheme_boundary(inner.utf16(offset))) as i32
    }

    /// End of the grapheme cluster after `offset` (cursor Right).
    pub fn grapheme_after(&self, offset: i32) -> i32 {
        let inner = self.lock();
        let b = &inner.buffer;
        b.char_to_utf16(b.next_grapheme_boundary(inner.utf16(offset))) as i32
    }

    // ----- selections ----------------------------------------------------

    pub fn selection(&self) -> SelectionRange {
        let inner = self.lock();
        inner.sel_to_utf16(inner.buffer.selections().primary())
    }

    pub fn selections(&self) -> Vec<SelectionRange> {
        let inner = self.lock();
        inner
            .buffer
            .selections()
            .ranges()
            .iter()
            .map(|s| inner.sel_to_utf16(*s))
            .collect()
    }

    /// Replaces all selections with a single one.
    pub fn set_selection(&self, anchor: i32, head: i32) {
        let mut inner = self.lock();
        let sel = Selection::new(inner.snap(anchor), inner.snap(head));
        inner.buffer.set_selection(sel);
    }

    /// Adds a cursor/selection (multi-cursor) and makes it primary.
    pub fn add_selection(&self, anchor: i32, head: i32) {
        let mut inner = self.lock();
        let sel = Selection::new(inner.snap(anchor), inner.snap(head));
        let mut sels: Selections = inner.buffer.selections().clone();
        sels.push(sel);
        inner.buffer.set_selections(sels);
    }

    // ----- editing ----------------------------------------------------------

    /// Replaces `start..end` (UTF-16) with `text`. `typing` coalesces with the
    /// previous typing edit for undo (IME composition, single characters).
    ///
    /// Line-ending hygiene: a range that would split a `\r\n` pair is widened,
    /// and line breaks in `text` are converted to the document's line ending.
    /// [`EditOutcome::end`] is where the (normalized) inserted text ends.
    pub fn replace(
        &self,
        start: i32,
        end: i32,
        text: String,
        typing: bool,
    ) -> Result<EditOutcome, FerrideError> {
        let mut inner = self.lock();
        let before = inner.buffer.version() as i64;
        let (a, b) = (inner.utf16(start.min(end)), inner.utf16(start.max(end)));
        let range = inner.buffer.widen_crlf(a..b);
        let text = inner.buffer.normalize_eol(&text);
        let new_end = range.start + text.chars().count();
        let kind = if typing { EditKind::Typing } else { EditKind::Other };
        let edits = inner
            .buffer
            .apply(vec![Edit::new(range, text)], kind)
            .map_err(|e| FerrideError::InvalidArgument { msg: e.to_string() })?;
        inner.after_edit(&edits, &self.state);
        let mut out = inner.outcome(before);
        out.end = inner.buffer.char_to_utf16(new_end) as i32;
        Ok(out)
    }

    /// Types/pastes `text` at every selection.
    pub fn insert(&self, text: String, typing: bool) -> EditOutcome {
        let mut inner = self.lock();
        let before = inner.buffer.version() as i64;
        let kind = if typing { EditKind::Typing } else { EditKind::Other };
        let text = inner.buffer.normalize_eol(&text);
        let edits = inner.buffer.insert_at_selections(&text, kind);
        inner.after_edit(&edits, &self.state);
        inner.outcome(before)
    }

    /// Enter: newline plus the current line's leading whitespace, at every
    /// selection.
    pub fn newline(&self) -> EditOutcome {
        let mut inner = self.lock();
        let before = inner.buffer.version() as i64;
        let b = &inner.buffer;
        let edits: Vec<Edit> = b
            .selections()
            .ranges()
            .iter()
            .map(|s| {
                let line = b.char_to_line(s.start());
                let indent: String = b
                    .line_text(line)
                    .chars()
                    .take_while(|c| *c == ' ' || *c == '\t')
                    .collect();
                Edit::new(
                    s.start()..s.end(),
                    format!("{}{}", b.line_ending().as_str(), indent),
                )
            })
            .collect();
        let edits = inner.buffer.apply(edits, EditKind::Other).unwrap_or_default();
        inner.after_edit(&edits, &self.state);
        inner.outcome(before)
    }

    pub fn backspace(&self) -> EditOutcome {
        let mut inner = self.lock();
        let before = inner.buffer.version() as i64;
        let edits = inner.buffer.delete_backward();
        inner.after_edit(&edits, &self.state);
        inner.outcome(before)
    }

    pub fn delete_forward(&self) -> EditOutcome {
        let mut inner = self.lock();
        let before = inner.buffer.version() as i64;
        let edits = inner.buffer.delete_forward();
        inner.after_edit(&edits, &self.state);
        inner.outcome(before)
    }

    pub fn undo(&self) -> EditOutcome {
        let mut inner = self.lock();
        let before = inner.buffer.version() as i64;
        if let Some(edits) = inner.buffer.undo() {
            inner.after_edit(&edits, &self.state);
        }
        inner.outcome(before)
    }

    pub fn redo(&self) -> EditOutcome {
        let mut inner = self.lock();
        let before = inner.buffer.version() as i64;
        if let Some(edits) = inner.buffer.redo() {
            inner.after_edit(&edits, &self.state);
        }
        inner.outcome(before)
    }

    pub fn can_undo(&self) -> bool {
        self.lock().buffer.can_undo()
    }

    pub fn can_redo(&self) -> bool {
        self.lock().buffer.can_redo()
    }

    /// Ends the current typing run (call on cursor jumps, focus loss, save).
    pub fn seal_undo_group(&self) {
        self.lock().buffer.seal_undo_group();
    }
}

/// An outline symbol, flattened in document order (pre-order): `parent` is
/// the index of the enclosing symbol in the same list, or -1. Offsets are
/// UTF-16; lines are 0-based.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DocumentSymbol {
    pub name: String,
    /// LSP `SymbolKind` value (1 File … 26 TypeParameter).
    pub kind: i32,
    pub parent: i32,
    pub depth: i32,
    pub start: i32,
    pub end: i32,
    pub start_line: i32,
    pub end_line: i32,
    pub name_start: i32,
    pub name_end: i32,
}

fn flatten(
    buffer: &Buffer,
    symbols: &[ferride_syntax::Symbol],
    parent: i32,
    depth: i32,
    out: &mut Vec<DocumentSymbol>,
) {
    let off = |byte: usize| buffer.char_to_utf16(buffer.byte_to_char(byte)) as i32;
    let line = |byte: usize| buffer.byte_to_line(byte) as i32;
    for s in symbols {
        let index = out.len() as i32;
        out.push(DocumentSymbol {
            name: s.name.clone(),
            kind: s.kind as i32,
            parent,
            depth,
            start: off(s.range.start),
            end: off(s.range.end),
            start_line: line(s.range.start),
            end_line: line(s.range.end.saturating_sub(1).max(s.range.start)),
            name_start: off(s.selection.start),
            name_end: off(s.selection.end),
        });
        flatten(buffer, &s.children, index, depth + 1, out);
    }
}

#[uniffi::export]
impl Document {
    /// Outline symbols (Outline view, breadcrumbs, `@` in Quick Open, sticky scroll).
    pub fn symbols(&self) -> Vec<DocumentSymbol> {
        let inner = self.lock();
        let Some(syntax) = &inner.syntax else {
            return Vec::new();
        };
        let mut out = Vec::new();
        flatten(
            &inner.buffer,
            &syntax.symbols(inner.buffer.rope()),
            -1,
            0,
            &mut out,
        );
        out
    }
}
