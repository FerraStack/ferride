# Ferride architecture

**Status:** living document, describes what's built and what's planned.
**Date:** 2026-11-07
**Decision context:** [`RESEARCH.md`](RESEARCH.md) → "Decision update (2026-11-07): own editor".

Ferride is a phone-first code editor for Android: a **Rust core** for everything about text, syntax, search, language servers and (later) git, and a **Kotlin + Jetpack Compose** app for everything Android. Programs (terminals, language servers, build tools) never run inside Ferride; they run in **Ferrux**, FerraStack's Linux environment, reached over a signature-protected AIDL service.

```
┌──────────────────────────── Ferride app (Kotlin) ────────────────────────────┐
│ Compose UI: drawer/file tree · tabs · search · symbols row · terminal panel  │
│        │                                                                     │
│ CodeEditorView (android.view.View + InputConnection)  ← IME, touch, keys     │
│        │  EditorModel interface (RustEditorModel / FakeEditorModel)          │
│        ▼                                                                     │
│ UniFFI Kotlin bindings (dev.ferride.core, JNA)                               │
├────────────────────────────── libferride_ffi.so ─────────────────────────────┤
│ ferride-ffi ── ferride-buffer  (rope, edits, undo, selections, offsets)      │
│            ├── ferride-syntax  (tree-sitter, incremental parse, highlights)  │
│            ├── ferride-workspace (folder, tree, fuzzy find, ripgrep search)  │
│            ├── ferride-lsp     (JSON-RPC client over any byte stream)        │
│            └── ferride-host    (ProcessHost trait; stub until Ferrux API)    │
└──────────────────────────────────────────────────────────────────────────────┘
                  │ Binder (control) + ParcelFileDescriptor (bytes)
┌─────────────────▼──────────── Ferrux app ────────────────────────────────────┐
│ Host service: spawn / PTY / resize / signal · language servers · shells · git│
└──────────────────────────────────────────────────────────────────────────────┘
```

## Rust crates (`crates/`)

| Crate | Role | Key dependencies |
|---|---|---|
| `ferride-buffer` | Text model: `ropey` rope, batched edits, undo/redo with typing/IME coalescing, multi-cursor `Selections`, offset conversions char ↔ byte ↔ UTF-16 ↔ line/column, CRLF awareness, grapheme cluster boundaries (cursor Left/Right and Delete skip whole clusters; Backspace removes a whole emoji sequence, otherwise one code point, like VS Code's `deleteLeft`). Emits `ByteEdit`s for tree-sitter. | `ropey`, `unicode-segmentation` |
| `ferride-syntax` | One `Syntax` per document: tree-sitter parser, incremental reparse from the rope (no copy), highlight queries → viewport-scoped style spans (bytes or per-line UTF-16 columns). Grammars: Rust, Python, JavaScript/JSX, TypeScript, TSX, JSON, Markdown (block). | `tree-sitter`, `tree-sitter-*` |
| `ferride-lsp` | LSP client: `Content-Length` codec, request/response correlation with timeouts and `$/cancelRequest`, default answers to server→client requests, events for diagnostics/messages, typed helpers (initialize, didOpen/didChange/didSave/didClose, completion, hover, shutdown). Transport is any `Read` + `Write`. | `lsp-types`, `serde_json` |
| `ferride-workspace` | Opened folder: path confinement, directory listing, atomic writes, create/rename/delete, gitignore-aware walk, fuzzy file finder, full-text search with ripgrep's engine. | `ignore`, `grep-searcher`, `grep-regex` |
| `ferride-host` | `ProcessHost` / `HostProcess` traits (spawn, take I/O streams, resize PTY, kill, wait). `UnavailableHost` stub (what the app uses today), `LocalHost` for desktop dev/tests. `FerruxHost` comes with the Ferrux Host API. | — |
| `ferride-ffi` | UniFFI surface: `Document`, `WorkspaceHandle`, `LspSession` + `LspListener` callback, `ferruxStatus()`. All offsets crossing the FFI are **UTF-16 `Int`s**. Also builds the `uniffi-bindgen` binary (feature `bindgen`). | `uniffi` |

Planned: `ferride-git` (gix with the rustls/`ring` backend first; `git2` only if gix lacks something we need), `ferride-term` glue around Ferrux's `ferrux-term` crate (git dependency) for the terminal emulator state, a `ferride-cli` package shipped into Ferrux (`ferride .` opens a folder in the app).

## Kotlin app (`android/`)

Single `:app` module for now (split into `:core-bindings`, `:editor-view`, `:feature-*` once the surface stabilises).

| Package | Contents |
|---|---|
| `dev.ferride.core` | UniFFI-generated bindings (build output, not committed). |
| `dev.ferride.editor` | `CodeEditorView` (custom `View`), `EditorInputConnection`, `EditorModel` interface with `RustEditorModel` (wraps `Document`) and `FakeEditorModel` (pure Kotlin, for previews and JVM screenshot tests), `EditorActions`, `EditorStyle`. |
| `dev.ferride.workbench` | `WorkbenchState` (views, tabs, panel, palette, search, settings), `CommandRegistry` + `CommandIds` + `KeyChord`, `Settings` (`settings.json`), `WorkspaceBackend` (`RustWorkspaceBackend` / `FakeWorkspaceBackend`), `SampleProject`. |
| `dev.ferride.theme` | `WorkbenchColors` (VS Code color ids), built-in *Ferride Dark/Light Modern*, `VsCodeTheme` loader (`colors` + `tokenColors`). |
| `dev.ferride.ui` | Compose workbench shell: Activity Bar, Side Bar views, editor area (tabs, breadcrumbs), Panel, Status Bar, Command Palette, symbols row. |
| `dev.ferride` | `MainActivity` (edge-to-edge, hardware keybindings → command registry), `FerrideViewModel`. |

### Threading

- **UI thread:** all `Document` calls (`replace`, `viewport`, offsets). They are short: an edit is a rope operation plus an incremental tree-sitter reparse, and `viewport()` runs the highlight query only over visible lines. `Document` is internally locked, so it's safe from any thread.
- **`Dispatchers.IO`:** workspace listing, file read/write, fuzzy find and text search, LSP requests (they block until the response or timeout).
- **Rust background threads:** one reader thread per LSP session; it calls `LspListener` (Kotlin), which must hop to the main thread before touching UI state.
- **Parse budgets:** the initial parse (on the IO thread, together with reading the file) gets 1.5 s; an incremental reparse after an edit gets 40 ms on the UI thread. If a budget runs out the edited old tree is kept (highlights stay aligned but may be stale) and the next edit retries. Background parsing on a rope snapshot is tracked in #14.
- **Model lifetime:** closing or replacing a tab only *retires* its native `Document`; it's freed after the editor view has switched to the next model, so neither drawing nor late IME callbacks can touch a freed object.

## The editor view and the IME

Compose's `BasicTextField` is not built for 10k-line documents, per-token styling, a gutter or multi-cursor, so the editor is a classic Android `View` hosted with `AndroidView`, in the same spirit as sora-editor and Squircle's editor (ideas only; no code is copied from either).

- **Drawing:** the view asks the model for the visible lines (`viewport(first, count)`), draws a gutter with line numbers, current-line highlight, selection rectangles, text runs per style span, and a blinking caret. Monospace font; x positions come from `Paint.measureText` on the line prefix, so wide glyphs and emoji still line up with the caret. Lines are cached per document version.
- **Scrolling:** `GestureDetector` + `OverScroller` (drag, fling), vertical and horizontal.
- **Touch:** tap places the caret and shows the keyboard (plus a caret handle that hides after 4 s); double tap / long press selects a word, and keeping the finger down after a long press extends the selection word by word. Selections get Android-style teardrop **handles** (accent color = `focusBorder`) that can be dragged with the platform magnifier and edge auto-scroll; handles never cross. A floating **selection toolbar** (Cut / Copy / Paste / Select All, VS Code's labels and `menu.*` colors) sits above the selection, or below it when there's no room; it hides while scrolling and comes back afterwards, and tapping the caret toggles Paste / Select All. Typing, hardware keys, IME cursor moves, focus loss and tab switches dismiss the handles; Back closes the toolbar. The view reports the toolbar anchor (`SelectionUi`) and Compose draws the bar in the same window, so it shows up in Roborazzi and UI Automator.
- **IME:** `onCreateInputConnection` returns `EditorInputConnection`, a `BaseInputConnection` subclass that does **not** use an `Editable` mirror. It implements `commitText`, `setComposingText`, `setComposingRegion`, `finishComposingText`, `deleteSurroundingText(InCodePoints)`, `getTextBefore/AfterCursor`, `getSelectedText`, `setSelection`, `sendKeyEvent`, `performContextMenuAction` (select all / cut / copy / paste) and batch edits directly against the Rust document, using UTF-16 offsets end to end. The composing region is tracked in the view and underlined. After every change the view calls `InputMethodManager.updateSelection` with the selection and composing range so keyboards (Gboard, SwiftKey, Samsung) stay in sync. Composition updates are sent as `typing` edits, which the buffer coalesces into one undo step. Typing is grouped **per word**: a new undo step starts when a word character follows a non-word one, after a pause of more than 500 ms (`TYPING_PAUSE`), on a newline or a cursor move; composition updates merge regardless of pauses.
- **Input type:** `TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_MULTI_LINE | TYPE_TEXT_FLAG_NO_SUGGESTIONS`, `IME_FLAG_NO_EXTRACT_UI | IME_FLAG_NO_FULLSCREEN`: code doesn't want autocorrect, and landscape must not switch to the full-screen extract UI.
- **Hardware keyboards:** `onKeyDown` handles arrows (with Shift to extend), Home/End, Page Up/Down, Tab, Enter (auto-indent), Backspace/Delete, Ctrl+Z/Y/A/C/X/V/S, and printable characters via `KeyEvent.unicodeChar`.
- **Symbols row:** a Compose row pinned above the keyboard (`imePadding`) with Tab, brackets, quotes, arrows, undo and redo, calling the view's public API.
- **CursorAnchorInfo** (floating-cursor and handwriting support) and accessibility (`AccessibilityNodeProvider` exposing text and selection) are M2 items.

## Workbench UX: VS Code look and feel, phone-first

Ferride is its own engine, but it should **look and feel like VS Code / VSCodium** so people coming from VS Code feel at home. Every surface below uses VS Code's names, layout and defaults, adapted for touch.

### Layout

| Part | Expanded (tablet, landscape, DeX) | Compact (phone, portrait) |
|---|---|---|
| Activity Bar (Explorer, Search, Source Control, Languages, Settings) | left rail | bottom bar (hidden while the keyboard is up); Medium width uses the rail |
| Side Bar | docked, 280 dp | drawer over the editor |
| Editor group | tabs (preview tabs in italic, dirty dot, close button), breadcrumbs, editor | same; a "command center" title bar on top opens Quick Open |
| Panel (Problems, Output, Terminal) | docked bottom, 220 dp | bottom section, ~40% height |
| Status Bar | Ferrux status, branch, errors/warnings, Ln/Col, indentation, encoding, EOL, language mode, LSP status | the same, fewer items |
| Extra keys | — (hardware keyboard) | symbols row above the keyboard |

Width classes follow Material breakpoints (compact < 600 dp ≤ medium < 840 dp ≤ expanded). The "Languages" view is Ferride's equivalent of Extensions: one entry per language with its bundled grammar and the language server it will run in Ferrux.

### Commands, palette, keybindings

- Every action is a **command with a VS Code id** (`workbench.action.files.save`, `workbench.action.showCommands`, `editor.action.formatDocument`, …) in one `CommandRegistry`. The palette, keybindings, toolbar buttons and menus all execute commands by id.
- **Command Palette / Quick Open** with VS Code prefixes: no prefix = files (fuzzy), `>` = commands, `:` = go to line, `@` = symbols (stub until LSP/tree-sitter symbols land).
- **Keybinding contexts:** text-editing chords (Ctrl+C/V/X/A/Z/Y) carry VS Code's `when: editorTextFocus` and only fire while the editor view has focus, so they keep their normal meaning in the palette, search box and settings fields.
- **Keybindings** match VS Code's defaults for hardware keyboards (Ctrl+Shift+P, Ctrl+P, Ctrl+S, Ctrl+B, Ctrl+J, Ctrl+\`, Ctrl+G, Ctrl+Shift+E/F/G/X/M, Ctrl+W, Ctrl+PageUp/PageDown, Ctrl+Z/Y, Ctrl+=/-, …). `MainActivity.dispatchKeyEvent` routes chords to the registry before the focused view. User `keybindings.json` (VS Code format) is planned.

### Settings and themes

- **Settings editor** opens as an editor tab (gear icon) with a search box, a table of contents on wide screens (Commonly Used / Text Editor / Workbench / Files / Ferride), checkboxes, compact number fields and dropdowns, and an "Open Settings (JSON)" title action. UI changes are written into `settings.json` as **minimal JSONC edits** (comments, ordering and unknown keys are preserved); if the file doesn't parse, Ferride never overwrites it and opens it for fixing.
- `settings.json` with VS Code key names (`editor.fontSize`, `editor.tabSize`, `editor.insertSpaces`, `editor.wordWrap`, `editor.lineNumbers`, `editor.minimap.enabled`, `editor.guides.indentation`, `editor.renderLineHighlight`, `editor.bracketPairColorization.enabled`, `files.autoSave`, `workbench.colorTheme`, `window.autoDetectColorScheme`); unknown keys are preserved. A settings UI edits the same file.
- Built-in **Ferride Dark Modern / Light Modern** (our own colors in that style), following the system light/dark mode by default.
- **VS Code color themes** (`*-color-theme.json`) load as-is: `colors` map to `WorkbenchColors` by their VS Code ids, and `tokenColors` resolve through a table that maps each tree-sitter highlight class to the **TextMate scopes** VS Code grammars produce for the same tokens (`keyword.control`, `entity.name.function`, `string.quoted`, …), longest-prefix match wins. Drop files into `files/themes/` for now; an import UI comes later. Finer mapping (capture name → scope, not just class) is a follow-up.

### Editor behaviors

Done now: current line highlight, indent guides, bracket pair colorization (bracket tokens and depths come from the tree-sitter tree in Rust, so brackets in strings/comments are ignored), line numbers, **word wrap** (`editor.wordWrap`; the default is on for compact/phone windows and off otherwise, continuation rows indented like the line, numbers only on the first visual row), thin scrollbars (horizontal when wrap is off), find/replace widget (case/word/regex, n of m, replace/replace all), diagnostics squiggles + Problems panel, VS Code-style copy/cut of the whole line without a selection, smart Home, auto-indent on Enter, unsaved-changes prompt on close.
Done in M1: selection handles and floating toolbar (#8).
Planned: minimap (optional, off on phones), multi-cursor UI (the buffer already supports it), snippets in VS Code snippet JSON format, sticky scroll, code folding.

## Syntax highlighting

1. The document creates `Syntax::new(language, rope)` on open (language from the file extension).
2. Each edit returns `ByteEdit`s (start/old end/new end in bytes and rows/byte-columns); `Syntax::update` calls `Tree::edit` for each and reparses with the old tree, reading the rope chunk by chunk.
3. `line_spans(first..last)` runs a `QueryCursor` restricted to the visible byte range and flattens nested captures: **inner nodes win over outer ones; for the same node the later pattern wins**, so the generic patterns at the top of upstream queries are refined by specific ones below. Capture names map to a small fixed set of `StyleKind`s; the app's theme picks colors.
4. Next: injections (Markdown fenced code, JS in HTML), the Markdown inline grammar, folding ranges and indentation from tree-sitter, more grammars (C/C++, Go, Kotlin, Java, TOML, YAML, HTML/CSS, Bash).

## LSP

- Language servers run **inside Ferrux** (rust-analyzer, pyright/basedpyright, typescript-language-server, …). Ferride spawns them through the process host and gets a socketpair fd; `LspSession.connectFds(fd, fd, listener)` wraps it.
- Positions are UTF-16 (we advertise only `utf-16` in `general.positionEncodings`), matching both LSP's default and Android's strings.
- Text sync: full-document `didChange` for now; incremental sync from `ByteEdit`s/UTF-16 ranges is planned once servers run on device.
- Features in this round: initialize/shutdown, open/change/save/close, completion, hover, diagnostics and messages as events. Next: signature help, go to definition, references, rename, formatting, code actions, semantic tokens (merged over tree-sitter spans).

## Workspace, file tree and search

- A workspace is a folder path. Today the app uses app-private storage (`filesDir/projects`, seeded with a sample project); folders in Ferrux's `$HOME` come through the host (and its `DocumentsProvider`) once the Host API exists. SAF trees would need a mirror (VSCodroid's approach, which breaks `.git`); not planned for the MVP.
- The tree is loaded lazily, one directory per expand (`listDir`), dirs first, `.git` hidden.
- Quick open: gitignore-aware walk + fuzzy subsequence scoring (file-name and word-start bonuses).
- Text search: ripgrep's `grep-searcher`/`grep-regex` (literal or regex, case, whole word), binary files and ignored paths skipped, results capped, match ranges as UTF-16 columns.
- File watching (`notify`, inotify) is optional and off for now; the tree refreshes on demand. It's unreliable on FUSE shared storage anyway.

## Git

Planned crate `ferride-git` on **gix** (pure Rust, builds with the NDK; use the `ring`/rustls backend, aws-lc-rs is harder to cross-compile). Scope for the MVP: status (gutter markers, tree decorations), diff against HEAD, stage/unstage, commit, branch switch. Network operations (clone/fetch/push with credentials) either via gix with HTTPS or by running `git` inside Ferrux, whichever is more robust on device.

## Terminal and the Ferrux host

- **Rust side:** `ferride_host::ProcessHost` (`status`, `spawn(SpawnSpec { argv, env, cwd, pty })` → `HostProcess { take_io, resize, kill, wait }`). The app currently uses `UnavailableHost`, so `ferruxStatus()` reports `NotInstalled` and the terminal panel says **"Requires Ferrux"**.
- **Kotlin side:** `FerruxConnector` will bind Ferrux's host service (`<queries>` + `bindService`, permission `signature|knownSigner`, caller-UID checks on the Ferrux side), call `spawn`, receive `ParcelFileDescriptor`s, and pass raw fds (`detachFd()`) into Rust. The PTY master stays in Ferrux; Ferride gets a socketpair plus `resize()` over Binder (RESEARCH.md §5, §10).
- **Emulator:** reuse Ferrux's `ferrux-term` crate (git dependency) for VT parsing and the screen model, rendered by a Kotlin `TerminalView` like the editor view. Shared sessions (`ferrux attach`) let a shell started in Ferrux show up in Ferride and vice versa.
- **Phantom-process budget:** language servers and shells count against Android's 32-process limit shared by both apps; Ferride starts servers lazily and stops idle ones.

## Build

- `cargo test`, `cargo clippy --all-targets -- -D warnings`, `cargo fmt --check` on Linux.
- Android: Gradle task `cargoNdkBuild` runs `cargo ndk -t arm64-v8a -t x86_64 build --release -p ferride-ffi` into a build directory registered as generated `jniLibs`; `uniffiBindgen` builds the host `uniffi-bindgen` and generates Kotlin from the arm64 `.so` (library mode) into a generated source dir. Both are wired through the AGP Variant API, so `assembleDebug` triggers them.
- Toolchains are pinned: Rust in `rust-toolchain.toml`, NDK r30 (`30.0.16248370`) in CI, third-party actions by commit SHA; cargo runs with `--locked`.
- 16 KB page alignment: NDK r28+ links with 16 KB max page size by default; CI checks `zipalign -c -P 16`.
- Heavy builds run in GitHub Actions (see `.github/workflows/ci.yml`); local work is limited to fmt/clippy/tests of single crates.
- **Screenshots:** Robolectric + Roborazzi render the main screens on the JVM with the fake backend (phone and tablet, light and dark); an emulator job installs the real APK, opens the sample file and captures it. On pull requests CI pushes the PNGs to the `ci-screenshots` branch and keeps one PR comment with a light/dark table up to date.

## MVP milestones

| # | Milestone | Scope | Exit criteria |
|---|---|---|---|
| **M0** | Skeleton (this round) | Rust core crates with tests; UniFFI; VS Code-style workbench shell (Activity Bar, Side Bar, tabs, breadcrumbs, Panel, Status Bar), command registry + palette + keybindings, settings.json, Dark/Light Modern + VS Code theme loading; `CodeEditorView` with highlighting and IME; symbols row; workspace search; terminal placeholder; CI building the APK with screenshots on PRs | APK installs; open/edit/save a file with highlighting and undo on a device; IME matrix recorded (Gboard, SwiftKey, Samsung) |
| **M1** | Editing polish | Selection handles + floating toolbar, CursorAnchorInfo, find/replace widget, bracket matching, auto-close pairs, indentation from tree-sitter, word wrap, pinch zoom, `keybindings.json`, theme import UI, sticky scroll, folding, more grammars | Comfortable one-handed editing of a 5k-line file at 60 fps on a mid-range phone |
| **M2** | Ferrux Host API v1 | Agree `ferrux-host-proto` with Ferrux; `FerruxConnector` + `FerruxHost`; pairing/permission UX; open folders in Ferrux `$HOME` | Ferride lists and edits files in Ferrux's home; `ferruxStatus()` = Ready |
| **M3** | Terminal | `ferrux-term` integration, `TerminalView`, PTY via host, multiple sessions, attach to Ferrux sessions, extra-keys for the terminal | Run `cargo build`/`python` in the panel; sessions survive app switches |
| **M4** | LSP on device | Server registry (per language, command in Ferrux), lazy start/stop, diagnostics in gutter and squiggles, completion popup, hover, go to definition, rename, formatting, incremental sync | rust-analyzer, pyright and typescript-language-server usable on a 6 GB phone |
| **M5** | Git | `ferride-git` (gix): status, diff gutter, stage/commit, branches; push/pull | Daily commit workflow without leaving the app |
| **M6** | Release 0.1 | Crash/ANR hardening, state restore, accessibility pass, split editors on tablets/foldables/DeX, snippets (VS Code JSON), minimap, multi-cursor UI, reproducible signed builds, F-Droid + GitHub, Play listing | Public 0.1 |

## Non-goals (for now)

VS Code extension compatibility, a plugin system (revisit with WASM later), debugging (DAP is possible after M4), remote/SSH workspaces.
