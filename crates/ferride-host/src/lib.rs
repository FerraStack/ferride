//! Where Ferride's processes run.
//!
//! Ferride never executes programs itself: on Android an app with
//! targetSdk ≥ 29 can't `execve` anything it downloaded (see
//! `docs/RESEARCH.md` §4.2). Terminals, language servers, git hooks and build
//! tools run inside **Ferrux**, which exposes a signature-protected AIDL
//! service (`dev.ferrux.api.IFerruxApi`). The Kotlin side binds that service
//! (`FerruxConnector`), implements [`HostBridge`], and hands the resulting file
//! descriptors (pipes or PTY master fd) to Rust.
//!
//! This crate is the Rust-side contract: a [`ProcessHost`] spawns
//! [`HostProcess`]es and gives back byte streams. Implementations:
//!
//! - [`UnavailableHost`] — stub reporting [`HostStatus::NotInstalled`].
//! - [`LocalHost`] (non-Android only) — spawns with `std::process` for desktop
//!   development and tests.
//! - [`FerruxHost`] — delegates lifecycle calls through [`HostBridge`] and
//!   takes ownership of the detached file descriptors from Ferrux.

use std::io::{Read, Write};
use std::sync::Arc;

/// Availability of the process host, shown in the UI (terminal panel,
/// language-server status).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum HostStatus {
    /// Ferrux isn't installed on the device.
    NotInstalled,
    /// Ferrux is installed but hasn't granted Ferride access yet.
    NotPaired,
    /// The host exists but can't be used right now.
    Unavailable {
        reason: String,
    },
    Ready {
        description: String,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PtySize {
    pub rows: u16,
    pub cols: u16,
}

/// What to run. Mirrors `SpawnRequest` in the Ferrux API v1.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SpawnSpec {
    pub argv: Vec<String>,
    pub env: Vec<(String, String)>,
    pub cwd: Option<String>,
    /// Allocate a PTY (terminals) instead of plain pipes (language servers).
    pub pty: Option<PtySize>,
    /// In pipes mode, redirect stderr onto stdout (`true`) or keep a separate
    /// stderr pipe (`false`). Ignored for PTYs.
    pub merge_stderr: bool,
}

#[derive(Debug, thiserror::Error)]
pub enum HostError {
    #[error("Ferrux is not installed")]
    NotInstalled,
    #[error("Ferrux has not granted Ferride access")]
    NotPaired,
    #[error("Ferrux is unavailable: {0}")]
    Unavailable(String),
    #[error("unsupported: {0}")]
    Unsupported(String),
    #[error("spawn failed: {0}")]
    Spawn(String),
    #[error(transparent)]
    Io(#[from] std::io::Error),
}

pub type Reader = Box<dyn Read + Send>;
pub type Writer = Box<dyn Write + Send>;

/// A running process in the host.
pub trait HostProcess: Send {
    fn id(&self) -> u64;
    /// Takes the process' stdout (merged with stderr for PTYs) and stdin.
    /// Returns `None` after the first call (or if [`Self::take_pty_fd`] already took the PTY).
    fn take_io(&mut self) -> Option<(Reader, Writer)>;
    /// Takes the process' separate stderr pipe in pipes mode (`merge_stderr = false`).
    fn take_stderr(&mut self) -> Option<Reader> {
        None
    }
    /// Takes ownership of the raw PTY master (or socket) fd when spawned with `pty = Some(...)`.
    /// Returns `None` after the first call or if [`Self::take_io`] already took it.
    #[cfg(unix)]
    fn take_pty_fd(&mut self) -> Option<std::os::fd::OwnedFd> {
        None
    }
    fn resize(&mut self, size: PtySize) -> Result<(), HostError>;
    fn signal(&mut self, _signo: i32) -> Result<(), HostError> {
        Err(HostError::Unsupported(
            "signals are not supported by this host".into(),
        ))
    }
    fn kill(&mut self) -> Result<(), HostError>;
    /// Blocks until the process exits and returns its exit code (or `-signal`).
    fn wait(&mut self) -> Result<i32, HostError>;
}

/// Spawns processes somewhere Ferride is allowed to run them.
pub trait ProcessHost: Send + Sync {
    fn status(&self) -> HostStatus;
    fn spawn(&self, spec: &SpawnSpec) -> Result<Box<dyn HostProcess>, HostError>;
}

/// Stub host used when no [`HostBridge`] is registered.
#[derive(Debug, Default, Clone, Copy)]
pub struct UnavailableHost;

impl ProcessHost for UnavailableHost {
    fn status(&self) -> HostStatus {
        HostStatus::NotInstalled
    }

    fn spawn(&self, _spec: &SpawnSpec) -> Result<Box<dyn HostProcess>, HostError> {
        Err(HostError::NotInstalled)
    }
}

/// Raw file descriptors detached from a `FerruxProcess`. `-1` means absent.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BridgeSpawned {
    pub handle: u64,
    pub stdin_fd: i32,
    pub stdout_fd: i32,
    pub stderr_fd: i32,
    pub pty_fd: i32,
    pub pty_is_socket: bool,
}

/// Kotlin-side Binder bridge (`FerruxConnector`) backing [`FerruxHost`].
///
/// The bridge retains `FerruxProcess` handles for Binder control calls
/// (`resize`, `signal`, `kill`, `close`, `wait`) and hands detached file
/// descriptors to Rust on `spawn`.
pub trait HostBridge: Send + Sync {
    fn status(&self) -> HostStatus;
    fn spawn(&self, spec: &SpawnSpec) -> Result<BridgeSpawned, HostError>;
    fn resize(&self, handle: u64, size: PtySize) -> Result<(), HostError>;
    fn signal(&self, handle: u64, signo: i32) -> Result<(), HostError>;
    fn kill(&self, handle: u64) -> Result<(), HostError>;
    fn close(&self, handle: u64);
    /// Blocks until the process exits and returns its exit code (or `-signal`).
    fn wait(&self, handle: u64) -> Result<i32, HostError>;
}

/// [`ProcessHost`] backed by Ferrux API v1 over [`HostBridge`].
#[derive(Clone)]
pub struct FerruxHost {
    bridge: Arc<dyn HostBridge>,
}

impl std::fmt::Debug for FerruxHost {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("FerruxHost")
            .field("status", &self.bridge.status())
            .finish()
    }
}

impl FerruxHost {
    pub fn new(bridge: Arc<dyn HostBridge>) -> Self {
        Self { bridge }
    }

    pub fn bridge(&self) -> &Arc<dyn HostBridge> {
        &self.bridge
    }
}

#[cfg(unix)]
mod ferrux_unix {
    use std::fs::File;
    use std::os::fd::{FromRawFd, OwnedFd};

    use super::*;

    struct FerruxProcess {
        bridge: Arc<dyn HostBridge>,
        handle: u64,
        io: Option<(OwnedFd, OwnedFd)>,
        stderr: Option<OwnedFd>,
        pty: Option<OwnedFd>,
    }

    impl FerruxProcess {
        fn new(bridge: Arc<dyn HostBridge>, spawned: BridgeSpawned, is_pty: bool) -> Result<Self, HostError> {
            let handle = spawned.handle;
            // Take ownership of all non-negative fds immediately so no fd leaks on validation errors.
            let adopt = |fd: i32| -> Option<OwnedFd> {
                // SAFETY: `HostBridge::spawn` transferred ownership of these detached fds to us.
                (fd >= 0).then(|| unsafe { OwnedFd::from_raw_fd(fd) })
            };
            let stdin = adopt(spawned.stdin_fd);
            let stdout = adopt(spawned.stdout_fd);
            let stderr = adopt(spawned.stderr_fd);
            let pty = adopt(spawned.pty_fd);

            if is_pty {
                let Some(pty) = pty else {
                    bridge.close(handle);
                    return Err(HostError::Spawn("Ferrux returned no PTY descriptor".into()));
                };
                return Ok(Self {
                    bridge,
                    handle,
                    io: None,
                    stderr: None,
                    pty: Some(pty),
                });
            }

            let (Some(stdout), Some(stdin)) = (stdout, stdin) else {
                bridge.close(handle);
                return Err(HostError::Spawn(
                    "Ferrux returned missing stdin/stdout pipe descriptors".into(),
                ));
            };
            Ok(Self {
                bridge,
                handle,
                io: Some((stdout, stdin)),
                stderr,
                pty: None,
            })
        }
    }

    impl HostProcess for FerruxProcess {
        fn id(&self) -> u64 {
            self.handle
        }

        fn take_io(&mut self) -> Option<(Reader, Writer)> {
            if let Some(pty) = self.pty.take() {
                let writer = pty.try_clone().ok()?;
                return Some((Box::new(File::from(pty)), Box::new(File::from(writer))));
            }
            let (out, inp) = self.io.take()?;
            Some((Box::new(File::from(out)), Box::new(File::from(inp))))
        }

        fn take_stderr(&mut self) -> Option<Reader> {
            self.stderr.take().map(|fd| Box::new(File::from(fd)) as Reader)
        }

        fn take_pty_fd(&mut self) -> Option<OwnedFd> {
            self.pty.take()
        }

        fn resize(&mut self, size: PtySize) -> Result<(), HostError> {
            self.bridge.resize(self.handle, size)
        }

        fn signal(&mut self, signo: i32) -> Result<(), HostError> {
            self.bridge.signal(self.handle, signo)
        }

        fn kill(&mut self) -> Result<(), HostError> {
            self.bridge.kill(self.handle)
        }

        fn wait(&mut self) -> Result<i32, HostError> {
            self.bridge.wait(self.handle)
        }
    }

    impl Drop for FerruxProcess {
        fn drop(&mut self) {
            // Drop fds before asking Ferrux to hang up the process.
            self.io.take();
            self.stderr.take();
            self.pty.take();
            self.bridge.close(self.handle);
        }
    }

    impl ProcessHost for FerruxHost {
        fn status(&self) -> HostStatus {
            self.bridge.status()
        }

        fn spawn(&self, spec: &SpawnSpec) -> Result<Box<dyn HostProcess>, HostError> {
            if spec.argv.is_empty() {
                return Err(HostError::Spawn("empty argv".into()));
            }
            let spawned = self.bridge.spawn(spec)?;
            let proc = FerruxProcess::new(Arc::clone(&self.bridge), spawned, spec.pty.is_some())?;
            Ok(Box::new(proc))
        }
    }
}

#[cfg(not(unix))]
impl ProcessHost for FerruxHost {
    fn status(&self) -> HostStatus {
        self.bridge.status()
    }

    fn spawn(&self, _spec: &SpawnSpec) -> Result<Box<dyn HostProcess>, HostError> {
        Err(HostError::Unsupported(
            "FerruxHost requires Unix file descriptors".into(),
        ))
    }
}

#[cfg(not(target_os = "android"))]
pub use local::LocalHost;

#[cfg(not(target_os = "android"))]
mod local {
    use std::process::{Child, Command, Stdio};

    use super::*;

    /// Desktop-only host: runs programs directly with pipes (no PTY).
    #[derive(Debug, Default, Clone, Copy)]
    pub struct LocalHost;

    struct LocalProcess {
        child: Child,
    }

    impl HostProcess for LocalProcess {
        fn id(&self) -> u64 {
            u64::from(self.child.id())
        }

        fn take_io(&mut self) -> Option<(Reader, Writer)> {
            let out = self.child.stdout.take()?;
            let inp = self.child.stdin.take()?;
            Some((Box::new(out), Box::new(inp)))
        }

        fn resize(&mut self, _size: PtySize) -> Result<(), HostError> {
            Err(HostError::Unsupported("LocalHost has no PTYs".into()))
        }

        fn kill(&mut self) -> Result<(), HostError> {
            Ok(self.child.kill()?)
        }

        fn wait(&mut self) -> Result<i32, HostError> {
            let status = self.child.wait()?;
            Ok(status.code().unwrap_or(-1))
        }
    }

    impl ProcessHost for LocalHost {
        fn status(&self) -> HostStatus {
            HostStatus::Ready {
                description: "local processes (development host)".into(),
            }
        }

        fn spawn(&self, spec: &SpawnSpec) -> Result<Box<dyn HostProcess>, HostError> {
            if spec.pty.is_some() {
                return Err(HostError::Unsupported("LocalHost has no PTYs".into()));
            }
            let (program, args) = spec
                .argv
                .split_first()
                .ok_or_else(|| HostError::Spawn("empty argv".into()))?;
            let mut cmd = Command::new(program);
            cmd.args(args)
                .envs(spec.env.iter().map(|(k, v)| (k, v)))
                .stdin(Stdio::piped())
                .stdout(Stdio::piped())
                .stderr(Stdio::inherit());
            if let Some(cwd) = &spec.cwd {
                cmd.current_dir(cwd);
            }
            let child = cmd.spawn().map_err(|e| HostError::Spawn(e.to_string()))?;
            Ok(Box::new(LocalProcess { child }))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stub_reports_not_installed() {
        let host = UnavailableHost;
        assert_eq!(host.status(), HostStatus::NotInstalled);
        assert!(matches!(
            host.spawn(&SpawnSpec::default()),
            Err(HostError::NotInstalled)
        ));
    }

    #[cfg(unix)]
    #[test]
    fn local_host_round_trip() {
        let host = LocalHost;
        let mut p = host
            .spawn(&SpawnSpec {
                argv: vec!["cat".into()],
                ..Default::default()
            })
            .unwrap();
        let (mut out, mut inp) = p.take_io().unwrap();
        inp.write_all(b"ping").unwrap();
        drop(inp);
        let mut s = String::new();
        out.read_to_string(&mut s).unwrap();
        assert_eq!(s, "ping");
        assert_eq!(p.wait().unwrap(), 0);
        assert!(p.take_io().is_none());
    }

    #[cfg(unix)]
    mod ferrux_tests {
        use std::os::fd::IntoRawFd;
        use std::os::unix::net::UnixStream;
        use std::sync::Mutex;

        use super::*;

        #[derive(Default)]
        struct MockBridge {
            calls: Mutex<Vec<String>>,
            next_spawn: Mutex<Option<BridgeSpawned>>,
            wait_result: Mutex<i32>,
        }

        impl HostBridge for MockBridge {
            fn status(&self) -> HostStatus {
                HostStatus::Ready {
                    description: "Ferrux mock".into(),
                }
            }

            fn spawn(&self, spec: &SpawnSpec) -> Result<BridgeSpawned, HostError> {
                self.calls.lock().unwrap().push(format!(
                    "spawn:{:?}:pty={:?}:merge={}",
                    spec.argv, spec.pty, spec.merge_stderr
                ));
                self.next_spawn
                    .lock()
                    .unwrap()
                    .take()
                    .ok_or_else(|| HostError::Spawn("no mock spawn prepared".into()))
            }

            fn resize(&self, handle: u64, size: PtySize) -> Result<(), HostError> {
                self.calls
                    .lock()
                    .unwrap()
                    .push(format!("resize:{handle}:{}x{}", size.rows, size.cols));
                Ok(())
            }

            fn signal(&self, handle: u64, signo: i32) -> Result<(), HostError> {
                self.calls
                    .lock()
                    .unwrap()
                    .push(format!("signal:{handle}:{signo}"));
                Ok(())
            }

            fn kill(&self, handle: u64) -> Result<(), HostError> {
                self.calls.lock().unwrap().push(format!("kill:{handle}"));
                Ok(())
            }

            fn close(&self, handle: u64) {
                self.calls.lock().unwrap().push(format!("close:{handle}"));
            }

            fn wait(&self, handle: u64) -> Result<i32, HostError> {
                self.calls.lock().unwrap().push(format!("wait:{handle}"));
                Ok(*self.wait_result.lock().unwrap())
            }
        }

        #[test]
        fn ferrux_host_pipes_and_stderr_and_lifecycle() {
            let (in_r, in_w) = std::io::pipe().unwrap();
            let (out_r, out_w) = std::io::pipe().unwrap();
            let (err_r, err_w) = std::io::pipe().unwrap();

            let bridge = Arc::new(MockBridge::default());
            *bridge.next_spawn.lock().unwrap() = Some(BridgeSpawned {
                handle: 42,
                stdin_fd: std::os::fd::OwnedFd::from(in_w).into_raw_fd(),
                stdout_fd: std::os::fd::OwnedFd::from(out_r).into_raw_fd(),
                stderr_fd: std::os::fd::OwnedFd::from(err_r).into_raw_fd(),
                pty_fd: -1,
                pty_is_socket: false,
            });
            *bridge.wait_result.lock().unwrap() = -15;

            let host = FerruxHost::new(bridge.clone());
            assert!(matches!(host.status(), HostStatus::Ready { .. }));

            let mut proc = host
                .spawn(&SpawnSpec {
                    argv: vec!["rust-analyzer".into()],
                    merge_stderr: false,
                    ..Default::default()
                })
                .unwrap();
            assert_eq!(proc.id(), 42);

            let (mut out, mut inp) = proc.take_io().unwrap();
            assert!(proc.take_io().is_none());
            let mut err = proc.take_stderr().unwrap();
            assert!(proc.take_stderr().is_none());

            inp.write_all(b"req").unwrap();
            drop(inp);
            let mut buf = String::new();
            let mut in_r = in_r;
            in_r.read_to_string(&mut buf).unwrap();
            assert_eq!(buf, "req");

            let mut out_w = out_w;
            out_w.write_all(b"resp").unwrap();
            drop(out_w);
            buf.clear();
            out.read_to_string(&mut buf).unwrap();
            assert_eq!(buf, "resp");

            let mut err_w = err_w;
            err_w.write_all(b"warn").unwrap();
            drop(err_w);
            buf.clear();
            err.read_to_string(&mut buf).unwrap();
            assert_eq!(buf, "warn");

            proc.resize(PtySize { rows: 24, cols: 80 }).unwrap();
            proc.signal(2).unwrap();
            proc.kill().unwrap();
            assert_eq!(proc.wait().unwrap(), -15);
            drop(proc);

            let calls = bridge.calls.lock().unwrap().clone();
            assert!(calls.contains(&"resize:42:24x80".to_string()));
            assert!(calls.contains(&"signal:42:2".to_string()));
            assert!(calls.contains(&"kill:42".to_string()));
            assert!(calls.contains(&"wait:42".to_string()));
            assert_eq!(calls.last().unwrap(), "close:42");
        }

        #[test]
        fn ferrux_host_pty_take_io_and_take_pty_fd() {
            let (a1, mut b1) = UnixStream::pair().unwrap();
            let bridge = Arc::new(MockBridge::default());
            *bridge.next_spawn.lock().unwrap() = Some(BridgeSpawned {
                handle: 7,
                stdin_fd: -1,
                stdout_fd: -1,
                stderr_fd: -1,
                pty_fd: std::os::fd::OwnedFd::from(a1).into_raw_fd(),
                pty_is_socket: false,
            });
            let host = FerruxHost::new(bridge.clone());
            let mut proc = host
                .spawn(&SpawnSpec {
                    argv: vec!["/system/bin/sh".into()],
                    pty: Some(PtySize { rows: 24, cols: 80 }),
                    ..Default::default()
                })
                .unwrap();

            let (mut r, mut w) = proc.take_io().unwrap();
            assert!(proc.take_pty_fd().is_none(), "take_io consumes the pty fd");
            w.write_all(b"hi").unwrap();
            let mut buf = [0u8; 2];
            b1.read_exact(&mut buf).unwrap();
            assert_eq!(&buf, b"hi");
            b1.write_all(b"ok").unwrap();
            r.read_exact(&mut buf).unwrap();
            assert_eq!(&buf, b"ok");
            drop(proc);

            // Second spawn: take_pty_fd directly
            let (a2, _b2) = UnixStream::pair().unwrap();
            *bridge.next_spawn.lock().unwrap() = Some(BridgeSpawned {
                handle: 8,
                stdin_fd: -1,
                stdout_fd: -1,
                stderr_fd: -1,
                pty_fd: std::os::fd::OwnedFd::from(a2).into_raw_fd(),
                pty_is_socket: false,
            });
            let mut proc2 = host
                .spawn(&SpawnSpec {
                    argv: vec!["/system/bin/sh".into()],
                    pty: Some(PtySize { rows: 24, cols: 80 }),
                    ..Default::default()
                })
                .unwrap();
            let fd = proc2.take_pty_fd().unwrap();
            drop(fd);
            assert!(proc2.take_io().is_none());
        }

        #[test]
        fn ferrux_host_rejects_missing_fds_and_closes_handle() {
            let bridge = Arc::new(MockBridge::default());
            *bridge.next_spawn.lock().unwrap() = Some(BridgeSpawned {
                handle: 99,
                stdin_fd: -1,
                stdout_fd: -1,
                stderr_fd: -1,
                pty_fd: -1,
                pty_is_socket: false,
            });
            let host = FerruxHost::new(bridge.clone());
            let err = host
                .spawn(&SpawnSpec {
                    argv: vec!["/system/bin/sh".into()],
                    pty: Some(PtySize { rows: 24, cols: 80 }),
                    ..Default::default()
                })
                .err()
                .unwrap();
            assert!(matches!(err, HostError::Spawn(_)));
            assert!(bridge.calls.lock().unwrap().contains(&"close:99".to_string()));
        }
    }
}
