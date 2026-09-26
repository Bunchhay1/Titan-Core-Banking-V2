-- ============================================================
-- V7: Deposit Bonus Campaign  (Jul 23 2026 → Aug 23 2026)
-- ============================================================
-- Rule  : transactionType == 'DEPOSIT' AND amount >= 100
-- Reward: $2.00 flat bonus per qualifying deposit
-- Window: 2026-07-23 06:59:00  →  2026-08-23 23:59:59
-- Auto-expiry: CampaignExpiryScheduler marks COMPLETED after endDate
-- ============================================================

INSERT INTO campaigns (
    campaign_code,
    name,
    rule_expression,
    reward_amount,
    status,
    quota_limit,
    quota_used,
    start_date,
    end_date,
    created_at
)
VALUES (
    'DEPOSIT_BONUS_JUL_AUG_2026',
    'Deposit $100+ Bonus $2 (Jul–Aug 2026)',
    '#transactionType == ''DEPOSIT'' && #transactionAmount >= 100',
    2.00,
    'ACTIVE',
    NULL,       -- no quota limit; open to all qualifying deposits
    0,
    '2026-07-23 06:59:00',
    '2026-08-23 23:59:59',
    NOW()
)
ON CONFLICT (campaign_code) DO UPDATE
    SET name            = EXCLUDED.name,
        rule_expression = EXCLUDED.rule_expression,
        reward_amount   = EXCLUDED.reward_amount,
        status          = EXCLUDED.status,
        start_date      = EXCLUDED.start_date,
        end_date        = EXCLUDED.end_date;

-- Verify the insert
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM campaigns
        WHERE campaign_code = 'DEPOSIT_BONUS_JUL_AUG_2026'
    ) THEN
        RAISE EXCEPTION 'Campaign DEPOSIT_BONUS_JUL_AUG_2026 was not inserted correctly';
    END IF;
END $$;
