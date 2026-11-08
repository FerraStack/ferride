//! Creating commits from the index.

use gix::ObjectId;
use gix::bstr::ByteSlice;
use gix::refs::Target;
use gix::refs::transaction::{Change, LogChange, PreviousValue, RefEdit, RefLog};

use crate::status::ChangeGroup;
use crate::worktree::{head_commit_id, load_index};
use crate::{Repository, Result, ScmError};

/// A commit author / committer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Identity {
    pub name: String,
    pub email: String,
}

/// What to commit and how.
#[derive(Debug, Clone, Default)]
pub struct CommitOptions {
    /// Author and committer. `None` uses the repository's `user.name` / `user.email`; the app passes its
    /// `git.user.name` / `git.user.email` settings here when they are set.
    pub identity: Option<Identity>,
    /// Replace the last commit (VS Code: "Commit Staged (Amend)").
    pub amend: bool,
    /// Stage all changes first when nothing is staged (VS Code's smart commit, `git.enableSmartCommit`).
    pub stage_all_if_empty: bool,
    /// Allow a commit whose tree equals its parent's ("Commit (Empty)").
    pub allow_empty: bool,
}

impl Repository {
    /// Commits the index with `message`. Returns the new commit id (hex).
    pub fn commit(&self, message: &str, options: &CommitOptions) -> Result<String> {
        let message = clean_message(message);
        if message.is_empty() {
            return Err(ScmError::InvalidInput("Please provide a commit message.".into()));
        }
        if options.stage_all_if_empty
            && !options.amend
            && self.status()?.group(ChangeGroup::Index).next().is_none()
        {
            self.stage_all()?;
        }

        let repo = self.repo();
        let identity = match &options.identity {
            Some(id) if !id.name.trim().is_empty() && !id.email.trim().is_empty() => id.clone(),
            _ => config_identity(&repo).ok_or(ScmError::MissingIdentity)?,
        };

        let index = load_index(&repo)?;
        if index
            .entries()
            .iter()
            .any(|e| e.stage() != gix::index::entry::Stage::Unconflicted)
        {
            return Err(ScmError::InvalidInput(
                "Commit failed: you have unresolved merge conflicts. Resolve them and stage the files first."
                    .into(),
            ));
        }
        let tree = write_tree(&repo, &index)?;

        let head = head_commit_id(&repo)?;
        let merge_heads = read_merge_heads(&repo);
        let parents: Vec<ObjectId> = if options.amend {
            let head = head.ok_or_else(|| ScmError::InvalidInput("There is no commit to amend.".into()))?;
            let commit = repo.find_commit(head).map_err(ScmError::git)?;
            commit.parent_ids().map(|id| id.detach()).collect()
        } else {
            head.into_iter().chain(merge_heads.iter().copied()).collect()
        };

        if !options.allow_empty && merge_heads.is_empty() {
            let parent_tree = match parents.first() {
                Some(p) => Some(crate::worktree::commit_tree_id(&repo, *p)?),
                None => None,
            };
            let unchanged = match parent_tree {
                Some(t) => t == tree,
                None => index.entries().is_empty(),
            };
            if unchanged && !options.amend {
                return Err(ScmError::NothingToCommit);
            }
        }

        let time = gix::date::Time::now_local_or_utc();
        let signature = gix::actor::Signature {
            name: identity.name.trim().into(),
            email: identity.email.trim().into(),
            time,
        };
        let commit = gix::objs::Commit {
            tree,
            parents: parents.iter().copied().collect(),
            author: signature.clone(),
            committer: signature.clone(),
            encoding: None,
            message: message.clone().into(),
            extra_headers: Vec::new(),
        };
        let id = repo.write_object(&commit).map_err(ScmError::git)?.detach();

        let summary = message.lines().next().unwrap_or_default();
        let log_message = if options.amend {
            format!("commit (amend): {summary}")
        } else if parents.is_empty() {
            format!("commit (initial): {summary}")
        } else if parents.len() > 1 {
            format!("commit (merge): {summary}")
        } else {
            format!("commit: {summary}")
        };
        let expected = match head {
            Some(h) => PreviousValue::MustExistAndMatch(Target::Object(h)),
            None => PreviousValue::MustNotExist,
        };
        let mut time_buf = gix::date::parse::TimeBuf::default();
        repo.edit_references_as(
            Some(RefEdit {
                change: Change::Update {
                    log: LogChange {
                        mode: RefLog::AndReference,
                        force_create_reflog: false,
                        message: log_message.into(),
                    },
                    expected,
                    new: Target::Object(id),
                },
                name: "HEAD".try_into().map_err(ScmError::git)?,
                deref: true,
            }),
            Some(signature.to_ref(&mut time_buf)),
        )
        .map_err(ScmError::git)?;

        if !merge_heads.is_empty() && !options.amend {
            for file in ["MERGE_HEAD", "MERGE_MSG", "MERGE_MODE"] {
                let _ = std::fs::remove_file(repo.git_dir().join(file));
            }
        }
        Ok(id.to_string())
    }

    /// The identity commits would use without an explicit one: `user.name` / `user.email` from the repository
    /// config. `None` if either is missing.
    pub fn configured_identity(&self) -> Option<Identity> {
        config_identity(&self.repo())
    }
}

fn config_identity(repo: &gix::Repository) -> Option<Identity> {
    let config = repo.config_snapshot();
    let name = config.string("user.name")?.to_str_lossy().trim().to_owned();
    let email = config.string("user.email")?.to_str_lossy().trim().to_owned();
    (!name.is_empty() && !email.is_empty()).then_some(Identity { name, email })
}

/// Like git's default `--cleanup=strip` for messages typed in an editor: drop `#` comment lines, trailing
/// whitespace and leading/trailing blank lines, collapse runs of blank lines; end with a newline.
fn clean_message(message: &str) -> String {
    let mut lines: Vec<&str> = Vec::new();
    for line in message.lines() {
        if line.starts_with('#') {
            continue;
        }
        let line = line.trim_end();
        if line.is_empty() && lines.last().is_none_or(|l| l.is_empty()) {
            continue;
        }
        lines.push(line);
    }
    while lines.last().is_some_and(|l| l.is_empty()) {
        lines.pop();
    }
    if lines.is_empty() {
        return String::new();
    }
    let mut out = lines.join("\n");
    out.push('\n');
    out
}

/// Builds and writes the tree for the index's stage-0 entries.
fn write_tree(repo: &gix::Repository, index: &gix::index::State) -> Result<ObjectId> {
    let empty = ObjectId::empty_tree(repo.object_hash());
    let mut editor = repo.edit_tree(empty).map_err(ScmError::git)?;
    for entry in index.entries() {
        let Some(mode) = entry.mode.to_tree_entry_mode() else {
            continue;
        };
        let path: &[u8] = entry.path(index);
        editor
            .upsert(path.as_bstr(), mode.kind(), entry.id)
            .map_err(ScmError::git)?;
    }
    Ok(editor.write().map_err(ScmError::git)?.detach())
}

fn read_merge_heads(repo: &gix::Repository) -> Vec<ObjectId> {
    let Ok(text) = std::fs::read_to_string(repo.git_dir().join("MERGE_HEAD")) else {
        return Vec::new();
    };
    text.lines()
        .filter_map(|l| ObjectId::from_hex(l.trim().as_bytes()).ok())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::clean_message;

    #[test]
    fn message_cleanup() {
        assert_eq!(
            clean_message("  \n\nFix it  \n\n\n\nbody\n# comment\n\n"),
            "Fix it\n\nbody\n"
        );
        assert_eq!(clean_message("# only a comment"), "");
        assert_eq!(clean_message("one line"), "one line\n");
    }
}
