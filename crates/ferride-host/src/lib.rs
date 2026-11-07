//! Where Ferride's processes run.
//!
//! Ferride never executes programs itself: on Android an app with
//! targetSdk ≥ 29 can't `execve` anything it downloaded (see
//! `docs/RESEARCH.md` §4.2). Terminals, language servers, git hooks and build
//! tools run inside **Ferrux**, which exposes a signature-protected AIDL
//! service. The Kotlin side binds that service and hands the resulting file
//! descriptors (a socketpair per process, PTY kept inside Ferrux) to Rust.
//!
//! This crate is the Rust-side contract: a [`ProcessHost`] spawns
//! [`HostProcess`]es and gives back byte streams. Implementations:
//!
//! - [`UnavailableHost`] — the stub used until the Ferrux Host API exists;
//!   every call reports [`HostStatus::NotInstalled`].
//! - [`LocalHost`] (non-Android only) — spawns with `std::process` for desktop
//!   development and tests.
//! - `FerruxHost` (planned) — wraps fds received from Ferrux's AIDL service.

use std::io::{Read, Write};

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

/// What to run. Mirrors `SpawnSpec` in the Ferrux Host API sketch.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SpawnSpec {
    pub argv: Vec<String>,
    pub env: Vec<(String, String)>,
    pub cwd: Option<String>,
    /// Allocate a PTY (terminals) instead of plain pipes (language servers).
    pub pty: Option<PtySize>,
}

#[derive(Debug, thiserror::Error)]
pub enum HostError {
    #[error("Ferrux is not installed")]
    NotInstalled,
    #[error("Ferrux has not granted Ferride access")]
    NotPaired,
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
    /// Returns `None` after the first call.
    fn take_io(&mut self) -> Option<(Reader, Writer)>;
    fn resize(&mut self, size: PtySize) -> Result<(), HostError>;
    fn kill(&mut self) -> Result<(), HostError>;
    /// Blocks until the process exits and returns its exit code (or `-signal`).
    fn wait(&mut self) -> Result<i32, HostError>;
}

/// Spawns processes somewhere Ferride is allowed to run them.
pub trait ProcessHost: Send + Sync {
    fn status(&self) -> HostStatus;
    fn spawn(&self, spec: &SpawnSpec) -> Result<Box<dyn HostProcess>, HostError>;
}

/// Stub host used until Ferrux's Host API lands.
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
}
