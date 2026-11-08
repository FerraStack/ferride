use super::*;

fn texts(b: &Block) -> String {
    b.spans.iter().map(|s| s.text.as_str()).collect()
}

#[test]
fn headings_paragraphs_and_inline_styles() {
    let md = "# Title\n\nSome **bold**, *italic*, ~~gone~~ and `code` with a [link](https://x.dev).\n";
    let b = blocks(md);
    assert_eq!(b[0].kind, BlockKind::Heading { level: 1 });
    assert_eq!(texts(&b[0]), "Title");
    assert_eq!(b[1].kind, BlockKind::Paragraph);
    assert_eq!(b[1].line, 2);
    let spans = &b[1].spans;
    assert!(spans.iter().any(|s| s.text == "bold" && s.bold));
    assert!(spans.iter().any(|s| s.text == "italic" && s.italic));
    assert!(spans.iter().any(|s| s.text == "gone" && s.strike));
    assert!(spans.iter().any(|s| s.text == "code" && s.code));
    assert!(
        spans
            .iter()
            .any(|s| s.text == "link" && s.link.as_deref() == Some("https://x.dev"))
    );
}

#[test]
fn lists_nested_ordered_and_tasks() {
    let md = "- one\n- two\n  1. inner\n  2. second\n- [x] done\n- [ ] todo\n";
    let b = blocks(md);
    let items: Vec<_> = b
        .iter()
        .map(|b| match &b.kind {
            BlockKind::ListItem {
                ordered,
                number,
                checked,
            } => (texts(b), *ordered, *number, *checked, b.list_depth),
            other => panic!("{other:?}"),
        })
        .collect();
    assert_eq!(
        items,
        vec![
            ("one".into(), false, 1, None, 0),
            ("two".into(), false, 2, None, 0),
            ("inner".into(), true, 1, None, 1),
            ("second".into(), true, 2, None, 1),
            ("done".into(), false, 3, Some(true), 0),
            ("todo".into(), false, 4, Some(false), 0),
        ]
    );
}

#[test]
fn code_blocks_quotes_rules_tables_images() {
    let md = "> quoted\n\n```rust\nfn main() {}\n```\n\n---\n\n| a | b |\n|:--|--:|\n| 1 | 2 |\n\n![logo](img.png)\n";
    let b = blocks(md);
    assert_eq!(b[0].quote_depth, 1);
    assert_eq!(texts(&b[0]), "quoted");
    assert_eq!(
        b[1].kind,
        BlockKind::Code {
            language: "rust".into()
        }
    );
    assert_eq!(texts(&b[1]), "fn main() {}");
    assert_eq!(b[2].kind, BlockKind::Rule);
    match &b[3].kind {
        BlockKind::TableRow { header, cells, align } => {
            assert!(*header);
            assert_eq!(cells.len(), 2);
            assert_eq!(align, &vec![Align::Left, Align::Right]);
        }
        other => panic!("{other:?}"),
    }
    assert!(matches!(&b[4].kind, BlockKind::TableRow { header: false, .. }));
    assert_eq!(
        b[5].kind,
        BlockKind::Image {
            source: "img.png".into()
        }
    );
    assert_eq!(texts(&b[5]), "logo");
}
