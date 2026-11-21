# Ferride

[![CI](https://github.com/FerraStack/ferride/actions/workflows/ci.yml/badge.svg)](https://github.com/FerraStack/ferride/actions/workflows/ci.yml)
[![Latest Release](https://img.shields.io/github/v/release/FerraStack/ferride)](https://github.com/FerraStack/ferride/releases/latest)

**A code editor for Android that feels like VS Code, built for phones.** Ferride has its own engine, a **Rust core** (rope buffer, tree-sitter highlighting, LSP client, ripgrep-powered search, `gix` Git support), and a native **Kotlin + Jetpack Compose** UI laid out like VS Code: Activity Bar, Side Bar, tabs, Panel, Status Bar, Command Palette, VS Code command ids, keybindings, `settings.json` keys and color themes. Terminals and language servers run in **[Ferrux](https://github.com/FerraStack/ferrux)**, FerraStack's Linux environment for Android.

Download the latest release APKs from **[GitHub Releases](https://github.com/FerraStack/ferride/releases/latest)** (`foss` for F-Droid/GitHub with optional all-files access, `play` for SAF + app-private storage).

Part of [FerraStack](https://github.com/FerraStack): Ferrux (terminal / Linux environment), Ferride (editor), Lomio (local LLM chat). Same stack everywhere: Rust core, Kotlin/Compose UI.

## Why not just VS Code?

Running real VS Code on Android works (see [VSCodroid](https://github.com/rmyndharis/VSCodroid)), but it means a ~200 MB APK, 400–700 MB of RAM, a WebView workbench that wasn't designed for touch, and rebasing dozens of patches every few weeks. Ferride takes the opposite trade: a small native app (tens of MB) designed for thumbs and soft keyboards, that *looks* like VS Code so it's instantly familiar. VS Code extensions don't run; language features come from LSP servers. The full reasoning is in [docs/RESEARCH.md](docs/RESEARCH.md) ("Decision update").

## Features

- **Editor & syntax highlighting**: `ropey` buffer with coalesced undo/redo, UTF-16/char/byte offset conversions, custom Android editor view with gutter, sticky scroll, indent guides, bracket pair colors, selection handles/toolbar, composing-text IME support, and a touch symbols row. Incremental tree-sitter highlighting across 17 languages (Rust, Python, JavaScript/JSX, TypeScript/TSX, JSON, Markdown, C, C++, Go, Java, Kotlin, HTML, CSS, YAML, TOML, Shell Script, SQL) with automatic language detection.
- **LSP via Ferrux**: Live language server integration over Ferrux pipes (`ferride-lsp`) supporting diagnostics (inline squiggles + Problems panel), completions, hover cards, document formatting, go-to-definition, document/workspace symbols, server restart, and per-language status in the Languages sidebar.
- **Integrated Terminal PTY via Ferrux**: Live terminal sessions inside the bottom panel backed by Ferrux PTYs and `dev.ferrux:terminal-view`, plus direct browsing of Ferrux's `$HOME` workspace.
- **Git / Source Control**: Native Git engine (`ferride-scm` on `gix`) with status, inline diffs, staging/unstaging, commits, branch switching/creation, and clone/pull/push with Android Keystore-backed credentials.
- **Search & navigation**: Ripgrep-powered workspace text search, fuzzy Quick Open (`Ctrl+P`), Command Palette (`>`, `:`, `@`, `#`), in-editor Find & Replace widget, and symbol outline.
- **Workspaces, encodings & themes**: App-private projects, SAF folder picker, optional all-files access (`foss`), Ferrux `$HOME`, configurable file encodings (UTF-8, UTF-16, Latin-1, Windows-125x, etc.) and line endings (LF/CRLF), `settings.json`, Dark/Light Modern themes, and VS Code color theme loading.

## Architecture

```
Kotlin/Compose workbench ── CodeEditorView (+ InputConnection)
        │ UniFFI (JNA)
ferride-ffi ─┬─ ferride-buffer     rope, edits, undo, selections, offsets, encodings
             ├─ ferride-syntax     tree-sitter, incremental parse, viewport highlights
             ├─ ferride-workspace  file tree (local / SAF / Ferrux), fuzzy find, ripgrep search
             ├─ ferride-lsp        JSON-RPC/LSP client over Ferrux process streams
             ├─ ferride-scm        git on gix: status, diff, stage, commit, branches, clone/pull/push
             └─ ferride-host       ProcessHost / Terminal / Ferrux API v1 bridge
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
./gradlew assemblePlayDebug        # runs cargo ndk + uniffi-bindgen first (assembleFossDebug for the foss build)
./gradlew testPlayDebugUnitTest -Proborazzi.test.record=true   # unit + screenshot tests
```

The Gradle task `cargoNdkBuild` builds `libferride_ffi.so` for `arm64-v8a` and `x86_64`, and `uniffiBindgen` generates the Kotlin bindings from it; both are wired into every variant automatically.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). License: [Apache-2.0](LICENSE).
