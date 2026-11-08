package dev.ferride.workbench

/**
 * One language Ferride knows: VS Code's language id (also the LSP
 * `languageId`), the name VS Code shows in the status bar and in Change
 * Language Mode, a sample file name (for its file icon) and the language
 * server that will run in Ferrux.
 *
 * Grammars and detection (file name, extension, `#!` line) live in Rust
 * (`ferride-syntax`); this is only the presentation side. A JVM test checks
 * that both list the same ids.
 */
data class LanguageInfo(val id: String, val name: String, val iconFile: String, val server: String)

object Languages {
    /** Plain text: no grammar. VS Code's id for it is `plaintext`. */
    const val PLAIN_TEXT_ID = "plaintext"
    const val PLAIN_TEXT_NAME = "Plain Text"

    /** Every bundled grammar, sorted by name like VS Code's language picker. */
    val all: List<LanguageInfo> = listOf(
        LanguageInfo("c", "C", "x.c", "clangd"),
        LanguageInfo("cpp", "C++", "x.cpp", "clangd"),
        LanguageInfo("css", "CSS", "x.css", "vscode-css-language-server"),
        LanguageInfo("go", "Go", "x.go", "gopls"),
        LanguageInfo("html", "HTML", "x.html", "vscode-html-language-server"),
        LanguageInfo("java", "Java", "x.java", "jdtls"),
        LanguageInfo("javascript", "JavaScript", "x.js", "typescript-language-server"),
        LanguageInfo("json", "JSON", "x.json", "vscode-json-language-server"),
        LanguageInfo("kotlin", "Kotlin", "x.kt", "kotlin-language-server"),
        LanguageInfo("markdown", "Markdown", "x.md", "marksman"),
        LanguageInfo("python", "Python", "x.py", "basedpyright / pyright"),
        LanguageInfo("rust", "Rust", "x.rs", "rust-analyzer"),
        LanguageInfo("shellscript", "Shell Script", "x.sh", "bash-language-server"),
        LanguageInfo("sql", "SQL", "x.sql", "sqls"),
        LanguageInfo("toml", "TOML", "x.toml", "taplo"),
        LanguageInfo("typescript", "TypeScript", "x.ts", "typescript-language-server"),
        LanguageInfo("typescriptreact", "TypeScript JSX", "x.tsx", "typescript-language-server"),
        LanguageInfo("yaml", "YAML", "x.yaml", "yaml-language-server"),
    )

    private val byId = all.associateBy { it.id }

    fun get(id: String?): LanguageInfo? = id?.let(byId::get)

    /** Status bar / picker name for a language id (`null` = Plain Text). */
    fun displayName(id: String?): String = when (id) {
        null, PLAIN_TEXT_ID -> PLAIN_TEXT_NAME
        else -> byId[id]?.name ?: id
    }
}
