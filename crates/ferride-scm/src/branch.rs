//! Branches: listing (with upstream and ahead/behind), creating, checking out.

use gix::ObjectId;
use gix::bstr::ByteSlice;
use gix::refs::transaction::{Change, LogChange, PreviousValue, RefEdit, RefLog};
use gix::refs::{FullName, Target};

use crate::worktree::{commit_tree_id, head_commit_id};
use crate::{Repository, Result, ScmError};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BranchKind {
    Local,
    Remote,
}

/// A branch for the branch quick pick ("Select a branch or tag to checkout").
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Branch {
    /// `main`, or `origin/main` for remote branches.
    pub name: String,
    pub kind: BranchKind,
    /// `HEAD` points to this branch.
    pub is_head: bool,
    /// Short id of the tip commit.
    pub commit: String,
    /// Summary of the tip commit.
    pub summary: String,
    /// Tip commit time, seconds since the Unix epoch.
    pub time: i64,
    /// Upstream (`origin/main`) of a local branch, if configured.
    pub upstream: Option<String>,
}

/// What `HEAD` is: the status bar's branch item and sync counts.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Head {
    /// The current branch, `None` when detached.
    pub branch: Option<String>,
    /// The commit `HEAD` resolves to; `None` on an unborn branch (no commits yet).
    pub commit: Option<String>,
    pub detached: bool,
    /// Upstream of the current branch (`origin/main`).
    pub upstream: Option<String>,
    /// Commits on the branch that aren't on the upstream (VS Code's `1↑`).
    pub ahead: u32,
    /// Commits on the upstream that aren't on the branch (VS Code's `1↓`).
    pub behind: u32,
}

impl Head {
    /// The status bar label: the branch name, or the short commit id when detached.
    pub fn label(&self) -> String {
        match (&self.branch, &self.commit) {
            (Some(b), _) => b.clone(),
            (None, Some(c)) => c.chars().take(8).collect(),
            (None, None) => "HEAD".into(),
        }
    }
}

/// Upstream configured for a local branch: `(remote, merge ref)`, e.g. `("origin", "refs/heads/main")`.
pub(crate) fn upstream_config(repo: &gix::Repository, branch: &str) -> Option<(String, String)> {
    let config = repo.config_snapshot();
    let remote = config
        .string(format!("branch.{branch}.remote").as_str())?
        .to_str_lossy()
        .into_owned();
    let merge = config
        .string(format!("branch.{branch}.merge").as_str())?
        .to_str_lossy()
        .into_owned();
    Some((remote, merge))
}

/// The remote-tracking ref of an upstream: `refs/remotes/<remote>/<branch>`.
pub(crate) fn tracking_ref(remote: &str, merge: &str) -> String {
    format!(
        "refs/remotes/{remote}/{}",
        merge.strip_prefix("refs/heads/").unwrap_or(merge)
    )
}

pub(crate) fn resolve_ref(repo: &gix::Repository, full_name: &str) -> Option<ObjectId> {
    let mut reference = repo.try_find_reference(full_name).ok()??;
    reference.peel_to_id().ok().map(|id| id.detach())
}

/// Counts commits reachable from `from` but not from `exclude` (capped, it's only for the status bar).
pub(crate) fn count_exclusive(repo: &gix::Repository, from: ObjectId, exclude: ObjectId) -> u32 {
    const CAP: usize = 10_000;
    match repo.rev_walk([from]).with_hidden([exclude]).all() {
        Ok(walk) => walk.take(CAP).filter(|c| c.is_ok()).count() as u32,
        Err(_) => 0,
    }
}

/// Whether `ancestor` is reachable from `descendant` (or equal to it).
pub(crate) fn is_ancestor(repo: &gix::Repository, ancestor: ObjectId, descendant: ObjectId) -> bool {
    if ancestor == descendant {
        return true;
    }
    match repo.rev_walk([descendant]).all() {
        Ok(mut walk) => walk.any(|c| c.is_ok_and(|c| c.id == ancestor)),
        Err(_) => false,
    }
}

pub(crate) fn commit_exists(repo: &gix::Repository, id: ObjectId) -> bool {
    repo.find_commit(id).is_ok()
}

impl Repository {
    /// The current branch, commit and sync state.
    pub fn head(&self) -> Result<Head> {
        let repo = self.repo();
        let head = repo.head().map_err(ScmError::git)?;
        let branch = head
            .referent_name()
            .map(|n| n.shorten().to_str_lossy().into_owned());
        let commit = head.id().map(|id| id.detach());
        let mut out = Head {
            branch: branch.clone(),
            commit: commit.map(|c| c.to_string()),
            detached: head.is_detached(),
            ..Head::default()
        };
        if let Some(branch) = &branch
            && let Some((remote, merge)) = upstream_config(&repo, branch)
        {
            let tracking = tracking_ref(&remote, &merge);
            out.upstream = Some(tracking.trim_start_matches("refs/remotes/").to_owned());
            if let (Some(local), Some(upstream)) = (commit, resolve_ref(&repo, &tracking)) {
                out.ahead = count_exclusive(&repo, local, upstream);
                out.behind = count_exclusive(&repo, upstream, local);
            }
        }
        Ok(out)
    }

    /// Local branches, then remote branches, each sorted by name.
    pub fn branches(&self) -> Result<Vec<Branch>> {
        let repo = self.repo();
        let head = repo.head().map_err(ScmError::git)?;
        let head_name = head
            .referent_name()
            .map(|n| n.as_bstr().to_str_lossy().into_owned());
        let platform = repo.references().map_err(ScmError::git)?;
        let mut out = Vec::new();
        for (iter, kind) in [
            (
                platform.local_branches().map_err(ScmError::git)?,
                BranchKind::Local,
            ),
            (
                platform.remote_branches().map_err(ScmError::git)?,
                BranchKind::Remote,
            ),
        ] {
            for reference in iter {
                let Ok(mut reference) = reference else { continue };
                let full = reference.name().as_bstr().to_str_lossy().into_owned();
                if kind == BranchKind::Remote && full.ends_with("/HEAD") {
                    continue;
                }
                let name = reference.name().shorten().to_str_lossy().into_owned();
                let Ok(commit) = reference.peel_to_commit() else {
                    continue;
                };
                let summary = commit
                    .message()
                    .map(|m| m.summary().to_str_lossy().into_owned())
                    .unwrap_or_default();
                let time = commit.time().map(|t| t.seconds).unwrap_or(0);
                let upstream = (kind == BranchKind::Local)
                    .then(|| upstream_config(&repo, &name))
                    .flatten()
                    .map(|(r, m)| {
                        tracking_ref(&r, &m)
                            .trim_start_matches("refs/remotes/")
                            .to_owned()
                    });
                out.push(Branch {
                    is_head: head_name.as_deref() == Some(full.as_str()),
                    name,
                    kind,
                    commit: commit.id().to_hex_with_len(7).to_string(),
                    summary,
                    time,
                    upstream,
                });
            }
        }
        out.sort_by(|a, b| (a.kind as u8, &a.name).cmp(&(b.kind as u8, &b.name)));
        Ok(out)
    }

    /// `git branch <name> [<start>]`, then optionally checks it out (VS Code: "Create Branch...").
    pub fn create_branch(&self, name: &str, start: Option<&str>, checkout: bool) -> Result<()> {
        crate::validate_branch_name(name)?;
        let repo = self.repo();
        let full = format!("refs/heads/{name}");
        if repo
            .try_find_reference(full.as_str())
            .map_err(ScmError::git)?
            .is_some()
        {
            return Err(ScmError::BranchExists(name.to_owned()));
        }
        let target = match start {
            Some(rev) => {
                repo.rev_parse_single(rev)
                    .map_err(|_| ScmError::InvalidInput(format!("Unknown revision '{rev}'.")))?
                    .object()
                    .map_err(ScmError::git)?
                    .peel_to_commit()
                    .map_err(ScmError::git)?
                    .id
            }
            None => match head_commit_id(&repo)? {
                Some(id) => id,
                None => {
                    // No commits yet: "creating" a branch just renames the unborn branch, like `git checkout -b`.
                    if checkout {
                        return self.set_head_symbolic(&repo, &full, "checkout: moving to new branch");
                    }
                    return Err(ScmError::InvalidInput(
                        "There are no commits yet to branch from.".into(),
                    ));
                }
            },
        };
        let from = start.unwrap_or("HEAD");
        repo.reference(
            full.as_str(),
            target,
            PreviousValue::MustNotExist,
            format!("branch: Created from {from}"),
        )
        .map_err(ScmError::git)?;
        if checkout { self.checkout(name) } else { Ok(()) }
    }

    /// Checks out a local branch; a remote branch (`origin/feature`) creates a local tracking branch first, like
    /// VS Code's "Checkout to..." does. Anything else that resolves to a commit is checked out detached.
    ///
    /// Fails with [`ScmError::WouldOverwrite`] if local changes are in the way; nothing is modified then.
    pub fn checkout(&self, name: &str) -> Result<()> {
        let repo = self.repo();
        let local_ref = format!("refs/heads/{name}");
        if let Some(target) = resolve_ref(&repo, &local_ref) {
            return self.switch_to(&repo, target, Some(&local_ref), name);
        }

        let remote_ref = format!("refs/remotes/{name}");
        if let Some(target) = resolve_ref(&repo, &remote_ref)
            && let Some((remote, branch)) = name.split_once('/')
        {
            let local_ref = format!("refs/heads/{branch}");
            if resolve_ref(&repo, &local_ref).is_some() {
                // `main` exists already: just check it out (git would refuse to create it again).
                return self.checkout(branch);
            }
            repo.reference(
                local_ref.as_str(),
                target,
                PreviousValue::MustNotExist,
                format!("branch: Created from {name}"),
            )
            .map_err(ScmError::git)?;
            self.set_config(&[
                ("branch", Some(branch), "remote", remote),
                ("branch", Some(branch), "merge", &format!("refs/heads/{branch}")),
            ])?;
            let repo = self.repo();
            return self.switch_to(&repo, target, Some(&local_ref), branch);
        }

        let target = repo
            .rev_parse_single(name)
            .map_err(|_| ScmError::BranchNotFound(name.to_owned()))?
            .object()
            .map_err(ScmError::git)?
            .peel_to_commit()
            .map_err(|_| ScmError::BranchNotFound(name.to_owned()))?
            .id;
        self.switch_to(&repo, target, None, name)
    }

    fn switch_to(
        &self,
        repo: &gix::Repository,
        target: ObjectId,
        branch_ref: Option<&str>,
        label: &str,
    ) -> Result<()> {
        let head = head_commit_id(repo)?;
        let from_tree = head.map(|h| commit_tree_id(repo, h)).transpose()?;
        let to_tree = commit_tree_id(repo, target)?;
        self.switch_tree(repo, from_tree, Some(to_tree))?;
        let from_label = repo
            .head()
            .ok()
            .and_then(|h| h.referent_name().map(|n| n.shorten().to_str_lossy().into_owned()))
            .or_else(|| head.map(|h| h.to_string()))
            .unwrap_or_else(|| "HEAD".into());
        let message = format!("checkout: moving from {from_label} to {label}");
        match branch_ref {
            Some(full) => self.set_head_symbolic(repo, full, &message),
            None => {
                repo.edit_reference(RefEdit {
                    change: Change::Update {
                        log: LogChange {
                            mode: RefLog::AndReference,
                            force_create_reflog: false,
                            message: message.into(),
                        },
                        expected: PreviousValue::Any,
                        new: Target::Object(target),
                    },
                    name: "HEAD".try_into().map_err(ScmError::git)?,
                    deref: false,
                })
                .map_err(ScmError::git)?;
                Ok(())
            }
        }
    }

    fn set_head_symbolic(&self, repo: &gix::Repository, full: &str, message: &str) -> Result<()> {
        let target: FullName = full.try_into().map_err(ScmError::git)?;
        repo.edit_reference(RefEdit {
            change: Change::Update {
                log: LogChange {
                    mode: RefLog::AndReference,
                    force_create_reflog: false,
                    message: message.into(),
                },
                expected: PreviousValue::Any,
                new: Target::Symbolic(target),
            },
            name: "HEAD".try_into().map_err(ScmError::git)?,
            deref: false,
        })
        .map_err(ScmError::git)?;
        Ok(())
    }

    /// Writes values into the repository's `.git/config`, keeping everything else.
    pub(crate) fn set_config(&self, values: &[(&str, Option<&str>, &str, &str)]) -> Result<()> {
        let path = self.git_dir().join("config");
        let mut file = gix::config::File::from_path_no_includes(path.clone(), gix::config::Source::Local)
            .map_err(ScmError::git)?;
        for (section, subsection, key, value) in values {
            file.set_raw_value_by(*section, subsection.map(|s| s.as_bytes().as_bstr()), *key, *value)
                .map_err(ScmError::git)?;
        }
        let mut out = Vec::new();
        file.write_to(&mut out)?;
        let tmp = path.with_extension("ferride.tmp");
        std::fs::write(&tmp, out)?;
        std::fs::rename(&tmp, &path)?;
        Ok(())
    }
}
