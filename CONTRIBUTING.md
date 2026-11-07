# Contributing to Ferride

Thanks for helping! Ferride is early, so the most useful contributions are focused and small.

## Ground rules

- **Discuss big changes first** in an issue (new crates, new dependencies, UI direction).
- **VS Code parity is a feature.** When adding a UI feature, use VS Code's name, command id, keybinding and setting key if one exists.
- **Rust for the core, Kotlin for Android.** Text, parsing, search, LSP and git logic go in `crates/`; Android APIs, UI and IME in `android/`. Offsets crossing the FFI are UTF-16.
- **Nothing executes inside Ferride.** Processes run in Ferrux via the host interface (`ferride-host`).
- Don't copy code from GPL/LGPL projects (e.g. sora-editor, Xed-Editor). Ideas are fine; code must be Apache-2.0 compatible, with attribution where required.

## Workflow

1. Branch from `main`, keep commits small, use [Conventional Commits](https://www.conventionalcommits.org/) (`feat(buffer): …`, `fix(android): …`, `docs: …`).
2. Before pushing, run the cheap checks locally:
   ```sh
   cargo fmt --check
   cargo clippy --all-targets -- -D warnings
   cargo test -p <crate you touched>
   ```
3. Open a PR using the template. CI runs fmt/clippy/tests, builds the APK, runs unit + Roborazzi screenshot tests and an emulator smoke test, and posts screenshots on the PR. Please check them.
4. Add tests with your change: Rust unit/integration tests for the core, Robolectric tests for Kotlin logic, and a screenshot test for new screens.

## Code style

- Rust: `rustfmt` (config in `rustfmt.toml`), clippy clean with `-D warnings`, no `unwrap()` on user input paths.
- Kotlin: official Kotlin style; Compose UI reads colors from `LocalWorkbenchColors`, not hard-coded values.
