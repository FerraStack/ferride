//! Low-level helpers shared by staging, checkout and pull: index I/O, tree lookups and working tree files.

use std::collections::BTreeMap;
use std::path::Path;

use gix::ObjectId;
use gix::bstr::{BStr, BString, ByteSlice};
use gix::index::entry::{Flags, Mode, Stat};
use gix::objs::tree::EntryMode;

use crate::{Repository, Result, ScmError};

/// A blob (or symlink, or submodule commit) at a path: its id and mode.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct Blob {
    pub id: ObjectId,
    pub mode: EntryMode,
}

/// Loads the index for modification, or an empty one if the repository has none yet.
pub(crate) fn load_index(repo: &gix::Repository) -> Result<gix::index::File> {
    let path = repo.index_path();
    if path.exists() {
        repo.open_index().map_err(ScmError::git)
    } else {
        Ok(gix::index::File::from_state(
            gix::index::State::new(repo.object_hash()),
            path,
        ))
    }
}

/// Sorts and writes the index. The cached tree extension is dropped since entries changed (git rebuilds it on
/// the next `write-tree`; we always build trees from the entries).
pub(crate) fn write_index(index: &mut gix::index::File) -> Result<()> {
    index.sort_entries();
    index.remove_tree();
    index
        .write(gix::index::write::Options::default())
        .map_err(ScmError::git)
}

/// Removes every entry (all stages) for `path`.
pub(crate) fn remove_entry(index: &mut gix::index::File, path: &BStr) {
    index.remove_entries(|_, p, _| p == path);
}

/// Replaces the entries for `path` with a single stage-0 entry, keeping the entries sorted so lookups by path
/// keep working (the vector is almost sorted, so this is cheap).
pub(crate) fn set_entry(index: &mut gix::index::File, path: &BStr, blob: Blob, stat: Stat) {
    remove_entry(index, path);
    index.dangerously_push_entry(stat, blob.id, Flags::empty(), Mode::from(blob.mode), path);
    index.sort_entries();
}

/// The stage-0 entry for `path` in the index.
pub(crate) fn index_entry(index: &gix::index::State, path: &BStr) -> Option<Blob> {
    let entry = index.entry_by_path_and_stage(path, gix::index::entry::Stage::Unconflicted)?;
    Some(Blob {
        id: entry.id,
        mode: entry.mode.to_tree_entry_mode()?,
    })
}

/// The commit `HEAD` points to, or `None` on an unborn branch.
pub(crate) fn head_commit_id(repo: &gix::Repository) -> Result<Option<ObjectId>> {
    let head = repo.head().map_err(ScmError::git)?;
    Ok(head.id().map(|id| id.detach()))
}

pub(crate) fn commit_tree_id(repo: &gix::Repository, commit: ObjectId) -> Result<ObjectId> {
    let commit = repo.find_commit(commit).map_err(ScmError::git)?;
    commit.tree_id().map(|id| id.detach()).map_err(ScmError::git)
}

/// The tree of `HEAD`, or `None` on an unborn branch.
pub(crate) fn head_tree_id(repo: &gix::Repository) -> Result<Option<ObjectId>> {
    match head_commit_id(repo)? {
        Some(commit) => commit_tree_id(repo, commit).map(Some),
        None => Ok(None),
    }
}

/// Looks up `path` in `tree`.
pub(crate) fn tree_entry(repo: &gix::Repository, tree: Option<ObjectId>, path: &str) -> Result<Option<Blob>> {
    let Some(tree) = tree else { return Ok(None) };
    let tree = repo.find_tree(tree).map_err(ScmError::git)?;
    let entry = tree.lookup_entry_by_path(path).map_err(ScmError::git)?;
    Ok(entry.filter(|e| !e.mode().is_tree()).map(|e| Blob {
        id: e.object_id(),
        mode: e.mode(),
    }))
}

/// All non-tree entries of `tree`, by path.
pub(crate) fn flatten_tree(
    repo: &gix::Repository,
    tree: Option<ObjectId>,
) -> Result<BTreeMap<BString, Blob>> {
    let mut out = BTreeMap::new();
    let Some(tree) = tree else { return Ok(out) };
    let tree = repo.find_tree(tree).map_err(ScmError::git)?;
    let mut recorder = gix::traverse::tree::Recorder::default();
    tree.traverse()
        .breadthfirst(&mut recorder)
        .map_err(ScmError::git)?;
    for entry in recorder.records {
        if !entry.mode.is_tree() {
            out.insert(
                entry.filepath,
                Blob {
                    id: entry.oid,
                    mode: entry.mode,
                },
            );
        }
    }
    Ok(out)
}

pub(crate) fn blob_data(repo: &gix::Repository, id: ObjectId) -> Result<Vec<u8>> {
    let mut blob = repo.find_blob(id).map_err(ScmError::git)?;
    Ok(blob.take_data())
}

/// Reads a working tree file the way git would store it: symlinks as their target, files with their
/// executable bit. `None` if it doesn't exist (or is a directory).
pub(crate) fn read_worktree(abs: &Path) -> Result<Option<(Vec<u8>, EntryMode)>> {
    let meta = match std::fs::symlink_metadata(abs) {
        Ok(meta) => meta,
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(err) => return Err(err.into()),
    };
    if meta.file_type().is_symlink() {
        let target = std::fs::read_link(abs)?;
        let bytes = gix::path::into_bstr(target)
            .map_err(ScmError::git)?
            .into_owned()
            .into();
        return Ok(Some((bytes, EntryMode::from(gix::objs::tree::EntryKind::Link))));
    }
    if meta.is_dir() {
        return Ok(None);
    }
    let data = std::fs::read(abs)?;
    let kind = if is_executable(&meta) {
        gix::objs::tree::EntryKind::BlobExecutable
    } else {
        gix::objs::tree::EntryKind::Blob
    };
    Ok(Some((data, EntryMode::from(kind))))
}

#[cfg(unix)]
fn is_executable(meta: &std::fs::Metadata) -> bool {
    use std::os::unix::fs::PermissionsExt;
    meta.permissions().mode() & 0o111 != 0
}

#[cfg(not(unix))]
fn is_executable(_meta: &std::fs::Metadata) -> bool {
    false
}

/// The index stat of a working tree path (not following symlinks).
pub(crate) fn stat_of(abs: &Path) -> Result<Stat> {
    let meta = gix::index::fs::Metadata::from_path_no_follow(abs)?;
    Stat::from_fs(&meta).map_err(ScmError::git)
}

/// Writes `data` at `abs` as a file of `mode` (creating parent directories), replacing whatever is there.
pub(crate) fn write_worktree(abs: &Path, data: &[u8], mode: EntryMode) -> Result<()> {
    if let Some(parent) = abs.parent() {
        std::fs::create_dir_all(parent)?;
    }
    if let Ok(meta) = std::fs::symlink_metadata(abs) {
        if meta.is_dir() && !meta.file_type().is_symlink() {
            std::fs::remove_dir_all(abs)?;
        } else if meta.file_type().is_symlink() || mode.is_link() {
            std::fs::remove_file(abs)?;
        }
    }
    if mode.is_link() {
        #[cfg(unix)]
        {
            let target = gix::path::from_bstr(data.as_bstr()).map_err(ScmError::git)?;
            std::os::unix::fs::symlink(target, abs)?;
            return Ok(());
        }
        #[cfg(not(unix))]
        {
            std::fs::write(abs, data)?;
            return Ok(());
        }
    }
    std::fs::write(abs, data)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut perms = std::fs::metadata(abs)?.permissions();
        let current = perms.mode();
        // Like git: executable where readable.
        let wanted = if mode.is_executable() {
            current | ((current & 0o444) >> 2)
        } else {
            current & !0o111
        };
        if wanted != current {
            perms.set_mode(wanted);
            std::fs::set_permissions(abs, perms)?;
        }
    }
    Ok(())
}

/// Removes a working tree file and then any parent directories it leaves empty (up to the root).
pub(crate) fn remove_worktree(root: &Path, abs: &Path) -> Result<()> {
    match std::fs::remove_file(abs) {
        Ok(()) => {}
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(err) => return Err(err.into()),
    }
    let mut dir = abs.parent();
    while let Some(d) = dir {
        if d == root || !d.starts_with(root) {
            break;
        }
        if std::fs::remove_dir(d).is_err() {
            break;
        }
        dir = d.parent();
    }
    Ok(())
}

impl Repository {
    /// Moves the working tree and index from tree `from` to tree `to` (checkout / fast-forward).
    ///
    /// Refuses, without touching anything, if a path that differs between the two trees has staged or unstaged
    /// changes, or is an untracked file that would be overwritten: the same rule git uses for `checkout`.
    pub(crate) fn switch_tree(
        &self,
        repo: &gix::Repository,
        from: Option<ObjectId>,
        to: Option<ObjectId>,
    ) -> Result<()> {
        let old = flatten_tree(repo, from)?;
        let new = flatten_tree(repo, to)?;
        let mut changed: Vec<&BString> = Vec::new();
        for (path, blob) in &old {
            if new.get(path) != Some(blob) {
                changed.push(path);
            }
        }
        for path in new.keys() {
            if !old.contains_key(path) {
                changed.push(path);
            }
        }
        if changed.is_empty() {
            return Ok(());
        }

        let status = self.status()?;
        let mut blocked: Vec<String> = status
            .changes
            .iter()
            .filter(|c| changed.iter().any(|p| p.as_bstr() == c.path.as_bytes().as_bstr()))
            .map(|c| c.path.clone())
            .collect();
        blocked.sort();
        blocked.dedup();
        if !blocked.is_empty() {
            return Err(ScmError::WouldOverwrite(blocked));
        }

        let mut index = load_index(repo)?;
        // Deletions first so a file can become a directory and vice versa.
        for path in &changed {
            if !new.contains_key(*path) {
                let abs = self
                    .workdir
                    .join(gix::path::from_bstr(path.as_bstr()).map_err(ScmError::git)?);
                remove_worktree(&self.workdir, &abs)?;
                remove_entry(&mut index, path.as_bstr());
            }
        }
        for path in &changed {
            if let Some(blob) = new.get(*path) {
                let abs = self
                    .workdir
                    .join(gix::path::from_bstr(path.as_bstr()).map_err(ScmError::git)?);
                if blob.mode.is_commit() {
                    // Submodule: record the commit, leave the directory alone.
                    std::fs::create_dir_all(&abs)?;
                    set_entry(&mut index, path.as_bstr(), *blob, Stat::default());
                    continue;
                }
                let data = blob_data(repo, blob.id)?;
                write_worktree(&abs, &data, blob.mode)?;
                set_entry(&mut index, path.as_bstr(), *blob, stat_of(&abs)?);
            }
        }
        write_index(&mut index)
    }
}
