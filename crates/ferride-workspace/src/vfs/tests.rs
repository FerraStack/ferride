use std::collections::BTreeMap;
use std::sync::atomic::{AtomicUsize, Ordering};

use super::*;

/// An in-memory SAF provider: documents keyed by id, with call counters so
/// tests can check that listings are cached (each query is a Binder call on
/// a real device).
/// id -> (name, parent id, is_dir, bytes)
type Docs = BTreeMap<String, (String, Option<String>, bool, Vec<u8>)>;

#[derive(Default)]
struct FakeTree {
    docs: Mutex<Docs>,
    next: AtomicUsize,
    children_calls: AtomicUsize,
}

impl FakeTree {
    fn with(files: &[&str]) -> Arc<Self> {
        let t = Arc::new(Self::default());
        t.docs
            .lock()
            .unwrap()
            .insert("root".into(), ("Shared".into(), None, true, vec![]));
        for f in files {
            let mut parent = "root".to_owned();
            let parts: Vec<&str> = f.split('/').collect();
            for (i, part) in parts.iter().enumerate() {
                let is_dir = i + 1 < parts.len();
                let existing = t.child(&parent, part);
                parent = match existing {
                    Some(id) => id,
                    None => t.create(&parent, part, is_dir).unwrap(),
                };
                if !is_dir {
                    t.write(&parent, format!("content of {f}").as_bytes()).unwrap();
                }
            }
        }
        t.children_calls.store(0, Ordering::SeqCst);
        t
    }

    fn child(&self, parent: &str, name: &str) -> Option<String> {
        self.docs
            .lock()
            .unwrap()
            .iter()
            .find(|(_, (n, p, _, _))| n == name && p.as_deref() == Some(parent))
            .map(|(id, _)| id.clone())
    }
}

impl DocumentTree for FakeTree {
    fn root_id(&self) -> String {
        "root".into()
    }

    fn display_name(&self) -> String {
        "Shared".into()
    }

    fn children(&self, dir_id: &str) -> std::result::Result<Vec<DocNode>, String> {
        self.children_calls.fetch_add(1, Ordering::SeqCst);
        Ok(self
            .docs
            .lock()
            .unwrap()
            .iter()
            .filter(|(_, (_, p, _, _))| p.as_deref() == Some(dir_id))
            .map(|(id, (name, _, is_dir, bytes))| DocNode {
                id: id.clone(),
                name: name.clone(),
                is_dir: *is_dir,
                size: bytes.len() as u64,
                modified_ms: 0,
            })
            .collect())
    }

    fn read(&self, id: &str) -> std::result::Result<Vec<u8>, String> {
        self.docs
            .lock()
            .unwrap()
            .get(id)
            .map(|d| d.3.clone())
            .ok_or_else(|| "gone".into())
    }

    fn write(&self, id: &str, bytes: &[u8]) -> std::result::Result<(), String> {
        let mut docs = self.docs.lock().unwrap();
        let d = docs.get_mut(id).ok_or("gone")?;
        d.3 = bytes.to_vec();
        Ok(())
    }

    fn create(&self, parent_id: &str, name: &str, is_dir: bool) -> std::result::Result<String, String> {
        let id = format!("doc{}", self.next.fetch_add(1, Ordering::SeqCst));
        self.docs
            .lock()
            .unwrap()
            .insert(id.clone(), (name.into(), Some(parent_id.into()), is_dir, vec![]));
        Ok(id)
    }

    fn rename(&self, id: &str, new_name: &str) -> std::result::Result<String, String> {
        let mut docs = self.docs.lock().unwrap();
        docs.get_mut(id).ok_or("gone")?.0 = new_name.into();
        Ok(id.into())
    }

    fn delete(&self, id: &str) -> std::result::Result<(), String> {
        let mut docs = self.docs.lock().unwrap();
        // Remove the subtree.
        let mut doomed = vec![id.to_owned()];
        let mut i = 0;
        while i < doomed.len() {
            let parent = doomed[i].clone();
            doomed.extend(
                docs.iter()
                    .filter(|(_, d)| d.1.as_deref() == Some(&parent))
                    .map(|(k, _)| k.clone()),
            );
            i += 1;
        }
        for d in doomed {
            docs.remove(&d);
        }
        Ok(())
    }
}

fn names(entries: Vec<VfsEntry>) -> Vec<(String, bool)> {
    entries.into_iter().map(|e| (e.rel_path, e.is_dir)).collect()
}

#[test]
fn normalize_rejects_escapes() {
    assert_eq!(normalize_rel("/src//./main.rs").unwrap(), "src/main.rs");
    assert_eq!(normalize_rel("a\\b").unwrap(), "a/b");
    assert_eq!(normalize_rel("").unwrap(), "");
    assert!(normalize_rel("../etc/passwd").is_err());
    assert!(normalize_rel("src/../../x").is_err());
}

#[test]
fn saf_lists_sorted_and_hides_git() {
    let tree = FakeTree::with(&["b.txt", "A.md", "src/main.rs", ".git/HEAD", "docs/x.md"]);
    let vfs = SafVfs::new(tree);
    assert_eq!(
        names(vfs.list("").unwrap()),
        vec![
            ("docs".into(), true),
            ("src".into(), true),
            ("A.md".into(), false),
            ("b.txt".into(), false)
        ]
    );
    assert_eq!(
        names(vfs.list("src").unwrap()),
        vec![("src/main.rs".into(), false)]
    );
    assert_eq!(read_text(&vfs, "src/main.rs").unwrap(), "content of src/main.rs");
}

#[test]
fn saf_listings_are_cached() {
    let tree = FakeTree::with(&["src/a/b/c.rs"]);
    let vfs = SafVfs::new(tree.clone());
    vfs.read("src/a/b/c.rs").unwrap();
    let first = tree.children_calls.load(Ordering::SeqCst);
    assert_eq!(first, 4, "one query per directory on the way down");
    vfs.read("src/a/b/c.rs").unwrap();
    vfs.list("src/a").unwrap();
    vfs.stat("src/a/b").unwrap();
    assert_eq!(
        tree.children_calls.load(Ordering::SeqCst),
        first,
        "everything else comes from the cache"
    );
    vfs.invalidate("src");
    vfs.list("src/a").unwrap();
    assert!(tree.children_calls.load(Ordering::SeqCst) > first);
}

#[test]
fn saf_mutations_refresh_the_cache() {
    let tree = FakeTree::with(&["src/main.rs"]);
    let vfs = SafVfs::new(tree);
    vfs.list("src").unwrap();
    vfs.create_file("src/lib.rs").unwrap();
    assert_eq!(names(vfs.list("src").unwrap()).len(), 2);
    vfs.write("src/lib.rs", b"pub fn f() {}").unwrap();
    assert_eq!(vfs.stat("src/lib.rs").unwrap().size, 13);
    // Writing a missing file creates it.
    vfs.write("notes.txt", b"hi").unwrap();
    assert_eq!(vfs.read("notes.txt").unwrap(), b"hi");
    // Nested create makes the parents.
    vfs.create_dir("a/b/c").unwrap();
    assert!(vfs.stat("a/b/c").unwrap().is_dir);
    vfs.rename("src/lib.rs", "src/util.rs").unwrap();
    assert_eq!(
        names(vfs.list("src").unwrap()),
        vec![("src/main.rs".into(), false), ("src/util.rs".into(), false)]
    );
    assert!(matches!(
        vfs.rename("src/util.rs", "a/util.rs"),
        Err(WorkspaceError::Unsupported(_))
    ));
    assert!(matches!(
        vfs.rename("src/util.rs", "src/main.rs"),
        Err(WorkspaceError::AlreadyExists(_))
    ));
    vfs.delete("src").unwrap();
    assert!(vfs.stat("src/main.rs").is_err());
    assert!(vfs.delete("").is_err(), "the root can't be deleted");
    assert!(matches!(
        vfs.create_file("notes.txt"),
        Err(WorkspaceError::AlreadyExists(_))
    ));
}

#[test]
fn saf_text_rules_match_local() {
    let tree = FakeTree::with(&["bin.dat"]);
    let vfs = SafVfs::new(tree);
    vfs.write("bin.dat", b"a\0b").unwrap();
    assert!(matches!(
        read_text(&vfs, "bin.dat"),
        Err(WorkspaceError::Binary(_))
    ));
    vfs.write("latin1.txt", &[0x63, 0x61, 0x66, 0xE9]).unwrap();
    assert!(matches!(
        read_text(&vfs, "latin1.txt"),
        Err(WorkspaceError::NotUtf8(_))
    ));
    assert!(vfs.read("../outside").is_err());
}

#[test]
fn walk_files_skips_heavy_dirs() {
    let tree = FakeTree::with(&[
        "src/main.rs",
        "node_modules/x/index.js",
        "target/debug/out",
        "README.md",
    ]);
    let vfs = SafVfs::new(tree);
    let mut files = walk_files(&vfs, 100);
    files.sort();
    assert_eq!(files, vec!["README.md", "src/main.rs"]);
    assert_eq!(walk_files(&vfs, 1).len(), 1);
}

#[test]
fn copy_saf_folder_into_local_projects() {
    let tree = FakeTree::with(&["src/main.rs", "src/util/strings.rs", "README.md", ".git/HEAD"]);
    let saf = SafVfs::new(tree);
    let dir = tempfile::tempdir().unwrap();
    let local = LocalVfs::open(dir.path()).unwrap();
    let mut seen = Vec::new();
    let n = copy_tree(&saf, "", &local, "shared", &mut |count, path| {
        seen.push((count, path.to_owned()))
    })
    .unwrap();
    assert_eq!(n, 3, ".git is not listed, so not copied");
    assert_eq!(seen.last().unwrap().0, 3);
    assert_eq!(
        std::fs::read_to_string(dir.path().join("shared/src/util/strings.rs")).unwrap(),
        "content of src/util/strings.rs"
    );
    assert!(local.local_root().is_some());
    assert!(saf.local_root().is_none());
}

#[test]
fn local_vfs_round_trip_and_confinement() {
    let dir = tempfile::tempdir().unwrap();
    let vfs = LocalVfs::open(dir.path()).unwrap();
    vfs.create_dir("src").unwrap();
    vfs.write("src/main.rs", b"fn main() {}").unwrap();
    assert_eq!(read_text(&vfs, "src/main.rs").unwrap(), "fn main() {}");
    assert_eq!(vfs.stat("src/main.rs").unwrap().size, 12);
    assert!(vfs.stat("src/main.rs").unwrap().modified_ms > 0);
    vfs.rename("src/main.rs", "main.rs").unwrap();
    assert_eq!(
        names(vfs.list("").unwrap()),
        vec![("src".into(), true), ("main.rs".into(), false)]
    );
    assert!(vfs.read("../x").is_err());
    assert!(vfs.delete("").is_err());
    vfs.delete("src").unwrap();
    assert_eq!(vfs.list("").unwrap().len(), 1);
}

#[test]
fn ferrux_is_a_clear_stub() {
    let vfs = FerruxVfs;
    assert_eq!(vfs.kind(), VfsKind::Ferrux);
    let err = vfs.list("").unwrap_err().to_string();
    assert!(err.contains("Ferrux"), "{err}");
}

#[test]
fn non_utf8_files_open_and_save_in_their_encoding() {
    let dir = tempfile::tempdir().unwrap();
    let vfs = LocalVfs::open(dir.path()).unwrap();
    let latin1 = b"// caf\xe9 cr\xe8me, d\xe9j\xe0 vu, \xe0 bient\xf4t\nfn main() {}\n";
    vfs.write("old.rs", latin1).unwrap();
    // read_text still refuses (it's the strict UTF-8 path)…
    assert!(matches!(
        read_text(&vfs, "old.rs"),
        Err(WorkspaceError::NotUtf8(_))
    ));
    // …read_decoded detects Windows 1252.
    let d = read_decoded(&vfs, "old.rs", None).unwrap();
    assert_eq!(d.encoding.id, "windows1252");
    assert!(d.text.starts_with("// café crème"));
    // Saving keeps the encoding byte for byte; characters it can't hold are refused.
    write_encoded(&vfs, "old.rs", &d.text, "windows1252").unwrap();
    assert_eq!(vfs.read("old.rs").unwrap(), latin1.to_vec());
    assert!(matches!(
        write_encoded(&vfs, "old.rs", "snow ☃", "windows1252"),
        Err(WorkspaceError::Unencodable(..))
    ));
    // Reopen with another encoding.
    let as_utf8 = read_decoded(&vfs, "old.rs", Some("utf8")).unwrap();
    assert!(as_utf8.lossy);
    assert!(matches!(
        read_decoded(&vfs, "old.rs", Some("nope")),
        Err(WorkspaceError::UnknownEncoding(_))
    ));
    // UTF-16 files contain NULs but aren't binary.
    write_encoded(&vfs, "wide.txt", "hello", "utf16le").unwrap();
    assert_eq!(read_decoded(&vfs, "wide.txt", None).unwrap().text, "hello");
    // Real binaries are still refused.
    vfs.write("blob.bin", b"\x00\x01\x02").unwrap();
    assert!(matches!(
        read_decoded(&vfs, "blob.bin", None),
        Err(WorkspaceError::Binary(_))
    ));
}
