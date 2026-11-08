(function_declaration name: (identifier) @name) @symbol.function
(generator_function_declaration name: (identifier) @name) @symbol.function
(class_declaration name: (_) @name) @symbol.class
(method_definition name: (_) @name) @symbol.method
(program (lexical_declaration (variable_declarator name: (identifier) @name) @symbol.constant))
; Later patterns win for the same node: `const f = () => …` is a function.
(variable_declarator name: (identifier) @name value: [(arrow_function) (function_expression)]) @symbol.function
(program (variable_declaration (variable_declarator name: (identifier) @name) @symbol.variable))
(program (export_statement (lexical_declaration (variable_declarator name: (identifier) @name) @symbol.constant)))
