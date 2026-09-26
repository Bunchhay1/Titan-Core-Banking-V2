-- ============================================================
-- V12: Deactivate other overlapping campaigns (HIGH_VALUE, DIGITAL_BANKING)
-- ============================================================
-- Only keep the Deposit Bonus (DEPOSIT_BONUS_JUL_AUG_2026):
--   - Deposit >= $100 USD   -> Bonus $2.00 USD
--   - Deposit >= 400,000 KHR -> Bonus 8,000 KHR
-- Deactivate other campaigns so no extra/duplicate rewards are granted.
-- ============================================================

UPDATE campaigns SET status = 'INACTIVE' WHERE campaign_code IN ('HIGH_VALUE', 'DIGITAL_BANKING');
