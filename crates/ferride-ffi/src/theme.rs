use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use crate::FerrideError;

/// Style of a token in a VS Code color theme.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct TokenStyle {
    /// ARGB.
    pub foreground: u32,
    /// Bits: 1 italic, 2 bold, 4 underline, 8 strikethrough.
    pub font_style: u8,
}

/// The `tokenColors` of a VS Code color theme, matched like VS Code does
/// (scope prefixes, descendant selectors, specificity, font styles).
#[derive(uniffi::Object)]
pub struct TokenTheme {
    theme: ferride_theme::TokenTheme,
    cache: Mutex<HashMap<i32, TokenStyle>>,
}

#[uniffi::export]
impl TokenTheme {
    /// Parses a color theme file (JSONC allowed).
    #[uniffi::constructor]
    pub fn new(theme_json: String) -> Result<Arc<Self>, FerrideError> {
        let theme = ferride_theme::TokenTheme::from_theme_json(&theme_json)
            .map_err(|e| FerrideError::InvalidArgument { msg: e.to_string() })?;
        Ok(Arc::new(Self {
            theme,
            cache: Mutex::new(HashMap::new()),
        }))
    }

    /// Style for a span's `scope` id (cached per id).
    pub fn style(&self, scope: i32) -> TokenStyle {
        let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        *cache.entry(scope).or_insert_with(|| {
            let s = self
                .theme
                .style(&ferride_syntax::scope_stack(scope.max(0) as u32));
            TokenStyle {
                foreground: s.foreground,
                font_style: s.font_style,
            }
        })
    }

    /// Style of a scope stack given as text (outermost first), for tests and tools.
    pub fn style_for_scopes(&self, scopes: Vec<String>) -> TokenStyle {
        let s = self.theme.style(&scopes);
        TokenStyle {
            foreground: s.foreground,
            font_style: s.font_style,
        }
    }
}

/// The TextMate scope stack of a span's `scope` id, outermost first.
#[uniffi::export]
pub fn scope_stack(scope: i32) -> Vec<String> {
    ferride_syntax::scope_stack(scope.max(0) as u32)
}
