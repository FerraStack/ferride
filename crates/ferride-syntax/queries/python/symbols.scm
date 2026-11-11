(class_definition name: (identifier) @name) @symbol.class
(function_definition name: (identifier) @name) @symbol.function
(class_definition body: (block (function_definition name: (identifier) @name) @symbol.method))
(class_definition body: (block (decorated_definition (function_definition name: (identifier) @name) @symbol.method)))
(module (expression_statement (assignment left: (identifier) @name) @symbol.variable))
(class_definition body: (block (expression_statement (assignment left: (identifier) @name) @symbol.field)))
