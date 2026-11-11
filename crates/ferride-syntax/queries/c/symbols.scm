(function_definition declarator: (function_declarator declarator: (identifier) @name)) @symbol.function
(function_definition declarator: (pointer_declarator declarator: (function_declarator declarator: (identifier) @name))) @symbol.function
(struct_specifier name: (type_identifier) @name body: (_)) @symbol.struct
(union_specifier name: (type_identifier) @name body: (_)) @symbol.struct
(enum_specifier name: (type_identifier) @name body: (_)) @symbol.enum
(enumerator name: (identifier) @name) @symbol.enummember
(field_declaration declarator: (field_identifier) @name) @symbol.field
(field_declaration declarator: (pointer_declarator declarator: (field_identifier) @name)) @symbol.field
(type_definition declarator: (type_identifier) @name) @symbol.struct
(preproc_def name: (identifier) @name) @symbol.constant
(preproc_function_def name: (identifier) @name) @symbol.function
