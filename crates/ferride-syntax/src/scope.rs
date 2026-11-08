//! TextMate scopes for tree-sitter captures, so VS Code color themes apply
//! the way they do in VS Code.
//!
//! VS Code themes target TextMate scopes (`keyword.control.rust`,
//! `entity.name.function.js`, `source.css meta.property-list`). Ferride
//! highlights with tree-sitter, so every highlighted token gets the scope
//! stack VS Code's grammar would most likely produce: the document's root
//! scope (`source.rust`), the scopes of any embedding (`meta.embedded.block.css
//! source.css` inside HTML) and a token scope derived from the capture name,
//! refined by the token's text where TextMate grammars are finer than
//! tree-sitter queries (`if` is `keyword.control`, `fn` is `storage.type`,
//! `;` is `punctuation.terminator`).
//!
//! Stacks are interned process-wide: spans carry a small [`ScopeId`] and the
//! app resolves (and caches) one theme style per id.

use std::collections::HashMap;
use std::sync::{Arc, LazyLock, Mutex};

use tree_sitter::Node;

use crate::LanguageId;
use crate::highlight::HighlightKind;

/// An interned scope stack plus its highlight class. `0` is plain text.
pub type ScopeId = u32;

/// An interned embedding path (`[root]` or `[root, meta.embedded…, source.x]`).
pub(crate) type LayerId = u32;

/// What a token scope is pushed onto: a layer's root scopes, or the scope
/// stack of an enclosing token (a string around an escape, a heading around
/// emphasis), like nested TextMate scopes.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub(crate) enum Parent {
    Layer(LayerId),
    Style(ScopeId),
}

struct Style {
    kind: HighlightKind,
    stack: Arc<[Arc<str>]>,
    parent: Parent,
}

#[derive(Default)]
struct Registry {
    layers: Vec<Arc<[Arc<str>]>>,
    layer_ids: HashMap<Vec<Arc<str>>, LayerId>,
    /// id - 1 → style
    styles: Vec<Style>,
    style_ids: HashMap<(Parent, &'static str, LanguageId, HighlightKind), ScopeId>,
}

static REGISTRY: LazyLock<Mutex<Registry>> = LazyLock::new(Mutex::default);

fn registry() -> std::sync::MutexGuard<'static, Registry> {
    REGISTRY.lock().unwrap_or_else(|e| e.into_inner())
}

/// The layer of a document in `language`.
pub(crate) fn root_layer(language: LanguageId) -> LayerId {
    intern_layer(vec![Arc::from(root_scope(language))])
}

/// `parent` with `language` embedded in it.
pub(crate) fn embedded_layer(parent: LayerId, language: LanguageId) -> LayerId {
    if language == LanguageId::MarkdownInline {
        return parent;
    }
    let mut path: Vec<Arc<str>> = registry().layers[parent as usize].to_vec();
    path.push(Arc::from(format!("meta.embedded.block.{}", suffix(language))));
    path.push(Arc::from(root_scope(language)));
    intern_layer(path)
}

fn intern_layer(path: Vec<Arc<str>>) -> LayerId {
    let mut reg = registry();
    if let Some(&id) = reg.layer_ids.get(&path) {
        return id;
    }
    let id = reg.layers.len() as LayerId;
    reg.layers.push(path.clone().into());
    reg.layer_ids.insert(path, id);
    id
}

/// Id of the style for a token with `token_scope` pushed onto `parent`.
pub(crate) fn intern(
    parent: Parent,
    token_scope: &'static str,
    language: LanguageId,
    kind: HighlightKind,
) -> ScopeId {
    let key = (parent, token_scope, language, kind);
    let mut reg = registry();
    if let Some(&id) = reg.style_ids.get(&key) {
        return id;
    }
    let mut stack = match parent {
        Parent::Layer(layer) => reg.layers[layer as usize].to_vec(),
        Parent::Style(id) => reg.styles[id as usize - 1].stack.to_vec(),
    };
    if !token_scope.is_empty() {
        stack.push(Arc::from(format!("{token_scope}.{}", suffix(language))));
    }
    reg.styles.push(Style {
        kind,
        stack: stack.into(),
        parent,
    });
    let id = reg.styles.len() as ScopeId;
    reg.style_ids.insert(key, id);
    id
}

/// What `id` was pushed onto (to replace it with a sibling).
pub(crate) fn parent_of(id: ScopeId) -> Option<Parent> {
    registry()
        .styles
        .get((id as usize).checked_sub(1)?)
        .map(|s| s.parent)
}

/// Highlight class of an id (`Plain` for 0 or unknown ids).
pub fn kind_of(id: ScopeId) -> HighlightKind {
    match id {
        0 => HighlightKind::Plain,
        _ => registry()
            .styles
            .get(id as usize - 1)
            .map_or(HighlightKind::Plain, |s| s.kind),
    }
}

/// The scope stack of an id, outermost first (`["source.rust",
/// "entity.name.function.rust"]`). Empty for 0 or unknown ids.
pub fn scope_stack(id: ScopeId) -> Vec<String> {
    match id {
        0 => Vec::new(),
        _ => registry()
            .styles
            .get(id as usize - 1)
            .map(|s| s.stack.iter().map(|x| x.to_string()).collect())
            .unwrap_or_default(),
    }
}

/// TextMate root scope of a document in `language` (VS Code's names).
pub fn root_scope(language: LanguageId) -> &'static str {
    use LanguageId as L;
    match language {
        L::Rust => "source.rust",
        L::Python => "source.python",
        L::JavaScript => "source.js",
        L::TypeScript => "source.ts",
        L::Tsx => "source.tsx",
        L::Json => "source.json",
        L::Markdown | L::MarkdownInline => "text.html.markdown",
        L::C => "source.c",
        L::Cpp => "source.cpp",
        L::Go => "source.go",
        L::Java => "source.java",
        L::Kotlin => "source.kotlin",
        L::Html => "text.html.basic",
        L::Css => "source.css",
        L::Yaml => "source.yaml",
        L::Toml => "source.toml",
        L::Bash => "source.shell",
        L::Sql => "source.sql",
    }
}

/// The language suffix TextMate grammars append to token scopes.
fn suffix(language: LanguageId) -> &'static str {
    use LanguageId as L;
    match language {
        L::Rust => "rust",
        L::Python => "python",
        L::JavaScript => "js",
        L::TypeScript => "ts",
        L::Tsx => "tsx",
        L::Json => "json",
        L::Markdown | L::MarkdownInline => "markdown",
        L::C => "c",
        L::Cpp => "cpp",
        L::Go => "go",
        L::Java => "java",
        L::Kotlin => "kotlin",
        L::Html => "html",
        L::Css => "css",
        L::Yaml => "yaml",
        L::Toml => "toml",
        L::Bash => "shell",
        L::Sql => "sql",
    }
}

const CONTROL: &[&str] = &[
    "if",
    "else",
    "elif",
    "for",
    "while",
    "loop",
    "do",
    "match",
    "switch",
    "case",
    "default",
    "return",
    "break",
    "continue",
    "goto",
    "try",
    "catch",
    "except",
    "finally",
    "throw",
    "raise",
    "yield",
    "await",
    "when",
    "then",
    "fi",
    "esac",
    "done",
    "in",
    "with",
    "pass",
    "select",
    "go",
    "defer",
    "fallthrough",
    "until",
    "unless",
    "assert",
    "range",
];
const IMPORT: &[&str] = &[
    "import", "export", "from", "use", "package", "require", "extern", "#include", "include",
];
const OPERATOR_WORDS: &[&str] = &[
    "new",
    "delete",
    "typeof",
    "instanceof",
    "sizeof",
    "alignof",
    "and",
    "or",
    "not",
    "is",
    "as",
    "keyof",
    "void",
    "satisfies",
    "!in",
    "!is",
    "as?",
];
const MODIFIERS: &[&str] = &[
    "pub",
    "public",
    "private",
    "protected",
    "internal",
    "static",
    "final",
    "abstract",
    "mut",
    "async",
    "readonly",
    "override",
    "sealed",
    "open",
    "inline",
    "unsafe",
    "volatile",
    "const",
    "constexpr",
    "virtual",
    "explicit",
    "data",
    "lateinit",
    "suspend",
    "operator",
    "infix",
    "tailrec",
    "external",
    "synchronized",
    "transient",
    "native",
    "strictfp",
    "default",
    "declare",
    "export",
    "ref",
    "move",
    "dyn",
    "inner",
    "companion",
    "value",
    "enum",
    "annotation",
    "local",
    "readonly",
];

/// TextMate token scope (without the language suffix) for a capture, given
/// the token's text. `""` adds no token scope (the root scope alone).
pub(crate) fn token_scope(capture: &str, language: LanguageId, text: &str, node: Node) -> &'static str {
    use LanguageId as L;
    if let Some(scope) = refine(capture, language, text, node) {
        return scope;
    }
    let mut parts = capture.split('.');
    let head = parts.next().unwrap_or("");
    let sub = parts.next().unwrap_or("");
    let sub2 = parts.next().unwrap_or("");
    match (head, sub) {
        ("comment", _) => {
            let t = text.trim_start();
            if t.starts_with("///") || t.starts_with("//!") {
                "comment.line.documentation"
            } else if t.starts_with("/**") {
                "comment.block.documentation"
            } else if t.starts_with("/*") || t.starts_with("<!--") {
                "comment.block"
            } else {
                "comment.line"
            }
        }
        ("string", "escape") | ("escape", _) => "constant.character.escape",
        ("string", "regex" | "regexp") => "string.regexp",
        ("string", "special") if sub2 == "key" => "support.type.property-name",
        ("string", "special") if language == L::Css => "constant.other.color.rgb-value",
        ("string", "special") if language == L::Toml => "constant.other.time",
        ("string", "special") => "string.other",
        ("string" | "character", _) | ("text", "literal") | ("markup", "raw") => match language {
            L::Markdown | L::MarkdownInline => "markup.inline.raw.string",
            L::Yaml if !text.starts_with(['"', '\'']) => "string.unquoted.plain.out",
            L::Css if !text.starts_with(['"', '\'']) => "support.constant.property-value",
            _ if text.starts_with('`') => "string.template",
            _ if text.starts_with('\'') => "string.quoted.single",
            _ if text.starts_with("\"\"\"") => "string.quoted.docstring.multi",
            _ if text.starts_with('"') || text.starts_with("f\"") || text.starts_with("r\"") => {
                "string.quoted.double"
            }
            _ if text.starts_with('<') => "string.quoted.other.lt-gt",
            _ => "string",
        },
        ("number" | "float", _) => "constant.numeric",
        ("boolean", _) => "constant.language.boolean",
        ("constant", "builtin") => match text {
            "true" | "false" | "True" | "False" | "TRUE" | "FALSE" => "constant.language.boolean",
            "null" | "nil" | "None" | "NULL" | "nullptr" | "undefined" | "~" => "constant.language.null",
            _ if text.starts_with(|c: char| c.is_ascii_digit()) => "constant.numeric",
            _ => "constant.language",
        },
        ("constant", "macro") => "entity.name.function.preprocessor",
        ("constant", _) => match language {
            L::Bash => "constant.other.option",
            _ if text.starts_with(|c: char| c.is_ascii_digit()) => "constant.numeric",
            _ => "variable.other.constant",
        },
        ("function" | "method", "builtin") => "support.function.builtin",
        ("function", "macro") => "entity.name.function.macro",
        ("function" | "method", _) => match language {
            L::Bash => "entity.name.command",
            L::Css => "support.function",
            _ => "entity.name.function",
        },
        ("constructor", _) => "entity.name.type.class",
        ("type", "builtin") => "support.type.primitive",
        ("type", "qualifier") => "storage.modifier",
        ("type", _) => match language {
            L::Css => "keyword.other.unit",
            L::Toml => "entity.name.table",
            _ => "entity.name.type",
        },
        ("module" | "namespace", _) => "entity.name.namespace",
        ("attribute", _) => match language {
            L::Rust => "meta.attribute",
            L::Java | L::Kotlin | L::Python => "storage.type.annotation",
            L::Css => "entity.other.attribute-name.class",
            L::Sql => "keyword.other",
            _ => "entity.other.attribute-name",
        },
        ("tag", "delimiter") => "punctuation.definition.tag",
        ("tag", _) => "entity.name.tag",
        ("label", _) => match language {
            L::Yaml => "variable.other.anchor",
            _ => "entity.name.label",
        },
        ("variable", "builtin") => "variable.language",
        ("variable", "parameter") | ("parameter", _) => "variable.parameter",
        ("variable", "member") | ("property" | "field", _) => match language {
            L::Yaml | L::Toml => "entity.name.tag",
            L::Css => "support.type.property-name",
            L::Json => "support.type.property-name",
            L::Bash => "variable.other.normal",
            L::Html => "entity.other.attribute-name",
            _ => "variable.other.property",
        },
        ("variable", _) => match language {
            L::Css => "variable.css",
            _ => "variable.other.readwrite",
        },
        ("operator", _) | ("keyword", "operator") => {
            if text.chars().all(|c| c.is_alphabetic()) && !text.is_empty() {
                "keyword.operator.expression"
            } else {
                "keyword.operator"
            }
        }
        ("keyword" | "conditional" | "repeat" | "include" | "exception" | "storageclass", _) => {
            keyword_scope(head, sub, language, text)
        }
        ("punctuation", "bracket") => match text {
            "(" | ")" => "meta.brace.round",
            "[" | "]" => "meta.brace.square",
            "{" | "}" => "punctuation.definition.block",
            "<" | ">" | "</" | "/>" if language == L::Html => "punctuation.definition.tag",
            _ => "punctuation",
        },
        ("punctuation", "delimiter") | ("delimiter", _) => match text {
            "," => "punctuation.separator.comma",
            ";" => "punctuation.terminator",
            "." | "::" | "?." => "punctuation.accessor",
            ":" => "punctuation.separator.key-value",
            _ if language == L::Markdown || language == L::MarkdownInline => {
                "punctuation.definition.markdown"
            }
            _ => "punctuation.separator",
        },
        ("punctuation", "special") => match language {
            L::Markdown if text.starts_with('#') => "punctuation.definition.heading",
            L::Markdown if text.starts_with('>') => "punctuation.definition.quote.begin",
            L::Markdown => "punctuation.definition.list.begin",
            _ if text.starts_with("${") || text == "}" || text == "$" => {
                "punctuation.definition.template-expression"
            }
            _ => "punctuation.definition",
        },
        ("punctuation", _) => "punctuation",
        ("text", "title") | ("markup", "heading") => "markup.heading",
        ("text", "emphasis") | ("markup", "italic") => "markup.italic",
        ("text", "strong") | ("markup", "strong" | "bold") => "markup.bold",
        ("text", "strike") | ("markup", "strikethrough") => "markup.strikethrough",
        ("text", "uri") | ("markup", "link") if sub2 == "label" => "string.other.link",
        ("text", "uri") | ("markup", "link") => "markup.underline.link",
        ("text", "reference") => "string.other.link.title",
        ("markup", "quote") => "markup.quote",
        _ => "",
    }
}

/// Language-specific scopes that need the syntax tree, mirroring how VS
/// Code's grammars scope the same tokens.
fn refine(capture: &str, language: LanguageId, text: &str, node: Node) -> Option<&'static str> {
    use LanguageId as L;
    let head = capture.split('.').next().unwrap_or("");
    let parent = node.parent();
    let parent_kind = parent.map_or("", |p| p.kind());
    let has_ancestor = |kinds: &[&str]| {
        let mut n = node.parent();
        while let Some(p) = n {
            if kinds.contains(&p.kind()) {
                return true;
            }
            n = p.parent();
        }
        false
    };
    Some(match (language, head) {
        (L::C | L::Cpp, "type") if matches!(node.kind(), "primitive_type" | "sized_type_specifier") => {
            "storage.type.built-in.primitive"
        }
        (L::C | L::Cpp, "constant") if parent_kind == "preproc_def" => "entity.name.function.preprocessor",
        (L::C | L::Cpp, "constant") => "",
        (L::C | L::Cpp, "type") if text == "auto" => "storage.type.built-in.primitive",
        (L::C | L::Cpp, "variable") if has_ancestor(&["parameter_declaration"]) => "variable.parameter",
        // VS Code's C grammar leaves plain identifiers unscoped.
        (L::C | L::Cpp, "variable") => "",
        (L::Java, "type")
            if matches!(
                node.kind(),
                "integral_type" | "floating_point_type" | "void_type" | "boolean_type"
            ) =>
        {
            "storage.type.primitive"
        }
        (L::Java, "type") if parent_kind == "type_arguments" => "storage.type.generic",
        (L::Java, "type")
            if !matches!(
                parent_kind,
                "class_declaration" | "record_declaration" | "interface_declaration" | "enum_declaration"
            ) =>
        {
            "storage.type"
        }
        (L::Java, "variable" | "type" | "constant") if has_ancestor(&["import_declaration"]) => {
            "storage.modifier.import"
        }
        (L::Java, "variable" | "type" | "constant") if has_ancestor(&["package_declaration"]) => {
            "storage.modifier.package"
        }
        (L::Python, "variable") => "",
        (L::Python, "function" | "constructor" | "type")
            if parent_kind == "call" && (text.ends_with("Error") || text.ends_with("Exception")) =>
        {
            "support.type.exception"
        }
        (L::Python, "type")
            if matches!(
                text,
                "int" | "float" | "str" | "bool" | "list" | "dict" | "set" | "tuple" | "bytes" | "object"
            ) =>
        {
            "support.type"
        }
        (L::Python, "function" | "constructor")
            if parent_kind == "call"
                || parent_kind == "attribute"
                    && parent
                        .and_then(|p| p.parent())
                        .is_some_and(|g| g.kind() == "call") =>
        {
            "meta.function-call.generic"
        }
        (L::Python, "constructor") if parent_kind == "call" => "meta.function-call.generic",
        (L::JavaScript | L::TypeScript | L::Tsx, "variable")
            if parent_kind == "member_expression"
                && parent.and_then(|p| p.child_by_field_name("object")) == Some(node) =>
        {
            "variable.other.object"
        }
        (L::JavaScript | L::TypeScript | L::Tsx, "variable")
            if parent_kind == "variable_declarator"
                && parent
                    .and_then(|p| p.parent())
                    .and_then(|d| d.child(0))
                    .is_some_and(|k| k.kind() == "const") =>
        {
            "variable.other.constant"
        }
        (L::Bash, "function")
            if matches!(
                text,
                "echo"
                    | "set"
                    | "exit"
                    | "shift"
                    | "read"
                    | "cd"
                    | "export"
                    | "unset"
                    | "readonly"
                    | "local"
                    | "return"
                    | "getopts"
                    | "printf"
                    | "source"
                    | "eval"
                    | "exec"
                    | "trap"
                    | "test"
                    | "true"
                    | "false"
                    | "type"
                    | "alias"
                    | "command"
                    | "pwd"
                    | "wait"
                    | "declare"
                    | "let"
                    | "."
            ) =>
        {
            "support.function.builtin"
        }
        (L::Css, "constant") => "support.constant.property-value",
        (L::Rust, "variable" | "type" | "module")
            if matches!(
                parent_kind,
                "scoped_identifier" | "scoped_type_identifier" | "scoped_use_list"
            ) && parent.and_then(|p| p.child_by_field_name("path")) == Some(node) =>
        {
            "entity.name.namespace"
        }
        (L::Rust, "variable") => "variable.other",
        (L::Rust, "punctuation") if text == "." => "keyword.operator.access.dot",
        (L::Go, "type")
            if matches!(
                text,
                "string"
                    | "int"
                    | "int8"
                    | "int16"
                    | "int32"
                    | "int64"
                    | "uint"
                    | "uint8"
                    | "uint16"
                    | "uint32"
                    | "uint64"
                    | "uintptr"
                    | "bool"
                    | "byte"
                    | "rune"
                    | "error"
                    | "float32"
                    | "float64"
                    | "complex64"
                    | "complex128"
                    | "any"
            ) =>
        {
            "storage.type"
        }
        (L::Java, "keyword") if matches!(text, "class" | "interface" | "enum" | "record") => {
            "storage.modifier"
        }
        (L::TypeScript | L::Tsx | L::JavaScript, "function") if text == "constructor" => "storage.type",
        (L::Sql, "type") if node.kind().starts_with("keyword_") => "storage.type",
        (L::Sql, "type" | "field" | "variable" | "attribute") => "",
        (L::Sql, "function") => "support.function",
        (L::Sql, "operator" | "keyword") if text.chars().all(|c| c.is_ascii_alphabetic()) => "keyword.other",
        _ => return None,
    })
}

fn keyword_scope(head: &str, sub: &str, language: LanguageId, text: &str) -> &'static str {
    use LanguageId as L;
    let lower = text.to_ascii_lowercase();
    let word = lower.as_str();
    match (head, sub) {
        ("conditional" | "repeat" | "exception", _)
        | ("keyword", "conditional" | "repeat" | "return" | "exception") => {
            return "keyword.control";
        }
        ("include", _) | ("keyword", "import") => return "keyword.control.import",
        ("keyword", "directive") => return "keyword.control.directive",
        ("storageclass", _) | ("keyword", "modifier") => return "storage.modifier",
        ("keyword", "function") if language != L::Rust => return "storage.type.function",
        _ => {}
    }
    if language == L::Sql {
        return "keyword.other";
    }
    if text.starts_with('#') {
        return "keyword.control.directive";
    }
    if text.starts_with('@') && language == L::Css {
        return "keyword.control.at-rule";
    }
    if CONTROL.contains(&word) {
        return "keyword.control";
    }
    if IMPORT.contains(&word) {
        return if language == L::Rust {
            "keyword.other"
        } else {
            "keyword.control.import"
        };
    }
    if OPERATOR_WORDS.contains(&word) {
        return "keyword.operator.expression";
    }
    if MODIFIERS.contains(&word) {
        return "storage.modifier";
    }
    match language {
        L::Rust
            if matches!(
                word,
                "fn" | "let"
                    | "struct"
                    | "impl"
                    | "trait"
                    | "type"
                    | "mod"
                    | "where"
                    | "crate"
                    | "super"
                    | "self"
            ) =>
        {
            "keyword.other"
        }
        L::Bash | L::Css => "keyword.other",
        _ => "storage.type",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keywords_split_like_textmate_grammars() {
        let k = |t: &str, l| keyword_scope("keyword", "", l, t);
        assert_eq!(k("if", LanguageId::Rust), "keyword.control");
        assert_eq!(k("return", LanguageId::JavaScript), "keyword.control");
        assert_eq!(k("import", LanguageId::JavaScript), "keyword.control.import");
        assert_eq!(k("fn", LanguageId::Rust), "keyword.other");
        assert_eq!(k("new", LanguageId::Java), "keyword.operator.expression");
        assert_eq!(k("SELECT", LanguageId::Sql), "keyword.other");
        assert_eq!(
            keyword_scope("keyword", "function", LanguageId::Python, "def"),
            "storage.type.function"
        );
    }

    #[test]
    fn stacks_include_root_and_embedding() {
        let html = root_layer(LanguageId::Html);
        let css = embedded_layer(html, LanguageId::Css);
        let id = intern(
            Parent::Layer(css),
            "support.type.property-name",
            LanguageId::Css,
            HighlightKind::Property,
        );
        assert_eq!(
            scope_stack(id),
            [
                "text.html.basic",
                "meta.embedded.block.css",
                "source.css",
                "support.type.property-name.css"
            ]
        );
        assert_eq!(kind_of(id), HighlightKind::Property);
        // Interning is stable.
        assert_eq!(
            id,
            intern(
                Parent::Layer(css),
                "support.type.property-name",
                LanguageId::Css,
                HighlightKind::Property
            )
        );
        // Nested tokens extend the enclosing stack.
        let s = intern(
            Parent::Layer(html),
            "string.quoted.double",
            LanguageId::Html,
            HighlightKind::String,
        );
        let q = intern(
            Parent::Style(s),
            "punctuation.definition.string.begin",
            LanguageId::Html,
            HighlightKind::String,
        );
        assert_eq!(
            scope_stack(q),
            [
                "text.html.basic",
                "string.quoted.double.html",
                "punctuation.definition.string.begin.html"
            ]
        );
        assert_eq!(parent_of(q), Some(Parent::Style(s)));
        // Markdown inline refines its host: no extra layer.
        let md = root_layer(LanguageId::Markdown);
        assert_eq!(embedded_layer(md, LanguageId::MarkdownInline), md);
        assert!(scope_stack(0).is_empty());
    }
}
