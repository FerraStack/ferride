(function_declaration name: (identifier) @name) @symbol.function
(method_declaration name: (field_identifier) @name) @symbol.method
(type_spec name: (type_identifier) @name type: (struct_type)) @symbol.struct
(type_spec name: (type_identifier) @name type: (interface_type)) @symbol.interface
(type_spec name: (type_identifier) @name type: [(type_identifier) (qualified_type) (pointer_type) (slice_type) (map_type) (function_type) (generic_type) (array_type) (channel_type)]) @symbol.class
(field_declaration name: (field_identifier) @name) @symbol.field
(method_elem name: (field_identifier) @name) @symbol.method
(source_file (const_declaration (const_spec name: (identifier) @name) @symbol.constant))
(source_file (var_declaration (var_spec name: (identifier) @name) @symbol.variable))
