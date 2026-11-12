//! Source control (git, via `ferride-scm`). Every call blocks: run them on `Dispatchers.IO`. Network calls
//! report progress and ask for credentials through [`ScmNetworkListener`] on the calling thread.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use ferride_scm as scm;

/// Errors with the cases the UI reacts to (publish prompt, pull first, sign in, …). The message is VS Code's
/// wording and never contains credentials. Flat for Kotlin: each case is an exception class whose `message`
/// is that text.
#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum GitError {
    #[error("{msg}")]
    NotARepository { msg: String },
    #[error("{msg}")]
    MissingIdentity { msg: String },
    #[error("{msg}")]
    NothingToCommit { msg: String },
    #[error("{msg}")]
    WouldOverwrite { msg: String, paths: Vec<String> },
    #[error("{msg}")]
    NoUpstream { msg: String, branch: String },
    #[error("{msg}")]
    NotFastForward { msg: String },
    #[error("{msg}")]
    PushRejected { msg: String },
    #[error("{msg}")]
    Authentication { msg: String },
    #[error("{msg}")]
    Cancelled { msg: String },
    #[error("{msg}")]
    Unsupported { msg: String },
    #[error("{msg}")]
    Failed { msg: String },
}

impl From<scm::ScmError> for GitError {
    fn from(e: scm::ScmError) -> Self {
        let msg = e.to_string();
        match e {
            scm::ScmError::NotARepository(_) => GitError::NotARepository { msg },
            scm::ScmError::MissingIdentity => GitError::MissingIdentity { msg },
            scm::ScmError::NothingToCommit => GitError::NothingToCommit { msg },
            scm::ScmError::WouldOverwrite(paths) => GitError::WouldOverwrite { msg, paths },
            scm::ScmError::NoUpstream(branch) => GitError::NoUpstream { msg, branch },
            scm::ScmError::NotFastForward => GitError::NotFastForward { msg },
            scm::ScmError::PushRejectedNonFastForward | scm::ScmError::PushRejected(_) => {
                GitError::PushRejected { msg }
            }
            scm::ScmError::Authentication(_) => GitError::Authentication { msg },
            scm::ScmError::Cancelled => GitError::Cancelled { msg },
            scm::ScmError::UnsupportedUrl(_) => GitError::Unsupported { msg },
            _ => GitError::Failed { msg },
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ScmGroup {
    /// "Merge Changes"
    Merge,
    /// "Staged Changes"
    Index,
    /// "Changes"
    WorkingTree,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ScmChange {
    /// Repository-relative, `/` separated.
    pub path: String,
    pub group: ScmGroup,
    /// VS Code's decoration letter: `M`, `A`, `D`, `U`, `T`, `!`.
    pub letter: String,
    /// VS Code's tooltip: "Index Modified", "Untracked", "Conflict: Both Modified", …
    pub tooltip: String,
    /// The file is gone on this side (drawn struck through).
    pub deleted: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ScmHead {
    /// Current branch; `None` when detached.
    pub branch: Option<String>,
    /// Full commit id; `None` before the first commit.
    pub commit: Option<String>,
    pub detached: bool,
    /// `origin/main`
    pub upstream: Option<String>,
    pub ahead: i32,
    pub behind: i32,
    /// Status bar text: branch name or short commit id.
    pub label: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ScmBranch {
    /// `main` or `origin/main`.
    pub name: String,
    pub remote: bool,
    pub is_head: bool,
    /// Short id of the tip.
    pub commit: String,
    pub summary: String,
    /// Tip commit time, seconds since the epoch.
    pub time: i64,
    pub upstream: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ScmRefKind {
    Head,
    LocalBranch,
    RemoteBranch,
    Tag,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ScmRefLabel {
    pub name: String,
    pub kind: ScmRefKind,
    pub is_head: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ScmCommit {
    pub id: String,
    pub short_id: String,
    pub summary: String,
    pub message: String,
    pub author_name: String,
    pub author_email: String,
    /// Seconds since the epoch.
    pub author_time: i64,
    pub parents: Vec<String>,
    pub refs: Vec<ScmRefLabel>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ScmWordRange {
    /// UTF-16 columns within the line.
    pub start: i32,
    pub end: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ScmDiffLine {
    /// 0-based line in the original (removed lines) or modified text (added lines).
    pub line: i32,
    pub text: String,
    pub words: Vec<ScmWordRange>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ScmHunk {
    pub original_start: i32,
    pub original_count: i32,
    pub modified_start: i32,
    pub modified_count: i32,
    pub removed: Vec<ScmDiffLine>,
    pub added: Vec<ScmDiffLine>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ScmFileDiff {
    pub path: String,
    pub original: String,
    pub modified: String,
    pub original_exists: bool,
    pub modified_exists: bool,
    pub binary: bool,
    pub hunks: Vec<ScmHunk>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ScmLineChangeKind {
    Added,
    Modified,
    Deleted,
}

/// A gutter decoration on lines `start..end` (0-based; `Deleted` marks the line after which lines were removed).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ScmLineChange {
    pub kind: ScmLineChangeKind,
    pub start: i32,
    pub end: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ScmIdentity {
    pub name: String,
    pub email: String,
}

#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct ScmCredential {
    pub username: String,
    pub password: String,
}

/// The app's side of network operations. Called on the thread running the operation (never the UI thread),
/// so `credentials` may block while the user types a token.
#[uniffi::export(callback_interface)]
pub trait ScmNetworkListener: Send + Sync {
    /// Credentials for `url` (no user info in it); `None` cancels with an authentication error.
    fn credentials(&self, url: String) -> Option<ScmCredential>;
    /// The remote accepted the credentials for `url`: store them.
    fn credentials_approved(&self, url: String);
    /// The remote rejected the credentials for `url`: forget them.
    fn credentials_rejected(&self, url: String);
    /// `phase` uses git's wording ("Receiving objects", …); `total` is unknown for some phases.
    fn progress(&self, phase: String, current: i64, total: Option<i64>);
}

/// Cancels a running network operation.
#[derive(uniffi::Object, Default)]
pub struct ScmCancel {
    flag: Arc<AtomicBool>,
}

#[uniffi::export]
impl ScmCancel {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    pub fn cancel(&self) {
        self.flag.store(true, Ordering::Relaxed);
    }

    pub fn is_cancelled(&self) -> bool {
        self.flag.load(Ordering::Relaxed)
    }
}

struct Bridge(Box<dyn ScmNetworkListener>);

impl scm::CredentialProvider for Bridge {
    fn get(&self, url: &str) -> Option<scm::Credential> {
        self.0.credentials(url.to_owned()).map(|c| scm::Credential {
            username: c.username,
            password: c.password,
        })
    }
    fn approved(&self, url: &str) {
        self.0.credentials_approved(url.to_owned());
    }
    fn rejected(&self, url: &str) {
        self.0.credentials_rejected(url.to_owned());
    }
}

impl scm::ProgressSink for Bridge {
    fn progress(&self, p: scm::Progress) {
        self.0
            .progress(p.phase, p.current as i64, p.total.map(|t| t as i64));
    }
}

fn network_options(listener: Box<dyn ScmNetworkListener>, cancel: &ScmCancel) -> scm::NetworkOptions {
    let bridge = Arc::new(Bridge(listener));
    scm::NetworkOptions {
        credentials: Some(bridge.clone()),
        progress: Some(bridge),
        cancel: Arc::clone(&cancel.flag),
        allow_local_transport: false,
    }
}

fn i32_of(v: u32) -> i32 {
    v.min(i32::MAX as u32) as i32
}

fn hunk_id(h: &ScmHunk) -> scm::HunkId {
    scm::HunkId {
        original_start: h.original_start.max(0) as u32,
        original_count: h.original_count.max(0) as u32,
        modified_start: h.modified_start.max(0) as u32,
        modified_count: h.modified_count.max(0) as u32,
    }
}

fn convert_line(l: scm::textdiff::ChangedLine) -> ScmDiffLine {
    ScmDiffLine {
        line: i32_of(l.line),
        text: l.text,
        words: l
            .words
            .into_iter()
            .map(|w| ScmWordRange {
                start: i32_of(w.start),
                end: i32_of(w.end),
            })
            .collect(),
    }
}

fn convert_hunk(h: scm::textdiff::Hunk) -> ScmHunk {
    ScmHunk {
        original_start: i32_of(h.original_start),
        original_count: i32_of(h.original_count),
        modified_start: i32_of(h.modified_start),
        modified_count: i32_of(h.modified_count),
        removed: h.removed.into_iter().map(convert_line).collect(),
        added: h.added.into_iter().map(convert_line).collect(),
    }
}

/// An opened repository. Thread safe; every method blocks.
#[derive(uniffi::Object)]
pub struct ScmRepository {
    repo: scm::Repository,
}

/// The repository containing `path`, or `None` if it isn't inside one.
#[uniffi::export]
pub fn scm_discover(path: String) -> Result<Option<Arc<ScmRepository>>, GitError> {
    Ok(scm::Repository::discover(path)?.map(|repo| Arc::new(ScmRepository { repo })))
}

/// `git init` (initial branch `main`).
#[uniffi::export]
pub fn scm_init(path: String) -> Result<Arc<ScmRepository>, GitError> {
    Ok(Arc::new(ScmRepository {
        repo: scm::Repository::init(path)?,
    }))
}

/// `git clone <url> <dest>` over HTTPS. `dest` must not exist or be empty; it's removed again on failure.
#[uniffi::export]
pub fn scm_clone(
    url: String,
    dest: String,
    listener: Box<dyn ScmNetworkListener>,
    cancel: Arc<ScmCancel>,
) -> Result<Arc<ScmRepository>, GitError> {
    let options = network_options(listener, &cancel);
    Ok(Arc::new(ScmRepository {
        repo: scm::clone(&url, dest, &options)?,
    }))
}

/// Gutter decorations for `modified` against `original` (VS Code's quick diff).
#[uniffi::export]
pub fn scm_line_changes(original: String, modified: String) -> Vec<ScmLineChange> {
    scm::textdiff::line_changes(&original, &modified)
        .into_iter()
        .map(|c| ScmLineChange {
            kind: match c.kind {
                scm::LineChangeKind::Added => ScmLineChangeKind::Added,
                scm::LineChangeKind::Modified => ScmLineChangeKind::Modified,
                scm::LineChangeKind::Deleted => ScmLineChangeKind::Deleted,
            },
            start: i32_of(c.start),
            end: i32_of(c.end),
        })
        .collect()
}

#[uniffi::export]
impl ScmRepository {
    /// Working tree root.
    pub fn root(&self) -> String {
        self.repo.root().to_string_lossy().into_owned()
    }

    pub fn status(&self) -> Result<Vec<ScmChange>, GitError> {
        Ok(self
            .repo
            .status()?
            .changes
            .iter()
            .map(|c| ScmChange {
                path: c.path.clone(),
                group: match c.group {
                    scm::ChangeGroup::Merge => ScmGroup::Merge,
                    scm::ChangeGroup::Index => ScmGroup::Index,
                    scm::ChangeGroup::WorkingTree => ScmGroup::WorkingTree,
                },
                letter: c.letter().to_owned(),
                tooltip: c.tooltip().to_owned(),
                deleted: c.is_deletion(),
            })
            .collect())
    }

    pub fn head(&self) -> Result<ScmHead, GitError> {
        let h = self.repo.head()?;
        Ok(ScmHead {
            label: h.label(),
            branch: h.branch,
            commit: h.commit,
            detached: h.detached,
            upstream: h.upstream,
            ahead: i32_of(h.ahead),
            behind: i32_of(h.behind),
        })
    }

    pub fn branches(&self) -> Result<Vec<ScmBranch>, GitError> {
        Ok(self
            .repo
            .branches()?
            .into_iter()
            .map(|b| ScmBranch {
                name: b.name,
                remote: b.kind == scm::BranchKind::Remote,
                is_head: b.is_head,
                commit: b.commit,
                summary: b.summary,
                time: b.time,
                upstream: b.upstream,
            })
            .collect())
    }

    pub fn stage(&self, paths: Vec<String>) -> Result<(), GitError> {
        Ok(self.repo.stage(&paths)?)
    }

    pub fn stage_all(&self) -> Result<(), GitError> {
        Ok(self.repo.stage_all()?)
    }

    pub fn unstage(&self, paths: Vec<String>) -> Result<(), GitError> {
        Ok(self.repo.unstage(&paths)?)
    }

    pub fn unstage_all(&self) -> Result<(), GitError> {
        Ok(self.repo.unstage_all()?)
    }

    /// Restores tracked files from the index and deletes untracked ones. Confirm first.
    pub fn discard(&self, paths: Vec<String>) -> Result<(), GitError> {
        Ok(self.repo.discard(&paths)?)
    }

    pub fn discard_all(&self) -> Result<(), GitError> {
        Ok(self.repo.discard_all()?)
    }

    pub fn stage_hunks(&self, path: String, hunks: Vec<ScmHunk>) -> Result<(), GitError> {
        let ids: Vec<_> = hunks.iter().map(hunk_id).collect();
        Ok(self.repo.stage_hunks(&path, &ids)?)
    }

    pub fn unstage_hunks(&self, path: String, hunks: Vec<ScmHunk>) -> Result<(), GitError> {
        let ids: Vec<_> = hunks.iter().map(hunk_id).collect();
        Ok(self.repo.unstage_hunks(&path, &ids)?)
    }

    pub fn discard_hunks(&self, path: String, hunks: Vec<ScmHunk>) -> Result<(), GitError> {
        let ids: Vec<_> = hunks.iter().map(hunk_id).collect();
        Ok(self.repo.discard_hunks(&path, &ids)?)
    }

    /// `staged`: `HEAD` ↔ index; otherwise index ↔ working tree.
    pub fn diff(&self, path: String, staged: bool) -> Result<ScmFileDiff, GitError> {
        let side = if staged {
            scm::DiffSide::Staged
        } else {
            scm::DiffSide::WorkingTree
        };
        let d = self.repo.diff(&path, side)?;
        Ok(ScmFileDiff {
            path: d.path,
            original: d.original,
            modified: d.modified,
            original_exists: d.original_exists,
            modified_exists: d.modified_exists,
            binary: d.binary,
            hunks: d.hunks.into_iter().map(convert_hunk).collect(),
        })
    }

    /// The index version of `path` for gutter decorations; `None` if untracked or binary.
    pub fn quick_diff_original(&self, path: String) -> Result<Option<String>, GitError> {
        Ok(self.repo.quick_diff_original(&path)?)
    }

    /// Commits the index. `name`/`email` (the `git.user.*` settings) override the repository config.
    /// `stage_all_if_empty` is VS Code's smart commit. Returns the commit id.
    pub fn commit(
        &self,
        message: String,
        name: Option<String>,
        email: Option<String>,
        amend: bool,
        stage_all_if_empty: bool,
    ) -> Result<String, GitError> {
        let identity = match (name, email) {
            (Some(name), Some(email)) => Some(scm::Identity { name, email }),
            _ => None,
        };
        let options = scm::CommitOptions {
            identity,
            amend,
            stage_all_if_empty,
            allow_empty: false,
        };
        Ok(self.repo.commit(&message, &options)?)
    }

    /// `user.name` / `user.email` from the repository config, if both are set.
    pub fn configured_identity(&self) -> Option<ScmIdentity> {
        self.repo.configured_identity().map(|i| ScmIdentity {
            name: i.name,
            email: i.email,
        })
    }

    /// Newest first. `start`: a rev (default `HEAD`); `all_refs`: every branch and tag.
    pub fn log(
        &self,
        start: Option<String>,
        all_refs: bool,
        skip: i32,
        limit: i32,
    ) -> Result<Vec<ScmCommit>, GitError> {
        let query = scm::LogQuery {
            start,
            all_refs,
            skip: skip.max(0) as usize,
            limit: limit.clamp(0, 10_000) as usize,
        };
        Ok(self
            .repo
            .log(&query)?
            .into_iter()
            .map(|c| ScmCommit {
                id: c.id,
                short_id: c.short_id,
                summary: c.summary,
                message: c.message,
                author_name: c.author_name,
                author_email: c.author_email,
                author_time: c.author_time,
                parents: c.parents,
                refs: c
                    .refs
                    .into_iter()
                    .map(|r| ScmRefLabel {
                        name: r.name,
                        kind: match r.kind {
                            scm::RefLabelKind::Head => ScmRefKind::Head,
                            scm::RefLabelKind::LocalBranch => ScmRefKind::LocalBranch,
                            scm::RefLabelKind::RemoteBranch => ScmRefKind::RemoteBranch,
                            scm::RefLabelKind::Tag => ScmRefKind::Tag,
                        },
                        is_head: r.is_head,
                    })
                    .collect(),
            })
            .collect())
    }

    pub fn create_branch(&self, name: String, start: Option<String>, checkout: bool) -> Result<(), GitError> {
        Ok(self.repo.create_branch(&name, start.as_deref(), checkout)?)
    }

    /// Local branch, remote branch (creates a tracking branch) or any commit (detached).
    pub fn checkout(&self, name: String) -> Result<(), GitError> {
        Ok(self.repo.checkout(&name)?)
    }

    pub fn remotes(&self) -> Vec<String> {
        self.repo.remotes()
    }

    pub fn add_remote(&self, name: String, url: String) -> Result<(), GitError> {
        Ok(self.repo.add_remote(&name, &url)?)
    }

    pub fn fetch(
        &self,
        listener: Box<dyn ScmNetworkListener>,
        cancel: Arc<ScmCancel>,
    ) -> Result<(), GitError> {
        Ok(self.repo.fetch(None, &network_options(listener, &cancel))?)
    }

    /// Fetch + fast-forward.
    pub fn pull(
        &self,
        listener: Box<dyn ScmNetworkListener>,
        cancel: Arc<ScmCancel>,
    ) -> Result<(), GitError> {
        Ok(self.repo.pull(&network_options(listener, &cancel))?)
    }

    /// `set_upstream` publishes the branch (VS Code's "Publish Branch").
    pub fn push(
        &self,
        set_upstream: bool,
        force: bool,
        listener: Box<dyn ScmNetworkListener>,
        cancel: Arc<ScmCancel>,
    ) -> Result<(), GitError> {
        let push = scm::PushOptions {
            remote: None,
            set_upstream,
            force,
        };
        Ok(self.repo.push(&push, &network_options(listener, &cancel))?)
    }
}
