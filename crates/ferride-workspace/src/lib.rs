//! Workspace model: an opened folder, its file tree, file I/O and search.
//!
//! Everything here is blocking and meant to run on a background thread
//! (Kotlin `Dispatchers.IO`). Paths crossing the API are absolute; every
//! operation checks that the path stays inside the workspace root.

mod fuzzy;
mod search;

use std::fs;
use std::io::Write;
use std::path::{Component, Path, PathBuf};

pub use fuzzy::{FileMatch, fuzzy_score};
pub use search::{SearchOptions, SearchResults, TextMatch};

/// Files larger than this are not opened in the editor or searched.
pub const MAX_FILE_SIZE: u64 = 8 * 1024 * 1024;

#[derive(Debug, thiserror::Error)]
pub enum WorkspaceError {
    #[error("{0} is outside the workspace")]
    OutsideWorkspace(PathBuf),
    #[error("{0} is not a directory")]
    NotADirectory(PathBuf),
    #[error("{0} is too large to open ({1} bytes)")]
    TooLarge(PathBuf, u64),
    #[error("{0} looks like a binary file")]
    Binary(PathBuf),
    #[error("{0} is not valid UTF-8 (other encodings aren't supported yet)")]
    NotUtf8(PathBuf),
    #[error("{0} already exists")]
    AlreadyExists(PathBuf),
    #[error("invalid search pattern: {0}")]
    BadPattern(String),
    #[error("{path}: {source}")]
    Io {
        path: PathBuf,
        #[source]
        source: std::io::Error,
    },
}

fn io_err(path: &Path) -> impl FnOnce(std::io::Error) -> WorkspaceError + '_ {
    move |source| WorkspaceError::Io {
        path: path.to_owned(),
        source,
    }
}

pub type Result<T> = std::result::Result<T, WorkspaceError>;

/// One entry of a directory listing.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileEntry {
    pub name: String,
    pub path: PathBuf,
    /// Path relative to the workspace root, `/`-separated.
    pub rel_path: String,
    pub is_dir: bool,
    /// Dotfile (the UI may dim these).
    pub is_hidden: bool,
}

#[derive(Debug, Clone)]
pub struct Workspace {
    root: PathBuf,
}

impl Workspace {
    pub fn open(root: impl AsRef<Path>) -> Result<Self> {
        let root = root.as_ref();
        let root = root.canonicalize().map_err(io_err(root))?;
        if !root.is_dir() {
            return Err(WorkspaceError::NotADirectory(root));
        }
        Ok(Self { root })
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    pub fn name(&self) -> String {
        self.root
            .file_name()
            .map_or_else(|| "/".to_owned(), |n| n.to_string_lossy().into_owned())
    }

    /// Resolves `path` (absolute, or relative to the root) and checks that it
    /// stays inside the workspace **after following symlinks**: the deepest
    /// existing ancestor is canonicalized, so a link like `x -> /` in a cloned
    /// repository can't be used to read or write outside the root. Works for
    /// paths that don't exist yet. Returns the real (link-resolved) path.
    pub fn resolve(&self, path: impl AsRef<Path>) -> Result<PathBuf> {
        let path = path.as_ref();
        let lexical = self.lexical(path)?;
        let real = canonicalize_existing_prefix(&lexical).map_err(io_err(&lexical))?;
        if real.starts_with(&self.root) {
            Ok(real)
        } else {
            Err(WorkspaceError::OutsideWorkspace(path.to_owned()))
        }
    }

    /// Like [`resolve`](Self::resolve) but doesn't follow a symlink in the last
    /// component, so delete/rename act on the link itself.
    fn resolve_entry(&self, path: &Path) -> Result<PathBuf> {
        let lexical = self.lexical(path)?;
        let (Some(parent), Some(name)) = (lexical.parent(), lexical.file_name()) else {
            return Err(WorkspaceError::OutsideWorkspace(path.to_owned()));
        };
        let real_parent = self.resolve(parent)?;
        Ok(real_parent.join(name))
    }

    fn lexical(&self, path: &Path) -> Result<PathBuf> {
        let joined = if path.is_absolute() {
            path.to_owned()
        } else {
            self.root.join(path)
        };
        let mut out = PathBuf::new();
        for c in joined.components() {
            match c {
                Component::ParentDir => {
                    out.pop();
                }
                Component::CurDir => {}
                other => out.push(other),
            }
        }
        if out.starts_with(&self.root) {
            Ok(out)
        } else {
            Err(WorkspaceError::OutsideWorkspace(path.to_owned()))
        }
    }

    pub fn rel_path(&self, path: &Path) -> String {
        path.strip_prefix(&self.root)
            .unwrap_or(path)
            .components()
            .map(|c| c.as_os_str().to_string_lossy())
            .collect::<Vec<_>>()
            .join("/")
    }

    /// Lists one directory: folders first, then files, each sorted
    /// case-insensitively. `.git` is never shown.
    pub fn list_dir(&self, dir: impl AsRef<Path>) -> Result<Vec<FileEntry>> {
        let dir = self.resolve(dir)?;
        let mut entries = Vec::new();
        for entry in fs::read_dir(&dir).map_err(io_err(&dir))? {
            let entry = entry.map_err(io_err(&dir))?;
            let name = entry.file_name().to_string_lossy().into_owned();
            if name == ".git" {
                continue;
            }
            let path = entry.path();
            // Follow symlinks for the dir flag; broken links count as files.
            let is_dir = fs::metadata(&path).map(|m| m.is_dir()).unwrap_or(false);
            entries.push(FileEntry {
                rel_path: self.rel_path(&path),
                is_hidden: name.starts_with('.'),
                name,
                path,
                is_dir,
            });
        }
        entries.sort_by(|a, b| {
            b.is_dir
                .cmp(&a.is_dir)
                .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
                .then_with(|| a.name.cmp(&b.name))
        });
        Ok(entries)
    }

    /// Reads a text file for editing. Rejects huge and binary files; invalid
    /// UTF-8 is replaced (lossy) for now.
    pub fn read_file(&self, path: impl AsRef<Path>) -> Result<String> {
        let path = self.resolve(path)?;
        let meta = fs::metadata(&path).map_err(io_err(&path))?;
        if meta.len() > MAX_FILE_SIZE {
            return Err(WorkspaceError::TooLarge(path, meta.len()));
        }
        let bytes = fs::read(&path).map_err(io_err(&path))?;
        if bytes[..bytes.len().min(8192)].contains(&0) {
            return Err(WorkspaceError::Binary(path));
        }
        // Refuse rather than lossy-decode: saving a lossy copy would corrupt
        // Latin-1/Shift-JIS files. (Encoding detection is a later feature.)
        String::from_utf8(bytes).map_err(|_| WorkspaceError::NotUtf8(path))
    }

    /// Writes atomically: a temp file in the same directory, then rename.
    pub fn write_file(&self, path: impl AsRef<Path>, contents: &str) -> Result<()> {
        let path = self.resolve(path)?;
        let dir = path.parent().unwrap_or(&self.root).to_owned();
        let file_name = path
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default();
        let tmp = dir.join(format!(".{file_name}.ferride-tmp"));
        {
            let mut f = fs::File::create(&tmp).map_err(io_err(&tmp))?;
            f.write_all(contents.as_bytes()).map_err(io_err(&tmp))?;
            f.sync_all().map_err(io_err(&tmp))?;
        }
        if let Ok(meta) = fs::metadata(&path) {
            let _ = fs::set_permissions(&tmp, meta.permissions());
        }
        fs::rename(&tmp, &path).map_err(io_err(&path))
    }

    pub fn create_file(&self, path: impl AsRef<Path>) -> Result<PathBuf> {
        let path = self.resolve(path)?;
        if path.exists() {
            return Err(WorkspaceError::AlreadyExists(path));
        }
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).map_err(io_err(parent))?;
        }
        fs::File::create(&path).map_err(io_err(&path))?;
        Ok(path)
    }

    pub fn create_dir(&self, path: impl AsRef<Path>) -> Result<PathBuf> {
        let path = self.resolve(path)?;
        if path.exists() {
            return Err(WorkspaceError::AlreadyExists(path));
        }
        fs::create_dir_all(&path).map_err(io_err(&path))?;
        Ok(path)
    }

    pub fn rename(&self, from: impl AsRef<Path>, to: impl AsRef<Path>) -> Result<PathBuf> {
        let from = self.resolve_entry(from.as_ref())?;
        let to = self.resolve_entry(to.as_ref())?;
        if to.exists() {
            return Err(WorkspaceError::AlreadyExists(to));
        }
        fs::rename(&from, &to).map_err(io_err(&from))?;
        Ok(to)
    }

    pub fn delete(&self, path: impl AsRef<Path>) -> Result<()> {
        let path = self.resolve_entry(path.as_ref())?;
        if path == self.root {
            return Err(WorkspaceError::OutsideWorkspace(path));
        }
        let meta = fs::symlink_metadata(&path).map_err(io_err(&path))?;
        if meta.is_dir() {
            fs::remove_dir_all(&path).map_err(io_err(&path))
        } else {
            fs::remove_file(&path).map_err(io_err(&path))
        }
    }

    /// A gitignore-aware walker over the workspace (hidden files included,
    /// `.git` excluded).
    pub(crate) fn walker(&self) -> ignore::Walk {
        ignore::WalkBuilder::new(&self.root)
            .hidden(false)
            .require_git(false)
            .filter_entry(|e| e.file_name() != ".git")
            .build()
    }

    /// Quick-open: fuzzy-matches `query` against relative paths, best first.
    pub fn find_files(&self, query: &str, limit: usize) -> Vec<FileMatch> {
        let mut matches: Vec<FileMatch> = self
            .walker()
            .filter_map(|e| e.ok())
            .filter(|e| e.file_type().is_some_and(|t| t.is_file()))
            .filter_map(|e| {
                let rel = self.rel_path(e.path());
                let (score, positions) = fuzzy_score(query, &rel)?;
                Some(FileMatch {
                    path: e.into_path(),
                    rel_path: rel,
                    score,
                    positions,
                })
            })
            .collect();
        matches.sort_by(|a, b| b.score.cmp(&a.score).then_with(|| a.rel_path.cmp(&b.rel_path)));
        matches.truncate(limit);
        matches
    }

    /// Full-text search across the workspace (ripgrep's engine).
    pub fn search_text(&self, query: &str, options: &SearchOptions) -> Result<SearchResults> {
        search::search(self, query, options)
    }
}

/// Canonicalizes the longest existing prefix of `path` and appends the rest.
fn canonicalize_existing_prefix(path: &Path) -> std::io::Result<PathBuf> {
    let mut existing = path.to_path_buf();
    let mut tail = Vec::new();
    loop {
        match existing.canonicalize() {
            Ok(real) => {
                let mut out = real;
                for part in tail.iter().rev() {
                    out.push(part);
                }
                return Ok(out);
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                let Some(name) = existing.file_name().map(|n| n.to_owned()) else {
                    return Err(e);
                };
                tail.push(name);
                if !existing.pop() {
                    return Err(e);
                }
            }
            Err(e) => return Err(e),
        }
    }
}

#[cfg(test)]
mod tests;
