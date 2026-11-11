(function_definition name: (word) @name) @symbol.function
(program (variable_assignment name: (variable_name) @name) @symbol.variable)
(program (declaration_command (variable_assignment name: (variable_name) @name)) @symbol.variable)
