#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TranslationTier { Cold, Warm, Hot, VeryHot }

#[derive(Debug, Clone, Copy)]
pub struct TranslationProfile {
    pub executions: u64,
    pub cache_hits: u64,
    pub cache_misses: u64,
}

impl TranslationProfile {
    pub fn tier(&self) -> TranslationTier {
        match self.executions {
            100_000..=u64::MAX => TranslationTier::VeryHot,
            10_000..=99_999 => TranslationTier::Hot,
            100..=9_999 => TranslationTier::Warm,
            _ => TranslationTier::Cold,
        }
    }

    pub fn observe(&mut self, cache_hit: bool) {
        self.executions = self.executions.saturating_add(1);
        if cache_hit { self.cache_hits = self.cache_hits.saturating_add(1); }
        else { self.cache_misses = self.cache_misses.saturating_add(1); }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn tiers_progress_monotonically() {
        let mut p = TranslationProfile { executions:0, cache_hits:0, cache_misses:0 };
        assert_eq!(p.tier(), TranslationTier::Cold);
        for _ in 0..100 { p.observe(true); }
        assert_eq!(p.tier(), TranslationTier::Warm);
        for _ in 0..9_900 { p.observe(true); }
        assert_eq!(p.tier(), TranslationTier::Hot);
        for _ in 0..90_000 { p.observe(false); }
        assert_eq!(p.tier(), TranslationTier::VeryHot);
    }
}
