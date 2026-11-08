use serde_json::json;

use super::*;

fn theme(rules: serde_json::Value) -> TokenTheme {
    TokenTheme::from_rules(0xFF00_0000, rules.as_array().unwrap())
}

fn fg(t: &TokenTheme, scopes: &[&str]) -> u32 {
    t.style(scopes).foreground
}

const RED: u32 = 0xFFFF_0000;
const GREEN: u32 = 0xFF00_FF00;
const BLUE: u32 = 0xFF00_00FF;

#[test]
fn deeper_scope_match_wins_and_later_rules_override() {
    let t = theme(json!([
        { "scope": "entity", "settings": { "foreground": "#f00" } },
        { "scope": "entity.name", "settings": { "foreground": "#0f0" } },
        { "scope": "entity.name", "settings": { "foreground": "#00f" } },
    ]));
    assert_eq!(fg(&t, &["source.x", "entity.name.function.x"]), BLUE);
    assert_eq!(fg(&t, &["source.x", "entity.other"]), RED);
    // A prefix must end at a dot: `entity.names` is not `entity.name`.
    assert_eq!(fg(&t, &["source.x", "entity.names"]), RED);
    assert_eq!(fg(&t, &["source.x", "keyword"]), 0xFF00_0000);
}

#[test]
fn descendant_selectors_need_their_ancestors() {
    let t = theme(json!([
        { "scope": "comment", "settings": { "foreground": "#f00" } },
        { "scope": "source.js comment", "settings": { "foreground": "#0f0" } },
        { "scope": "text.html source.js comment", "settings": { "foreground": "#00f" } },
        { "scope": "meta.tag > string", "settings": { "foreground": "#00f" } },
    ]));
    assert_eq!(fg(&t, &["source.py", "comment.line.py"]), RED);
    assert_eq!(fg(&t, &["source.js", "comment.line.js"]), GREEN);
    // More parent scopes are more specific.
    assert_eq!(
        fg(
            &t,
            &["text.html.basic", "meta.embedded", "source.js", "comment.js"]
        ),
        BLUE
    );
    // Parents must appear in order (outer to inner).
    assert_eq!(fg(&t, &["source.js", "text.html", "comment.js"]), GREEN);
    // `>` means the direct parent.
    assert_eq!(fg(&t, &["text.html", "meta.tag", "string.quoted"]), BLUE);
    assert_eq!(
        fg(&t, &["text.html", "meta.tag", "meta.attr", "string.quoted"]),
        0xFF00_0000
    );
}

#[test]
fn longer_parent_scopes_are_more_specific() {
    let t = theme(json!([
        { "scope": "source.js string", "settings": { "foreground": "#f00" } },
        { "scope": "meta.embedded.block string", "settings": { "foreground": "#0f0" } },
    ]));
    assert_eq!(
        fg(&t, &["source.js", "meta.embedded.block.css", "string.quoted"]),
        GREEN
    );
}

#[test]
fn font_style_is_resolved_separately_and_can_be_reset() {
    let t = theme(json!([
        { "scope": "comment", "settings": { "foreground": "#f00", "fontStyle": "italic" } },
        { "scope": "comment.line.documentation", "settings": { "foreground": "#0f0" } },
        { "scope": "markup.bold", "settings": { "fontStyle": "bold" } },
        { "scope": "markup.underline.link", "settings": { "fontStyle": "underline strikethrough" } },
        { "scope": "comment.block", "settings": { "fontStyle": "" } },
    ]));
    // The deeper rule sets only the color; italic is inherited from `comment`.
    let doc = t.style(&["source.rust", "comment.line.documentation.rust"]);
    assert_eq!((doc.foreground, doc.font_style), (GREEN, font_style::ITALIC));
    // `fontStyle: ""` explicitly turns it off.
    assert_eq!(
        t.style(&["source.c", "comment.block.c"]).font_style,
        font_style::NONE
    );
    // A style-only rule keeps the default color.
    let bold = t.style(&["text.html.markdown", "markup.bold.markdown"]);
    assert_eq!(
        (bold.foreground, bold.font_style),
        (0xFF00_0000, font_style::BOLD)
    );
    assert_eq!(
        t.style(&["text.html.markdown", "markup.underline.link.markdown"])
            .font_style,
        font_style::UNDERLINE | font_style::STRIKETHROUGH
    );
}

#[test]
fn styles_inherit_down_the_scope_stack() {
    let t = theme(json!([
        { "scope": "markup.heading", "settings": { "foreground": "#f00", "fontStyle": "bold" } },
        { "scope": "markup.italic", "settings": { "fontStyle": "italic" } },
    ]));
    // An italic run inside a heading: color and bold come from the heading.
    let s = t.style(&[
        "text.html.markdown",
        "markup.heading.markdown",
        "markup.italic.markdown",
    ]);
    assert_eq!((s.foreground, s.font_style), (RED, font_style::ITALIC));
}

#[test]
fn selectors_lists_and_scopeless_defaults() {
    let t = TokenTheme::from_theme_json(
        r##"// comment
        { "type": "dark", "colors": { "editor.foreground": "#abc" },
          "tokenColors": [
            { "settings": { "foreground": "#123456" } },
            { "scope": "keyword, storage.type ,", "settings": { "foreground": "#ff0000cc" } },
            { "scope": ["string", "constant.numeric"], "settings": { "foreground": "#0f0" } }, /* trailing */
          ], }"##,
    )
    .unwrap();
    assert_eq!(t.default_foreground(), 0xFF12_3456);
    assert_eq!(fg(&t, &["source.x", "storage.type.x"]), 0xCCFF_0000);
    assert_eq!(fg(&t, &["source.x", "keyword.x"]), 0xCCFF_0000);
    assert_eq!(fg(&t, &["source.x", "constant.numeric.x"]), GREEN);
    assert_eq!(fg(&t, &["source.x", "variable"]), 0xFF12_3456);
}

#[test]
fn jsonc_keeps_strings_intact() {
    let s = strip_jsonc(r#"{ "a": "// not a comment, ]", "b": [1, 2,], /* x */ "c": "\"," , }"#);
    let v: serde_json::Value = serde_json::from_str(&s).unwrap();
    assert_eq!(v["a"], "// not a comment, ]");
    assert_eq!(v["b"], json!([1, 2]));
    assert_eq!(v["c"], "\",");
}

#[test]
fn colors() {
    assert_eq!(parse_color("#abc"), Some(0xFFAA_BBCC));
    assert_eq!(parse_color("#11223344"), Some(0x4411_2233));
    assert_eq!(parse_color("#1234"), Some(0x4411_2233));
    assert_eq!(parse_color("red"), None);
    assert_eq!(parse_color("#12345"), None);
}
