//! Commit history, paged, with branch / tag decorations.

use std::collections::HashMap;

use gix::ObjectId;
use gix::bstr::ByteSlice;
use gix::revision::walk::Sorting;
use gix::traverse::commit::simple::CommitTimeOrder;

use crate::worktree::head_commit_id;
use crate::{Repository, Result, ScmError};

/// Which commits to list.
#[derive(Debug, Clone)]
pub struct LogQuery {
    /// Start from this revision (`main`, `origin/main`, a commit id, …) instead of `HEAD`.
    pub start: Option<String>,
    /// Include every local and remote branch and tag (a "graph" of the whole repository) instead of one tip.
    pub all_refs: bool,
    /// Commits to skip (paging).
    pub skip: usize,
    /// Maximum number of commits to return.
    pub limit: usize,
}

impl Default for LogQuery {
    fn default() -> Self {
        LogQuery {
            start: None,
            all_refs: false,
            skip: 0,
            limit: 50,
        }
    }
}

/// The kind of a ref decoration (VS Code / Git Graph "pills").
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RefLabelKind {
    /// `HEAD` when detached.
    Head,
    LocalBranch,
    RemoteBranch,
    Tag,
}

/// A decoration on a commit.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RefLabel {
    /// Short name: `main`, `origin/main`, `v1.0`, `HEAD`.
    pub name: String,
    pub kind: RefLabelKind,
    /// The branch `HEAD` points to (drawn emphasized).
    pub is_head: bool,
}

/// One commit of the log.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CommitInfo {
    pub id: String,
    pub short_id: String,
    /// First line of the message.
    pub summary: String,
    pub message: String,
    pub author_name: String,
    pub author_email: String,
    /// Author time, seconds since the Unix epoch.
    pub author_time: i64,
    pub parents: Vec<String>,
    pub refs: Vec<RefLabel>,
}

impl Repository {
    /// Lists commits newest first (by commit time), `query.skip` .. `query.skip + query.limit`.
    /// An unborn branch has no commits: the result is empty.
    pub fn log(&self, query: &LogQuery) -> Result<Vec<CommitInfo>> {
        let repo = self.repo();
        let decorations = decorations(&repo)?;
        let mut tips: Vec<ObjectId> = Vec::new();
        match &query.start {
            Some(rev) => tips.push(
                repo.rev_parse_single(rev.as_str())
                    .map_err(|_| ScmError::InvalidInput(format!("Unknown revision '{rev}'.")))?
                    .detach(),
            ),
            None => tips.extend(head_commit_id(&repo)?),
        }
        if query.all_refs {
            tips.extend(decorations.keys().copied());
        }
        tips.sort();
        tips.dedup();
        // Tags may point to non-commits; only walk from commits.
        tips.retain(|id| repo.find_commit(*id).is_ok());
        if tips.is_empty() {
            return Ok(Vec::new());
        }

        let walk = repo
            .rev_walk(tips)
            .sorting(Sorting::ByCommitTime(CommitTimeOrder::NewestFirst))
            .all()
            .map_err(ScmError::git)?;
        let mut out = Vec::with_capacity(query.limit.min(500));
        for info in walk.skip(query.skip).take(query.limit) {
            let info = info.map_err(ScmError::git)?;
            let commit = info.object().map_err(ScmError::git)?;
            let decoded = commit.decode().map_err(ScmError::git)?;
            let message = decoded.message.to_str_lossy().into_owned();
            let summary = decoded.message_summary().to_str_lossy().into_owned();
            let author = decoded.author().map_err(ScmError::git)?;
            let author_time = author.time().map(|t| t.seconds).unwrap_or(0);
            let id = info.id;
            out.push(CommitInfo {
                id: id.to_string(),
                short_id: id.to_hex_with_len(7).to_string(),
                summary,
                message,
                author_name: author.name.to_str_lossy().trim().to_owned(),
                author_email: author.email.to_str_lossy().trim().to_owned(),
                author_time,
                parents: decoded.parents().map(|p| p.to_string()).collect(),
                refs: decorations.get(&id).cloned().unwrap_or_default(),
            });
        }
        Ok(out)
    }
}

/// Commit id → labels, ordered like VS Code's Git Graph: HEAD, local branches, remote branches, tags.
fn decorations(repo: &gix::Repository) -> Result<HashMap<ObjectId, Vec<RefLabel>>> {
    let mut map: HashMap<ObjectId, Vec<RefLabel>> = HashMap::new();
    let head = repo.head().map_err(ScmError::git)?;
    let head_branch = head
        .referent_name()
        .map(|n| n.as_bstr().to_str_lossy().into_owned());
    if head_branch.is_none()
        && let Some(id) = head.id()
    {
        map.entry(id.detach()).or_default().push(RefLabel {
            name: "HEAD".into(),
            kind: RefLabelKind::Head,
            is_head: true,
        });
    }
    let refs = repo.references().map_err(ScmError::git)?;
    for reference in refs.all().map_err(ScmError::git)? {
        let Ok(mut reference) = reference else { continue };
        let full = reference.name().as_bstr().to_str_lossy().into_owned();
        let (kind, name) = if let Some(n) = full.strip_prefix("refs/heads/") {
            (RefLabelKind::LocalBranch, n.to_owned())
        } else if let Some(n) = full.strip_prefix("refs/remotes/") {
            if n.ends_with("/HEAD") {
                continue;
            }
            (RefLabelKind::RemoteBranch, n.to_owned())
        } else if let Some(n) = full.strip_prefix("refs/tags/") {
            (RefLabelKind::Tag, n.to_owned())
        } else {
            continue;
        };
        let Ok(id) = reference.peel_to_id() else { continue };
        let is_head = kind == RefLabelKind::LocalBranch && head_branch.as_deref() == Some(full.as_str());
        map.entry(id.detach())
            .or_default()
            .push(RefLabel { name, kind, is_head });
    }
    for labels in map.values_mut() {
        labels.sort_by_key(|l| (!l.is_head, l.kind as u8, l.name.clone()));
    }
    Ok(map)
}
