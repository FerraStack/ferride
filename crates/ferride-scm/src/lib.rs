//! Source control for Ferride, built on [gix](https://github.com/GitoxideLabs/gitoxide) (pure Rust).
//!
//! The crate is path based: it works on a working tree on a real file system path (app-private projects or
//! Ferrux's home), never on SAF documents. Everything VS Code's Source Control view needs lives here:
//!
//! - repository discovery and `init`
//! - [status](Repository::status) grouped like VS Code (Merge Changes / Staged Changes / Changes)
//! - [file diffs](Repository::diff) with hunks and word-level highlights, plus gutter [line changes](textdiff::line_changes)
//! - stage / unstage / discard for whole files and single hunks
//! - [commit](Repository::commit) (author from settings or the repository config), amend
//! - paged [log](Repository::log) with branch and tag decorations
//! - branches: list, create, checkout (refuses to overwrite local changes, like git)
//! - network over HTTPS: [clone], [fetch](Repository::fetch), [pull](Repository::pull) (fast-forward only) and
//!   [push](Repository::push), with progress, cancellation and credentials supplied by the app through
//!   [`CredentialProvider`]. Secrets are never logged or included in errors.
//!
//! Paths crossing this API are repository-relative with `/` separators.

mod branch;
mod commit;
mod diff;
mod error;
mod index_ops;
mod log;
mod net;
mod push;
mod status;
pub mod textdiff;
mod worktree;

use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::SystemTime;

pub use branch::{Branch, BranchKind, Head};
pub use commit::{CommitOptions, Identity};
pub use diff::{DiffSide, FileDiff};
pub use error::{Result, ScmError};
pub use log::{CommitInfo, LogQuery, RefLabel, RefLabelKind};
pub use net::{Credential, CredentialProvider, NetworkOptions, Progress, ProgressSink, clone};
pub use push::PushOptions;
pub use status::{Change, ChangeGroup, ChangeKind, Status};
pub use textdiff::{ColumnRange, HunkId, LineChange, LineChangeKind};

/// An opened git repository with a working tree.
///
/// Cheap to share between threads: every operation works on a fresh thread-local handle, so it always sees the
/// current state on disk (refs, index). The parsed config is reused until `.git/config` changes.
pub struct Repository {
    inner: Mutex<Cached>,
    git_dir: PathBuf,
    workdir: PathBuf,
}

struct Cached {
    repo: gix::ThreadSafeRepository,
    config_stamp: Option<(SystemTime, u64)>,
}

fn config_stamp(git_dir: &Path) -> Option<(SystemTime, u64)> {
    let meta = std::fs::metadata(git_dir.join("config")).ok()?;
    Some((meta.modified().ok()?, meta.len()))
}

impl std::fmt::Debug for Repository {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Repository")
            .field("workdir", &self.workdir)
            .finish()
    }
}

/// Options used for every repository Ferride opens: only the repository's own config counts (no
/// `$HOME/.gitconfig`, `/etc/gitconfig` or `GIT_*` environment variables), so behaviour does not depend on the
/// device or on whatever environment the app was started in. The app supplies identity and credentials itself.
fn open_options() -> gix::open::Options {
    gix::open::Options::isolated()
}

impl Repository {
    /// Finds the repository containing `path` (the path itself or one of its parents). Returns `Ok(None)` if
    /// there is none, and an error for bare repositories (there is nothing to show without a working tree).
    pub fn discover(path: impl AsRef<Path>) -> Result<Option<Self>> {
        // Any failure to find one means "no repository" for the UI (VS Code: "The folder currently open
        // doesn't have a git repository").
        let Ok((repo_path, _trust)) = gix::discover::upwards(path.as_ref()) else {
            return Ok(None);
        };
        let (git_dir, _) = repo_path.into_repository_and_work_tree_directories();
        let repo = gix::ThreadSafeRepository::open_opts(git_dir, open_options()).map_err(ScmError::git)?;
        Self::from_gix(repo).map(Some)
    }

    /// Opens the repository whose working tree (or `.git` directory) is exactly `path`.
    pub fn open(path: impl AsRef<Path>) -> Result<Self> {
        let path = path.as_ref();
        let repo = gix::ThreadSafeRepository::open_opts(path, open_options())
            .map_err(|_| ScmError::NotARepository(path.display().to_string()))?;
        Self::from_gix(repo)
    }

    /// `git init` in `path` (created if needed). The initial branch is `main`, like VS Code's
    /// `git.defaultBranchName`.
    pub fn init(path: impl AsRef<Path>) -> Result<Self> {
        Self::init_with_branch(path, "main")
    }

    /// `git init` with a given initial branch name.
    pub fn init_with_branch(path: impl AsRef<Path>, branch: &str) -> Result<Self> {
        let path = path.as_ref();
        validate_branch_name(branch)?;
        std::fs::create_dir_all(path)?;
        let repo = gix::ThreadSafeRepository::init_opts(
            path,
            gix::create::Kind::WithWorktree,
            gix::create::Options::default(),
            open_options(),
        )
        .map_err(ScmError::git)?;
        let head = repo.git_dir().join("HEAD");
        std::fs::write(&head, format!("ref: refs/heads/{branch}\n"))?;
        Self::from_gix(repo)
    }

    fn from_gix(inner: gix::ThreadSafeRepository) -> Result<Self> {
        let workdir = inner
            .work_dir()
            .map(Path::to_path_buf)
            .ok_or_else(|| ScmError::InvalidInput("bare repositories are not supported".into()))?;
        let git_dir = inner.git_dir().to_path_buf();
        let config_stamp = config_stamp(&git_dir);
        Ok(Repository {
            inner: Mutex::new(Cached {
                repo: inner,
                config_stamp,
            }),
            git_dir,
            workdir,
        })
    }

    /// The working tree root.
    pub fn root(&self) -> &Path {
        &self.workdir
    }

    /// The `.git` directory.
    pub fn git_dir(&self) -> &Path {
        &self.git_dir
    }

    pub(crate) fn repo(&self) -> gix::Repository {
        let stamp = config_stamp(&self.git_dir);
        let mut cached = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        if cached.config_stamp != stamp
            && let Ok(fresh) = gix::ThreadSafeRepository::open_opts(&self.git_dir, open_options())
        {
            *cached = Cached {
                repo: fresh,
                config_stamp: stamp,
            };
        }
        let mut repo = cached.repo.to_thread_local();
        drop(cached);
        // Reflog entries need a committer; commits themselves resolve their identity explicitly (see `commit`).
        let _ = repo.committer_or_set_fallback("Ferride", "ferride@localhost");
        repo
    }

    /// Resolves a repository-relative path to an absolute one, refusing paths that escape the working tree or
    /// point into `.git`.
    pub(crate) fn abs_path(&self, rela: &str) -> Result<PathBuf> {
        let rela = normalize_rela(rela)?;
        Ok(self.workdir.join(rela))
    }
}

/// Validates and normalizes a repository-relative path (`/` separated, no `.`/`..`, not inside `.git`).
pub(crate) fn normalize_rela(rela: &str) -> Result<String> {
    let rela = rela.trim_start_matches('/');
    let mut parts = Vec::new();
    for part in rela.split('/') {
        match part {
            "" | "." => continue,
            ".." => {
                return Err(ScmError::InvalidInput(format!(
                    "path escapes the repository: {rela}"
                )));
            }
            p => parts.push(p),
        }
    }
    if parts.is_empty() {
        return Err(ScmError::InvalidInput("empty path".into()));
    }
    if parts[0].eq_ignore_ascii_case(".git") {
        return Err(ScmError::InvalidInput(format!("refusing to touch {rela}")));
    }
    Ok(parts.join("/"))
}

/// Git's ref name rules, the subset that matters for names typed into a quick input.
pub(crate) fn validate_branch_name(name: &str) -> Result<()> {
    let bad = name.is_empty()
        || name.starts_with('-')
        || name.starts_with('/')
        || name.ends_with('/')
        || name.ends_with('.')
        || name.ends_with(".lock")
        || name.contains("..")
        || name.contains("//")
        || name.contains("@{")
        || name == "@"
        || name.split('/').any(|c| c.starts_with('.'))
        || name.chars().any(|c| c.is_control() || " ~^:?*[\\".contains(c));
    if bad {
        Err(ScmError::InvalidInput(format!(
            "'{name}' is not a valid branch name."
        )))
    } else {
        Ok(())
    }
}

#[cfg(test)]
mod tests;
