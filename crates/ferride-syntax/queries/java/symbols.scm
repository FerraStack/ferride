(class_declaration name: (identifier) @name) @symbol.class
(record_declaration name: (identifier) @name) @symbol.struct
(interface_declaration name: (identifier) @name) @symbol.interface
(enum_declaration name: (identifier) @name) @symbol.enum
(enum_constant name: (identifier) @name) @symbol.enummember
(annotation_type_declaration name: (identifier) @name) @symbol.interface
(method_declaration name: (identifier) @name) @symbol.method
(constructor_declaration name: (identifier) @name) @symbol.constructor
(field_declaration declarator: (variable_declarator name: (identifier) @name)) @symbol.field
(constant_declaration declarator: (variable_declarator name: (identifier) @name)) @symbol.constant
