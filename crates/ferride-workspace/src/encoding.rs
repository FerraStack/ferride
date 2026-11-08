//! Text encodings: detect on open, keep on save (VS Code's `files.encoding`,
//! "Reopen with Encoding", "Save with Encoding").
//!
//! UTF-8 (with or without BOM) and UTF-16 with a BOM are recognised exactly;
//! anything else is guessed with `chardetng` (the detector Firefox uses) and
//! decoded with `encoding_rs`. Saving re-encodes into the file's encoding and
//! refuses (rather than writing `&#…;` escapes) when a character can't be
//! represented in it.

use encoding_rs::{Encoding, UTF_8, UTF_16BE, UTF_16LE};

/// An encoding Ferride can open and save, with VS Code's id and label.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TextEncoding {
    /// VS Code's `files.encoding` value (`utf8`, `utf8bom`, `windows1252`, …).
    pub id: &'static str,
    /// What the status bar and the picker show (`UTF-8`, `Western (Windows 1252)`).
    pub label: &'static str,
    encoding: &'static Encoding,
    bom: bool,
}

macro_rules! enc {
    ($id:literal, $label:literal, $enc:expr) => {
        TextEncoding {
            id: $id,
            label: $label,
            encoding: $enc,
            bom: false,
        }
    };
}

/// The encodings offered in the picker (VS Code's list, minus the ones
/// `encoding_rs` folds into others, e.g. ISO 8859-1 decodes as Windows 1252).
pub static ENCODINGS: &[TextEncoding] = &[
    enc!("utf8", "UTF-8", UTF_8),
    TextEncoding {
        id: "utf8bom",
        label: "UTF-8 with BOM",
        encoding: UTF_8,
        bom: true,
    },
    TextEncoding {
        id: "utf16le",
        label: "UTF-16 LE",
        encoding: UTF_16LE,
        bom: true,
    },
    TextEncoding {
        id: "utf16be",
        label: "UTF-16 BE",
        encoding: UTF_16BE,
        bom: true,
    },
    enc!("windows1252", "Western (Windows 1252)", encoding_rs::WINDOWS_1252),
    enc!("iso885915", "Western (ISO 8859-15)", encoding_rs::ISO_8859_15),
    enc!("macroman", "Western (Mac Roman)", encoding_rs::MACINTOSH),
    enc!(
        "windows1250",
        "Central European (Windows 1250)",
        encoding_rs::WINDOWS_1250
    ),
    enc!(
        "iso88592",
        "Central European (ISO 8859-2)",
        encoding_rs::ISO_8859_2
    ),
    enc!(
        "windows1251",
        "Cyrillic (Windows 1251)",
        encoding_rs::WINDOWS_1251
    ),
    enc!("koi8r", "Cyrillic (KOI8-R)", encoding_rs::KOI8_R),
    enc!("koi8u", "Cyrillic (KOI8-U)", encoding_rs::KOI8_U),
    enc!("iso88595", "Cyrillic (ISO 8859-5)", encoding_rs::ISO_8859_5),
    enc!("windows1253", "Greek (Windows 1253)", encoding_rs::WINDOWS_1253),
    enc!("iso88597", "Greek (ISO 8859-7)", encoding_rs::ISO_8859_7),
    enc!("windows1254", "Turkish (Windows 1254)", encoding_rs::WINDOWS_1254),
    enc!("windows1255", "Hebrew (Windows 1255)", encoding_rs::WINDOWS_1255),
    enc!("windows1256", "Arabic (Windows 1256)", encoding_rs::WINDOWS_1256),
    enc!("windows1257", "Baltic (Windows 1257)", encoding_rs::WINDOWS_1257),
    enc!(
        "windows1258",
        "Vietnamese (Windows 1258)",
        encoding_rs::WINDOWS_1258
    ),
    enc!("windows874", "Thai (Windows 874)", encoding_rs::WINDOWS_874),
    enc!("shiftjis", "Japanese (Shift JIS)", encoding_rs::SHIFT_JIS),
    enc!("eucjp", "Japanese (EUC-JP)", encoding_rs::EUC_JP),
    enc!("gb18030", "Simplified Chinese (GB 18030)", encoding_rs::GB18030),
    enc!("gbk", "Simplified Chinese (GBK)", encoding_rs::GBK),
    enc!("big5", "Traditional Chinese (Big5)", encoding_rs::BIG5),
    enc!("euckr", "Korean (EUC-KR)", encoding_rs::EUC_KR),
];

impl TextEncoding {
    pub fn by_id(id: &str) -> Option<&'static TextEncoding> {
        ENCODINGS.iter().find(|e| e.id == id)
    }

    fn of(encoding: &'static Encoding) -> &'static TextEncoding {
        ENCODINGS
            .iter()
            .find(|e| e.encoding == encoding && !e.bom)
            .or_else(|| ENCODINGS.iter().find(|e| e.encoding == encoding))
            .unwrap_or(&ENCODINGS[0])
    }

    /// Decodes `bytes` as this encoding (a matching BOM is skipped). Invalid
    /// sequences become U+FFFD; the flag says whether that happened.
    pub fn decode(&self, bytes: &[u8]) -> (String, bool) {
        let (text, had_errors) = self.encoding.decode_with_bom_removal(bytes);
        (text.into_owned(), had_errors)
    }

    /// Encodes `text` (with a BOM for the `…bom` / UTF-16 variants). `None`
    /// when some character has no representation in this encoding.
    pub fn encode(&self, text: &str) -> Option<Vec<u8>> {
        let mut out = Vec::with_capacity(text.len() + 3);
        if self.encoding == UTF_16LE || self.encoding == UTF_16BE {
            // encoding_rs only encodes *to* UTF-8 for these (per the WHATWG spec): do it by hand.
            let le = self.encoding == UTF_16LE;
            out.extend_from_slice(if le { &[0xFF, 0xFE] } else { &[0xFE, 0xFF] });
            for unit in text.encode_utf16() {
                out.extend_from_slice(&if le {
                    unit.to_le_bytes()
                } else {
                    unit.to_be_bytes()
                });
            }
            return Some(out);
        }
        if self.bom {
            out.extend_from_slice(&[0xEF, 0xBB, 0xBF]);
        }
        let (bytes, _, unmappable) = self.encoding.encode(text);
        if unmappable {
            return None;
        }
        out.extend_from_slice(&bytes);
        Some(out)
    }
}

/// What a file's bytes most likely are, and the decoded text.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Decoded {
    pub text: String,
    pub encoding: &'static TextEncoding,
    /// Some bytes didn't decode (shown as U+FFFD): a wrong guess, or a damaged file.
    pub lossy: bool,
}

/// Detects the encoding: BOMs first, then strict UTF-8, then `chardetng`.
pub fn detect(bytes: &[u8]) -> Decoded {
    if let Some((encoding, _)) = Encoding::for_bom(bytes) {
        let enc = if encoding == UTF_8 {
            TextEncoding::by_id("utf8bom").unwrap_or(&ENCODINGS[0])
        } else {
            TextEncoding::of(encoding)
        };
        let (text, lossy) = enc.decode(bytes);
        return Decoded {
            text,
            encoding: enc,
            lossy,
        };
    }
    if let Ok(text) = std::str::from_utf8(bytes) {
        return Decoded {
            text: text.to_owned(),
            encoding: &ENCODINGS[0],
            lossy: false,
        };
    }
    let mut detector = chardetng::EncodingDetector::new();
    detector.feed(bytes, true);
    let guess = detector.guess(None, false);
    let enc = TextEncoding::of(guess);
    let (text, lossy) = enc.decode(bytes);
    Decoded {
        text,
        encoding: enc,
        lossy,
    }
}

/// True when the bytes look like UTF-16 (a BOM), which legitimately contains NULs.
pub fn has_utf16_bom(bytes: &[u8]) -> bool {
    bytes.starts_with(&[0xFF, 0xFE]) || bytes.starts_with(&[0xFE, 0xFF])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn utf8_with_and_without_bom() {
        let d = detect("héllo".as_bytes());
        assert_eq!(
            (d.encoding.id, d.text.as_str(), d.lossy),
            ("utf8", "héllo", false)
        );
        let mut bom = vec![0xEF, 0xBB, 0xBF];
        bom.extend_from_slice("x".as_bytes());
        let d = detect(&bom);
        assert_eq!((d.encoding.id, d.text.as_str()), ("utf8bom", "x"));
        assert_eq!(d.encoding.encode("x").unwrap(), bom, "the BOM survives a save");
    }

    #[test]
    fn latin1_is_detected_and_round_trips() {
        // "café crème" in Windows-1252 / Latin-1.
        let bytes = b"caf\xe9 cr\xe8me, d\xe9j\xe0 vu. Les \xe9l\xe8ves sont all\xe9s \xe0 l'\xe9cole.";
        let d = detect(bytes);
        assert_eq!(d.encoding.id, "windows1252");
        assert!(d.text.starts_with("café crème"));
        assert!(!d.lossy);
        assert_eq!(d.encoding.encode(&d.text).unwrap(), bytes.to_vec());
    }

    #[test]
    fn shift_jis_is_detected() {
        let (bytes, _, _) = encoding_rs::SHIFT_JIS.encode("こんにちは、世界。日本語のテキストです。");
        let d = detect(&bytes);
        assert_eq!(d.encoding.id, "shiftjis");
        assert_eq!(d.text, "こんにちは、世界。日本語のテキストです。");
    }

    #[test]
    fn utf16_with_bom_round_trips() {
        let enc = TextEncoding::by_id("utf16le").unwrap();
        let bytes = enc.encode("a😀").unwrap();
        assert!(has_utf16_bom(&bytes));
        let d = detect(&bytes);
        assert_eq!((d.encoding.id, d.text.as_str()), ("utf16le", "a😀"));
        let be = TextEncoding::by_id("utf16be").unwrap().encode("hi").unwrap();
        assert_eq!(be, vec![0xFE, 0xFF, 0, b'h', 0, b'i']);
    }

    #[test]
    fn unencodable_text_is_refused_not_escaped() {
        let latin = TextEncoding::by_id("windows1252").unwrap();
        assert!(latin.encode("snow ☃").is_none());
        assert_eq!(latin.encode("café").unwrap(), b"caf\xe9".to_vec());
    }

    #[test]
    fn reopen_with_another_encoding() {
        let bytes = b"\xcf\xf0\xe8\xe2\xb3\xf2"; // "Привіт" in Windows-1251
        let d = TextEncoding::by_id("windows1251").unwrap().decode(bytes);
        assert_eq!(d, ("Привіт".to_owned(), false));
        assert!(ENCODINGS.iter().all(|e| TextEncoding::by_id(e.id) == Some(e)));
    }
}
