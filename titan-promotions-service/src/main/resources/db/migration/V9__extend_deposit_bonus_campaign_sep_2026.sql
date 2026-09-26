-- ============================================================
-- V9: Extend Deposit Bonus Campaign window to 2026-09-30
-- ============================================================
-- Context:
--   V7 created DEPOSIT_BONUS_JUL_AUG_2026 with end_date = 2026-08-23 23:59:59.
--   As of 2026-08-29 the campaign has already expired, so deposits of $100+
--   are no longer receiving the $2 bonus.
--
-- This migration:
--   1. Extends end_date to 2026-09-30 23:59:59 so the campaign covers
--      Aug–Sep 2026 (current date: 2026-08-29).
--   2. Re-activates the campaign status to ACTIVE if it was auto-expired
--      by CampaignExpiryScheduler.
--   3. Updates the campaign name to reflect the extended window.
--   4. Verifies the update was applied correctly.
--
-- Corresponding Java change:
--   DepositPromotionService.CAMPAIGN_END updated to 2026-09-30T23:59:59
-- ============================================================

UPDATE campaigns
SET
    name       = 'Deposit $100+ Bonus $2 (Jul–Sep 2026)',
    end_date   = '2026-09-30 23:59:59',
    status     = 'ACTIVE',
    updated_at = NOW()
WHERE campaign_code = 'DEPOSIT_BONUS_JUL_AUG_2026';

-- Verify the update was applied
DO $$
DECLARE
    v_end_date  TIMESTAMP;
    v_status    VARCHAR;
BEGIN
    SELECT end_date, status
    INTO   v_end_date, v_status
    FROM   campaigns
    WHERE  campaign_code = 'DEPOSIT_BONUS_JUL_AUG_2026';

    IF v_end_date IS NULL THEN
        RAISE EXCEPTION 'V9: Campaign DEPOSIT_BONUS_JUL_AUG_2026 not found — was V7 migration applied?';
    END IF;

    IF v_end_date < '2026-09-30 23:59:59'::TIMESTAMP THEN
        RAISE EXCEPTION 'V9: end_date was not updated to 2026-09-30 23:59:59 (got %)', v_end_date;
    END IF;

    IF v_status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'V9: campaign status is % instead of ACTIVE', v_status;
    END IF;

    RAISE NOTICE 'V9: Campaign DEPOSIT_BONUS_JUL_AUG_2026 extended — end_date=%, status=%',
        v_end_date, v_status;
END $$;
