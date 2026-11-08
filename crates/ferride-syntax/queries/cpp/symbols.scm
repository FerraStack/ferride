(namespace_definition name: (_) @name) @symbol.namespace
(class_specifier name: (_) @name body: (_)) @symbol.class
(function_definition declarator: (function_declarator declarator: (qualified_identifier) @name)) @symbol.method
(field_declaration_list (function_definition declarator: (function_declarator declarator: (_) @name)) @symbol.method)
(field_declaration_list (declaration declarator: (function_declarator declarator: (_) @name)) @symbol.method)
