(class_declaration name: (identifier) @name) @symbol.class
(object_declaration name: (identifier) @name) @symbol.object
(companion_object) @symbol.object @name
(function_declaration name: (identifier) @name) @symbol.function
(class_body (function_declaration name: (identifier) @name) @symbol.method)
(class_body (property_declaration (variable_declaration (identifier) @name)) @symbol.property)
(source_file (property_declaration (variable_declaration (identifier) @name)) @symbol.variable)
(class_parameter (identifier) @name) @symbol.property
(enum_entry (identifier) @name) @symbol.enummember
(type_alias type: (identifier) @name) @symbol.typeparameter
