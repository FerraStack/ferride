; Highlights for tree-sitter-kotlin-ng (which ships no queries).
; Written for Ferride; later patterns win over earlier ones for the same node.

(identifier) @variable

; Types
(user_type (identifier) @type)
(class_declaration name: (identifier) @type)
(object_declaration name: (identifier) @type)
(type_alias type: (identifier) @type)
(enum_entry (identifier) @constant)

((identifier) @type
  (#match? @type "^[A-Z][a-z0-9]"))
((identifier) @constant
  (#match? @constant "^[A-Z][A-Z0-9_]+$"))

; Properties (refined to methods below when called)
(navigation_expression (identifier) @property .)

; Functions
(function_declaration name: (identifier) @function)
(call_expression (identifier) @function.call)
(call_expression (navigation_expression (identifier) @function.method .))
(secondary_constructor "constructor" @keyword)

; Parameters
(parameter (identifier) @variable.parameter)
(class_parameter (identifier) @variable.parameter)
(value_argument (identifier) @variable.parameter "=")
(lambda_parameters (variable_declaration (identifier) @variable.parameter))

; `this` / `super`
(this_expression) @variable.builtin
(super_expression) @variable.builtin

; Annotations and labels
(annotation "@" @attribute)
(annotation (user_type (identifier) @attribute))
(annotation (constructor_invocation (user_type (identifier) @attribute)))
(file_annotation) @attribute
(label) @label

; Packages and imports
(package_header (qualified_identifier (identifier) @module))
(import (qualified_identifier (identifier) @module))

; Literals
(string_literal) @string
(multiline_string_literal) @string
(character_literal) @string
(string_content) @string
(escape_sequence) @string.escape
(interpolation "${" @punctuation.special "}" @punctuation.special)
(interpolation "$" @punctuation.special)
(number_literal) @number
(float_literal) @number
; true/false/null are plain identifiers in this grammar.
((identifier) @boolean
  (#any-of? @boolean "true" "false"))
((identifier) @constant.builtin
  (#eq? @constant.builtin "null"))

; Comments
(line_comment) @comment
(block_comment) @comment
(shebang) @comment

; Keywords
[
  "as" "as?" "by" "catch" "class" "companion" "constructor" "do" "else" "enum"
  "finally" "for" "fun" "get" "if" "import" "in" "!in" "init" "interface" "is" "!is"
  "object" "package" "return" "return@" "set" "super" "super@" "this" "this@"
  "throw" "try" "typealias" "val" "var" "when" "where" "while" "field" "file"
  "property" "receiver" "param" "setparam" "delegate" "dynamic"
] @keyword

[
  (visibility_modifier) (inheritance_modifier) (member_modifier) (class_modifier)
  (function_modifier) (property_modifier) (parameter_modifier) (platform_modifier)
  (variance_modifier) (reification_modifier)
] @keyword.modifier

; Operators and punctuation
[
  "!" "!!" "!=" "!==" "%" "%=" "&&" "*" "*=" "+" "++" "+=" "-" "--" "-=" "->"
  ".." "..<" "/" "/=" "<" "<=" "=" "==" "===" ">" ">=" "?:" "||" "::" "?."
] @operator

["(" ")" "[" "]" "{" "}"] @punctuation.bracket
(type_arguments ["<" ">"] @punctuation.bracket)
(type_parameters ["<" ">"] @punctuation.bracket)
["," "." ":" ";"] @punctuation.delimiter
