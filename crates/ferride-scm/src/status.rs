//! Working tree status, grouped like VS Code's Source Control view.

use std::collections::BTreeSet;

use gix::bstr::ByteSlice;
use gix::status::{Submodule, UntrackedFiles, index_worktree, tree_index::TrackRenames};
use gix_status_types::{Conflict, EntryStatus, IndexChange};

use crate::{Repository, Result, ScmError};

/// Names gix's re-exported status types so the match below stays readable.
mod gix_status_types {
    pub use gix::diff::index::Change as IndexChange;
    pub use gix::status::plumbing::index_as_worktree::{Conflict, EntryStatus};
}

/// The resource group a change belongs to (VS Code: "Merge Changes", "Staged Changes", "Changes").
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum ChangeGroup {
    /// Unmerged paths.
    Merge,
    /// Changes between `HEAD` and the index.
    Index,
    /// Changes between the index and the working tree, including untracked files.
    WorkingTree,
}

impl ChangeGroup {
    /// VS Code's group label.
    pub fn label(self) -> &'static str {
        match self {
            ChangeGroup::Merge => "Merge Changes",
            ChangeGroup::Index => "Staged Changes",
            ChangeGroup::WorkingTree => "Changes",
        }
    }
}

/// What happened to a path; the variants mirror VS Code's git `Status` enum.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ChangeKind {
    Modified,
    Added,
    Deleted,
    TypeChanged,
    Untracked,
    IntentToAdd,
    BothDeleted,
    AddedByUs,
    DeletedByThem,
    AddedByThem,
    DeletedByUs,
    BothAdded,
    BothModified,
}

/// One entry of the status.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct Change {
    /// Repository-relative path, `/` separated.
    pub path: String,
    pub group: ChangeGroup,
    pub kind: ChangeKind,
}

impl Change {
    /// The decoration letter VS Code shows next to the file name.
    pub fn letter(&self) -> &'static str {
        use ChangeKind::*;
        match self.kind {
            Modified => "M",
            Added | IntentToAdd => "A",
            Deleted => "D",
            TypeChanged => "T",
            Untracked => "U",
            BothDeleted | AddedByUs | DeletedByThem | AddedByThem | DeletedByUs | BothAdded
            | BothModified => "!",
        }
    }

    /// VS Code's tooltip for the decoration ("Index Modified", "Untracked", "Conflict: Both Modified", …).
    pub fn tooltip(&self) -> &'static str {
        use ChangeKind::*;
        let staged = self.group == ChangeGroup::Index;
        match self.kind {
            Modified if staged => "Index Modified",
            Modified => "Modified",
            Added if staged => "Index Added",
            Added => "Added",
            Deleted if staged => "Index Deleted",
            Deleted => "Deleted",
            TypeChanged => "Type Changed",
            Untracked => "Untracked",
            IntentToAdd => "Intent to Add",
            BothDeleted => "Conflict: Both Deleted",
            AddedByUs => "Conflict: Added By Us",
            DeletedByThem => "Conflict: Deleted By Them",
            AddedByThem => "Conflict: Added By Them",
            DeletedByUs => "Conflict: Deleted By Us",
            BothAdded => "Conflict: Both Added",
            BothModified => "Conflict: Both Modified",
        }
    }

    /// Whether the file no longer exists on this side (VS Code strikes those through).
    pub fn is_deletion(&self) -> bool {
        matches!(
            self.kind,
            ChangeKind::Deleted | ChangeKind::BothDeleted | ChangeKind::DeletedByUs
        )
    }
}

/// The status of a repository: every change, sorted by group then path.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Status {
    pub changes: Vec<Change>,
}

impl Status {
    pub fn group(&self, group: ChangeGroup) -> impl Iterator<Item = &Change> {
        self.changes.iter().filter(move |c| c.group == group)
    }

    /// The number VS Code shows on the Source Control badge (`scm.countBadge: all`).
    pub fn count(&self) -> usize {
        self.changes.len()
    }
}

impl Repository {
    /// `git status -uall` without renames or submodule details.
    pub fn status(&self) -> Result<Status> {
        let repo = self.repo();
        let iter = repo
            .status(gix::progress::Discard)
            .map_err(ScmError::git)?
            .untracked_files(UntrackedFiles::Files)
            .index_worktree_rewrites(None)
            .tree_index_track_renames(TrackRenames::Disabled)
            .index_worktree_submodules(Submodule::Given {
                ignore: gix::submodule::config::Ignore::All,
                check_dirty: false,
            })
            .into_iter(None)
            .map_err(ScmError::git)?;

        let mut changes = Vec::new();
        let mut conflicted = BTreeSet::new();
        for item in iter {
            let item = item.map_err(ScmError::git)?;
            match item {
                gix::status::Item::TreeIndex(change) => {
                    let (path, kind) = match &change {
                        IndexChange::Addition { location, .. } => (location, ChangeKind::Added),
                        IndexChange::Deletion { location, .. } => (location, ChangeKind::Deleted),
                        IndexChange::Modification {
                            location,
                            previous_entry_mode,
                            entry_mode,
                            ..
                        } => (
                            location,
                            if previous_entry_mode.to_tree_entry_mode().map(|m| m.kind())
                                != entry_mode.to_tree_entry_mode().map(|m| m.kind())
                            {
                                ChangeKind::TypeChanged
                            } else {
                                ChangeKind::Modified
                            },
                        ),
                        IndexChange::Rewrite { location, .. } => (location, ChangeKind::Added),
                    };
                    changes.push(Change {
                        group: ChangeGroup::Index,
                        path: path.to_str_lossy().into_owned(),
                        kind,
                    });
                }
                gix::status::Item::IndexWorktree(item) => match item {
                    index_worktree::Item::Modification {
                        rela_path, status, ..
                    } => {
                        let path = rela_path.to_str_lossy().into_owned();
                        let kind = match status {
                            EntryStatus::Conflict { summary, .. } => {
                                conflicted.insert(path.clone());
                                let kind = match summary {
                                    Conflict::BothDeleted => ChangeKind::BothDeleted,
                                    Conflict::AddedByUs => ChangeKind::AddedByUs,
                                    Conflict::DeletedByThem => ChangeKind::DeletedByThem,
                                    Conflict::AddedByThem => ChangeKind::AddedByThem,
                                    Conflict::DeletedByUs => ChangeKind::DeletedByUs,
                                    Conflict::BothAdded => ChangeKind::BothAdded,
                                    Conflict::BothModified => ChangeKind::BothModified,
                                };
                                changes.push(Change {
                                    group: ChangeGroup::Merge,
                                    path,
                                    kind,
                                });
                                continue;
                            }
                            EntryStatus::Change(change) => match change {
                                gix::status::plumbing::index_as_worktree::Change::Removed => {
                                    ChangeKind::Deleted
                                }
                                gix::status::plumbing::index_as_worktree::Change::Type { .. } => {
                                    ChangeKind::TypeChanged
                                }
                                _ => ChangeKind::Modified,
                            },
                            EntryStatus::IntentToAdd => ChangeKind::IntentToAdd,
                            EntryStatus::NeedsUpdate(_) => continue,
                        };
                        changes.push(Change {
                            group: ChangeGroup::WorkingTree,
                            path,
                            kind,
                        });
                    }
                    index_worktree::Item::DirectoryContents { entry, .. } => {
                        if entry.status == gix::dir::entry::Status::Untracked
                            && entry.disk_kind.is_none_or(|k| !k.is_dir())
                        {
                            let path = entry.rela_path.to_str_lossy().into_owned();
                            changes.push(Change {
                                group: ChangeGroup::WorkingTree,
                                path,
                                kind: ChangeKind::Untracked,
                            });
                        }
                    }
                    index_worktree::Item::Rewrite { dirwalk_entry, .. } => {
                        // Renames are disabled above, but be safe: report the destination as untracked.
                        let path = dirwalk_entry.rela_path.to_str_lossy().into_owned();
                        changes.push(Change {
                            group: ChangeGroup::WorkingTree,
                            path,
                            kind: ChangeKind::Untracked,
                        });
                    }
                },
            }
        }

        changes.retain(|c| c.group == ChangeGroup::Merge || !conflicted.contains(&c.path));
        changes.sort_by(|a, b| (a.group, &a.path).cmp(&(b.group, &b.path)));
        changes.dedup_by(|a, b| a.group == b.group && a.path == b.path);
        Ok(Status { changes })
    }
}
