//! Network operations over HTTPS: clone, fetch, pull (fast-forward only).
//!
//! TLS uses rustls with the `ring` provider and Android's system CA store (`/system/etc/security/cacerts`,
//! or the Conscrypt APEX one on Android 14+), falling back to Mozilla's roots elsewhere. Credentials come from
//! the app through [`CredentialProvider`] and are never logged or put into error messages.

use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use gix::bstr::{BString, ByteSlice};
use gix::credentials::helper::{Action, NextAction};
use gix::credentials::protocol::Outcome;
use gix::progress::tree::{Item, Root};
use gix::protocol::transport::client::blocking_io::http;
use gix::remote::Direction;

use crate::branch::{commit_exists, is_ancestor, resolve_ref, tracking_ref, upstream_config};
use crate::worktree::{commit_tree_id, head_commit_id};
use crate::{Repository, Result, ScmError};

/// A username and password (for GitHub/GitLab/Gitea: any username and a personal access token).
#[derive(Clone, PartialEq, Eq)]
pub struct Credential {
    pub username: String,
    pub password: String,
}

impl std::fmt::Debug for Credential {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Credential")
            .field("username", &self.username)
            .field("password", &"<redacted>")
            .finish()
    }
}

/// Supplies credentials when a remote asks for them. Implemented by the app (Android Keystore-backed storage,
/// a sign-in prompt, …).
pub trait CredentialProvider: Send + Sync {
    /// Credentials for `url` (scheme, host and path; never contains credentials). `None` cancels the
    /// operation with an authentication error.
    fn get(&self, url: &str) -> Option<Credential>;
    /// The remote accepted the credentials returned for `url`; a good moment to store them.
    fn approved(&self, _url: &str) {}
    /// The remote rejected the credentials returned for `url`; forget them.
    fn rejected(&self, _url: &str) {}
}

/// A progress report: `phase` is git's wording ("Receiving objects", "Resolving deltas", "Checking out files"…).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Progress {
    pub phase: String,
    pub current: u64,
    pub total: Option<u64>,
}

pub trait ProgressSink: Send + Sync {
    fn progress(&self, progress: Progress);
}

/// Options shared by all network operations.
#[derive(Clone, Default)]
pub struct NetworkOptions {
    pub credentials: Option<Arc<dyn CredentialProvider>>,
    pub progress: Option<Arc<dyn ProgressSink>>,
    /// Set to `true` from any thread to cancel; the operation then fails with [`ScmError::Cancelled`] and a
    /// partial clone is removed.
    pub cancel: Arc<AtomicBool>,
    /// Allow `file://` URLs and local paths. Off in the app (those spawn `git` processes); tests and desktop
    /// tools use it.
    pub allow_local_transport: bool,
}

impl std::fmt::Debug for NetworkOptions {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("NetworkOptions")
            .field("credentials", &self.credentials.is_some())
            .field("progress", &self.progress.is_some())
            .field("cancel", &self.cancel.load(Ordering::Relaxed))
            .finish()
    }
}

/// Removes user info from a URL for display and error messages.
pub(crate) fn redact_url(url: &str) -> String {
    match gix::url::parse(url.as_bytes().as_bstr()) {
        Ok(mut parsed) => {
            parsed.set_user(None);
            parsed.set_password(None);
            parsed.to_bstring().to_string()
        }
        Err(_) => "<invalid url>".into(),
    }
}

/// Accepts `https://` (and `http://`) URLs; local ones only when allowed.
pub(crate) fn check_url(url: &gix::Url, options: &NetworkOptions) -> Result<()> {
    use gix::url::Scheme;
    match url.scheme {
        Scheme::Https | Scheme::Http => Ok(()),
        Scheme::File if options.allow_local_transport => Ok(()),
        _ => Err(ScmError::UnsupportedUrl(redact_url(
            &url.to_bstring().to_string(),
        ))),
    }
}

fn context_url(ctx: &gix::credentials::protocol::Context) -> String {
    if let Some(url) = &ctx.url {
        return redact_url(&url.to_str_lossy());
    }
    let protocol = ctx.protocol.as_deref().unwrap_or("https");
    let host = ctx.host.as_deref().unwrap_or("");
    let path = ctx
        .path
        .as_ref()
        .map(|p| p.to_str_lossy().into_owned())
        .unwrap_or_default();
    format!("{protocol}://{host}/{}", path.trim_start_matches('/'))
}

type AuthFn = Box<dyn FnMut(Action) -> gix::Result<Option<Outcome>> + Send>;

/// The credential callback gix calls on `401`/`403`: asks the app, reports approval / rejection back.
pub(crate) fn credential_fn(
    provider: Option<Arc<dyn CredentialProvider>>,
    last_url: Arc<Mutex<Option<String>>>,
) -> AuthFn {
    Box::new(move |action| {
        let Some(provider) = provider.as_ref() else {
            return Ok(None);
        };
        match action {
            Action::Get(ctx) => {
                let url = context_url(&ctx);
                *last_url.lock().unwrap_or_else(|e| e.into_inner()) = Some(url.clone());
                match provider.get(&url) {
                    Some(cred) => Ok(Some(Outcome {
                        identity: gix::sec::identity::Account {
                            username: cred.username,
                            password: cred.password,
                            oauth_refresh_token: None,
                        },
                        next: NextAction::from(ctx),
                    })),
                    None => Ok(None),
                }
            }
            Action::Store(_) => {
                if let Some(url) = last_url.lock().unwrap_or_else(|e| e.into_inner()).as_deref() {
                    provider.approved(url);
                }
                Ok(None)
            }
            Action::Erase(_) => {
                if let Some(url) = last_url.lock().unwrap_or_else(|e| e.into_inner()).as_deref() {
                    provider.rejected(url);
                }
                Ok(None)
            }
        }
    })
}

/// rustls client configuration (ring provider, system roots), built once.
fn tls_config() -> Result<rustls::ClientConfig> {
    static CONFIG: OnceLock<std::result::Result<rustls::ClientConfig, String>> = OnceLock::new();
    CONFIG
        .get_or_init(|| {
            let mut roots = rustls::RootCertStore::empty();
            for dir in [
                "/apex/com.android.conscrypt/cacerts",
                "/system/etc/security/cacerts",
            ] {
                let Ok(entries) = std::fs::read_dir(dir) else {
                    continue;
                };
                for entry in entries.flatten() {
                    use rustls::pki_types::{CertificateDer, pem::PemObject};
                    if let Ok(cert) = CertificateDer::from_pem_file(entry.path()) {
                        let _ = roots.add(cert);
                    }
                }
                if !roots.is_empty() {
                    break;
                }
            }
            if roots.is_empty() {
                roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
            }
            let provider = Arc::new(rustls::crypto::ring::default_provider());
            rustls::ClientConfig::builder_with_provider(provider)
                .with_safe_default_protocol_versions()
                .map(|b| b.with_root_certificates(roots).with_no_client_auth())
                .map_err(|e| e.to_string())
        })
        .clone()
        .map_err(ScmError::Git)
}

/// HTTP transport options: reqwest with our TLS configuration and user agent.
pub(crate) fn http_options() -> Result<http::Options> {
    let tls = tls_config()?;
    let backend = http::reqwest::Options {
        configure_client: Some(Box::new(move |builder| {
            Ok(builder
                .tls_backend_preconfigured(tls.clone())
                .connect_timeout(Duration::from_secs(30)))
        })),
        configure_request: None,
    };
    Ok(http::Options {
        user_agent: Some(format!("git/2.47.0 (ferride/{})", env!("CARGO_PKG_VERSION"))),
        backend: Some(Arc::new(Mutex::new(backend))),
        ..Default::default()
    })
}

/// Bridges gix's progress tree to a [`ProgressSink`] by polling it on a helper thread.
pub(crate) struct ProgressBridge {
    root: Arc<Root>,
    sink: Option<Arc<dyn ProgressSink>>,
    stop: Arc<AtomicBool>,
    thread: Option<std::thread::JoinHandle<()>>,
}

impl ProgressBridge {
    pub(crate) fn new(sink: Option<Arc<dyn ProgressSink>>) -> Self {
        let root = Root::new();
        let stop = Arc::new(AtomicBool::new(false));
        let thread = sink.clone().map(|sink| {
            let root = Arc::clone(&root);
            let stop = Arc::clone(&stop);
            std::thread::spawn(move || {
                let mut snapshot = Vec::new();
                let mut last: Option<Progress> = None;
                while !stop.load(Ordering::Relaxed) {
                    root.sorted_snapshot(&mut snapshot);
                    let current = snapshot.iter().rev().find_map(|(_, task)| {
                        let value = task.progress.as_ref()?;
                        let step = value.step.load(Ordering::Relaxed) as u64;
                        if step == 0 && value.done_at.is_none() {
                            return None;
                        }
                        Some(Progress {
                            phase: phase_name(&task.name),
                            current: step,
                            total: value.done_at.map(|d| d as u64),
                        })
                    });
                    if let Some(progress) = current
                        && last.as_ref() != Some(&progress)
                    {
                        sink.progress(progress.clone());
                        last = Some(progress);
                    }
                    std::thread::sleep(Duration::from_millis(100));
                }
            })
        });
        ProgressBridge {
            root,
            sink,
            stop,
            thread,
        }
    }

    /// A progress item for one phase; the phase start is reported right away.
    pub(crate) fn item(&self, name: &str) -> Item {
        if let Some(sink) = &self.sink {
            sink.progress(Progress {
                phase: phase_name(name),
                current: 0,
                total: None,
            });
        }
        self.root.add_child(name)
    }
}

impl Drop for ProgressBridge {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

/// gix's task names → git's progress wording.
fn phase_name(name: &str) -> String {
    let lower = name.to_ascii_lowercase();
    if lower.contains("receiv") || lower.contains("read pack") {
        "Receiving objects".into()
    } else if lower.contains("resolv") || lower.contains("index") || lower.contains("decompress") {
        "Resolving deltas".into()
    } else if lower.contains("checkout") || lower.contains("files") {
        "Checking out files".into()
    } else if lower.contains("count") {
        "Counting objects".into()
    } else if lower.contains("negotiat")
        || lower.contains("handshake")
        || lower.contains("remote")
        || lower.contains("connect")
        || lower == "fetch"
    {
        "Connecting".into()
    } else {
        name.to_owned()
    }
}

/// Maps a gix error to ours: cancellation and authentication failures get their own variants.
pub(crate) fn map_net_error(err: impl std::fmt::Display, url: &str, options: &NetworkOptions) -> ScmError {
    if options.cancel.load(Ordering::Relaxed) {
        return ScmError::Cancelled;
    }
    let text = crate::error::sanitize(&format!("{err:#}"));
    let lower = text.to_ascii_lowercase();
    if lower.contains("clear text") {
        return ScmError::InvalidInput(
            "Refusing to send credentials over unencrypted http://. Use an https:// URL.".into(),
        );
    }
    if lower.contains("credentials")
        || lower.contains("authentication")
        || lower.contains("unauthorized")
        || lower.contains("401")
        || lower.contains("403")
        || lower.contains("permission denied")
    {
        return ScmError::Authentication(redact_url(url));
    }
    if lower.contains("interrupt") || lower.contains("cancel") {
        return ScmError::Cancelled;
    }
    ScmError::Git(text)
}

/// `git clone <url> <dest>`: fetches everything and checks out the default branch. `dest` must not exist or be
/// empty; on failure or cancellation it's removed again.
pub fn clone(url: &str, dest: impl AsRef<Path>, options: &NetworkOptions) -> Result<Repository> {
    let dest = dest.as_ref();
    let parsed = gix::url::parse(url.trim().as_bytes().as_bstr())
        .map_err(|_| ScmError::InvalidInput("The URL is not a valid git repository URL.".into()))?;
    check_url(&parsed, options)?;
    if dest.exists() && std::fs::read_dir(dest)?.next().is_some() {
        return Err(ScmError::InvalidInput(format!(
            "Directory '{}' already exists and is not empty.",
            dest.display()
        )));
    }
    let display_url = redact_url(url);
    let http = http_options()?;
    let provider = options.credentials.clone();
    let last_url = Arc::new(Mutex::new(None));
    let mut prepare = gix::clone::PrepareFetch::new(
        parsed,
        dest,
        gix::create::Kind::WithWorktree,
        gix::create::Options::default(),
        crate::open_options(),
    )
    .map_err(|e| map_net_error(e, &display_url, options))?
    .configure_connection(move |conn| {
        conn.set_credentials(credential_fn(provider.clone(), Arc::clone(&last_url)));
        conn.set_transport_options(Box::new(http.clone()));
        Ok(())
    });
    let bridge = ProgressBridge::new(options.progress.clone());
    let (mut checkout, _) = prepare
        .fetch_then_checkout(bridge.item("fetch"), &options.cancel)
        .map_err(|e| map_net_error(e, &display_url, options))?;
    let (repo, _) = checkout
        .main_worktree(bridge.item("checkout"), &options.cancel)
        .map_err(|e| map_net_error(e, &display_url, options))?;
    drop(bridge);
    let workdir = repo
        .workdir()
        .map(Path::to_path_buf)
        .unwrap_or_else(|| dest.to_path_buf());
    drop(repo);
    Repository::open(workdir)
}

impl Repository {
    /// The remote to fetch from / push to when none is given: the current branch's upstream remote, else
    /// `origin`, else the only remote.
    pub(crate) fn default_remote(&self, repo: &gix::Repository, direction: Direction) -> Option<String> {
        if let Some(branch) = repo
            .head()
            .ok()
            .and_then(|h| h.referent_name().map(|n| n.shorten().to_string()))
            && let Some((remote, _)) = upstream_config(repo, &branch)
            && remote != "."
        {
            return Some(remote);
        }
        let names: Vec<String> = repo.remote_names().iter().map(|n| n.to_string()).collect();
        if names.iter().any(|n| n == "origin") {
            return Some("origin".into());
        }
        if names.len() == 1 {
            return names.into_iter().next();
        }
        repo.remote_default_name(direction).map(|n| n.to_string())
    }

    /// Names of the configured remotes.
    pub fn remotes(&self) -> Vec<String> {
        self.repo().remote_names().iter().map(|n| n.to_string()).collect()
    }

    /// `git remote add <name> <url>`.
    pub fn add_remote(&self, name: &str, url: &str) -> Result<()> {
        crate::validate_branch_name(name)?;
        if self.remotes().iter().any(|r| r == name) {
            return Err(ScmError::InvalidInput(format!("Remote '{name}' already exists.")));
        }
        self.set_config(&[
            ("remote", Some(name), "url", url),
            (
                "remote",
                Some(name),
                "fetch",
                &format!("+refs/heads/*:refs/remotes/{name}/*"),
            ),
        ])
    }

    /// `git fetch [<remote>]`: updates the remote-tracking branches.
    pub fn fetch(&self, remote: Option<&str>, options: &NetworkOptions) -> Result<()> {
        let repo = self.repo();
        let name = match remote {
            Some(r) => r.to_owned(),
            None => self
                .default_remote(&repo, Direction::Fetch)
                .ok_or(ScmError::NoRemote("fetch"))?,
        };
        let remote = repo
            .find_remote(name.as_str())
            .map_err(|_| ScmError::NoRemote("fetch"))?;
        let url = remote
            .url(Direction::Fetch)
            .ok_or(ScmError::NoRemote("fetch"))?
            .clone();
        check_url(&url, options)?;
        let display_url = redact_url(&url.to_bstring().to_string());
        let last_url = Arc::new(Mutex::new(None));
        let bridge = ProgressBridge::new(options.progress.clone());
        let connection = remote
            .connect(Direction::Fetch)
            .map_err(|e| map_net_error(e, &display_url, options))?
            .with_credentials(credential_fn(options.credentials.clone(), last_url))
            .with_transport_options(Box::new(http_options()?));
        connection
            .prepare_fetch(bridge.item("negotiate"), Default::default())
            .map_err(|e| map_net_error(e, &display_url, options))?
            .receive(bridge.item("fetch"), &options.cancel)
            .map_err(|e| map_net_error(e, &display_url, options))?;
        Ok(())
    }

    /// `git pull --ff-only`: fetches the upstream's remote, then fast-forwards the current branch (and working
    /// tree) to the upstream. Fails with [`ScmError::NotFastForward`] if the branches diverged.
    pub fn pull(&self, options: &NetworkOptions) -> Result<()> {
        let head = self.head()?;
        let branch = head
            .branch
            .clone()
            .ok_or_else(|| ScmError::InvalidInput("Can't pull: HEAD is detached.".into()))?;
        let (remote, merge) =
            upstream_config(&self.repo(), &branch).ok_or_else(|| ScmError::NoUpstream(branch.clone()))?;
        self.fetch(Some(&remote), options)?;

        let repo = self.repo();
        let tracking = tracking_ref(&remote, &merge);
        let upstream = resolve_ref(&repo, &tracking)
            .ok_or_else(|| ScmError::InvalidInput(format!("Couldn't find remote ref {merge}.")))?;
        let local = head_commit_id(&repo)?;
        if local == Some(upstream) {
            return Ok(());
        }
        if let Some(local) = local {
            if is_ancestor(&repo, upstream, local) {
                return Ok(()); // Only ahead: nothing to pull.
            }
            if !commit_exists(&repo, local) || !is_ancestor(&repo, local, upstream) {
                return Err(ScmError::NotFastForward);
            }
        }
        let from_tree = local.map(|l| commit_tree_id(&repo, l)).transpose()?;
        let to_tree = commit_tree_id(&repo, upstream)?;
        self.switch_tree(&repo, from_tree, Some(to_tree))?;
        let expected = match local {
            Some(l) => gix::refs::transaction::PreviousValue::MustExistAndMatch(gix::refs::Target::Object(l)),
            None => gix::refs::transaction::PreviousValue::Any,
        };
        repo.reference(
            format!("refs/heads/{branch}").as_str(),
            upstream,
            expected,
            BString::from(format!(
                "pull: Fast-forward to {}",
                tracking.trim_start_matches("refs/remotes/")
            )),
        )
        .map_err(ScmError::git)?;
        Ok(())
    }
}
