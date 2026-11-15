use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, RwLock};

use ferride_host::{
    BridgeSpawned, FerruxHost, HostBridge as RustHostBridge, HostError, HostProcess, HostStatus, ProcessHost,
    PtySize, SpawnSpec, UnavailableHost,
};

use crate::{FerrideError, FerruxStatus};

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct EnvEntry {
    pub key: String,
    pub value: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BridgeSpawnSpec {
    pub argv: Vec<String>,
    pub env: Vec<EnvEntry>,
    pub cwd: Option<String>,
    pub pty_rows: Option<i32>,
    pub pty_cols: Option<i32>,
    pub merge_stderr: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SpawnedFds {
    pub handle: i64,
    pub stdin_fd: i32,
    pub stdout_fd: i32,
    pub stderr_fd: i32,
    pub pty_fd: i32,
    pub pty_is_socket: bool,
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum HostBridgeError {
    #[error("{msg}")]
    NotInstalled { msg: String },
    #[error("{msg}")]
    NotPaired { msg: String },
    #[error("{msg}")]
    Unavailable { msg: String },
    #[error("{msg}")]
    Unsupported { msg: String },
    #[error("{msg}")]
    Failed { msg: String },
}

impl From<uniffi::UnexpectedUniFFICallbackError> for HostBridgeError {
    fn from(e: uniffi::UnexpectedUniFFICallbackError) -> Self {
        Self::Failed { msg: e.reason }
    }
}

impl From<HostBridgeError> for HostError {
    fn from(e: HostBridgeError) -> Self {
        match e {
            HostBridgeError::NotInstalled { .. } => HostError::NotInstalled,
            HostBridgeError::NotPaired { .. } => HostError::NotPaired,
            HostBridgeError::Unavailable { msg } => HostError::Unavailable(msg),
            HostBridgeError::Unsupported { msg } => HostError::Unsupported(msg),
            HostBridgeError::Failed { msg } => HostError::Spawn(msg),
        }
    }
}

/// Callback implemented by Kotlin (`FerruxConnector`) to run programs in Ferrux
/// over `dev.ferrux.api.IFerruxApi`.
#[uniffi::export(callback_interface)]
pub trait HostBridge: Send + Sync {
    fn status(&self) -> FerruxStatus;
    fn spawn(&self, spec: BridgeSpawnSpec) -> Result<SpawnedFds, HostBridgeError>;
    fn resize(&self, handle: i64, rows: i32, cols: i32) -> Result<(), HostBridgeError>;
    fn signal(&self, handle: i64, signo: i32) -> Result<(), HostBridgeError>;
    fn kill(&self, handle: i64) -> Result<(), HostBridgeError>;
    fn close(&self, handle: i64);
    fn wait(&self, handle: i64) -> Result<i32, HostBridgeError>;
}

struct BridgeAdapter(Box<dyn HostBridge>);

fn to_host_status(s: FerruxStatus) -> HostStatus {
    match s {
        FerruxStatus::NotInstalled => HostStatus::NotInstalled,
        FerruxStatus::NotPaired => HostStatus::NotPaired,
        FerruxStatus::Unavailable { reason } => HostStatus::Unavailable { reason },
        FerruxStatus::Ready { description } => HostStatus::Ready { description },
    }
}

impl RustHostBridge for BridgeAdapter {
    fn status(&self) -> HostStatus {
        to_host_status(self.0.status())
    }

    fn spawn(&self, spec: &SpawnSpec) -> Result<BridgeSpawned, HostError> {
        let ffi_spec = BridgeSpawnSpec {
            argv: spec.argv.clone(),
            env: spec
                .env
                .iter()
                .map(|(k, v)| EnvEntry {
                    key: k.clone(),
                    value: v.clone(),
                })
                .collect(),
            cwd: spec.cwd.clone(),
            pty_rows: spec.pty.map(|p| i32::from(p.rows)),
            pty_cols: spec.pty.map(|p| i32::from(p.cols)),
            merge_stderr: spec.merge_stderr,
        };
        let spawned = self.0.spawn(ffi_spec)?;
        Ok(BridgeSpawned {
            handle: spawned.handle as u64,
            stdin_fd: spawned.stdin_fd,
            stdout_fd: spawned.stdout_fd,
            stderr_fd: spawned.stderr_fd,
            pty_fd: spawned.pty_fd,
            pty_is_socket: spawned.pty_is_socket,
        })
    }

    fn resize(&self, handle: u64, size: PtySize) -> Result<(), HostError> {
        self.0
            .resize(handle as i64, i32::from(size.rows), i32::from(size.cols))
            .map_err(Into::into)
    }

    fn signal(&self, handle: u64, signo: i32) -> Result<(), HostError> {
        self.0.signal(handle as i64, signo).map_err(Into::into)
    }

    fn kill(&self, handle: u64) -> Result<(), HostError> {
        self.0.kill(handle as i64).map_err(Into::into)
    }

    fn close(&self, handle: u64) {
        self.0.close(handle as i64);
    }

    fn wait(&self, handle: u64) -> Result<i32, HostError> {
        self.0.wait(handle as i64).map_err(Into::into)
    }
}

static HOST: RwLock<Option<Arc<FerruxHost>>> = RwLock::new(None);

/// Registers the Kotlin-side [`HostBridge`] (`FerruxConnector`) as Ferride's process host.
#[uniffi::export]
pub fn register_host_bridge(bridge: Box<dyn HostBridge>) {
    let host = Arc::new(FerruxHost::new(Arc::new(BridgeAdapter(bridge))));
    *HOST.write().unwrap_or_else(|e| e.into_inner()) = Some(host);
}

#[cfg(test)]
pub(crate) fn clear_host_bridge_for_test() {
    *HOST.write().unwrap_or_else(|e| e.into_inner()) = None;
}

pub(crate) fn current_ferrux_host() -> Option<Arc<FerruxHost>> {
    HOST.read().unwrap_or_else(|e| e.into_inner()).clone()
}

pub(crate) fn current_host_status() -> HostStatus {
    match current_ferrux_host() {
        Some(h) => h.status(),
        None => UnavailableHost.status(),
    }
}

fn clamp_u16(v: i32) -> u16 {
    v.clamp(1, i32::from(u16::MAX)) as u16
}

/// A terminal session running inside Ferrux whose VT screen is hosted by
/// `ferrux-jni-core`'s process-wide `SessionManager`.
#[derive(uniffi::Object)]
pub struct TerminalSession {
    session_id: i64,
    handle: u64,
    bridge: Arc<dyn RustHostBridge>,
    // Kept solely for Drop cleanup; `wait`/`kill`/`resize` go through `bridge`
    // directly so a blocking `wait` never deadlocks `kill` or `close`.
    process: Mutex<Option<Box<dyn HostProcess>>>,
    closed: AtomicBool,
}

impl TerminalSession {
    pub(crate) fn spawn_with_host(
        host: &FerruxHost,
        argv: Vec<String>,
        env: Vec<EnvEntry>,
        cwd: Option<String>,
        rows: i32,
        cols: i32,
    ) -> Result<Arc<Self>, FerrideError> {
        #[cfg(unix)]
        {
            use std::os::fd::IntoRawFd;

            let rows_u16 = clamp_u16(rows);
            let cols_u16 = clamp_u16(cols);
            let spec = SpawnSpec {
                argv,
                env: env.into_iter().map(|e| (e.key, e.value)).collect(),
                cwd,
                pty: Some(PtySize {
                    rows: rows_u16,
                    cols: cols_u16,
                }),
                merge_stderr: false,
            };
            let mut proc = host.spawn(&spec)?;
            let handle = proc.id();
            let pty_fd = proc.take_pty_fd().ok_or_else(|| FerrideError::HostUnavailable {
                msg: "Ferrux process did not return a PTY fd".into(),
            })?;
            let raw_fd = pty_fd.into_raw_fd();
            let session_id = ferrux_jni_core::core::attach_pty(raw_fd, rows_u16, cols_u16)
                .map_err(|e| FerrideError::Io { msg: e.to_string() })?;
            Ok(Arc::new(Self {
                session_id: session_id as i64,
                handle,
                bridge: Arc::clone(host.bridge()),
                process: Mutex::new(Some(proc)),
                closed: AtomicBool::new(false),
            }))
        }
        #[cfg(not(unix))]
        {
            let _ = (host, argv, env, cwd, rows, cols);
            Err(FerrideError::HostUnavailable {
                msg: "Terminal sessions require Unix file descriptors".into(),
            })
        }
    }
}

#[uniffi::export]
impl TerminalSession {
    /// Spawns a PTY process inside Ferrux and attaches its master fd to `ferrux-jni-core`.
    #[uniffi::constructor]
    pub fn spawn(
        argv: Vec<String>,
        env: Vec<EnvEntry>,
        cwd: Option<String>,
        rows: i32,
        cols: i32,
    ) -> Result<Arc<Self>, FerrideError> {
        let host = current_ferrux_host().ok_or_else(|| FerrideError::HostUnavailable {
            msg: HostError::NotInstalled.to_string(),
        })?;
        Self::spawn_with_host(&host, argv, env, cwd, rows, cols)
    }

    /// `ferrux-jni-core` session id for `NativeSessionBackend` / `TerminalNative`.
    pub fn session_id(&self) -> i64 {
        self.session_id
    }

    pub fn process_id(&self) -> i64 {
        self.handle as i64
    }

    pub fn resize(&self, rows: i32, cols: i32) -> Result<(), FerrideError> {
        let r = clamp_u16(rows);
        let c = clamp_u16(cols);
        ferrux_jni_core::core::resize(self.session_id, i32::from(r), i32::from(c));
        self.bridge.resize(self.handle, PtySize { rows: r, cols: c })?;
        Ok(())
    }

    pub fn signal(&self, signo: i32) -> Result<(), FerrideError> {
        Ok(self.bridge.signal(self.handle, signo)?)
    }

    pub fn kill(&self) -> Result<(), FerrideError> {
        Ok(self.bridge.kill(self.handle)?)
    }

    /// Blocks until the remote process exits and returns its exit code (or `-signal`).
    pub fn wait(&self) -> Result<i32, FerrideError> {
        Ok(self.bridge.wait(self.handle)?)
    }

    pub fn close(&self) {
        if self.closed.swap(true, Ordering::SeqCst) {
            return;
        }
        ferrux_jni_core::core::destroy_session(self.session_id);
        let proc = self.process.lock().unwrap_or_else(|e| e.into_inner()).take();
        drop(proc);
    }
}

impl Drop for TerminalSession {
    fn drop(&mut self) {
        self.close();
    }
}
