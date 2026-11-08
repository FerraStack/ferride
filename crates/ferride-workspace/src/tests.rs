use std::fs;

use super::*;

fn fixture() -> (tempfile::TempDir, Workspace) {
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path();
    fs::create_dir_all(root.join("src/util")).unwrap();
    fs::create_dir_all(root.join(".git")).unwrap();
    fs::create_dir_all(root.join("target")).unwrap();
    fs::write(root.join(".gitignore"), "target/\n*.log\n").unwrap();
    fs::write(root.join("README.md"), "# Demo\nHello world\n").unwrap();
    fs::write(
        root.join("src/main.rs"),
        "fn main() {\n    println!(\"Hello, 😀 world\");\n}\n",
    )
    .unwrap();
    fs::write(
        root.join("src/util/strings.rs"),
        "pub fn hello() -> &'static str { \"hello\" }\n",
    )
    .unwrap();
    fs::write(root.join("target/out.rs"), "hello from build output\n").unwrap();
    fs::write(root.join("debug.log"), "hello log\n").unwrap();
    fs::write(root.join("blob.bin"), b"hello\0binary").unwrap();
    fs::write(root.join(".git/config"), "hello git\n").unwrap();
    let ws = Workspace::open(root).unwrap();
    (dir, ws)
}

#[test]
fn list_dir_sorts_and_hides_git() {
    let (_d, ws) = fixture();
    let names: Vec<_> = ws
        .list_dir(ws.root())
        .unwrap()
        .into_iter()
        .map(|e| (e.name, e.is_dir))
        .collect();
    assert_eq!(
        names,
        [
            ("src".to_owned(), true),
            ("target".to_owned(), true),
            (".gitignore".to_owned(), false),
            ("blob.bin".to_owned(), false),
            ("debug.log".to_owned(), false),
            ("README.md".to_owned(), false),
        ]
    );
    let src = ws.list_dir("src").unwrap();
    assert_eq!(src[0].rel_path, "src/util");
    assert!(src[0].is_dir);
    assert_eq!(src[1].rel_path, "src/main.rs");
}

#[test]
fn paths_cannot_escape_the_root() {
    let (_d, ws) = fixture();
    assert!(matches!(
        ws.resolve("../etc/passwd"),
        Err(WorkspaceError::OutsideWorkspace(_))
    ));
    assert!(matches!(
        ws.list_dir("/"),
        Err(WorkspaceError::OutsideWorkspace(_))
    ));
    assert!(ws.resolve("src/../README.md").unwrap().ends_with("README.md"));
    assert!(matches!(
        ws.delete(ws.root()),
        Err(WorkspaceError::OutsideWorkspace(_))
    ));
}

#[test]
fn read_write_create_rename_delete() {
    let (_d, ws) = fixture();
    assert!(ws.read_file("README.md").unwrap().starts_with("# Demo"));
    assert!(matches!(ws.read_file("blob.bin"), Err(WorkspaceError::Binary(_))));

    ws.write_file("README.md", "new ✓\n").unwrap();
    assert_eq!(ws.read_file("README.md").unwrap(), "new ✓\n");
    assert!(!ws.root().join(".README.md.ferride-tmp").exists());

    let p = ws.create_file("docs/notes.txt").unwrap();
    assert!(p.is_file());
    assert!(matches!(
        ws.create_file("docs/notes.txt"),
        Err(WorkspaceError::AlreadyExists(_))
    ));
    let q = ws.rename("docs/notes.txt", "docs/todo.txt").unwrap();
    assert!(q.is_file() && !p.exists());
    ws.create_dir("empty").unwrap();
    ws.delete("docs").unwrap();
    ws.delete("empty").unwrap();
    assert!(!ws.root().join("docs").exists());
}

#[test]
fn find_files_respects_gitignore_and_ranks() {
    let (_d, ws) = fixture();
    let all: Vec<_> = ws.find_files("", 100).into_iter().map(|m| m.rel_path).collect();
    assert!(all.contains(&"src/main.rs".to_owned()));
    assert!(
        !all.iter()
            .any(|p| p.starts_with("target/") || p.ends_with(".log") || p.starts_with(".git/"))
    );

    let hits = ws.find_files("main", 10);
    assert_eq!(hits[0].rel_path, "src/main.rs");
    assert_eq!(hits[0].positions, vec![4, 5, 6, 7]);

    let hits = ws.find_files("sustr", 10);
    assert_eq!(hits[0].rel_path, "src/util/strings.rs");
    assert!(ws.find_files("zzz", 10).is_empty());
}

#[test]
fn fuzzy_prefers_file_name_and_word_starts() {
    let (a, _) = fuzzy_score("rd", "README.md").unwrap();
    let (b, _) = fuzzy_score("rd", "src/random/data.txt").unwrap();
    assert!(a > b, "{a} vs {b}");
    let (exact, _) = fuzzy_score("main.rs", "src/main.rs").unwrap();
    let (partial, _) = fuzzy_score("main.rs", "src/domain_rules.rs").unwrap();
    assert!(exact > partial);
    assert!(fuzzy_score("abc", "acb").is_none());
}

#[test]
fn text_search_literal_regex_case_word() {
    let (_d, ws) = fixture();
    let r = ws.search_text("hello", &SearchOptions::default()).unwrap();
    let mut files: Vec<_> = r.matches.iter().map(|m| m.rel_path.as_str()).collect();
    files.sort();
    files.dedup();
    // Ignored, binary and .git files are skipped.
    assert_eq!(files, ["README.md", "src/main.rs", "src/util/strings.rs"]);
    assert!(!r.truncated);

    // UTF-16 columns: "world" comes after the emoji (2 units).
    let r = ws
        .search_text(
            "world",
            &SearchOptions {
                case_sensitive: true,
                ..Default::default()
            },
        )
        .unwrap();
    let m = r.matches.iter().find(|m| m.rel_path == "src/main.rs").unwrap();
    assert_eq!(m.line, 1);
    assert_eq!(m.ranges, vec![(24, 29)]);
    assert_eq!(
        &m.line_text.encode_utf16().collect::<Vec<_>>()[24..29],
        &"world".encode_utf16().collect::<Vec<_>>()[..]
    );

    // Case sensitivity.
    let r = ws
        .search_text(
            "Hello",
            &SearchOptions {
                case_sensitive: true,
                ..Default::default()
            },
        )
        .unwrap();
    assert_eq!(r.matches.len(), 2);

    // Regex vs literal.
    let re = SearchOptions {
        regex: true,
        ..Default::default()
    };
    assert_eq!(ws.search_text(r"fn \w+\(\)", &re).unwrap().matches.len(), 2);
    assert_eq!(
        ws.search_text(r"fn \w+\(\)", &SearchOptions::default())
            .unwrap()
            .matches
            .len(),
        0
    );
    assert!(matches!(
        ws.search_text("(", &re),
        Err(WorkspaceError::BadPattern(_))
    ));

    // Whole word: "hello" inside `hello()` counts, "Hello," too; not "helloo".
    let ww = SearchOptions {
        whole_word: true,
        ..Default::default()
    };
    assert_eq!(ws.search_text("hell", &ww).unwrap().matches.len(), 0);

    // Limit.
    let lim = SearchOptions {
        max_results: 1,
        ..Default::default()
    };
    let r = ws.search_text("hello", &lim).unwrap();
    assert_eq!(r.matches.len(), 1);
    assert!(r.truncated);
}

#[test]
fn search_can_be_cancelled() {
    let (_d, ws) = fixture();
    let flag = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(true));
    let opts = SearchOptions {
        cancel: Some(flag.clone()),
        ..Default::default()
    };
    let r = ws.search_text("hello", &opts).unwrap();
    assert!(r.cancelled);
    assert!(
        r.matches.is_empty(),
        "a search cancelled before it starts finds nothing"
    );
    flag.store(false, std::sync::atomic::Ordering::SeqCst);
    let r = ws.search_text("hello", &opts).unwrap();
    assert!(!r.cancelled);
    assert!(!r.matches.is_empty());
}

#[cfg(unix)]
#[test]
fn symlinks_cannot_escape_and_writes_go_through_links() {
    use std::os::unix::fs::symlink;
    let (_d, ws) = fixture();
    let outside = tempfile::tempdir().unwrap();
    std::fs::write(outside.path().join("secret.txt"), "top secret").unwrap();
    symlink(outside.path(), ws.root().join("escape")).unwrap();
    assert!(matches!(
        ws.read_file("escape/secret.txt"),
        Err(WorkspaceError::OutsideWorkspace(_))
    ));
    assert!(matches!(
        ws.write_file("escape/new.txt", "x"),
        Err(WorkspaceError::OutsideWorkspace(_))
    ));
    assert!(matches!(
        ws.list_dir("escape"),
        Err(WorkspaceError::OutsideWorkspace(_))
    ));
    assert!(!outside.path().join("new.txt").exists());

    // Deleting the link removes the link, not the target.
    ws.delete("escape").unwrap();
    assert!(outside.path().join("secret.txt").exists());

    // Saving through an in-workspace link keeps the link.
    symlink(ws.root().join("README.md"), ws.root().join("readme-link.md")).unwrap();
    ws.write_file("readme-link.md", "via link\n").unwrap();
    assert!(
        std::fs::symlink_metadata(ws.root().join("readme-link.md"))
            .unwrap()
            .file_type()
            .is_symlink()
    );
    assert_eq!(ws.read_file("README.md").unwrap(), "via link\n");
}

#[test]
fn invalid_utf8_is_refused() {
    let (_d, ws) = fixture();
    std::fs::write(ws.root().join("latin1.txt"), b"caf\xe9\n").unwrap();
    assert!(matches!(
        ws.read_file("latin1.txt"),
        Err(WorkspaceError::NotUtf8(_))
    ));
}

#[test]
fn workspace_symbols_are_indexed_and_follow_edits() {
    use crate::symbols::SymbolIndex;
    let dir = tempfile::tempdir().unwrap();
    std::fs::create_dir_all(dir.path().join("src")).unwrap();
    std::fs::write(
        dir.path().join("src/lib.rs"),
        "struct Circle { radius: f64 }\nimpl Circle {\n    fn area(&self) {}\n}\n",
    )
    .unwrap();
    std::fs::write(
        dir.path().join("app.py"),
        "class Summary:\n    def area_of(self): pass\n",
    )
    .unwrap();
    std::fs::write(dir.path().join("notes.txt"), "fn not_code() {}").unwrap();
    let ws = Workspace::open(dir.path()).unwrap();
    let mut index = SymbolIndex::new();
    assert_eq!(index.update(&ws), 2, "only files with a grammar are parsed");

    let hits = index.query("area", 10);
    let names: Vec<_> = hits
        .iter()
        .map(|h| (h.name.as_str(), h.container.as_deref(), h.rel_path.as_str()))
        .collect();
    assert_eq!(names[0], ("area", Some("impl Circle"), "src/lib.rs"), "{names:?}");
    assert!(names.contains(&("area_of", Some("Summary"), "app.py")));
    let area = &hits[0];
    assert_eq!((area.line, area.column), (2, 7));
    assert!(index.query("zzz", 10).is_empty());

    // Unchanged files aren't reparsed; edited and deleted ones are picked up.
    assert_eq!(index.update(&ws), 0);
    std::fs::write(dir.path().join("app.py"), "def renamed_function():\n    pass\n").unwrap();
    std::fs::remove_file(dir.path().join("src/lib.rs")).unwrap();
    assert_eq!(index.update(&ws), 1);
    assert!(index.query("area", 10).is_empty());
    assert_eq!(index.query("renfun", 10)[0].name, "renamed_function");
}
