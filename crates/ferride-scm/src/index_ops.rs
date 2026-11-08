//! Stage, unstage and discard: whole files and single hunks.

use gix::bstr::ByteSlice;
use gix::index::entry::Stat;
use gix::objs::tree::{EntryKind, EntryMode};

use crate::diff::as_text;
use crate::status::ChangeGroup;
use crate::textdiff::{self, HunkId};
use crate::worktree::{
    Blob, blob_data, head_tree_id, index_entry, load_index, read_worktree, remove_entry, remove_worktree,
    set_entry, stat_of, tree_entry, write_index, write_worktree,
};
use crate::{Repository, Result, ScmError};

impl Repository {
    /// `git add -A -- <paths>`: records the working tree state of each path (including deletions) in the index.
    /// Staging a conflicted file marks it as resolved.
    pub fn stage(&self, paths: &[String]) -> Result<()> {
        let repo = self.repo();
        let mut index = load_index(&repo)?;
        for path in paths {
            let path = crate::normalize_rela(path)?;
            let abs = self.workdir.join(&path);
            let key = path.as_bytes().as_bstr();
            match read_worktree(&abs)? {
                Some((data, mode)) => {
                    let id = repo.write_blob(&data).map_err(ScmError::git)?.detach();
                    set_entry(&mut index, key, Blob { id, mode }, stat_of(&abs)?);
                }
                None => remove_entry(&mut index, key),
            }
        }
        write_index(&mut index)
    }

    /// Stages every change in the "Changes" and "Merge Changes" groups (VS Code: "Stage All Changes").
    pub fn stage_all(&self) -> Result<()> {
        let paths = self.paths_in(&[ChangeGroup::WorkingTree, ChangeGroup::Merge])?;
        if paths.is_empty() {
            Ok(())
        } else {
            self.stage(&paths)
        }
    }

    /// `git restore --staged -- <paths>`: resets the index entries to `HEAD` (removes ones `HEAD` doesn't have).
    pub fn unstage(&self, paths: &[String]) -> Result<()> {
        let repo = self.repo();
        let head = head_tree_id(&repo)?;
        let mut index = load_index(&repo)?;
        for path in paths {
            let path = crate::normalize_rela(path)?;
            let key = path.as_bytes().as_bstr();
            match tree_entry(&repo, head, &path)? {
                // A zeroed stat makes the next status re-hash the file instead of trusting a stale stat.
                Some(blob) => set_entry(&mut index, key, blob, Stat::default()),
                None => remove_entry(&mut index, key),
            }
        }
        write_index(&mut index)
    }

    /// Unstages everything in "Staged Changes" (VS Code: "Unstage All Changes").
    pub fn unstage_all(&self) -> Result<()> {
        let paths = self.paths_in(&[ChangeGroup::Index])?;
        if paths.is_empty() {
            Ok(())
        } else {
            self.unstage(&paths)
        }
    }

    /// VS Code's "Discard Changes": tracked files are restored from the index (`git checkout -- <path>`),
    /// untracked files are deleted (`git clean`). The UI must confirm first; this can't be undone.
    pub fn discard(&self, paths: &[String]) -> Result<()> {
        let repo = self.repo();
        let mut index = load_index(&repo)?;
        let mut index_changed = false;
        for path in paths {
            let path = crate::normalize_rela(path)?;
            let abs = self.workdir.join(&path);
            let key = path.as_bytes().as_bstr();
            match index_entry(&index, key) {
                Some(blob) if blob.mode.is_commit() => {}
                Some(blob) => {
                    let data = blob_data(&repo, blob.id)?;
                    write_worktree(&abs, &data, blob.mode)?;
                    set_entry(&mut index, key, blob, stat_of(&abs)?);
                    index_changed = true;
                }
                None => {
                    if index.entry_range(key).is_some() {
                        return Err(ScmError::InvalidInput(format!("{path} has unresolved conflicts")));
                    }
                    remove_worktree(&self.workdir, &abs)?;
                }
            }
        }
        if index_changed {
            write_index(&mut index)?;
        }
        Ok(())
    }

    /// Discards every change in "Changes" (VS Code: "Discard All Changes"), deleting untracked files.
    pub fn discard_all(&self) -> Result<()> {
        let paths = self.paths_in(&[ChangeGroup::WorkingTree])?;
        if paths.is_empty() {
            Ok(())
        } else {
            self.discard(&paths)
        }
    }

    /// "Stage Change" for single hunks of the working tree diff ([`DiffSide::WorkingTree`](crate::DiffSide)).
    pub fn stage_hunks(&self, path: &str, hunks: &[HunkId]) -> Result<()> {
        let path = crate::normalize_rela(path)?;
        let repo = self.repo();
        let mut index = load_index(&repo)?;
        let key = path.as_bytes().as_bstr();
        let staged = index_entry(&index, key);
        let original = text_of(&repo, staged, &path)?;
        let (worktree, worktree_mode) = match read_worktree(&self.workdir.join(&path))? {
            Some((data, mode)) => (as_text_owned(data, &path)?, mode),
            None => (
                String::new(),
                staged.map(|b| b.mode).unwrap_or(EntryMode::from(EntryKind::Blob)),
            ),
        };
        let (text, applied) = textdiff::apply_hunks(&original, &worktree, hunks);
        check_applied(applied, hunks)?;
        let id = repo.write_blob(text.as_bytes()).map_err(ScmError::git)?.detach();
        let mode = staged.map(|b| b.mode).unwrap_or(worktree_mode);
        set_entry(&mut index, key, Blob { id, mode }, Stat::default());
        write_index(&mut index)
    }

    /// "Unstage Change" for single hunks of the staged diff ([`DiffSide::Staged`](crate::DiffSide)).
    pub fn unstage_hunks(&self, path: &str, hunks: &[HunkId]) -> Result<()> {
        let path = crate::normalize_rela(path)?;
        let repo = self.repo();
        let mut index = load_index(&repo)?;
        let key = path.as_bytes().as_bstr();
        let head_blob = tree_entry(&repo, head_tree_id(&repo)?, &path)?;
        let staged = index_entry(&index, key);
        let head = text_of(&repo, head_blob, &path)?;
        let staged_text = text_of(&repo, staged, &path)?;
        let keep = complement(&head, &staged_text, hunks)?;
        let (text, _) = textdiff::apply_hunks(&head, &staged_text, &keep);
        let id = repo.write_blob(text.as_bytes()).map_err(ScmError::git)?.detach();
        let mode = staged
            .or(head_blob)
            .map(|b| b.mode)
            .unwrap_or(EntryMode::from(EntryKind::Blob));
        set_entry(&mut index, key, Blob { id, mode }, Stat::default());
        write_index(&mut index)
    }

    /// "Revert Change" for single hunks of the working tree diff: the working tree file gets the index version of
    /// those lines back.
    pub fn discard_hunks(&self, path: &str, hunks: &[HunkId]) -> Result<()> {
        let path = crate::normalize_rela(path)?;
        let repo = self.repo();
        let index = load_index(&repo)?;
        let staged = index_entry(&index, path.as_bytes().as_bstr());
        let original = text_of(&repo, staged, &path)?;
        let abs = self.workdir.join(&path);
        let Some((data, mode)) = read_worktree(&abs)? else {
            return Err(ScmError::InvalidInput(format!(
                "{path} doesn't exist in the working tree"
            )));
        };
        let worktree = as_text_owned(data, &path)?;
        let keep = complement(&original, &worktree, hunks)?;
        let (text, _) = textdiff::apply_hunks(&original, &worktree, &keep);
        write_worktree(&abs, text.as_bytes(), mode)
    }

    fn paths_in(&self, groups: &[ChangeGroup]) -> Result<Vec<String>> {
        let mut paths: Vec<String> = self
            .status()?
            .changes
            .into_iter()
            .filter(|c| groups.contains(&c.group))
            .map(|c| c.path)
            .collect();
        paths.dedup();
        Ok(paths)
    }
}

fn text_of(repo: &gix::Repository, blob: Option<Blob>, path: &str) -> Result<String> {
    match blob {
        Some(blob) if !blob.mode.is_commit() => as_text_owned(blob_data(repo, blob.id)?, path),
        _ => Ok(String::new()),
    }
}

fn as_text_owned(data: Vec<u8>, path: &str) -> Result<String> {
    if as_text(&data).is_none() {
        return Err(ScmError::InvalidInput(format!("{path} is a binary file")));
    }
    String::from_utf8(data).map_err(|_| ScmError::InvalidInput(format!("{path} is a binary file")))
}

fn check_applied(applied: usize, wanted: &[HunkId]) -> Result<()> {
    if wanted.is_empty() || applied != wanted.len() {
        return Err(ScmError::InvalidInput(
            "The change no longer exists. Refresh and try again.".into(),
        ));
    }
    Ok(())
}

/// All hunks of `original → modified` except `selected`, verifying every selected hunk still exists.
fn complement(original: &str, modified: &str, selected: &[HunkId]) -> Result<Vec<HunkId>> {
    let all: Vec<HunkId> = textdiff::diff_texts(original, modified)
        .iter()
        .map(HunkId::from)
        .collect();
    let found = selected.iter().filter(|h| all.contains(h)).count();
    check_applied(found, selected)?;
    Ok(all.into_iter().filter(|h| !selected.contains(h)).collect())
}
