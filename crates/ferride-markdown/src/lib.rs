//! Markdown for the preview pane: CommonMark + GFM (tables, task lists,
//! strikethrough) parsed with `pulldown-cmark` into a **flat list of blocks**
//! with styled inline spans, which the Compose UI renders directly. Nesting is
//! kept as a depth (lists, block quotes) so the UI needs no tree walking.

use pulldown_cmark::{
    Alignment as CmAlignment, CodeBlockKind, Event, HeadingLevel, Options, Parser, Tag, TagEnd,
};

/// A run of inline text with its styles.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Span {
    pub text: String,
    pub bold: bool,
    pub italic: bool,
    pub strike: bool,
    pub code: bool,
    /// Link target, for link text.
    pub link: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Align {
    None,
    Left,
    Center,
    Right,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BlockKind {
    Heading {
        level: u8,
    },
    Paragraph,
    /// A list item's first paragraph: bullet or number, and the task box if any.
    ListItem {
        ordered: bool,
        number: u64,
        checked: Option<bool>,
    },
    /// Fenced or indented code; `spans` holds the code as one plain span.
    Code {
        language: String,
    },
    Rule,
    /// One table row; `cells` are inline spans per cell.
    TableRow {
        header: bool,
        cells: Vec<Vec<Span>>,
        align: Vec<Align>,
    },
    /// An image (shown by its alt text and source; images aren't loaded).
    Image {
        source: String,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Block {
    pub kind: BlockKind,
    pub spans: Vec<Span>,
    /// List nesting (0 = top level).
    pub list_depth: u32,
    /// Block quote nesting.
    pub quote_depth: u32,
    /// 0-based source line where the block starts (scroll sync with the editor).
    pub line: u32,
}

#[derive(Default)]
struct Style {
    bold: u32,
    italic: u32,
    strike: u32,
    link: Vec<String>,
}

/// Parses Markdown into preview blocks.
pub fn blocks(markdown: &str) -> Vec<Block> {
    let mut opts = Options::empty();
    opts.insert(Options::ENABLE_TABLES);
    opts.insert(Options::ENABLE_STRIKETHROUGH);
    opts.insert(Options::ENABLE_TASKLISTS);
    let line_starts: Vec<usize> = std::iter::once(0)
        .chain(markdown.match_indices('\n').map(|(i, _)| i + 1))
        .collect();
    let line_of = |byte: usize| line_starts.partition_point(|&s| s <= byte).saturating_sub(1) as u32;

    let mut out: Vec<Block> = Vec::new();
    let mut style = Style::default();
    let mut spans: Vec<Span> = Vec::new();
    let mut current: Option<(BlockKind, u32)> = None;
    // List stack: (ordered, next number).
    let mut lists: Vec<(bool, u64)> = Vec::new();
    let mut pending_item: Option<(bool, u64)> = None;
    let mut pending_check: Option<bool> = None;
    let mut quote_depth = 0u32;
    // Tables.
    let mut align: Vec<Align> = Vec::new();
    let mut in_head = false;
    let mut cells: Vec<Vec<Span>> = Vec::new();
    let mut code: Option<(String, String, u32)> = None;
    let mut image: Option<(String, u32)> = None;

    let push_text = |spans: &mut Vec<Span>, style: &Style, text: &str, code: bool| {
        spans.push(Span {
            text: text.to_owned(),
            bold: style.bold > 0,
            italic: style.italic > 0,
            strike: style.strike > 0,
            code,
            link: style.link.last().cloned(),
        });
    };

    let flush = |out: &mut Vec<Block>,
                 current: &mut Option<(BlockKind, u32)>,
                 spans: &mut Vec<Span>,
                 lists: &Vec<(bool, u64)>,
                 quote_depth: u32| {
        if let Some((kind, line)) = current.take() {
            out.push(Block {
                kind,
                spans: std::mem::take(spans),
                list_depth: lists.len().saturating_sub(1) as u32,
                quote_depth,
                line,
            });
        }
        spans.clear();
    };

    for (event, range) in Parser::new_ext(markdown, opts).into_offset_iter() {
        let line = line_of(range.start);
        match event {
            Event::Start(tag) => match tag {
                Tag::Heading { level, .. } => {
                    flush(&mut out, &mut current, &mut spans, &lists, quote_depth);
                    let level = match level {
                        HeadingLevel::H1 => 1,
                        HeadingLevel::H2 => 2,
                        HeadingLevel::H3 => 3,
                        HeadingLevel::H4 => 4,
                        HeadingLevel::H5 => 5,
                        HeadingLevel::H6 => 6,
                    };
                    current = Some((BlockKind::Heading { level }, line));
                }
                Tag::Paragraph => {
                    if current.is_none() {
                        let kind = match pending_item.take() {
                            Some((ordered, number)) => BlockKind::ListItem {
                                ordered,
                                number,
                                checked: pending_check.take(),
                            },
                            None => BlockKind::Paragraph,
                        };
                        current = Some((kind, line));
                    }
                }
                Tag::List(start) => {
                    flush(&mut out, &mut current, &mut spans, &lists, quote_depth);
                    lists.push((start.is_some(), start.unwrap_or(1)));
                }
                Tag::Item => {
                    flush(&mut out, &mut current, &mut spans, &lists, quote_depth);
                    if let Some(top) = lists.last_mut() {
                        pending_item = Some((top.0, top.1));
                        top.1 += 1;
                    }
                    // Tight lists have no Paragraph event: start the item now.
                    let (ordered, number) = pending_item.take().unwrap_or((false, 1));
                    current = Some((
                        BlockKind::ListItem {
                            ordered,
                            number,
                            checked: None,
                        },
                        line,
                    ));
                }
                Tag::BlockQuote(_) => {
                    flush(&mut out, &mut current, &mut spans, &lists, quote_depth);
                    quote_depth += 1;
                }
                Tag::CodeBlock(kind) => {
                    flush(&mut out, &mut current, &mut spans, &lists, quote_depth);
                    let lang = match kind {
                        CodeBlockKind::Fenced(info) => {
                            info.split_whitespace().next().unwrap_or("").to_owned()
                        }
                        CodeBlockKind::Indented => String::new(),
                    };
                    code = Some((lang, String::new(), line));
                }
                Tag::Table(a) => {
                    flush(&mut out, &mut current, &mut spans, &lists, quote_depth);
                    align = a
                        .iter()
                        .map(|x| match x {
                            CmAlignment::None => Align::None,
                            CmAlignment::Left => Align::Left,
                            CmAlignment::Center => Align::Center,
                            CmAlignment::Right => Align::Right,
                        })
                        .collect();
                }
                Tag::TableHead => {
                    in_head = true;
                    cells.clear();
                }
                Tag::TableRow => cells.clear(),
                Tag::TableCell => spans.clear(),
                Tag::Emphasis => style.italic += 1,
                Tag::Strong => style.bold += 1,
                Tag::Strikethrough => style.strike += 1,
                Tag::Link { dest_url, .. } => style.link.push(dest_url.to_string()),
                Tag::Image { dest_url, .. } => image = Some((dest_url.to_string(), line)),
                _ => {}
            },
            Event::End(tag) => match tag {
                TagEnd::Heading(_) | TagEnd::Paragraph => {
                    flush(&mut out, &mut current, &mut spans, &lists, quote_depth);
                }
                TagEnd::Item => flush(&mut out, &mut current, &mut spans, &lists, quote_depth),
                TagEnd::List(_) => {
                    flush(&mut out, &mut current, &mut spans, &lists, quote_depth);
                    lists.pop();
                }
                TagEnd::BlockQuote(_) => {
                    flush(&mut out, &mut current, &mut spans, &lists, quote_depth);
                    quote_depth = quote_depth.saturating_sub(1);
                }
                TagEnd::CodeBlock => {
                    if let Some((language, text, line)) = code.take() {
                        let text = text.strip_suffix('\n').unwrap_or(&text).to_owned();
                        out.push(Block {
                            kind: BlockKind::Code { language },
                            spans: vec![Span {
                                text,
                                code: true,
                                ..Span::default()
                            }],
                            list_depth: lists.len().saturating_sub(1) as u32,
                            quote_depth,
                            line,
                        });
                    }
                }
                TagEnd::TableCell => cells.push(std::mem::take(&mut spans)),
                TagEnd::TableHead | TagEnd::TableRow => {
                    out.push(Block {
                        kind: BlockKind::TableRow {
                            header: in_head,
                            cells: std::mem::take(&mut cells),
                            align: align.clone(),
                        },
                        spans: Vec::new(),
                        list_depth: 0,
                        quote_depth,
                        line,
                    });
                    in_head = false;
                }
                TagEnd::Emphasis => style.italic = style.italic.saturating_sub(1),
                TagEnd::Strong => style.bold = style.bold.saturating_sub(1),
                TagEnd::Strikethrough => style.strike = style.strike.saturating_sub(1),
                TagEnd::Link => {
                    style.link.pop();
                }
                TagEnd::Image => {
                    if let Some((source, line)) = image.take() {
                        // The alt text arrived as Text events into `spans`.
                        let alt = std::mem::take(&mut spans);
                        flush(&mut out, &mut current, &mut spans, &lists, quote_depth);
                        out.push(Block {
                            kind: BlockKind::Image { source },
                            spans: alt,
                            list_depth: 0,
                            quote_depth,
                            line,
                        });
                    }
                }
                _ => {}
            },
            Event::Text(t) => {
                if let Some((_, text, _)) = code.as_mut() {
                    text.push_str(&t);
                } else {
                    push_text(&mut spans, &style, &t, false);
                }
            }
            Event::Code(t) => push_text(&mut spans, &style, &t, true),
            Event::SoftBreak => push_text(&mut spans, &style, " ", false),
            Event::HardBreak => push_text(&mut spans, &style, "\n", false),
            Event::Rule => {
                flush(&mut out, &mut current, &mut spans, &lists, quote_depth);
                out.push(Block {
                    kind: BlockKind::Rule,
                    spans: Vec::new(),
                    list_depth: 0,
                    quote_depth,
                    line,
                });
            }
            Event::TaskListMarker(done) => {
                if let Some((BlockKind::ListItem { checked, .. }, _)) = current.as_mut() {
                    *checked = Some(done);
                } else {
                    pending_check = Some(done);
                }
            }
            Event::Html(t) | Event::InlineHtml(t) => push_text(&mut spans, &style, &t, true),
            _ => {}
        }
    }
    flush(&mut out, &mut current, &mut spans, &lists, quote_depth);
    // Drop empty paragraphs left by structure-only events.
    out.retain(|b| {
        !(matches!(b.kind, BlockKind::Paragraph) && b.spans.iter().all(|s| s.text.trim().is_empty()))
    });
    out
}

#[cfg(test)]
mod tests;
