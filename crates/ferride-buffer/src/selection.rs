//! Multi-cursor-ready selections. Positions are char indices.

/// One selection range. `anchor` is where the selection started, `head` is
/// where the cursor is. An empty selection (`anchor == head`) is a caret.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Selection {
    pub anchor: usize,
    pub head: usize,
}

impl Selection {
    pub fn new(anchor: usize, head: usize) -> Self {
        Self { anchor, head }
    }

    pub fn caret(pos: usize) -> Self {
        Self::new(pos, pos)
    }

    pub fn start(&self) -> usize {
        self.anchor.min(self.head)
    }

    pub fn end(&self) -> usize {
        self.anchor.max(self.head)
    }

    pub fn is_empty(&self) -> bool {
        self.anchor == self.head
    }

    pub fn len(&self) -> usize {
        self.end() - self.start()
    }

    fn clamp(self, max: usize) -> Self {
        Self::new(self.anchor.min(max), self.head.min(max))
    }
}

/// A non-empty set of selections, kept sorted by start and with overlapping
/// ranges merged. One of them is the primary selection (the one the IME and
/// scrolling follow).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Selections {
    ranges: Vec<Selection>,
    primary: usize,
}

impl Default for Selections {
    fn default() -> Self {
        Self::single(Selection::caret(0))
    }
}

impl Selections {
    pub fn single(sel: Selection) -> Self {
        Self {
            ranges: vec![sel],
            primary: 0,
        }
    }

    /// Builds a selection set. `primary` indexes into `ranges` before
    /// normalization. Returns `None` for an empty list.
    pub fn new(ranges: Vec<Selection>, primary: usize) -> Option<Self> {
        if ranges.is_empty() {
            return None;
        }
        let primary_sel = ranges[primary.min(ranges.len() - 1)];
        let mut s = Self { ranges, primary: 0 };
        s.normalize(primary_sel);
        Some(s)
    }

    pub fn ranges(&self) -> &[Selection] {
        &self.ranges
    }

    pub fn primary(&self) -> Selection {
        self.ranges[self.primary]
    }

    pub fn primary_index(&self) -> usize {
        self.primary
    }

    pub fn len(&self) -> usize {
        self.ranges.len()
    }

    pub fn is_empty(&self) -> bool {
        false
    }

    /// Adds a cursor/selection (multi-cursor) and makes it primary.
    pub fn push(&mut self, sel: Selection) {
        self.ranges.push(sel);
        self.normalize(sel);
    }

    pub(crate) fn clamp(&mut self, max: usize) {
        let primary = self.primary().clamp(max);
        for r in &mut self.ranges {
            *r = r.clamp(max);
        }
        self.normalize(primary);
    }

    /// Applies `f` to every endpoint and re-normalizes.
    pub(crate) fn map(&mut self, mut f: impl FnMut(usize) -> usize) {
        let p = self.primary();
        let primary = Selection::new(f(p.anchor), f(p.head));
        for r in &mut self.ranges {
            *r = Selection::new(f(r.anchor), f(r.head));
        }
        self.normalize(primary);
    }

    fn normalize(&mut self, primary_sel: Selection) {
        self.ranges.sort_by_key(|r| (r.start(), r.end()));
        let mut merged: Vec<Selection> = Vec::with_capacity(self.ranges.len());
        for r in self.ranges.drain(..) {
            match merged.last_mut() {
                Some(last) if r.start() < last.end() || (r.is_empty() && r.start() == last.end()) => {
                    let start = last.start();
                    let end = last.end().max(r.end());
                    // Keep the direction of the earlier selection.
                    *last = if last.head >= last.anchor {
                        Selection::new(start, end)
                    } else {
                        Selection::new(end, start)
                    };
                }
                Some(last) if r == *last => {}
                _ => merged.push(r),
            }
        }
        self.ranges = merged;
        self.primary = self
            .ranges
            .iter()
            .position(|r| r.start() <= primary_sel.head && primary_sel.head <= r.end())
            .unwrap_or(0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn merges_overlapping_and_sorts() {
        let s = Selections::new(
            vec![
                Selection::new(10, 12),
                Selection::new(0, 4),
                Selection::new(3, 6),
                Selection::caret(12),
            ],
            0,
        )
        .unwrap();
        assert_eq!(s.ranges(), &[Selection::new(0, 6), Selection::new(10, 12)]);
        assert_eq!(s.primary(), Selection::new(10, 12));
    }

    #[test]
    fn empty_is_rejected() {
        assert!(Selections::new(vec![], 0).is_none());
    }
}
