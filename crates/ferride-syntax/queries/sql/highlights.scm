; Appended after tree-sitter-sequel's highlights.scm. Its number patterns use
; Neovim's Lua patterns (`%d`), which tree-sitter's regex predicates don't
; understand, so numeric literals came out as strings. Later patterns win.

((literal) @number
  (#match? @number "^[-+]?[0-9]+$"))

((literal) @number
  (#match? @number "^[-+]?[0-9]*\\.[0-9]*$"))
