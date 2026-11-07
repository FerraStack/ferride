/// Errors of source control operations. Messages follow VS Code's git extension wording where one exists, and
/// never contain credentials.
#[derive(Debug, thiserror::Error)]
pub enum ScmError {
    #[error("'{0}' is not a git repository.")]
    NotARepository(String),
    #[error("Make sure you configure your \"user.name\" and \"user.email\" in git.")]
    MissingIdentity,
    #[error("There are no changes to commit.")]
    NothingToCommit,
    #[error("Your local changes to the following files would be overwritten by checkout:\n{}", .0.join("\n"))]
    WouldOverwrite(Vec<String>),
    #[error("A branch named '{0}' already exists.")]
    BranchExists(String),
    #[error("Branch '{0}' not found.")]
    BranchNotFound(String),
    #[error("The branch '{0}' has no remote branch.")]
    NoUpstream(String),
    #[error("This repository has no remotes configured to {0} from.")]
    NoRemote(&'static str),
    #[error("Can't pull: the local branch has commits that aren't on the remote (fast-forward only).")]
    NotFastForward,
    #[error("Can't push refs to remote. Try running 'Pull' first to integrate your changes.")]
    PushRejectedNonFastForward,
    #[error("The remote rejected the push: {0}")]
    PushRejected(String),
    #[error("Authentication failed for '{0}'.")]
    Authentication(String),
    #[error("The operation was cancelled.")]
    Cancelled,
    #[error("Only HTTPS remotes are supported: {0}")]
    UnsupportedUrl(String),
    #[error("{0}")]
    InvalidInput(String),
    #[error("{0}")]
    Io(#[from] std::io::Error),
    #[error("{0}")]
    Git(String),
}

impl ScmError {
    /// Wraps an error from gix (or anything displayable) as a generic git error.
    pub(crate) fn git(err: impl std::fmt::Display) -> Self {
        ScmError::Git(sanitize(&format!("{err:#}")))
    }
}

pub type Result<T, E = ScmError> = std::result::Result<T, E>;

/// Removes `user:password@` from any URL in `text`, so error messages never carry credentials.
pub(crate) fn sanitize(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut rest = text;
    while let Some(pos) = rest.find("://") {
        let (head, tail) = rest.split_at(pos + 3);
        out.push_str(head);
        let authority_end = tail
            .find(|c: char| c == '/' || c.is_whitespace() || c == '"' || c == '\'')
            .unwrap_or(tail.len());
        let authority = &tail[..authority_end];
        match authority.rfind('@') {
            Some(at) => out.push_str(&authority[at + 1..]),
            None => out.push_str(authority),
        }
        rest = &tail[authority_end..];
    }
    out.push_str(rest);
    out
}

#[cfg(test)]
mod tests {
    #[test]
    fn sanitize_strips_userinfo() {
        assert_eq!(
            super::sanitize("failed for https://me:ghp_x@github.com/o/r.git and 'http://tok@h:8080/x'"),
            "failed for https://github.com/o/r.git and 'http://h:8080/x'"
        );
        assert_eq!(super::sanitize("no urls here"), "no urls here");
    }
}
