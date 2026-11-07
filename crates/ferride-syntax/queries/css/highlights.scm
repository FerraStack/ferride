; Appended to tree-sitter-css's highlights (later patterns win).
; Class and id selectors are attribute names in VS Code's CSS grammar
; (`entity.other.attribute-name.class.css`), not properties.
(class_selector (class_name) @attribute)
(id_selector (id_name) @attribute)

; Custom properties (`--accent`) are variables; upstream lists this before
; the generic `(property_name) @property`, which would win here.
((property_name) @variable
  (#match? @variable "^--"))
