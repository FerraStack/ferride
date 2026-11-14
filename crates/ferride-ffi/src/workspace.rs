use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use ferride_workspace::{
    DocNode, DocumentTree, ENCODINGS, FerruxVfs, LocalVfs, SafVfs, SearchOptions, Vfs, VfsKind,
    WorkspaceError, copy_tree, fuzzy_score, normalize_rel, read_decoded, read_text, walk_files,
    write_encoded,
};

use crate::FerrideError;

/// A file opened as text, with the encoding it was read in.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct OpenedText {
    pub text: String,
    /// VS Code's `files.encoding` id (`utf8`, `windows1252`, `shiftjis`, …).
    pub encoding: String,
    /// What the status bar shows (`UTF-8`, `Western (Windows 1252)`).
    pub encoding_label: String,
    /// Some bytes didn't decode (U+FFFD): probably the wrong encoding.
    pub lossy: bool,
}

/// One entry of the "Reopen/Save with Encoding" picker.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct EncodingInfo {
    pub id: String,
    pub label: String,
}

/// The encodings Ferride can open and save, in picker order.
#[uniffi::export]
pub fn supported_encodings() -> Vec<EncodingInfo> {
    ENCODINGS
        .iter()
        .map(|e| EncodingInfo {
            id: e.id.to_owned(),
            label: e.label.to_owned(),
        })
        .collect()
}

/// Where a workspace's files live (see `ferride_workspace::vfs`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum WorkspaceKind {
    /// A real directory: app-private projects, or any folder with all-files access (foss build).
    Local,
    /// A folder picked with the system file picker (Storage Access Framework).
    Saf,
    /// Ferrux's home directory (not available yet).
    Ferrux,
}

impl From<VfsKind> for WorkspaceKind {
    fn from(k: VfsKind) -> Self {
        match k {
            VfsKind::Local => Self::Local,
            VfsKind::Saf => Self::Saf,
            VfsKind::Ferrux => Self::Ferrux,
        }
    }
}

/// A document of a SAF tree (`DocumentsContract` columns).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SafDoc {
    pub id: String,
    pub name: String,
    pub is_dir: bool,
    pub size: i64,
    pub modified_ms: i64,
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum SafError {
    #[error("{msg}")]
    Failed { msg: String },
}

impl From<uniffi::UnexpectedUniFFICallbackError> for SafError {
    fn from(e: uniffi::UnexpectedUniFFICallbackError) -> Self {
        SafError::Failed { msg: e.reason }
    }
}

/// A SAF tree, implemented in Kotlin with `DocumentsContract` on a persisted
/// tree URI. Each method is one provider round trip; Rust caches listings.
#[uniffi::export(callback_interface)]
pub trait SafTree: Send + Sync {
    fn root_id(&self) -> String;
    fn display_name(&self) -> String;
    /// All children of a directory document in one query.
    fn children(&self, dir_id: String) -> Result<Vec<SafDoc>, SafError>;
    fn read(&self, id: String) -> Result<Vec<u8>, SafError>;
    /// Truncate and write.
    fn write(&self, id: String, bytes: Vec<u8>) -> Result<(), SafError>;
    /// Returns the new document id.
    fn create(&self, parent_id: String, name: String, is_dir: bool) -> Result<String, SafError>;
    /// Returns the (possibly new) document id.
    fn rename(&self, id: String, new_name: String) -> Result<String, SafError>;
    fn delete(&self, id: String) -> Result<(), SafError>;
}

/// Adapts the Kotlin callback to the core's [`DocumentTree`].
struct SafAdapter(Box<dyn SafTree>);

fn msg(e: SafError) -> String {
    e.to_string()
}

impl DocumentTree for SafAdapter {
    fn root_id(&self) -> String {
        self.0.root_id()
    }

    fn display_name(&self) -> String {
        self.0.display_name()
    }

    fn children(&self, dir_id: &str) -> Result<Vec<DocNode>, String> {
        Ok(self
            .0
            .children(dir_id.to_owned())
            .map_err(msg)?
            .into_iter()
            .map(|d| DocNode {
                id: d.id,
                name: d.name,
                is_dir: d.is_dir,
                size: d.size.max(0) as u64,
                modified_ms: d.modified_ms,
            })
            .collect())
    }

    fn read(&self, id: &str) -> Result<Vec<u8>, String> {
        self.0.read(id.to_owned()).map_err(msg)
    }

    fn write(&self, id: &str, bytes: &[u8]) -> Result<(), String> {
        self.0.write(id.to_owned(), bytes.to_vec()).map_err(msg)
    }

    fn create(&self, parent_id: &str, name: &str, is_dir: bool) -> Result<String, String> {
        self.0
            .create(parent_id.to_owned(), name.to_owned(), is_dir)
            .map_err(msg)
    }

    fn rename(&self, id: &str, new_name: &str) -> Result<String, String> {
        self.0.rename(id.to_owned(), new_name.to_owned()).map_err(msg)
    }

    fn delete(&self, id: &str) -> Result<(), String> {
        self.0.delete(id.to_owned()).map_err(msg)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FileNode {
    pub name: String,
    pub path: String,
    pub rel_path: String,
    pub is_dir: bool,
    pub is_hidden: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FileHit {
    pub path: String,
    pub rel_path: String,
    pub score: i64,
    /// Char indices in `rel_path` that matched.
    pub positions: Vec<i32>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MatchRange {
    pub start: i32,
    pub end: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SearchHit {
    pub path: String,
    pub rel_path: String,
    /// 0-based.
    pub line: i32,
    pub line_text: String,
    /// UTF-16 columns within `line_text`.
    pub ranges: Vec<MatchRange>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SearchReport {
    pub hits: Vec<SearchHit>,
    pub files_searched: i32,
    pub truncated: bool,
    /// Stopped by [`SearchCancellation::cancel`]; `hits` is partial.
    pub cancelled: bool,
}

/// Cancels a running [`WorkspaceHandle::search_text`] from another thread
/// (a newer query started). The search checks it between files and lines.
#[derive(Debug, Default, uniffi::Object)]
pub struct SearchCancellation {
    flag: Arc<AtomicBool>,
}

#[uniffi::export]
impl SearchCancellation {
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

/// An opened folder on any backend. Paths may be absolute (starting with
/// [`root`](WorkspaceHandle::root)) or workspace-relative. All methods block;
/// call them off the UI thread.
#[derive(uniffi::Object)]
pub struct WorkspaceHandle {
    vfs: Arc<dyn Vfs>,
    /// Set for local folders: ripgrep search and the gitignore-aware walker.
    local: Option<LocalVfs>,
    root: String,
    /// Workspace symbols (`#`), local folders only.
    symbols: std::sync::Mutex<ferride_workspace::symbols::ThrottledSymbolIndex>,
}

/// A workspace symbol (`#` in Quick Open): name, LSP kind and where it is.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SymbolHit {
    pub name: String,
    /// LSP `SymbolKind` value.
    pub kind: i32,
    pub container: Option<String>,
    pub path: String,
    pub rel_path: String,
    /// 0-based line and UTF-16 column of the name.
    pub line: i32,
    pub column: i32,
    /// Char indices of `name` that matched.
    pub positions: Vec<i32>,
}

const NO_TEXT_SEARCH: &str = "Text search needs a folder in device storage. Copy this folder into Ferride projects to enable Git and fast search.";

impl WorkspaceHandle {
    fn rel(&self, path: &str) -> Result<String, FerrideError> {
        let stripped = path.strip_prefix(&self.root).unwrap_or(path);
        Ok(normalize_rel(stripped)?)
    }

    fn abs(&self, rel: &str) -> String {
        if rel.is_empty() {
            self.root.clone()
        } else {
            format!("{}/{rel}", self.root.trim_end_matches('/'))
        }
    }

    fn node(&self, name: String, rel: String, is_dir: bool) -> FileNode {
        FileNode {
            is_hidden: name.starts_with('.'),
            name,
            path: self.abs(&rel),
            rel_path: rel,
            is_dir,
        }
    }
}

fn path_string(p: &std::path::Path) -> String {
    p.to_string_lossy().into_owned()
}

#[uniffi::export]
impl WorkspaceHandle {
    /// A real directory (app-private projects; any folder in the foss build).
    #[uniffi::constructor]
    pub fn open(root: String) -> Result<Arc<Self>, FerrideError> {
        let local = LocalVfs::open(root)?;
        Ok(Arc::new(Self {
            root: path_string(local.workspace().root()),
            vfs: Arc::new(local.clone()),
            local: Some(local),
            symbols: Default::default(),
        }))
    }

    /// A folder picked with the system picker. `root` is the tree URI; it
    /// prefixes every path this handle returns.
    #[uniffi::constructor]
    pub fn open_saf(root: String, tree: Box<dyn SafTree>) -> Arc<Self> {
        Arc::new(Self {
            vfs: Arc::new(SafVfs::new(Arc::new(SafAdapter(tree)))),
            local: None,
            symbols: Default::default(),
            root: root.trim_end_matches('/').to_owned(),
        })
    }

    /// Ferrux's home directory (every call fails until Ferrux has a file API).
    #[uniffi::constructor]
    pub fn open_ferrux() -> Arc<Self> {
        Arc::new(Self {
            vfs: Arc::new(FerruxVfs),
            local: None,
            symbols: Default::default(),
            root: "ferrux:~".to_owned(),
        })
    }

    pub fn kind(&self) -> WorkspaceKind {
        self.vfs.kind().into()
    }

    pub fn root(&self) -> String {
        self.root.clone()
    }

    pub fn name(&self) -> String {
        self.vfs.name()
    }

    /// The real directory behind this workspace, if any (search, Git).
    pub fn local_root(&self) -> Option<String> {
        self.vfs.local_root().map(path_string)
    }

    /// ripgrep search and Git need a real directory.
    pub fn supports_text_search(&self) -> bool {
        self.local.is_some()
    }

    pub fn list_dir(&self, path: String) -> Result<Vec<FileNode>, FerrideError> {
        let rel = self.rel(&path)?;
        Ok(self
            .vfs
            .list(&rel)?
            .into_iter()
            .map(|e| self.node(e.name, e.rel_path, e.is_dir))
            .collect())
    }

    pub fn read_file(&self, path: String) -> Result<String, FerrideError> {
        Ok(read_text(self.vfs.as_ref(), &self.rel(&path)?)?)
    }

    /// Opens a file in any encoding: detected, or `encoding` (Reopen with Encoding).
    pub fn open_text(&self, path: String, encoding: Option<String>) -> Result<OpenedText, FerrideError> {
        let d = read_decoded(self.vfs.as_ref(), &self.rel(&path)?, encoding.as_deref())?;
        Ok(OpenedText {
            text: d.text,
            encoding: d.encoding.id.to_owned(),
            encoding_label: d.encoding.label.to_owned(),
            lossy: d.lossy,
        })
    }

    /// Saves in `encoding`; fails (nothing written) if a character can't be represented.
    pub fn write_text(&self, path: String, contents: String, encoding: String) -> Result<(), FerrideError> {
        Ok(write_encoded(
            self.vfs.as_ref(),
            &self.rel(&path)?,
            &contents,
            &encoding,
        )?)
    }

    pub fn write_file(&self, path: String, contents: String) -> Result<(), FerrideError> {
        Ok(self.vfs.write(&self.rel(&path)?, contents.as_bytes())?)
    }

    pub fn create_file(&self, path: String) -> Result<String, FerrideError> {
        let rel = self.rel(&path)?;
        self.vfs.create_file(&rel)?;
        Ok(self.abs(&rel))
    }

    pub fn create_dir(&self, path: String) -> Result<String, FerrideError> {
        let rel = self.rel(&path)?;
        self.vfs.create_dir(&rel)?;
        Ok(self.abs(&rel))
    }

    pub fn rename(&self, from: String, to: String) -> Result<String, FerrideError> {
        let to = self.rel(&to)?;
        self.vfs.rename(&self.rel(&from)?, &to)?;
        Ok(self.abs(&to))
    }

    pub fn delete(&self, path: String) -> Result<(), FerrideError> {
        Ok(self.vfs.delete(&self.rel(&path)?)?)
    }

    /// Drops cached listings under `path` (Explorer refresh on SAF folders).
    pub fn refresh(&self, path: String) {
        if let Ok(rel) = self.rel(&path) {
            self.vfs.invalidate(&rel);
        }
    }

    /// Copies the whole workspace into `dest` (a new local directory, e.g.
    /// `files/projects/<name>`) and returns its path. This is how a SAF folder
    /// gets Git and fast search.
    pub fn copy_into(&self, dest: String) -> Result<String, FerrideError> {
        let dest_path = std::path::PathBuf::from(&dest);
        if dest_path.exists() {
            return Err(WorkspaceError::AlreadyExists(dest_path).into());
        }
        std::fs::create_dir_all(&dest_path).map_err(|e| FerrideError::Io {
            msg: format!("{dest}: {e}"),
        })?;
        let target = LocalVfs::open(&dest_path)?;
        copy_tree(self.vfs.as_ref(), "", &target, "", &mut |_, _| {})?;
        Ok(path_string(target.workspace().root()))
    }

    /// Go to Symbol in Workspace (`#`): tree-sitter outline symbols of every
    /// file with a grammar, fuzzy-matched by name. The index refreshes at most
    /// every few seconds (`force` refreshes now, e.g. after a save). Folders
    /// outside device storage (SAF) aren't indexed.
    pub fn workspace_symbols(&self, query: String, limit: i32, force: bool) -> Vec<SymbolHit> {
        let Some(local) = &self.local else {
            return Vec::new();
        };
        let mut index = self.symbols.lock().unwrap_or_else(|e| e.into_inner());
        index
            .query(local.workspace(), &query, limit.max(0) as usize, force)
            .into_iter()
            .map(|h| SymbolHit {
                name: h.name,
                kind: h.kind as i32,
                container: h.container,
                path: path_string(&h.path),
                rel_path: h.rel_path,
                line: h.line as i32,
                column: h.column as i32,
                positions: h.positions.into_iter().map(|p| p as i32).collect(),
            })
            .collect()
    }

    /// Quick Open. Local folders use the gitignore-aware walker; other
    /// backends walk their (cached) listings, capped at 20k files.
    pub fn find_files(&self, query: String, limit: i32) -> Vec<FileHit> {
        let limit = limit.max(0) as usize;
        if let Some(local) = &self.local {
            return local
                .workspace()
                .find_files(&query, limit)
                .into_iter()
                .map(|m| FileHit {
                    path: path_string(&m.path),
                    rel_path: m.rel_path,
                    score: m.score,
                    positions: m.positions.into_iter().map(|p| p as i32).collect(),
                })
                .collect();
        }
        let mut hits: Vec<FileHit> = walk_files(self.vfs.as_ref(), 20_000)
            .into_iter()
            .filter_map(|rel| {
                let (score, positions) = fuzzy_score(&query, &rel)?;
                Some(FileHit {
                    path: self.abs(&rel),
                    rel_path: rel,
                    score,
                    positions: positions.into_iter().map(|p| p as i32).collect(),
                })
            })
            .collect();
        hits.sort_by(|a, b| b.score.cmp(&a.score).then_with(|| a.rel_path.cmp(&b.rel_path)));
        hits.truncate(limit);
        hits
    }

    pub fn search_text(
        &self,
        query: String,
        regex: bool,
        case_sensitive: bool,
        whole_word: bool,
        max_results: i32,
        cancel: Option<Arc<SearchCancellation>>,
    ) -> Result<SearchReport, FerrideError> {
        let Some(local) = &self.local else {
            return Err(WorkspaceError::Unsupported(NO_TEXT_SEARCH.into()).into());
        };
        let r = local.workspace().search_text(
            &query,
            &SearchOptions {
                regex,
                case_sensitive,
                whole_word,
                max_results: max_results.max(0) as usize,
                cancel: cancel.map(|c| c.flag.clone()),
            },
        )?;
        Ok(SearchReport {
            hits: r
                .matches
                .into_iter()
                .map(|m| SearchHit {
                    path: path_string(&m.path),
                    rel_path: m.rel_path,
                    line: m.line as i32,
                    line_text: m.line_text,
                    ranges: m
                        .ranges
                        .into_iter()
                        .map(|(s, e)| MatchRange {
                            start: s as i32,
                            end: e as i32,
                        })
                        .collect(),
                })
                .collect(),
            files_searched: r.files_searched as i32,
            truncated: r.truncated,
            cancelled: r.cancelled,
        })
    }
}
