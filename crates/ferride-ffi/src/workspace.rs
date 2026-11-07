use std::sync::Arc;

use ferride_workspace::{SearchOptions, Workspace};

use crate::FerrideError;

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
}

/// An opened folder. All methods block; call them off the UI thread.
#[derive(uniffi::Object)]
pub struct WorkspaceHandle {
    ws: Workspace,
}

fn path_string(p: &std::path::Path) -> String {
    p.to_string_lossy().into_owned()
}

#[uniffi::export]
impl WorkspaceHandle {
    #[uniffi::constructor]
    pub fn open(root: String) -> Result<Arc<Self>, FerrideError> {
        Ok(Arc::new(Self {
            ws: Workspace::open(root)?,
        }))
    }

    pub fn root(&self) -> String {
        path_string(self.ws.root())
    }

    pub fn name(&self) -> String {
        self.ws.name()
    }

    pub fn list_dir(&self, path: String) -> Result<Vec<FileNode>, FerrideError> {
        Ok(self
            .ws
            .list_dir(path)?
            .into_iter()
            .map(|e| FileNode {
                name: e.name,
                path: path_string(&e.path),
                rel_path: e.rel_path,
                is_dir: e.is_dir,
                is_hidden: e.is_hidden,
            })
            .collect())
    }

    pub fn read_file(&self, path: String) -> Result<String, FerrideError> {
        Ok(self.ws.read_file(path)?)
    }

    pub fn write_file(&self, path: String, contents: String) -> Result<(), FerrideError> {
        Ok(self.ws.write_file(path, &contents)?)
    }

    pub fn create_file(&self, path: String) -> Result<String, FerrideError> {
        Ok(path_string(&self.ws.create_file(path)?))
    }

    pub fn create_dir(&self, path: String) -> Result<String, FerrideError> {
        Ok(path_string(&self.ws.create_dir(path)?))
    }

    pub fn rename(&self, from: String, to: String) -> Result<String, FerrideError> {
        Ok(path_string(&self.ws.rename(from, to)?))
    }

    pub fn delete(&self, path: String) -> Result<(), FerrideError> {
        Ok(self.ws.delete(path)?)
    }

    pub fn find_files(&self, query: String, limit: i32) -> Vec<FileHit> {
        self.ws
            .find_files(&query, limit.max(0) as usize)
            .into_iter()
            .map(|m| FileHit {
                path: path_string(&m.path),
                rel_path: m.rel_path,
                score: m.score,
                positions: m.positions.into_iter().map(|p| p as i32).collect(),
            })
            .collect()
    }

    pub fn search_text(
        &self,
        query: String,
        regex: bool,
        case_sensitive: bool,
        whole_word: bool,
        max_results: i32,
    ) -> Result<SearchReport, FerrideError> {
        let r = self.ws.search_text(
            &query,
            &SearchOptions {
                regex,
                case_sensitive,
                whole_word,
                max_results: max_results.max(0) as usize,
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
        })
    }
}
