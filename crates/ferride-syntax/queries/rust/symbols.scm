; Outline symbols: @symbol.<LSP kind> on the definition, @name on its name.
(mod_item name: (identifier) @name) @symbol.module
(struct_item name: (type_identifier) @name) @symbol.struct
(union_item name: (type_identifier) @name) @symbol.struct
(enum_item name: (type_identifier) @name) @symbol.enum
(enum_variant name: (identifier) @name) @symbol.enummember
(field_declaration name: (field_identifier) @name) @symbol.field
(trait_item name: (type_identifier) @name) @symbol.interface
; `impl Circle`, `impl Display for Circle`: named by their header.
(impl_item) @symbol.object @name
(function_item name: (identifier) @name) @symbol.function
(declaration_list (function_item name: (identifier) @name) @symbol.method)
(function_signature_item name: (identifier) @name) @symbol.method
(const_item name: (identifier) @name) @symbol.constant
(static_item name: (identifier) @name) @symbol.variable
(type_item name: (type_identifier) @name) @symbol.typeparameter
(macro_definition name: (identifier) @name) @symbol.function
