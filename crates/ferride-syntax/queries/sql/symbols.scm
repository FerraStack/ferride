(create_table (object_reference name: (identifier) @name)) @symbol.struct
(column_definition name: (identifier) @name) @symbol.field
(create_view (object_reference name: (identifier) @name)) @symbol.interface
(create_function (object_reference name: (identifier) @name)) @symbol.function
(create_index column: (identifier) @name) @symbol.key
