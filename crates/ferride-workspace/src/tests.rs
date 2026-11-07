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
