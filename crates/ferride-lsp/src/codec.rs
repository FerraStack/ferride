//! JSON-RPC base protocol framing (`Content-Length` headers).

use std::io::{self, BufRead, Write};

/// Largest message body accepted (a misbehaving server can't make us allocate more).
pub const MAX_CONTENT_LENGTH: usize = 64 * 1024 * 1024;

/// Reads one message body. Returns `Ok(None)` on a clean EOF between messages.
pub fn read_message(reader: &mut impl BufRead) -> io::Result<Option<Vec<u8>>> {
    let mut content_length: Option<usize> = None;
    let mut line = String::new();
    let mut saw_header = false;
    loop {
        line.clear();
        let n = reader.read_line(&mut line)?;
        if n == 0 {
            if saw_header {
                return Err(io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    "EOF inside message headers",
                ));
            }
            return Ok(None);
        }
        let trimmed = line.trim_end_matches(['\r', '\n']);
        if trimmed.is_empty() {
            if !saw_header {
                // Tolerate stray blank lines between messages.
                continue;
            }
            break;
        }
        saw_header = true;
        let (name, value) = trimmed
            .split_once(':')
            .ok_or_else(|| invalid(format!("malformed header {trimmed:?}")))?;
        if name.trim().eq_ignore_ascii_case("content-length") {
            content_length = Some(
                value
                    .trim()
                    .parse()
                    .map_err(|_| invalid(format!("bad Content-Length {value:?}")))?,
            );
        }
    }
    let len = content_length.ok_or_else(|| invalid("missing Content-Length".into()))?;
    if len > MAX_CONTENT_LENGTH {
        return Err(invalid(format!(
            "Content-Length {len} exceeds {MAX_CONTENT_LENGTH}"
        )));
    }
    let mut body = vec![0; len];
    reader.read_exact(&mut body)?;
    Ok(Some(body))
}

/// Writes one framed message and flushes.
pub fn write_message(writer: &mut (impl Write + ?Sized), body: &[u8]) -> io::Result<()> {
    write!(writer, "Content-Length: {}\r\n\r\n", body.len())?;
    writer.write_all(body)?;
    writer.flush()
}

fn invalid(msg: String) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, msg)
}

#[cfg(test)]
mod tests {
    use std::io::BufReader;

    use super::*;

    #[test]
    fn round_trip_and_eof() {
        let mut buf = Vec::new();
        write_message(&mut buf, br#"{"a":1}"#).unwrap();
        write_message(&mut buf, "{\"é\":2}".as_bytes()).unwrap();
        let mut r = BufReader::new(&buf[..]);
        assert_eq!(read_message(&mut r).unwrap().unwrap(), br#"{"a":1}"#);
        assert_eq!(read_message(&mut r).unwrap().unwrap(), "{\"é\":2}".as_bytes());
        assert!(read_message(&mut r).unwrap().is_none());
    }

    #[test]
    fn extra_headers_and_case() {
        let raw = b"content-length: 2\r\nContent-Type: application/vscode-jsonrpc; charset=utf-8\r\n\r\n{}";
        let mut r = BufReader::new(&raw[..]);
        assert_eq!(read_message(&mut r).unwrap().unwrap(), b"{}");
    }

    #[test]
    fn errors() {
        let mut r = BufReader::new(&b"Content-Type: x\r\n\r\n{}"[..]);
        assert_eq!(
            read_message(&mut r).unwrap_err().kind(),
            io::ErrorKind::InvalidData
        );
        let mut r = BufReader::new(&b"Content-Length: 10\r\n\r\n{}"[..]);
        assert_eq!(
            read_message(&mut r).unwrap_err().kind(),
            io::ErrorKind::UnexpectedEof
        );
        let mut r = BufReader::new(&b"Content-Length: 99999999999\r\n\r\n"[..]);
        assert_eq!(
            read_message(&mut r).unwrap_err().kind(),
            io::ErrorKind::InvalidData
        );
        let mut r = BufReader::new(&b"Content-Length: 1\r\n"[..]);
        assert_eq!(
            read_message(&mut r).unwrap_err().kind(),
            io::ErrorKind::UnexpectedEof
        );
    }
}
