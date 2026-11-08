//! File diffs for the diff editor and the original text for gutter decorations.

use gix::bstr::ByteSlice;

use crate::textdiff::{self, Hunk};
use crate::worktree::{blob_data, head_tree_id, index_entry, load_index, read_worktree, tree_entry};
use crate::{Repository, Result};

/// Which pair of versions to compare.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DiffSide {
    /// `HEAD` ↔ index ("Staged Changes"; VS Code's title `file.txt (Index)`).
    Staged,
    /// Index ↔ working tree ("Changes"; VS Code's title `file.txt (Working Tree)`). Untracked files compare
    /// against nothing; a conflicted file compares "ours" with the working tree.
    WorkingTree,
}

/// A two-sided diff of one file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileDiff {
    pub path: String,
    /// Left side text (empty if it doesn't exist or is binary).
    pub original: String,
    /// Right side text (empty if it doesn't exist or is binary).
    pub modified: String,
    pub original_exists: bool,
    pub modified_exists: bool,
    /// Either side isn't text (contains NUL bytes or isn't UTF-8); `hunks` is empty then.
    pub binary: bool,
    pub hunks: Vec<Hunk>,
}

/// Git's heuristic: a NUL byte in the first 8000 bytes means binary. We also need valid UTF-8 to show text.
pub(crate) fn as_text(data: &[u8]) -> Option<&str> {
    let head = &data[..data.len().min(8000)];
    if head.contains(&0) {
        return None;
    }
    std::str::from_utf8(data).ok()
}

impl Repository {
    /// Diffs one file on the given side.
    pub fn diff(&self, path: &str, side: DiffSide) -> Result<FileDiff> {
        let path = crate::normalize_rela(path)?;
        let (original, modified) = match side {
            DiffSide::Staged => (self.head_bytes(&path)?, self.index_bytes(&path)?),
            DiffSide::WorkingTree => {
                let original = match self.index_bytes(&path)? {
                    Some(bytes) => Some(bytes),
                    None => self.ours_bytes(&path)?,
                };
                (original, self.worktree_bytes(&path)?)
            }
        };
        Ok(build_diff(path, original, modified))
    }

    /// The text the editor's gutter decorations compare against: the index version of the file, like VS Code's
    /// quick diff (so staged changes stop being decorated). `None` for untracked, ignored, binary files or paths
    /// outside a repository.
    pub fn quick_diff_original(&self, path: &str) -> Result<Option<String>> {
        let path = crate::normalize_rela(path)?;
        Ok(self
            .index_bytes(&path)?
            .and_then(|b| as_text(&b).map(str::to_owned)))
    }

    /// The file's content at `HEAD`.
    pub fn head_bytes(&self, path: &str) -> Result<Option<Vec<u8>>> {
        let repo = self.repo();
        match tree_entry(&repo, head_tree_id(&repo)?, path)? {
            Some(blob) if !blob.mode.is_commit() => blob_data(&repo, blob.id).map(Some),
            _ => Ok(None),
        }
    }

    /// The file's staged content (stage 0).
    pub fn index_bytes(&self, path: &str) -> Result<Option<Vec<u8>>> {
        let repo = self.repo();
        let index = load_index(&repo)?;
        match index_entry(&index, path.as_bytes().as_bstr()) {
            Some(blob) if !blob.mode.is_commit() => blob_data(&repo, blob.id).map(Some),
            _ => Ok(None),
        }
    }

    /// "Ours" (stage 2) of a conflicted file.
    fn ours_bytes(&self, path: &str) -> Result<Option<Vec<u8>>> {
        let repo = self.repo();
        let index = load_index(&repo)?;
        match index.entry_by_path_and_stage(path.as_bytes().as_bstr(), gix::index::entry::Stage::Ours) {
            Some(entry) => blob_data(&repo, entry.id).map(Some),
            None => Ok(None),
        }
    }

    fn worktree_bytes(&self, path: &str) -> Result<Option<Vec<u8>>> {
        Ok(read_worktree(&self.abs_path(path)?)?.map(|(data, _)| data))
    }
}

fn build_diff(path: String, original: Option<Vec<u8>>, modified: Option<Vec<u8>>) -> FileDiff {
    let original_exists = original.is_some();
    let modified_exists = modified.is_some();
    let original_text = original.as_deref().map(as_text);
    let modified_text = modified.as_deref().map(as_text);
    let binary = matches!(original_text, Some(None)) || matches!(modified_text, Some(None));
    if binary {
        return FileDiff {
            path,
            original: String::new(),
            modified: String::new(),
            original_exists,
            modified_exists,
            binary,
            hunks: Vec::new(),
        };
    }
    let original = original_text.flatten().unwrap_or("").to_owned();
    let modified = modified_text.flatten().unwrap_or("").to_owned();
    let hunks = textdiff::diff_texts(&original, &modified);
    FileDiff {
        path,
        original,
        modified,
        original_exists,
        modified_exists,
        binary,
        hunks,
    }
}
