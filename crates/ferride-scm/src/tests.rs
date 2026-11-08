//! Tests against real repositories in temp directories. The `git` CLI (isolated from the user's config) is
//! used to set up remotes and to cross-check what we write: index, objects, refs, packs.

use std::path::Path;
use std::process::Command;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use crate::*;

fn identity() -> Identity {
    Identity {
        name: "Ada Lovelace".into(),
        email: "ada@example.com".into(),
    }
}

fn commit_opts() -> CommitOptions {
    CommitOptions {
        identity: Some(identity()),
        ..Default::default()
    }
}

/// Runs `git` with no global/system config and a fixed identity; panics on failure; returns stdout.
fn git(dir: &Path, args: &[&str]) -> String {
    let home = dir.parent().unwrap_or(dir);
    let out = Command::new("git")
        .args(args)
        .current_dir(dir)
        .env("GIT_CONFIG_NOSYSTEM", "1")
        .env("GIT_CONFIG_GLOBAL", "/dev/null")
        .env("HOME", home)
        .env("GIT_AUTHOR_NAME", "Git Cli")
        .env("GIT_AUTHOR_EMAIL", "cli@example.com")
        .env("GIT_COMMITTER_NAME", "Git Cli")
        .env("GIT_COMMITTER_EMAIL", "cli@example.com")
        .env("GIT_TERMINAL_PROMPT", "0")
        .output()
        .expect("git is installed");
    assert!(
        out.status.success(),
        "git {args:?} failed: {}",
        String::from_utf8_lossy(&out.stderr)
    );
    String::from_utf8(out.stdout).unwrap()
}

fn write(root: &Path, path: &str, text: &str) {
    let abs = root.join(path);
    std::fs::create_dir_all(abs.parent().unwrap()).unwrap();
    std::fs::write(abs, text).unwrap();
}

fn read(root: &Path, path: &str) -> String {
    std::fs::read_to_string(root.join(path)).unwrap()
}

fn changes(repo: &Repository) -> Vec<(ChangeGroup, String, &'static str)> {
    repo.status()
        .unwrap()
        .changes
        .iter()
        .map(|c| (c.group, c.path.clone(), c.letter()))
        .collect()
}

/// A repository with one commit containing `a.txt` and `dir/b.txt`.
fn repo_with_commit() -> (tempfile::TempDir, Repository) {
    let tmp = tempfile::tempdir().unwrap();
    let root = tmp.path().join("work");
    let repo = Repository::init(&root).unwrap();
    write(&root, "a.txt", "one\ntwo\nthree\n");
    write(&root, "dir/b.txt", "bee\n");
    repo.stage_all().unwrap();
    repo.commit("Initial commit", &commit_opts()).unwrap();
    (tmp, repo)
}

#[test]
fn discover_finds_the_repository_from_a_subdirectory() {
    let (tmp, repo) = repo_with_commit();
    let found = Repository::discover(repo.root().join("dir"))
        .unwrap()
        .expect("found");
    assert_eq!(
        found.root().canonicalize().unwrap(),
        repo.root().canonicalize().unwrap()
    );
    let plain = tmp.path().join("plain");
    std::fs::create_dir_all(&plain).unwrap();
    // `tmp` itself is not inside a repository (temp dirs never are).
    assert!(Repository::discover(&plain).unwrap().is_none());
}

#[test]
fn init_then_first_commit_matches_git() {
    let tmp = tempfile::tempdir().unwrap();
    let root = tmp.path().join("w");
    let repo = Repository::init(&root).unwrap();
    assert_eq!(repo.head().unwrap().branch.as_deref(), Some("main"));
    assert!(repo.log(&LogQuery::default()).unwrap().is_empty());

    write(&root, "README.md", "# Hello\n");
    write(&root, "src/main.rs", "fn main() {}\n");
    assert_eq!(
        changes(&repo),
        vec![
            (ChangeGroup::WorkingTree, "README.md".into(), "U"),
            (ChangeGroup::WorkingTree, "src/main.rs".into(), "U"),
        ]
    );
    repo.stage(&["README.md".into()]).unwrap();
    assert_eq!(
        changes(&repo),
        vec![
            (ChangeGroup::Index, "README.md".into(), "A"),
            (ChangeGroup::WorkingTree, "src/main.rs".into(), "U"),
        ]
    );
    let id = repo.commit("Add readme\n\nBody text", &commit_opts()).unwrap();
    assert_eq!(
        changes(&repo),
        vec![(ChangeGroup::WorkingTree, "src/main.rs".into(), "U")]
    );

    let log = repo.log(&LogQuery::default()).unwrap();
    assert_eq!(log.len(), 1);
    assert_eq!(log[0].id, id);
    assert_eq!(log[0].summary, "Add readme");
    assert_eq!(log[0].message, "Add readme\n\nBody text\n");
    assert_eq!(log[0].author_name, "Ada Lovelace");
    assert!(log[0].parents.is_empty());
    assert_eq!(
        log[0].refs,
        vec![RefLabel {
            name: "main".into(),
            kind: RefLabelKind::LocalBranch,
            is_head: true
        }]
    );

    // git agrees on everything we wrote.
    assert_eq!(git(&root, &["rev-parse", "HEAD"]).trim(), id);
    assert_eq!(
        git(&root, &["log", "--format=%an <%ae>|%s"]).trim(),
        "Ada Lovelace <ada@example.com>|Add readme"
    );
    assert_eq!(
        git(&root, &["status", "--porcelain", "-uall"]),
        "?? src/main.rs\n"
    );
    assert_eq!(
        git(&root, &["reflog", "-1", "--format=%gs"]).trim(),
        "commit (initial): Add readme"
    );
    git(&root, &["fsck", "--strict"]);
}

#[test]
fn status_matches_git_porcelain_for_mixed_changes() {
    let (_tmp, repo) = repo_with_commit();
    let root = repo.root().to_path_buf();
    write(&root, "a.txt", "one\nTWO\nthree\n");
    repo.stage(&["a.txt".into()]).unwrap();
    write(&root, "a.txt", "one\nTWO\nthree\nfour\n");
    std::fs::remove_file(root.join("dir/b.txt")).unwrap();
    write(&root, "new/c.txt", "see\n");
    write(&root, "staged-new.txt", "x\n");
    repo.stage(&["staged-new.txt".into()]).unwrap();

    let status = repo.status().unwrap();
    let got: Vec<_> = status
        .changes
        .iter()
        .map(|c| (c.group, c.path.as_str(), c.letter(), c.tooltip()))
        .collect();
    assert_eq!(
        got,
        vec![
            (ChangeGroup::Index, "a.txt", "M", "Index Modified"),
            (ChangeGroup::Index, "staged-new.txt", "A", "Index Added"),
            (ChangeGroup::WorkingTree, "a.txt", "M", "Modified"),
            (ChangeGroup::WorkingTree, "dir/b.txt", "D", "Deleted"),
            (ChangeGroup::WorkingTree, "new/c.txt", "U", "Untracked"),
        ]
    );
    assert_eq!(status.count(), 5);
    assert_eq!(
        git(&root, &["status", "--porcelain", "-uall"]),
        "MM a.txt\n D dir/b.txt\nA  staged-new.txt\n?? new/c.txt\n"
    );
}

#[test]
fn unstage_and_discard() {
    let (_tmp, repo) = repo_with_commit();
    let root = repo.root().to_path_buf();
    write(&root, "a.txt", "changed\n");
    write(&root, "fresh.txt", "new\n");
    std::fs::remove_file(root.join("dir/b.txt")).unwrap();
    repo.stage_all().unwrap();
    assert_eq!(repo.status().unwrap().group(ChangeGroup::Index).count(), 3);

    repo.unstage(&["fresh.txt".into(), "a.txt".into()]).unwrap();
    assert_eq!(
        changes(&repo),
        vec![
            (ChangeGroup::Index, "dir/b.txt".into(), "D"),
            (ChangeGroup::WorkingTree, "a.txt".into(), "M"),
            (ChangeGroup::WorkingTree, "fresh.txt".into(), "U"),
        ]
    );
    repo.unstage_all().unwrap();
    assert_eq!(
        git(&root, &["status", "--porcelain", "-uall"]),
        " M a.txt\n D dir/b.txt\n?? fresh.txt\n"
    );

    repo.discard(&["a.txt".into(), "dir/b.txt".into(), "fresh.txt".into()])
        .unwrap();
    assert!(repo.status().unwrap().changes.is_empty());
    assert_eq!(read(&root, "a.txt"), "one\ntwo\nthree\n");
    assert_eq!(read(&root, "dir/b.txt"), "bee\n");
    assert!(!root.join("fresh.txt").exists());

    write(&root, "x/y/z.txt", "deep\n");
    repo.discard_all().unwrap();
    assert!(
        !root.join("x").exists(),
        "empty parent directories are removed too"
    );
    assert_eq!(git(&root, &["status", "--porcelain", "-uall"]), "");
}

#[test]
fn diffs_on_both_sides() {
    let (_tmp, repo) = repo_with_commit();
    let root = repo.root().to_path_buf();
    write(&root, "a.txt", "one\n2\nthree\n");
    repo.stage(&["a.txt".into()]).unwrap();
    write(&root, "a.txt", "one\n2\nthree\nfour\n");

    let staged = repo.diff("a.txt", DiffSide::Staged).unwrap();
    assert_eq!(staged.original, "one\ntwo\nthree\n");
    assert_eq!(staged.modified, "one\n2\nthree\n");
    assert_eq!(staged.hunks.len(), 1);
    assert_eq!(staged.hunks[0].removed[0].text, "two");
    assert_eq!(
        staged.hunks[0].added[0].words,
        vec![ColumnRange { start: 0, end: 1 }]
    );

    let wt = repo.diff("a.txt", DiffSide::WorkingTree).unwrap();
    assert_eq!(wt.hunks.len(), 1);
    assert_eq!((wt.hunks[0].modified_start, wt.hunks[0].modified_count), (3, 1));

    write(&root, "new.txt", "hello\n");
    let untracked = repo.diff("new.txt", DiffSide::WorkingTree).unwrap();
    assert!(!untracked.original_exists && untracked.modified_exists);
    assert_eq!(untracked.hunks[0].added[0].text, "hello");

    std::fs::write(root.join("bin.dat"), [0u8, 1, 2, 3]).unwrap();
    assert!(repo.diff("bin.dat", DiffSide::WorkingTree).unwrap().binary);

    assert_eq!(
        repo.quick_diff_original("a.txt").unwrap().as_deref(),
        Some("one\n2\nthree\n")
    );
    assert_eq!(repo.quick_diff_original("new.txt").unwrap(), None);
    assert!(repo.diff("../escape", DiffSide::WorkingTree).is_err());
    assert!(repo.diff(".git/config", DiffSide::WorkingTree).is_err());
}

#[test]
fn stage_unstage_and_revert_single_hunks() {
    let tmp = tempfile::tempdir().unwrap();
    let root = tmp.path().join("w");
    let repo = Repository::init(&root).unwrap();
    let base: String = (1..=20).map(|i| format!("line {i}\n")).collect();
    write(&root, "f.txt", &base);
    repo.stage_all().unwrap();
    repo.commit("base", &commit_opts()).unwrap();
    let edited = base
        .replace("line 2\n", "line two\n")
        .replace("line 18\n", "line eighteen\n");
    write(&root, "f.txt", &edited);

    let wt = repo.diff("f.txt", DiffSide::WorkingTree).unwrap();
    assert_eq!(wt.hunks.len(), 2);
    repo.stage_hunks("f.txt", &[HunkId::from(&wt.hunks[0])]).unwrap();
    let staged_text = git(&root, &["show", ":f.txt"]);
    assert_eq!(staged_text, base.replace("line 2\n", "line two\n"));
    assert_eq!(repo.diff("f.txt", DiffSide::Staged).unwrap().hunks.len(), 1);
    assert_eq!(repo.diff("f.txt", DiffSide::WorkingTree).unwrap().hunks.len(), 1);
    assert_eq!(git(&root, &["status", "--porcelain"]), "MM f.txt\n");
    // A stale hunk id is refused.
    assert!(repo.stage_hunks("f.txt", &[HunkId::from(&wt.hunks[0])]).is_err());

    let staged = repo.diff("f.txt", DiffSide::Staged).unwrap();
    repo.unstage_hunks("f.txt", &[HunkId::from(&staged.hunks[0])])
        .unwrap();
    assert_eq!(git(&root, &["show", ":f.txt"]), base);
    assert_eq!(git(&root, &["status", "--porcelain"]), " M f.txt\n");

    let wt = repo.diff("f.txt", DiffSide::WorkingTree).unwrap();
    repo.discard_hunks("f.txt", &[HunkId::from(&wt.hunks[1])])
        .unwrap();
    assert_eq!(read(&root, "f.txt"), base.replace("line 2\n", "line two\n"));
    git(&root, &["fsck", "--strict"]);
}

#[test]
fn commit_identity_amend_and_errors() {
    let tmp = tempfile::tempdir().unwrap();
    let root = tmp.path().join("w");
    let repo = Repository::init(&root).unwrap();
    write(&root, "a", "a\n");
    repo.stage_all().unwrap();
    assert!(matches!(
        repo.commit("x", &CommitOptions::default()),
        Err(ScmError::MissingIdentity)
    ));
    assert!(matches!(
        repo.commit("  \n# comment\n", &commit_opts()),
        Err(ScmError::InvalidInput(_))
    ));

    git(&root, &["config", "user.name", "Repo User"]);
    git(&root, &["config", "user.email", "repo@example.com"]);
    assert_eq!(
        repo.configured_identity(),
        Some(Identity {
            name: "Repo User".into(),
            email: "repo@example.com".into()
        })
    );
    let first = repo.commit("first", &CommitOptions::default()).unwrap();
    assert_eq!(
        git(&root, &["log", "-1", "--format=%an|%cn"]).trim(),
        "Repo User|Repo User"
    );
    assert!(matches!(
        repo.commit("again", &commit_opts()),
        Err(ScmError::NothingToCommit)
    ));

    // Smart commit stages everything when nothing is staged.
    write(&root, "b", "b\n");
    let opts = CommitOptions {
        stage_all_if_empty: true,
        ..commit_opts()
    };
    let second = repo.commit("second", &opts).unwrap();
    assert_eq!(git(&root, &["status", "--porcelain"]), "");

    let amended = repo
        .commit(
            "second, amended",
            &CommitOptions {
                amend: true,
                ..commit_opts()
            },
        )
        .unwrap();
    assert_ne!(amended, second);
    let log = repo.log(&LogQuery::default()).unwrap();
    assert_eq!(log.len(), 2);
    assert_eq!(log[0].summary, "second, amended");
    assert_eq!(log[0].parents, vec![first]);
    assert_eq!(
        git(&root, &["reflog", "-1", "--format=%gs"]).trim(),
        "commit (amend): second, amended"
    );
}

#[test]
fn log_pages_and_decorations() {
    let (_tmp, repo) = repo_with_commit();
    let root = repo.root().to_path_buf();
    for i in 0..5 {
        write(&root, "a.txt", &format!("{i}\n"));
        repo.stage_all().unwrap();
        repo.commit(&format!("change {i}"), &commit_opts()).unwrap();
        std::thread::sleep(std::time::Duration::from_millis(5));
    }
    git(&root, &["tag", "v1.0", "HEAD~1"]);
    git(&root, &["branch", "feature", "HEAD~2"]);

    let all = repo
        .log(&LogQuery {
            limit: 100,
            ..Default::default()
        })
        .unwrap();
    assert_eq!(all.len(), 6);
    let git_order: Vec<String> = git(&root, &["log", "--format=%H"])
        .lines()
        .map(str::to_owned)
        .collect();
    assert_eq!(all.iter().map(|c| c.id.clone()).collect::<Vec<_>>(), git_order);

    let page = repo
        .log(&LogQuery {
            skip: 1,
            limit: 2,
            ..Default::default()
        })
        .unwrap();
    assert_eq!(
        page.iter().map(|c| c.summary.as_str()).collect::<Vec<_>>(),
        vec!["change 3", "change 2"]
    );
    assert_eq!(
        page[0].refs,
        vec![RefLabel {
            name: "v1.0".into(),
            kind: RefLabelKind::Tag,
            is_head: false
        }]
    );
    assert_eq!(
        page[1].refs,
        vec![RefLabel {
            name: "feature".into(),
            kind: RefLabelKind::LocalBranch,
            is_head: false
        }]
    );
    assert_eq!(all[0].short_id.len(), 7);
    assert!(all[0].author_time > 1_600_000_000);

    let from_feature = repo
        .log(&LogQuery {
            start: Some("feature".into()),
            ..Default::default()
        })
        .unwrap();
    assert_eq!(from_feature[0].summary, "change 2");
    assert!(
        repo.log(&LogQuery {
            start: Some("nope".into()),
            ..Default::default()
        })
        .is_err()
    );
}

#[test]
fn branches_create_checkout_and_protect_local_changes() {
    let (_tmp, repo) = repo_with_commit();
    let root = repo.root().to_path_buf();
    repo.create_branch("feature/x", None, true).unwrap();
    assert_eq!(repo.head().unwrap().label(), "feature/x");
    write(&root, "a.txt", "feature version\n");
    write(&root, "only-on-feature.txt", "f\n");
    repo.stage_all().unwrap();
    repo.commit("feature work", &commit_opts()).unwrap();

    repo.checkout("main").unwrap();
    assert_eq!(read(&root, "a.txt"), "one\ntwo\nthree\n");
    assert!(!root.join("only-on-feature.txt").exists());
    assert_eq!(git(&root, &["status", "--porcelain", "-uall"]), "");
    assert_eq!(git(&root, &["symbolic-ref", "HEAD"]).trim(), "refs/heads/main");

    // A local change to a file that differs between the branches blocks the checkout; nothing changes.
    write(&root, "a.txt", "local edit\n");
    match repo.checkout("feature/x") {
        Err(ScmError::WouldOverwrite(paths)) => assert_eq!(paths, vec!["a.txt".to_owned()]),
        other => panic!("unexpected {other:?}"),
    }
    assert_eq!(read(&root, "a.txt"), "local edit\n");
    assert_eq!(repo.head().unwrap().branch.as_deref(), Some("main"));

    // A change to an unrelated file is carried over, like git does.
    repo.discard(&["a.txt".into()]).unwrap();
    write(&root, "dir/b.txt", "carried\n");
    repo.checkout("feature/x").unwrap();
    assert_eq!(read(&root, "a.txt"), "feature version\n");
    assert_eq!(read(&root, "dir/b.txt"), "carried\n");
    assert_eq!(git(&root, &["status", "--porcelain", "-uall"]), " M dir/b.txt\n");

    let branches = repo.branches().unwrap();
    let names: Vec<_> = branches.iter().map(|b| (b.name.as_str(), b.is_head)).collect();
    assert_eq!(names, vec![("feature/x", true), ("main", false)]);
    assert_eq!(branches[0].summary, "feature work");

    assert!(matches!(
        repo.create_branch("main", None, false),
        Err(ScmError::BranchExists(_))
    ));
    assert!(repo.create_branch("bad name", None, false).is_err());
    assert!(repo.create_branch("a..b", None, false).is_err());
    assert!(matches!(
        repo.checkout("missing"),
        Err(ScmError::BranchNotFound(_))
    ));

    // Detached checkout of a commit.
    let first = repo.log(&LogQuery::default()).unwrap()[1].id.clone();
    repo.discard(&["dir/b.txt".into()]).unwrap();
    repo.checkout(&first).unwrap();
    let head = repo.head().unwrap();
    assert!(head.detached);
    assert_eq!(head.label(), first[..8]);
    git(&root, &["fsck", "--strict"]);
}

#[derive(Default)]
struct Recorder {
    phases: Mutex<Vec<String>>,
}

impl ProgressSink for Recorder {
    fn progress(&self, progress: Progress) {
        self.phases.lock().unwrap().push(progress.phase);
    }
}

fn local_net() -> NetworkOptions {
    NetworkOptions {
        allow_local_transport: true,
        ..Default::default()
    }
}

/// A bare "server" repository with one commit on `main`.
fn bare_remote(tmp: &Path) -> String {
    let seed = tmp.join("seed");
    let repo = Repository::init(&seed).unwrap();
    write(&seed, "README.md", "remote\n");
    repo.stage_all().unwrap();
    repo.commit("Initial commit", &commit_opts()).unwrap();
    git(tmp, &["clone", "-q", "--bare", "seed", "remote.git"]);
    format!("file://{}", tmp.join("remote.git").display())
}

#[test]
fn clone_push_pull_round_trip() {
    let tmp = tempfile::tempdir().unwrap();
    let url = bare_remote(tmp.path());
    let remote_dir = tmp.path().join("remote.git");

    let recorder = Arc::new(Recorder::default());
    let opts = NetworkOptions {
        progress: Some(recorder.clone()),
        ..local_net()
    };
    let a = clone(&url, tmp.path().join("a"), &opts).unwrap();
    assert_eq!(read(a.root(), "README.md"), "remote\n");
    let head = a.head().unwrap();
    assert_eq!(head.branch.as_deref(), Some("main"));
    assert_eq!(head.upstream.as_deref(), Some("origin/main"));
    assert_eq!((head.ahead, head.behind), (0, 0));
    assert!(a.status().unwrap().changes.is_empty());

    // Commit and push from A.
    write(a.root(), "src/lib.rs", "pub fn a() {}\n");
    write(a.root(), "README.md", "remote\nmore\n");
    a.stage_all().unwrap();
    let pushed = a.commit("Add lib", &commit_opts()).unwrap();
    assert_eq!(a.head().unwrap().ahead, 1);
    a.push(&PushOptions::default(), &local_net()).unwrap();
    assert_eq!(git(&remote_dir, &["rev-parse", "main"]).trim(), pushed);
    git(&remote_dir, &["fsck", "--strict"]);
    assert_eq!(a.head().unwrap().ahead, 0, "origin/main moved with the push");
    // Pushing again is a no-op.
    a.push(&PushOptions::default(), &local_net()).unwrap();

    // B clones, A pushes more, B pulls (fast-forward).
    let b = clone(&url, tmp.path().join("b"), &local_net()).unwrap();
    assert_eq!(read(b.root(), "src/lib.rs"), "pub fn a() {}\n");
    write(a.root(), "src/lib.rs", "pub fn a() {}\npub fn b() {}\n");
    a.stage_all().unwrap();
    let second = a.commit("Add b", &commit_opts()).unwrap();
    a.push(&PushOptions::default(), &local_net()).unwrap();
    b.fetch(None, &local_net()).unwrap();
    assert_eq!(b.head().unwrap().behind, 1);
    b.pull(&local_net()).unwrap();
    assert_eq!(b.head().unwrap().commit.as_deref(), Some(second.as_str()));
    assert_eq!(read(b.root(), "src/lib.rs"), "pub fn a() {}\npub fn b() {}\n");
    assert_eq!(git(b.root(), &["status", "--porcelain", "-uall"]), "");

    // Diverge: B commits, A commits and pushes; B can't push or fast-forward.
    write(b.root(), "b.txt", "b\n");
    b.stage_all().unwrap();
    b.commit("From B", &commit_opts()).unwrap();
    write(a.root(), "a.txt", "a\n");
    a.stage_all().unwrap();
    a.commit("From A", &commit_opts()).unwrap();
    a.push(&PushOptions::default(), &local_net()).unwrap();
    assert!(matches!(
        b.push(&PushOptions::default(), &local_net()),
        Err(ScmError::PushRejectedNonFastForward)
    ));
    assert!(matches!(b.pull(&local_net()), Err(ScmError::NotFastForward)));

    // Publish a new branch from B with upstream tracking.
    b.create_branch("topic", None, true).unwrap();
    assert!(matches!(
        b.push(&PushOptions::default(), &local_net()),
        Err(ScmError::NoUpstream(_))
    ));
    b.push(
        &PushOptions {
            set_upstream: true,
            ..Default::default()
        },
        &local_net(),
    )
    .unwrap();
    assert_eq!(b.head().unwrap().upstream.as_deref(), Some("origin/topic"));
    assert_eq!(
        git(&remote_dir, &["log", "-1", "--format=%s", "topic"]).trim(),
        "From B"
    );
    git(&remote_dir, &["fsck", "--strict"]);

    // Remote branches show up and can be checked out as tracking branches.
    a.fetch(None, &local_net()).unwrap();
    assert!(
        a.branches()
            .unwrap()
            .iter()
            .any(|br| br.name == "origin/topic" && br.kind == BranchKind::Remote)
    );
    a.checkout("origin/topic").unwrap();
    let head = a.head().unwrap();
    assert_eq!(head.branch.as_deref(), Some("topic"));
    assert_eq!(head.upstream.as_deref(), Some("origin/topic"));
    assert_eq!(read(a.root(), "b.txt"), "b\n");

    let phases = recorder.phases.lock().unwrap();
    assert!(phases.iter().any(|p| p == "Connecting"), "{phases:?}");
    assert!(phases.iter().any(|p| p == "Checking out files"), "{phases:?}");
}

#[test]
fn clone_cancellation_and_url_rules() {
    let tmp = tempfile::tempdir().unwrap();
    let url = bare_remote(tmp.path());

    let opts = local_net();
    opts.cancel.store(true, Ordering::Relaxed);
    let dest = tmp.path().join("cancelled");
    assert!(matches!(clone(&url, &dest, &opts), Err(ScmError::Cancelled)));
    assert!(
        !dest.join(".git").exists(),
        "a cancelled clone leaves nothing behind"
    );

    assert!(matches!(
        clone(&url, tmp.path().join("x"), &NetworkOptions::default()),
        Err(ScmError::UnsupportedUrl(_))
    ));
    assert!(matches!(
        clone(
            "ssh://git@example.com/repo.git",
            tmp.path().join("y"),
            &NetworkOptions::default()
        ),
        Err(ScmError::UnsupportedUrl(_))
    ));
    std::fs::create_dir_all(tmp.path().join("full")).unwrap();
    write(&tmp.path().join("full"), "f", "x");
    assert!(matches!(
        clone(&url, tmp.path().join("full"), &local_net()),
        Err(ScmError::InvalidInput(_))
    ));
}

#[test]
fn credentials_are_redacted() {
    let cred = Credential {
        username: "me".into(),
        password: "ghp_secret".into(),
    };
    assert!(!format!("{cred:?}").contains("ghp_secret"));
    assert_eq!(
        net::redact_url("https://me:ghp_secret@github.com/o/r.git"),
        "https://github.com/o/r.git"
    );
    let err = net::map_net_error(
        "HTTP 401 Unauthorized",
        "https://me:ghp_secret@github.com/o/r.git",
        &NetworkOptions::default(),
    );
    assert!(matches!(&err, ScmError::Authentication(u) if !u.contains("secret")));
}

struct CountingProvider {
    asked: AtomicUsize,
    urls: Mutex<Vec<String>>,
}

impl CredentialProvider for CountingProvider {
    fn get(&self, url: &str) -> Option<Credential> {
        self.asked.fetch_add(1, Ordering::Relaxed);
        self.urls.lock().unwrap().push(url.to_owned());
        Some(Credential {
            username: "user".into(),
            password: "token".into(),
        })
    }
}

/// An HTTP server that answers every request with `401` and records the `Authorization` headers.
fn unauthorized_server() -> (String, Arc<Mutex<Vec<String>>>) {
    use std::io::{BufRead, BufReader, Write};
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let auth = Arc::new(Mutex::new(Vec::new()));
    let seen = Arc::clone(&auth);
    std::thread::spawn(move || {
        for stream in listener.incoming().take(8) {
            let Ok(mut stream) = stream else { continue };
            let mut reader = BufReader::new(stream.try_clone().unwrap());
            let mut line = String::new();
            while reader.read_line(&mut line).is_ok_and(|n| n > 2) {
                if line.to_ascii_lowercase().starts_with("authorization:") {
                    seen.lock().unwrap().push(line.trim().to_owned());
                }
                line.clear();
            }
            let _ = stream.write_all(
                b"HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"test\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
            );
        }
    });
    (format!("http://127.0.0.1:{port}/owner/repo.git"), auth)
}

#[test]
fn http_asks_the_credential_provider_and_reports_authentication_failure() {
    let tmp = tempfile::tempdir().unwrap();
    let (url, auth_headers) = unauthorized_server();
    let provider = Arc::new(CountingProvider {
        asked: AtomicUsize::new(0),
        urls: Mutex::new(Vec::new()),
    });
    let opts = NetworkOptions {
        credentials: Some(provider.clone()),
        ..Default::default()
    };
    let err = clone(&url, tmp.path().join("c"), &opts).unwrap_err();
    assert!(matches!(err, ScmError::Authentication(_)), "{err:?}");
    assert!(provider.asked.load(Ordering::Relaxed) >= 1);
    assert!(provider.urls.lock().unwrap()[0].starts_with("http://127.0.0.1"));
    assert!(!err.to_string().contains("token"));
    let _ = auth_headers; // Whether credentials go over plain http is gix's policy; we only check we were asked.
}

#[test]
fn tls_configuration_builds() {
    net::http_options().expect("rustls with ring and a root store");
}

/// Real network: `cargo test -p ferride-scm -- --ignored` to clone a small public repository over HTTPS.
#[test]
#[ignore]
fn clone_public_https_repository() {
    let tmp = tempfile::tempdir().unwrap();
    let repo = clone(
        "https://github.com/octocat/Hello-World.git",
        tmp.path().join("hw"),
        &NetworkOptions::default(),
    )
    .unwrap();
    assert!(repo.root().join("README").exists());
    assert!(!repo.log(&LogQuery::default()).unwrap().is_empty());
}

/// A smart-HTTP git server for tests: `git http-backend` behind a tiny HTTP/1.1 front that requires
/// `Authorization: Basic user:token` (like GitHub with a personal access token).
fn http_git_server(project_root: &Path) -> String {
    use std::io::{BufRead, BufReader, Read, Write};
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let root = project_root.to_path_buf();
    std::thread::spawn(move || {
        for stream in listener.incoming() {
            let Ok(mut stream) = stream else { continue };
            let mut reader = BufReader::new(stream.try_clone().unwrap());
            let mut request_line = String::new();
            if reader.read_line(&mut request_line).unwrap_or(0) == 0 {
                continue;
            }
            let mut parts = request_line.split_whitespace();
            let method = parts.next().unwrap_or("").to_owned();
            let target = parts.next().unwrap_or("").to_owned();
            let (mut auth, mut content_type, mut length, mut chunked) = (None, String::new(), 0usize, false);
            loop {
                let mut line = String::new();
                reader.read_line(&mut line).unwrap();
                let line = line.trim_end();
                if line.is_empty() {
                    break;
                }
                let (name, value) = line.split_once(':').unwrap();
                let value = value.trim().to_owned();
                match name.to_ascii_lowercase().as_str() {
                    "authorization" => auth = Some(value),
                    "content-type" => content_type = value,
                    "content-length" => length = value.parse().unwrap(),
                    "transfer-encoding" => chunked = value.eq_ignore_ascii_case("chunked"),
                    _ => {}
                }
            }
            let mut body = Vec::new();
            if chunked {
                loop {
                    let mut size = String::new();
                    reader.read_line(&mut size).unwrap();
                    let size = usize::from_str_radix(size.trim(), 16).unwrap();
                    let mut chunk = vec![0; size + 2];
                    reader.read_exact(&mut chunk).unwrap();
                    if size == 0 {
                        break;
                    }
                    body.extend_from_slice(&chunk[..size]);
                }
            } else {
                body.resize(length, 0);
                reader.read_exact(&mut body).unwrap();
            }
            // "user:token" in base64.
            if auth.as_deref() != Some("Basic dXNlcjp0b2tlbg==") {
                let _ = stream.write_all(
                    b"HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"git\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                );
                continue;
            }
            let (path, query) = target.split_once('?').unwrap_or((&target, ""));
            let mut child = Command::new("git")
                .arg("http-backend")
                .env_clear()
                .env("PATH", std::env::var_os("PATH").unwrap())
                .env("GIT_CONFIG_NOSYSTEM", "1")
                .env("GIT_CONFIG_GLOBAL", "/dev/null")
                .env("GIT_PROJECT_ROOT", &root)
                .env("GIT_HTTP_EXPORT_ALL", "1")
                .env("REMOTE_USER", "user")
                .env("REQUEST_METHOD", &method)
                .env("PATH_INFO", path)
                .env("QUERY_STRING", query)
                .env("CONTENT_TYPE", &content_type)
                .env("CONTENT_LENGTH", body.len().to_string())
                .stdin(std::process::Stdio::piped())
                .stdout(std::process::Stdio::piped())
                .spawn()
                .unwrap();
            child.stdin.take().unwrap().write_all(&body).unwrap();
            let out = child.wait_with_output().unwrap().stdout;
            let split = out
                .windows(4)
                .position(|w| w == b"\r\n\r\n")
                .map(|p| (p, 4))
                .or_else(|| out.windows(2).position(|w| w == b"\n\n").map(|p| (p, 2)));
            let (head_end, sep) = split.unwrap();
            let headers = String::from_utf8_lossy(&out[..head_end]).to_string();
            let payload = &out[head_end + sep..];
            let mut status = "200 OK".to_owned();
            let mut response = String::new();
            for header in headers.lines() {
                if let Some(s) = header.strip_prefix("Status:") {
                    status = s.trim().to_owned();
                } else if !header.is_empty() {
                    response.push_str(header.trim_end());
                    response.push_str("\r\n");
                }
            }
            let head = format!(
                "HTTP/1.1 {status}\r\n{response}Content-Length: {}\r\nConnection: close\r\n\r\n",
                payload.len()
            );
            let _ = stream.write_all(head.as_bytes());
            let _ = stream.write_all(payload);
        }
    });
    format!("http://127.0.0.1:{port}")
}

#[test]
fn http_clone_and_push_with_token() {
    let tmp = tempfile::tempdir().unwrap();
    bare_remote(tmp.path());
    git(
        &tmp.path().join("remote.git"),
        &["config", "http.receivepack", "true"],
    );
    let base = http_git_server(tmp.path());
    let url = format!("{base}/remote.git");

    // Without credentials the server says 401.
    let err = clone(&url, tmp.path().join("anon"), &NetworkOptions::default()).unwrap_err();
    assert!(matches!(err, ScmError::Authentication(_)), "{err:?}");

    struct Token;
    impl CredentialProvider for Token {
        fn get(&self, _url: &str) -> Option<Credential> {
            Some(Credential {
                username: "user".into(),
                password: "token".into(),
            })
        }
    }
    let opts = NetworkOptions {
        credentials: Some(Arc::new(Token)),
        ..Default::default()
    };
    let repo = clone(&url, tmp.path().join("c"), &opts).unwrap();
    assert_eq!(read(repo.root(), "README.md"), "remote\n");

    write(repo.root(), "pushed.txt", "over http\n");
    repo.stage_all().unwrap();
    let id = repo.commit("Push over HTTP", &commit_opts()).unwrap();
    repo.push(&PushOptions::default(), &opts).unwrap();
    let remote_dir = tmp.path().join("remote.git");
    assert_eq!(git(&remote_dir, &["rev-parse", "main"]).trim(), id);
    git(&remote_dir, &["fsck", "--strict"]);
    repo.fetch(None, &opts).unwrap();
    repo.pull(&opts).unwrap();
}

#[test]
fn merge_conflicts_are_listed_resolved_by_staging_and_committed_as_merge() {
    let (_tmp, repo) = repo_with_commit();
    let root = repo.root().to_path_buf();
    repo.create_branch("other", None, true).unwrap();
    write(&root, "a.txt", "one\nOTHER\nthree\n");
    repo.stage_all().unwrap();
    repo.commit("other side", &commit_opts()).unwrap();
    repo.checkout("main").unwrap();
    write(&root, "a.txt", "one\nMAIN\nthree\n");
    repo.stage_all().unwrap();
    repo.commit("main side", &commit_opts()).unwrap();

    let out = Command::new("git")
        .args(["merge", "other"])
        .current_dir(&root)
        .env("GIT_CONFIG_NOSYSTEM", "1")
        .env("GIT_CONFIG_GLOBAL", "/dev/null")
        .env("GIT_AUTHOR_NAME", "x")
        .env("GIT_AUTHOR_EMAIL", "x@x")
        .env("GIT_COMMITTER_NAME", "x")
        .env("GIT_COMMITTER_EMAIL", "x@x")
        .output()
        .unwrap();
    assert!(!out.status.success(), "the merge conflicts");

    let status = repo.status().unwrap();
    let got: Vec<_> = status
        .changes
        .iter()
        .map(|c| (c.group, c.path.as_str(), c.letter(), c.tooltip()))
        .collect();
    assert_eq!(
        got,
        vec![(ChangeGroup::Merge, "a.txt", "!", "Conflict: Both Modified")]
    );
    assert_eq!(ChangeGroup::Merge.label(), "Merge Changes");
    assert!(matches!(
        repo.commit("merge", &commit_opts()),
        Err(ScmError::InvalidInput(_))
    ));
    // The diff of a conflicted file compares "ours" with the working tree (conflict markers).
    let diff = repo.diff("a.txt", DiffSide::WorkingTree).unwrap();
    assert!(diff.modified.contains("<<<<<<<"));

    write(&root, "a.txt", "one\nMAIN and OTHER\nthree\n");
    repo.stage(&["a.txt".into()]).unwrap();
    assert_eq!(changes(&repo), vec![(ChangeGroup::Index, "a.txt".into(), "M")]);
    repo.commit("Merge branch 'other'", &commit_opts()).unwrap();
    assert_eq!(repo.log(&LogQuery::default()).unwrap()[0].parents.len(), 2);
    assert!(!repo.git_dir().join("MERGE_HEAD").exists());
    assert_eq!(git(&root, &["status", "--porcelain"]), "");
    git(&root, &["fsck", "--strict"]);
}
