package dev.ferride.ui

import androidx.compose.ui.graphics.Color

/**
 * Codicon and color for an LSP `SymbolKind` (1 File … 26 TypeParameter),
 * like VS Code's Outline, breadcrumbs and Go to Symbol: `symbol-*` codicons
 * tinted with the default `symbolIcon.*Foreground` colors.
 */
object SymbolIcons {
    private const val ORANGE_DARK = 0xFFEE9D28
    private const val PURPLE_DARK = 0xFFB180D7
    private const val BLUE_DARK = 0xFF75BEFF
    private const val ORANGE_LIGHT = 0xFFD67E00
    private const val PURPLE_LIGHT = 0xFF652D90
    private const val BLUE_LIGHT = 0xFF007ACC

    fun glyph(kind: Int): Char = when (kind) {
        1 -> '' // file
        2, 3, 4, 19 -> '' // module, namespace, package, object
        5 -> '' // class
        6, 9, 12 -> '' // method, constructor, function
        7 -> '' // property
        8 -> '' // field
        10 -> '' // enum
        11 -> '' // interface
        13 -> '' // variable
        14 -> '' // constant
        15 -> '' // string
        16 -> '' // number
        17, 21 -> '' // boolean, null
        18 -> '' // array
        20 -> '' // key
        22 -> '' // enum member
        23 -> '' // struct
        24 -> '' // event
        25 -> '' // operator
        26 -> '' // type parameter
        else -> '' // misc
    }

    fun color(kind: Int, dark: Boolean, foreground: Color): Color = when (kind) {
        5, 10, 24 -> Color(if (dark) ORANGE_DARK else ORANGE_LIGHT) // class, enum, event
        6, 9, 12 -> Color(if (dark) PURPLE_DARK else PURPLE_LIGHT) // method, constructor, function
        8, 11, 13, 22 -> Color(if (dark) BLUE_DARK else BLUE_LIGHT) // field, interface, variable, enum member
        else -> foreground
    }

    /** VS Code's lower-case kind names, used as the Quick Open description. */
    fun label(kind: Int): String = when (kind) {
        1 -> "file"; 2 -> "module"; 3 -> "namespace"; 4 -> "package"; 5 -> "class"; 6 -> "method"
        7 -> "property"; 8 -> "field"; 9 -> "constructor"; 10 -> "enum"; 11 -> "interface"
        12 -> "function"; 13 -> "variable"; 14 -> "constant"; 15 -> "string"; 16 -> "number"
        17 -> "boolean"; 18 -> "array"; 19 -> "object"; 20 -> "key"; 21 -> "null"
        22 -> "enum member"; 23 -> "struct"; 24 -> "event"; 25 -> "operator"; 26 -> "type parameter"
        else -> "symbol"
    }
}
