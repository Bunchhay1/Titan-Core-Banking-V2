-- ============================================================
-- V10: Remove duplicate deposit bonus campaign '4444'
-- ============================================================
-- Campaign '4444' was an earlier duplicate of 'DEPOSIT_BONUS_JUL_AUG_2026'
-- which caused users to receive the $2 bonus twice on a single deposit.
-- Delete or disable '4444' so only DEPOSIT_BONUS_JUL_AUG_2026 is evaluated.
-- ============================================================

DELETE FROM campaigns WHERE campaign_code = '4444';
