//! `git push` over the smart protocol (gix has no push yet): ref advertisement via `git-receive-pack`, a pack
//! of the new objects built with gix-pack, and the `report-status` answer.

use std::io::{BufRead, Write};
use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex};

use gix::ObjectId;
use gix::protocol::handshake::Ref;
use gix::protocol::transport::client::blocking_io::Transport;
use gix::protocol::transport::client::{MessageKind, WriteMode};
use gix::protocol::transport::{Protocol, Service};
use gix::refs::transaction::PreviousValue;
use gix::remote::Direction;
use gix_pack::data::output;

use crate::branch::{is_ancestor, upstream_config};
use crate::net::{
    NetworkOptions, ProgressBridge, check_url, credential_fn, http_options, map_net_error, redact_url,
};
use crate::worktree::head_commit_id;
use crate::{Repository, Result, ScmError};

/// What to push.
#[derive(Debug, Clone, Default)]
pub struct PushOptions {
    /// Remote name; default: the branch's upstream remote, else `origin`.
    pub remote: Option<String>,
    /// Record the pushed branch as upstream (`git push -u`, VS Code's "Publish Branch").
    pub set_upstream: bool,
    /// Overwrite the remote branch even if that loses commits (`--force`). VS Code asks before doing this.
    pub force: bool,
}

impl Repository {
    /// Pushes the current branch to its upstream (or `refs/heads/<branch>` on the remote when publishing).
    ///
    /// Fails with [`ScmError::NoUpstream`] if the branch has no upstream and `set_upstream` isn't set, and with
    /// [`ScmError::PushRejectedNonFastForward`] if the remote has commits we don't (pull first).
    pub fn push(&self, push: &PushOptions, options: &NetworkOptions) -> Result<()> {
        let repo = self.repo();
        let head = repo.head().map_err(ScmError::git)?;
        let branch = head
            .referent_name()
            .map(|n| n.shorten().to_string())
            .ok_or_else(|| ScmError::InvalidInput("Can't push: HEAD is detached.".into()))?;
        let local = head_commit_id(&repo)?.ok_or_else(|| {
            ScmError::InvalidInput("There are no commits on this branch to push yet.".into())
        })?;

        let upstream = upstream_config(&repo, &branch);
        let remote_name = match (&push.remote, &upstream) {
            (Some(r), _) => r.clone(),
            (None, Some((r, _))) => r.clone(),
            (None, None) => self
                .default_remote(&repo, Direction::Push)
                .ok_or(ScmError::NoRemote("push to"))?,
        };
        let remote_ref = match &upstream {
            Some((r, merge)) if *r == remote_name => merge.clone(),
            _ if push.set_upstream => format!("refs/heads/{branch}"),
            _ => return Err(ScmError::NoUpstream(branch)),
        };
        let remote = repo
            .find_remote(remote_name.as_str())
            .map_err(|_| ScmError::NoRemote("push to"))?;
        let url = remote
            .url(Direction::Push)
            .or_else(|| remote.url(Direction::Fetch))
            .ok_or(ScmError::NoRemote("push to"))?
            .clone();
        check_url(&url, options)?;
        let display_url = redact_url(&url.to_bstring().to_string());
        let net_err = |e: &dyn std::fmt::Display| map_net_error(e, &display_url, options);

        // 1. Ref advertisement (asks the app for credentials on 401).
        let mut transport = gix::protocol::transport::client::blocking_io::connect::connect(
            url.clone(),
            gix::protocol::transport::client::blocking_io::connect::Options {
                version: Protocol::V1,
                ..Default::default()
            },
        )
        .map_err(|e| net_err(&e))?;
        if matches!(url.scheme, gix::url::Scheme::Https | gix::url::Scheme::Http) {
            transport.configure(&http_options()?).map_err(|e| net_err(&e))?;
        }
        let bridge = ProgressBridge::new(options.progress.clone());
        let mut handshake_progress = bridge.item("Connecting");
        let last_url = Arc::new(Mutex::new(None));
        let handshake = gix::protocol::handshake(
            &mut transport,
            Service::ReceivePack,
            credential_fn(options.credentials.clone(), last_url),
            Vec::new(),
            &mut handshake_progress,
        )
        .map_err(|e| net_err(&e))?;
        let advertised: Vec<(String, ObjectId)> = handshake
            .refs
            .unwrap_or_default()
            .into_iter()
            .filter_map(|r| match r {
                Ref::Direct {
                    full_ref_name,
                    object,
                }
                | Ref::Peeled {
                    full_ref_name,
                    object,
                    ..
                } => Some((full_ref_name.to_string(), object)),
                Ref::Symbolic {
                    full_ref_name,
                    object,
                    ..
                } => Some((full_ref_name.to_string(), object)),
                Ref::Unborn { .. } => None,
            })
            .collect();
        let null = ObjectId::null(repo.object_hash());
        let old = advertised
            .iter()
            .find(|(name, _)| *name == remote_ref)
            .map(|(_, id)| *id)
            .unwrap_or(null);

        let finish = |this: &Self| -> Result<()> {
            // Keep the remote-tracking ref and upstream in sync with what the remote now has.
            let short = remote_ref.strip_prefix("refs/heads/").unwrap_or(&remote_ref);
            let tracking = format!("refs/remotes/{remote_name}/{short}");
            repo.reference(tracking.as_str(), local, PreviousValue::Any, "update by push")
                .map_err(ScmError::git)?;
            if push.set_upstream {
                this.set_config(&[
                    ("branch", Some(&branch), "remote", &remote_name),
                    ("branch", Some(&branch), "merge", &remote_ref),
                ])?;
            }
            Ok(())
        };

        if old == local {
            return finish(self); // Everything up-to-date.
        }
        if !old.is_null() && !push.force {
            let known = repo.find_commit(old).is_ok();
            if !known || !is_ancestor(&repo, old, local) {
                return Err(ScmError::PushRejectedNonFastForward);
            }
        }

        // 2. The objects the remote doesn't have: commits not reachable from any advertised tip we know.
        let hidden: Vec<ObjectId> = advertised
            .iter()
            .map(|(_, id)| *id)
            .filter(|id| !id.is_null() && repo.find_commit(*id).is_ok())
            .collect();
        let commits: Vec<ObjectId> = repo
            .rev_walk([local])
            .with_hidden(hidden)
            .all()
            .map_err(ScmError::git)?
            .map(|info| info.map(|i| i.id).map_err(ScmError::git))
            .collect::<Result<_>>()?;
        if options.cancel.load(Ordering::Relaxed) {
            return Err(ScmError::Cancelled);
        }
        let pack = build_pack(&repo, commits, &bridge, options)?;

        // 3. Send the update command and the pack, read `report-status`.
        let capabilities = &handshake.capabilities;
        let mut caps = vec!["report-status".to_owned()];
        if capabilities.contains("ofs-delta") {
            caps.push("ofs-delta".into());
        }
        if capabilities.contains("quiet") {
            caps.push("quiet".into());
        }
        caps.push(format!("agent=ferride/{}", env!("CARGO_PKG_VERSION")));
        let command = format!(
            "{} {} {}\0{}",
            old.to_hex(),
            local.to_hex(),
            remote_ref,
            caps.join(" ")
        );

        let writer = transport
            .request(WriteMode::Binary, MessageKind::Flush, false)
            .map_err(|e| net_err(&e))?;
        let (mut out, mut reader) = writer.into_parts();
        write_pkt_line(&mut out, command.as_bytes())?;
        out.write_all(b"0000")?;
        out.write_all(&pack)?;
        out.flush()?;
        drop(out);

        let mut unpack_error = None;
        let mut ref_error = None;
        let mut ref_ok = false;
        let mut line = String::new();
        loop {
            line.clear();
            match reader.read_line(&mut line) {
                Ok(0) => break,
                Ok(_) => {}
                Err(e) => return Err(net_err(&e)),
            }
            let text = line.trim_end_matches('\n');
            if let Some(status) = text.strip_prefix("unpack ") {
                if status != "ok" {
                    unpack_error = Some(status.to_owned());
                }
            } else if let Some(rest) = text.strip_prefix("ok ") {
                ref_ok |= rest == remote_ref;
            } else if let Some(rest) = text.strip_prefix("ng ") {
                let reason = rest.split_once(' ').map(|(_, r)| r).unwrap_or(rest).to_owned();
                ref_error = Some(reason);
            }
        }
        drop(reader);
        drop(bridge);
        if let Some(err) = unpack_error {
            return Err(ScmError::PushRejected(format!("unpack failed: {err}")));
        }
        if let Some(reason) = ref_error {
            if reason.contains("non-fast-forward") || reason.contains("fetch first") {
                return Err(ScmError::PushRejectedNonFastForward);
            }
            return Err(ScmError::PushRejected(reason));
        }
        if !ref_ok {
            return Err(ScmError::PushRejected(
                "the remote didn't confirm the update".into(),
            ));
        }
        finish(self)
    }
}

fn write_pkt_line(out: &mut dyn Write, data: &[u8]) -> std::io::Result<()> {
    write!(out, "{:04x}", data.len() + 4)?;
    out.write_all(data)
}

/// A complete (non-thin) pack with `commits` and the trees and blobs they add compared to their parents.
fn build_pack(
    repo: &gix::Repository,
    commits: Vec<ObjectId>,
    bridge: &ProgressBridge,
    options: &NetworkOptions,
) -> Result<Vec<u8>> {
    let mut db = repo.objects.clone().into_inner();
    db.prevent_pack_unload();
    let counting = bridge.item("Counting objects");
    let (counts, _) = output::count::objects_unthreaded(
        &db,
        &mut commits.into_iter().map(Ok),
        &counting,
        &options.cancel,
        output::count::objects::ObjectExpansion::TreeAdditionsComparedToAncestor,
    )
    .map_err(ScmError::git)?;
    let num_entries = counts.len() as u32;
    let entries = output::entry::iter_from_counts(
        counts,
        db,
        Box::new(bridge.item("Compressing objects")),
        output::entry::iter_from_counts::Options {
            thread_limit: Some(1),
            mode: output::entry::iter_from_counts::Mode::PackCopyAndBaseObjects,
            allow_thin_pack: false,
            chunk_size: 32,
            version: gix_pack::data::Version::V2,
            compression: Default::default(),
        },
    )
    .map_err(ScmError::git)?;
    let mut chunks: Vec<(usize, Vec<output::Entry>)> = Vec::new();
    for chunk in entries {
        let (seq, entries) = chunk.map_err(ScmError::git)?;
        chunks.push((seq, entries));
    }
    chunks.sort_by_key(|(seq, _)| *seq);
    let mut pack = Vec::new();
    let writer = output::bytes::FromEntriesIter::new(
        chunks.into_iter().map(|(_, entries)| Ok(entries)),
        &mut pack,
        num_entries,
        gix_pack::data::Version::V2,
        repo.object_hash(),
    );
    for written in writer {
        written.map_err(ScmError::git)?;
    }
    Ok(pack)
}
