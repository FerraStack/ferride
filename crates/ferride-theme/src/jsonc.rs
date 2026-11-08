/// Turns JSONC (VS Code's JSON with `//` and `/* */` comments and trailing
/// commas) into plain JSON. Strings are copied verbatim.
pub fn strip_jsonc(src: &str) -> String {
    let bytes = src.as_bytes();
    let mut out = String::with_capacity(src.len());
    // Byte offset in `out` of a comma that may turn out to be trailing.
    let mut pending_comma: Option<usize> = None;
    let mut i = 0;
    let mut copy_from = 0;
    let flush = |out: &mut String, from: usize, to: usize| out.push_str(&src[from..to]);
    while i < bytes.len() {
        match bytes[i] {
            b'"' => {
                pending_comma = None;
                let start = i;
                i += 1;
                while i < bytes.len() && bytes[i] != b'"' {
                    i += if bytes[i] == b'\\' { 2 } else { 1 };
                }
                i = (i + 1).min(bytes.len());
                flush(&mut out, copy_from, start);
                flush(&mut out, start, i);
                copy_from = i;
            }
            b'/' if bytes.get(i + 1) == Some(&b'/') => {
                flush(&mut out, copy_from, i);
                while i < bytes.len() && bytes[i] != b'\n' {
                    i += 1;
                }
                copy_from = i;
            }
            b'/' if bytes.get(i + 1) == Some(&b'*') => {
                flush(&mut out, copy_from, i);
                i += 2;
                while i < bytes.len() && !(bytes[i] == b'*' && bytes.get(i + 1) == Some(&b'/')) {
                    i += 1;
                }
                i = (i + 2).min(bytes.len());
                copy_from = i;
            }
            b',' => {
                flush(&mut out, copy_from, i);
                pending_comma = Some(out.len());
                out.push(',');
                i += 1;
                copy_from = i;
            }
            b'}' | b']' => {
                flush(&mut out, copy_from, i);
                if let Some(at) = pending_comma.take() {
                    out.replace_range(at..at + 1, " ");
                }
                out.push(bytes[i] as char);
                i += 1;
                copy_from = i;
            }
            b if b.is_ascii_whitespace() => i += 1,
            _ => {
                pending_comma = None;
                i += 1;
            }
        }
    }
    flush(&mut out, copy_from, bytes.len());
    out
}
