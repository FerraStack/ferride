; TOML highlights for tree-sitter-toml-ng, written for Ferride (token
; captures only; later patterns win). Table headers are types, keys are
; properties, like the TOML grammars VS Code users install.

(bare_key) @property
(quoted_key) @property
(dotted_key (bare_key) @property)

(table (bare_key) @type)
(table (dotted_key (bare_key) @type))
(table (quoted_key) @type)
(table_array_element (bare_key) @type)
(table_array_element (dotted_key (bare_key) @type))
(table_array_element (quoted_key) @type)

(comment) @comment
(string) @string
(escape_sequence) @string.escape
(boolean) @boolean
[(integer) (float)] @number
[(offset_date_time) (local_date_time) (local_date) (local_time)] @number

"=" @operator
["." ","] @punctuation.delimiter
["[" "]" "[[" "]]" "{" "}"] @punctuation.bracket
