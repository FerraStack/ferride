//! Virtual file systems: where a workspace's files actually live.
//!
//! A workspace is a tree of files addressed by **workspace-relative paths**
//! (`""` is the root, `src/main.rs`, always `/`-separated, never `..`). Three
//! backends implement [`Vfs`]:
//!
//! - [`LocalVfs`]: a real directory. App-private `files/projects/` (the
//!   default for new and cloned projects) and, in the `foss` build only, any
//!   folder after the user grants all-files access. Has a [`Vfs::local_root`],
//!   so ripgrep search and git (gix) work.
//! - [`SafVfs`]: an Android Storage Access Framework tree (`content://` URI),
//!   reached through a [`DocumentTree`] the app implements with
//!   `DocumentsContract`. SAF is slow (each query is a Binder round trip to the
//!   provider), so directory listings are cached and every lookup reuses them.
//!   No real path: text search and git are unavailable, and the UI offers to
//!   copy the folder into Ferride's projects instead ([`copy_tree`]).
//! - [`FerruxVfs`]: Ferrux's home directory, the "real" dev workspace. A stub
//!   until Ferrux exposes its file API; every call reports that.
//!
//! All calls block; run them off the UI thread.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard};

use crate::{MAX_FILE_SIZE, Result, Workspace, WorkspaceError};

/// Which backend a [`Vfs`] is.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VfsKind {
    Local,
    Saf,
    Ferrux,
}

/// One child of a directory.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VfsEntry {
    pub name: String,
    /// Workspace-relative, `/`-separated.
    pub rel_path: String,
    pub is_dir: bool,
    /// Bytes (0 for directories or when the provider doesn't say).
    pub size: u64,
    /// Milliseconds since the Unix epoch (0 if unknown).
    pub modified_ms: i64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct VfsStat {
    pub is_dir: bool,
    pub size: u64,
    pub modified_ms: i64,
}

/// A file tree the editor can browse and edit.
pub trait Vfs: Send + Sync {
    fn kind(&self) -> VfsKind;

    /// Folder name shown in the Explorer title and the Welcome page.
    fn name(&self) -> String;

    /// The real directory behind this tree, when there is one. ripgrep and
    /// gix need it; `None` disables text search and Source Control.
    fn local_root(&self) -> Option<&Path> {
        None
    }

    /// Lists a directory: folders first, then files, case-insensitively;
    /// `.git` is never shown.
    fn list(&self, dir: &str) -> Result<Vec<VfsEntry>>;
    fn stat(&self, path: &str) -> Result<VfsStat>;
    fn read(&self, path: &str) -> Result<Vec<u8>>;
    /// Replaces a file's content (creating it if needed).
    fn write(&self, path: &str, bytes: &[u8]) -> Result<()>;
    fn create_file(&self, path: &str) -> Result<()>;
    fn create_dir(&self, path: &str) -> Result<()>;
    /// Renames or moves `from` to `to` (both relative; `to` must not exist).
    fn rename(&self, from: &str, to: &str) -> Result<()>;
    /// Deletes a file or a whole directory.
    fn delete(&self, path: &str) -> Result<()>;

    /// Forgets cached listings under `dir` (after external changes); no-op
    /// for backends without a cache.
    fn invalidate(&self, _dir: &str) {}
}

/// Normalizes a workspace-relative path: `\` → `/`, empty and `.` segments
/// dropped, leading `/` ignored. `..` is rejected rather than resolved, so
/// nothing can address a file outside the tree.
pub fn normalize_rel(path: &str) -> Result<String> {
    let mut parts = Vec::new();
    for seg in path.split(['/', '\\']) {
        match seg {
            "" | "." => {}
            ".." => return Err(WorkspaceError::OutsideWorkspace(PathBuf::from(path))),
            s => parts.push(s),
        }
    }
    Ok(parts.join("/"))
}

fn split_parent(rel: &str) -> (&str, &str) {
    match rel.rfind('/') {
        Some(i) => (&rel[..i], &rel[i + 1..]),
        None => ("", rel),
    }
}

fn join_rel(dir: &str, name: &str) -> String {
    if dir.is_empty() {
        name.to_owned()
    } else {
        format!("{dir}/{name}")
    }
}

fn sort_entries(entries: &mut [VfsEntry]) {
    entries.sort_by(|a, b| {
        b.is_dir
            .cmp(&a.is_dir)
            .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
            .then_with(|| a.name.cmp(&b.name))
    });
}

/// Reads a text file for editing with the same rules on every backend:
/// at most [`MAX_FILE_SIZE`], no NUL bytes in the first 8 KiB, valid UTF-8.
pub fn read_text(vfs: &dyn Vfs, path: &str) -> Result<String> {
    let rel = normalize_rel(path)?;
    let stat = vfs.stat(&rel)?;
    if stat.size > MAX_FILE_SIZE {
        return Err(WorkspaceError::TooLarge(PathBuf::from(&rel), stat.size));
    }
    let bytes = vfs.read(&rel)?;
    if bytes.len() as u64 > MAX_FILE_SIZE {
        return Err(WorkspaceError::TooLarge(PathBuf::from(&rel), bytes.len() as u64));
    }
    if bytes[..bytes.len().min(8192)].contains(&0) {
        return Err(WorkspaceError::Binary(PathBuf::from(&rel)));
    }
    String::from_utf8(bytes).map_err(|_| WorkspaceError::NotUtf8(PathBuf::from(&rel)))
}

/// Every file under the root (relative paths), breadth-first, skipping
/// `.git`, `node_modules` and `target`, stopping after `limit` files. Quick
/// Open uses this on backends without a real path; listings are cached by
/// SAF, so a second walk is cheap.
pub fn walk_files(vfs: &dyn Vfs, limit: usize) -> Vec<String> {
    let mut out = Vec::new();
    let mut queue = std::collections::VecDeque::from([String::new()]);
    while let Some(dir) = queue.pop_front() {
        let Ok(entries) = vfs.list(&dir) else { continue };
        for e in entries {
            if e.is_dir {
                if !matches!(e.name.as_str(), "node_modules" | "target") {
                    queue.push_back(e.rel_path);
                }
            } else {
                out.push(e.rel_path);
                if out.len() >= limit {
                    return out;
                }
            }
        }
    }
    out
}

/// Progress of [`copy_tree`]: files copied so far and the current file.
pub type CopyProgress<'a> = &'a mut dyn FnMut(usize, &str);

/// Copies everything under `src_dir` of `src` into `dst_dir` of `dst`
/// (created if missing), e.g. a SAF folder into `files/projects/<name>` so
/// Git and fast search work on it. Returns the number of files copied.
pub fn copy_tree(
    src: &dyn Vfs,
    src_dir: &str,
    dst: &dyn Vfs,
    dst_dir: &str,
    progress: CopyProgress<'_>,
) -> Result<usize> {
    let src_dir = normalize_rel(src_dir)?;
    let dst_dir = normalize_rel(dst_dir)?;
    if !dst_dir.is_empty() && dst.stat(&dst_dir).is_err() {
        dst.create_dir(&dst_dir)?;
    }
    let mut copied = 0;
    let mut stack = vec![(src_dir, dst_dir)];
    while let Some((from, to)) = stack.pop() {
        for e in src.list(&from)? {
            let target = join_rel(&to, &e.name);
            if e.is_dir {
                if dst.stat(&target).is_err() {
                    dst.create_dir(&target)?;
                }
                stack.push((e.rel_path, target));
            } else {
                let bytes = src.read(&e.rel_path)?;
                dst.write(&target, &bytes)?;
                copied += 1;
                progress(copied, &e.rel_path);
            }
        }
    }
    Ok(copied)
}

// ----- local ------------------------------------------------------------------

/// A real directory, with the root confinement of [`Workspace`].
#[derive(Debug, Clone)]
pub struct LocalVfs {
    ws: Workspace,
}

impl LocalVfs {
    pub fn open(root: impl AsRef<Path>) -> Result<Self> {
        Ok(Self {
            ws: Workspace::open(root)?,
        })
    }

    pub fn workspace(&self) -> &Workspace {
        &self.ws
    }

    fn path(&self, rel: &str) -> Result<PathBuf> {
        Ok(self.ws.root().join(normalize_rel(rel)?))
    }
}

fn modified_ms(meta: &std::fs::Metadata) -> i64 {
    meta.modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map_or(0, |d| d.as_millis() as i64)
}

impl Vfs for LocalVfs {
    fn kind(&self) -> VfsKind {
        VfsKind::Local
    }

    fn name(&self) -> String {
        self.ws.name()
    }

    fn local_root(&self) -> Option<&Path> {
        Some(self.ws.root())
    }

    fn list(&self, dir: &str) -> Result<Vec<VfsEntry>> {
        let entries = self.ws.list_dir(self.path(dir)?)?;
        Ok(entries
            .into_iter()
            .map(|e| {
                let meta = std::fs::metadata(&e.path).ok();
                VfsEntry {
                    size: meta.as_ref().filter(|m| m.is_file()).map_or(0, |m| m.len()),
                    modified_ms: meta.as_ref().map_or(0, modified_ms),
                    name: e.name,
                    rel_path: e.rel_path,
                    is_dir: e.is_dir,
                }
            })
            .collect())
    }

    fn stat(&self, path: &str) -> Result<VfsStat> {
        let p = self.ws.resolve(self.path(path)?)?;
        let meta = std::fs::metadata(&p).map_err(|source| WorkspaceError::Io {
            path: p.clone(),
            source,
        })?;
        Ok(VfsStat {
            is_dir: meta.is_dir(),
            size: if meta.is_file() { meta.len() } else { 0 },
            modified_ms: modified_ms(&meta),
        })
    }

    fn read(&self, path: &str) -> Result<Vec<u8>> {
        let p = self.ws.resolve(self.path(path)?)?;
        std::fs::read(&p).map_err(|source| WorkspaceError::Io { path: p, source })
    }

    fn write(&self, path: &str, bytes: &[u8]) -> Result<()> {
        self.ws.write_bytes(self.path(path)?, bytes)
    }

    fn create_file(&self, path: &str) -> Result<()> {
        self.ws.create_file(self.path(path)?).map(drop)
    }

    fn create_dir(&self, path: &str) -> Result<()> {
        self.ws.create_dir(self.path(path)?).map(drop)
    }

    fn rename(&self, from: &str, to: &str) -> Result<()> {
        self.ws.rename(self.path(from)?, self.path(to)?).map(drop)
    }

    fn delete(&self, path: &str) -> Result<()> {
        let rel = normalize_rel(path)?;
        if rel.is_empty() {
            return Err(WorkspaceError::OutsideWorkspace(PathBuf::from(path)));
        }
        self.ws.delete(self.path(&rel)?)
    }
}

// ----- SAF ----------------------------------------------------------------------

/// A document in a SAF tree, as `DocumentsContract` describes it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DocNode {
    /// `COLUMN_DOCUMENT_ID`.
    pub id: String,
    pub name: String,
    pub is_dir: bool,
    pub size: u64,
    pub modified_ms: i64,
}

/// The Android side of a SAF tree. The app implements it with
/// `DocumentsContract` on a persisted tree URI; errors are human-readable
/// messages (they end up in the UI).
pub trait DocumentTree: Send + Sync {
    /// Document id of the tree root (`getTreeDocumentId`).
    fn root_id(&self) -> String;
    fn display_name(&self) -> String;
    /// All children of a directory in **one** query (SAF is slow per call).
    fn children(&self, dir_id: &str) -> std::result::Result<Vec<DocNode>, String>;
    fn read(&self, id: &str) -> std::result::Result<Vec<u8>, String>;
    /// Truncates and writes (`openFileDescriptor(uri, "wt")`).
    fn write(&self, id: &str, bytes: &[u8]) -> std::result::Result<(), String>;
    /// Creates a file or directory; returns the new document id.
    fn create(&self, parent_id: &str, name: &str, is_dir: bool) -> std::result::Result<String, String>;
    /// Renames in place; returns the (possibly new) document id.
    fn rename(&self, id: &str, new_name: &str) -> std::result::Result<String, String>;
    fn delete(&self, id: &str) -> std::result::Result<(), String>;
}

/// A SAF tree with cached directory listings (keyed by relative path).
pub struct SafVfs {
    tree: Arc<dyn DocumentTree>,
    cache: Mutex<HashMap<String, Vec<DocNode>>>,
}

impl std::fmt::Debug for SafVfs {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SafVfs")
            .field("name", &self.tree.display_name())
            .finish()
    }
}

fn provider(rel: &str) -> impl FnOnce(String) -> WorkspaceError + '_ {
    move |msg| WorkspaceError::Provider(format!("{rel}: {msg}"))
}

impl SafVfs {
    pub fn new(tree: Arc<dyn DocumentTree>) -> Self {
        Self {
            tree,
            cache: Mutex::new(HashMap::new()),
        }
    }

    fn cache(&self) -> MutexGuard<'_, HashMap<String, Vec<DocNode>>> {
        // A panic while holding the lock only leaves a stale cache behind.
        self.cache.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Children of the directory `dir` (relative), from the cache or one query.
    fn children(&self, dir: &str, dir_id: &str) -> Result<Vec<DocNode>> {
        if let Some(c) = self.cache().get(dir) {
            return Ok(c.clone());
        }
        let nodes = self.tree.children(dir_id).map_err(provider(dir))?;
        self.cache().insert(dir.to_owned(), nodes.clone());
        Ok(nodes)
    }

    /// Resolves a relative path to its node by walking cached listings.
    fn lookup(&self, rel: &str) -> Result<DocNode> {
        let root = DocNode {
            id: self.tree.root_id(),
            name: self.tree.display_name(),
            is_dir: true,
            size: 0,
            modified_ms: 0,
        };
        if rel.is_empty() {
            return Ok(root);
        }
        let mut node = root;
        let mut dir = String::new();
        for seg in rel.split('/') {
            if !node.is_dir {
                return Err(WorkspaceError::NotADirectory(PathBuf::from(&dir)));
            }
            let kids = self.children(&dir, &node.id)?;
            node = kids
                .into_iter()
                .find(|k| k.name == seg)
                .ok_or_else(|| WorkspaceError::Io {
                    path: PathBuf::from(rel),
                    source: std::io::ErrorKind::NotFound.into(),
                })?;
            dir = join_rel(&dir, seg);
        }
        Ok(node)
    }

    fn forget(&self, dir: &str) {
        let mut cache = self.cache();
        let prefix = format!("{dir}/");
        cache.retain(|k, _| !(k == dir || dir.is_empty() || k.starts_with(&prefix)));
    }

    fn exists(&self, rel: &str) -> bool {
        self.lookup(rel).is_ok()
    }
}

impl Vfs for SafVfs {
    fn kind(&self) -> VfsKind {
        VfsKind::Saf
    }

    fn name(&self) -> String {
        self.tree.display_name()
    }

    fn list(&self, dir: &str) -> Result<Vec<VfsEntry>> {
        let rel = normalize_rel(dir)?;
        let node = self.lookup(&rel)?;
        if !node.is_dir {
            return Err(WorkspaceError::NotADirectory(PathBuf::from(&rel)));
        }
        let mut entries: Vec<VfsEntry> = self
            .children(&rel, &node.id)?
            .into_iter()
            .filter(|n| n.name != ".git")
            .map(|n| VfsEntry {
                rel_path: join_rel(&rel, &n.name),
                name: n.name,
                is_dir: n.is_dir,
                size: n.size,
                modified_ms: n.modified_ms,
            })
            .collect();
        sort_entries(&mut entries);
        Ok(entries)
    }

    fn stat(&self, path: &str) -> Result<VfsStat> {
        let node = self.lookup(&normalize_rel(path)?)?;
        Ok(VfsStat {
            is_dir: node.is_dir,
            size: node.size,
            modified_ms: node.modified_ms,
        })
    }

    fn read(&self, path: &str) -> Result<Vec<u8>> {
        let rel = normalize_rel(path)?;
        let node = self.lookup(&rel)?;
        self.tree.read(&node.id).map_err(provider(&rel))
    }

    fn write(&self, path: &str, bytes: &[u8]) -> Result<()> {
        let rel = normalize_rel(path)?;
        let id = match self.lookup(&rel) {
            Ok(node) => node.id,
            Err(_) => {
                let (parent, name) = split_parent(&rel);
                let parent_node = self.lookup(parent)?;
                let id = self
                    .tree
                    .create(&parent_node.id, name, false)
                    .map_err(provider(&rel))?;
                self.forget(parent);
                id
            }
        };
        self.tree.write(&id, bytes).map_err(provider(&rel))?;
        // Size and mtime changed.
        self.forget(split_parent(&rel).0);
        Ok(())
    }

    fn create_file(&self, path: &str) -> Result<()> {
        self.create(path, false)
    }

    fn create_dir(&self, path: &str) -> Result<()> {
        self.create(path, true)
    }

    fn rename(&self, from: &str, to: &str) -> Result<()> {
        let from = normalize_rel(from)?;
        let to = normalize_rel(to)?;
        let (from_parent, _) = split_parent(&from);
        let (to_parent, new_name) = split_parent(&to);
        if from_parent != to_parent {
            return Err(WorkspaceError::Unsupported(
                "moving between folders isn't supported for folders opened with the system picker".into(),
            ));
        }
        if self.exists(&to) {
            return Err(WorkspaceError::AlreadyExists(PathBuf::from(&to)));
        }
        let node = self.lookup(&from)?;
        self.tree.rename(&node.id, new_name).map_err(provider(&from))?;
        self.forget(from_parent);
        self.forget(&from);
        Ok(())
    }

    fn delete(&self, path: &str) -> Result<()> {
        let rel = normalize_rel(path)?;
        if rel.is_empty() {
            return Err(WorkspaceError::OutsideWorkspace(PathBuf::from(path)));
        }
        let node = self.lookup(&rel)?;
        self.tree.delete(&node.id).map_err(provider(&rel))?;
        self.forget(split_parent(&rel).0);
        self.forget(&rel);
        Ok(())
    }

    fn invalidate(&self, dir: &str) {
        if let Ok(rel) = normalize_rel(dir) {
            self.forget(&rel);
        }
    }
}

impl SafVfs {
    fn create(&self, path: &str, is_dir: bool) -> Result<()> {
        let rel = normalize_rel(path)?;
        if rel.is_empty() || self.exists(&rel) {
            return Err(WorkspaceError::AlreadyExists(PathBuf::from(&rel)));
        }
        // Create missing parents one level at a time, like `create_dir_all`.
        let (parent, name) = split_parent(&rel);
        let parent_id = if parent.is_empty() || self.exists(parent) {
            self.lookup(parent)?.id
        } else {
            self.create(parent, true)?;
            self.lookup(parent)?.id
        };
        self.tree
            .create(&parent_id, name, is_dir)
            .map_err(provider(&rel))?;
        self.forget(parent);
        Ok(())
    }
}

// ----- Ferrux -------------------------------------------------------------------

/// Ferrux's home directory. Placeholder until Ferrux ships its file API (AIDL
/// + file descriptors, see `ferride-host`); every operation says so.
#[derive(Debug, Clone, Default)]
pub struct FerruxVfs;

impl FerruxVfs {
    fn unavailable<T>() -> Result<T> {
        Err(WorkspaceError::Unavailable(
            "Ferrux file access isn't available yet: install Ferrux and update both apps".into(),
        ))
    }
}

impl Vfs for FerruxVfs {
    fn kind(&self) -> VfsKind {
        VfsKind::Ferrux
    }

    fn name(&self) -> String {
        "Ferrux home".into()
    }

    fn list(&self, _dir: &str) -> Result<Vec<VfsEntry>> {
        Self::unavailable()
    }

    fn stat(&self, _path: &str) -> Result<VfsStat> {
        Self::unavailable()
    }

    fn read(&self, _path: &str) -> Result<Vec<u8>> {
        Self::unavailable()
    }

    fn write(&self, _path: &str, _bytes: &[u8]) -> Result<()> {
        Self::unavailable()
    }

    fn create_file(&self, _path: &str) -> Result<()> {
        Self::unavailable()
    }

    fn create_dir(&self, _path: &str) -> Result<()> {
        Self::unavailable()
    }

    fn rename(&self, _from: &str, _to: &str) -> Result<()> {
        Self::unavailable()
    }

    fn delete(&self, _path: &str) -> Result<()> {
        Self::unavailable()
    }
}

#[cfg(test)]
mod tests;
