# Ferride

**A code editor for Android that feels like VS Code, built for phones.** Ferride has its own engine, a **Rust core** (rope buffer, tree-sitter highlighting, LSP client, ripgrep-powered search), and a native **Kotlin + Jetpack Compose** UI laid out like VS Code: Activity Bar, Side Bar, tabs, Panel, Status Bar, Command Palette, VS Code command ids, keybindings, `settings.json` keys and color themes. Terminals and language servers run in **[Ferrux](https://github.com/FerraStack/ferrux)**, FerraStack's Linux environment for Android.

Part of [FerraStack](https://github.com/FerraStack): Ferrux (terminal / Linux environment), Ferride (editor), Lomio (local LLM chat). Same stack everywhere: Rust core, Kotlin/Compose UI.

> **Status: pre-alpha (M0 skeleton).** The core crates work and are tested; the app opens a sample project, edits with highlighting and undo, searches, and shows the workbench. No terminal or language servers yet: they need the Ferrux Host API.

## Why not just VS Code?

Running real VS Code on Android works (see [VSCodroid](https://github.com/rmyndharis/VSCodroid)), but it means a ~200 MB APK, 400–700 MB of RAM, a WebView workbench that wasn't designed for touch, and rebasing dozens of patches every few weeks. Ferride takes the opposite trade: a small native app (tens of MB) designed for thumbs and soft keyboards, that *looks* like VS Code so it's instantly familiar. VS Code extensions don't run; language features come from LSP servers. The full reasoning is in [docs/RESEARCH.md](docs/RESEARCH.md) ("Decision update").

## What works today

- Rust core: `ropey` buffer with undo/redo (typing and IME compositions coalesce), multi-cursor-ready selections, UTF-16/char/byte offset conversions; incremental tree-sitter highlighting for Rust, Python, JavaScript/JSX, TypeScript/TSX, JSON, Markdown; LSP client (tested against a mock server over a socket fd); workspace tree, fuzzy file finder and gitignore-aware text search.
- App: VS Code-style workbench that adapts from phone portrait to tablet landscape; custom editor view with gutter, current line, indent guides, bracket pair colors, selection and an `InputConnection` that handles composing text; symbols row; Command Palette (`>`, `:`, `@`) with VS Code keybindings on hardware keyboards; settings UI backed by `settings.json`; Dark/Light Modern themes and VS Code color theme loading.
- Stubbed: terminal panel ("Requires Ferrux"), language servers, git, symbol search, find/replace widget.

## Architecture

```
Kotlin/Compose workbench ── CodeEditorView (+ InputConnection)
        │ UniFFI (JNA)
ferride-ffi ─┬─ ferride-buffer     rope, edits, undo, selections, offsets
             ├─ ferride-syntax     tree-sitter, incremental parse, viewport highlights
             ├─ ferride-workspace  file tree, fuzzy find, ripgrep search
             ├─ ferride-lsp        JSON-RPC/LSP client over any byte stream
             ├─ ferride-scm        git on gix: status, diff, stage, commit, branches, clone/pull/push
             └─ ferride-host       ProcessHost trait (Ferrux integration, stub today)
                     │ AIDL + file descriptors
                   Ferrux: shells, PTYs, language servers, toolchains
```

Details, threading, IME design and milestones: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Building

Heavy builds run in GitHub Actions; every PR gets an APK artifact and screenshots posted as a comment.

**Rust core** (any Linux/macOS with Rust 1.88+):

```sh
cargo fmt --check
cargo clippy --all-targets -- -D warnings
cargo test
```

**Android app** needs JDK 17–25 (Gradle 9.8 doesn't run on JDK 26+; JDK 21 or 25 recommended), the Android SDK (platform 37), an NDK r28+ (16 KB page alignment), Rust Android targets and `cargo-ndk`:

```sh
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk
export ANDROID_HOME=~/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/<version>
export JAVA_HOME=/path/to/jdk-21   # if your default JDK is newer than 25
cd android
./gradlew assembleDebug            # runs cargo ndk + uniffi-bindgen first
./gradlew testDebugUnitTest -Proborazzi.test.record=true   # unit + screenshot tests
```

The Gradle task `cargoNdkBuild` builds `libferride_ffi.so` for `arm64-v8a` and `x86_64`, and `uniffiBindgen` generates the Kotlin bindings from it; both are wired into every variant automatically.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). License: [Apache-2.0](LICENSE).
