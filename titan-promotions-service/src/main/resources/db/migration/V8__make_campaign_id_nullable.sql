-- ============================================================
-- V8: Make applied_promotions.campaign_id nullable
-- ============================================================
-- Context:
--   V1 created applied_promotions without campaign_id.
--   V2 added campaign_id column but without NOT NULL constraint.
--   The JPA entity declares campaignId as @Column(nullable = false),
--   which causes Hibernate to enforce NOT NULL at the application layer,
--   but the DB column itself was added without NOT NULL in V2.
--
--   PromotionService creates "system" promotions (REFERRAL_REWARD,
--   CASHBACK, COIN_POINTS, MEMBER_DEPOSIT_BONUS) that have no direct
--   campaign row in the campaigns table. These use campaignId = 0
--   as a sentinel for "system/legacy" promotions.
--
--   This migration:
--     1. Drops the NOT NULL constraint if it was ever added.
--     2. Sets a DEFAULT of 0 so that rows without an explicit campaignId
--        always satisfy any application-level NOT NULL check.
--     3. Backfills any existing NULLs to 0.
-- ============================================================

-- Step 1: Ensure the column exists (idempotent)
ALTER TABLE applied_promotions
    ADD COLUMN IF NOT EXISTS campaign_id BIGINT;

-- Step 2: Backfill any existing NULL values to 0 (system/legacy sentinel)
UPDATE applied_promotions
SET campaign_id = 0
WHERE campaign_id IS NULL;

-- Step 3: Set column DEFAULT = 0 so future inserts without campaign_id still pass
ALTER TABLE applied_promotions
    ALTER COLUMN campaign_id SET DEFAULT 0;

-- Step 4: Drop NOT NULL constraint if present (allows system promotions with id=0)
ALTER TABLE applied_promotions
    ALTER COLUMN campaign_id DROP NOT NULL;

-- Verification
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name   = 'applied_promotions'
          AND column_name  = 'campaign_id'
          AND is_nullable  = 'YES'
    ) THEN
        RAISE NOTICE 'V8: applied_promotions.campaign_id is nullable — OK';
    ELSE
        RAISE EXCEPTION 'V8: applied_promotions.campaign_id is still NOT NULL after migration';
    END IF;
END $$;
