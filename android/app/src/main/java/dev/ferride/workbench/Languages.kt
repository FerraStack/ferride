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
data class LanguageInfo(
    val id: String,
    val name: String,
    val iconFile: String,
    val server: String,
    val serverCommands: List<List<String>> = emptyList(),
)

object Languages {
    /** Plain text: no grammar. VS Code's id for it is `plaintext`. */
    const val PLAIN_TEXT_ID = "plaintext"
    const val PLAIN_TEXT_NAME = "Plain Text"

    /** Every bundled grammar, sorted by name like VS Code's language picker. */
    val all: List<LanguageInfo> = listOf(
        LanguageInfo("c", "C", "x.c", "clangd", listOf(listOf("clangd"))),
        LanguageInfo("cpp", "C++", "x.cpp", "clangd", listOf(listOf("clangd"))),
        LanguageInfo("css", "CSS", "x.css", "vscode-css-language-server", listOf(listOf("vscode-css-language-server", "--stdio"))),
        LanguageInfo("go", "Go", "x.go", "gopls", listOf(listOf("gopls"))),
        LanguageInfo("html", "HTML", "x.html", "vscode-html-language-server", listOf(listOf("vscode-html-language-server", "--stdio"))),
        LanguageInfo("java", "Java", "x.java", "jdtls", listOf(listOf("jdtls"))),
        LanguageInfo(
            "javascript",
            "JavaScript",
            "x.js",
            "typescript-language-server",
            listOf(listOf("typescript-language-server", "--stdio"), listOf("vtsls", "--stdio")),
        ),
        LanguageInfo("json", "JSON", "x.json", "vscode-json-language-server", listOf(listOf("vscode-json-language-server", "--stdio"))),
        LanguageInfo("kotlin", "Kotlin", "x.kt", "kotlin-language-server", listOf(listOf("kotlin-language-server"))),
        LanguageInfo("markdown", "Markdown", "x.md", "marksman", listOf(listOf("marksman", "server"))),
        LanguageInfo(
            "python",
            "Python",
            "x.py",
            "basedpyright / pyright",
            listOf(
                listOf("basedpyright-langserver", "--stdio"),
                listOf("pyright-langserver", "--stdio"),
                listOf("pylsp"),
            ),
        ),
        LanguageInfo("rust", "Rust", "x.rs", "rust-analyzer", listOf(listOf("rust-analyzer"))),
        LanguageInfo("shellscript", "Shell Script", "x.sh", "bash-language-server", listOf(listOf("bash-language-server", "start"))),
        LanguageInfo("sql", "SQL", "x.sql", "sqls", listOf(listOf("sqls"))),
        LanguageInfo("toml", "TOML", "x.toml", "taplo", listOf(listOf("taplo", "lsp", "stdio"))),
        LanguageInfo(
            "typescript",
            "TypeScript",
            "x.ts",
            "typescript-language-server",
            listOf(listOf("typescript-language-server", "--stdio"), listOf("vtsls", "--stdio")),
        ),
        LanguageInfo(
            "typescriptreact",
            "TypeScript JSX",
            "x.tsx",
            "typescript-language-server",
            listOf(listOf("typescript-language-server", "--stdio"), listOf("vtsls", "--stdio")),
        ),
        LanguageInfo("yaml", "YAML", "x.yaml", "yaml-language-server", listOf(listOf("yaml-language-server", "--stdio"))),
    )

    private val byId = all.associateBy { it.id }

    fun get(id: String?): LanguageInfo? = id?.let(byId::get)

    /** Status bar / picker name for a language id (`null` = Plain Text). */
    fun displayName(id: String?): String = when (id) {
        null, PLAIN_TEXT_ID -> PLAIN_TEXT_NAME
        else -> byId[id]?.name ?: id
    }
}
