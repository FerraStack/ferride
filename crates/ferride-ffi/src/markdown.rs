//! Markdown preview blocks for Kotlin (see `ferride-markdown`).

use ferride_markdown::{Align, Block, BlockKind, Span};

/// A run of styled inline text.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MdSpan {
    pub text: String,
    pub bold: bool,
    pub italic: bool,
    pub strike: bool,
    pub code: bool,
    pub link: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MdAlign {
    None,
    Left,
    Center,
    Right,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum MdKind {
    Heading {
        level: u8,
    },
    Paragraph,
    ListItem {
        ordered: bool,
        number: u64,
        checked: Option<bool>,
    },
    Code {
        language: String,
    },
    Rule,
    TableRow {
        header: bool,
        cells: Vec<Vec<MdSpan>>,
        align: Vec<MdAlign>,
    },
    Image {
        source: String,
    },
}

/// One block of the preview, flat (nesting as depths).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MdBlock {
    pub kind: MdKind,
    pub spans: Vec<MdSpan>,
    pub list_depth: u32,
    pub quote_depth: u32,
    /// 0-based source line (scrolls the preview with the editor).
    pub line: u32,
}

fn span(s: Span) -> MdSpan {
    MdSpan {
        text: s.text,
        bold: s.bold,
        italic: s.italic,
        strike: s.strike,
        code: s.code,
        link: s.link,
    }
}

fn align(a: Align) -> MdAlign {
    match a {
        Align::None => MdAlign::None,
        Align::Left => MdAlign::Left,
        Align::Center => MdAlign::Center,
        Align::Right => MdAlign::Right,
    }
}

fn block(b: Block) -> MdBlock {
    let kind = match b.kind {
        BlockKind::Heading { level } => MdKind::Heading { level },
        BlockKind::Paragraph => MdKind::Paragraph,
        BlockKind::ListItem {
            ordered,
            number,
            checked,
        } => MdKind::ListItem {
            ordered,
            number,
            checked,
        },
        BlockKind::Code { language } => MdKind::Code { language },
        BlockKind::Rule => MdKind::Rule,
        BlockKind::TableRow {
            header,
            cells,
            align: a,
        } => MdKind::TableRow {
            header,
            cells: cells
                .into_iter()
                .map(|c| c.into_iter().map(span).collect())
                .collect(),
            align: a.into_iter().map(align).collect(),
        },
        BlockKind::Image { source } => MdKind::Image { source },
    };
    MdBlock {
        kind,
        spans: b.spans.into_iter().map(span).collect(),
        list_depth: b.list_depth,
        quote_depth: b.quote_depth,
        line: b.line,
    }
}

/// Parses Markdown (CommonMark + GFM tables, task lists, strikethrough) for the preview.
#[uniffi::export]
pub fn markdown_blocks(markdown: String) -> Vec<MdBlock> {
    ferride_markdown::blocks(&markdown)
        .into_iter()
        .map(block)
        .collect()
}
