-- V6: Add deposit bonus campaign (code: 4444)
-- Rule: Any DEPOSIT transaction >= $100 earns a $2 flat reward.
-- transactionType maps to the "type" field from core-banking
-- (values: DEPOSIT, WITHDRAWAL, TRANSFER, TRANSFER_RECEIVED).
-- SpEL variables bound by RuleEngine:
--   #transactionAmount  → event.getAmount()
--   #transactionType    → event.getTransactionType()  (derived from event.type)

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
    '4444',
    'Deposit $100+ Bonus $2',
    '#transactionType == ''DEPOSIT'' && #transactionAmount >= 100',
    2.00,
    'ACTIVE',
    NULL,
    0,
    NOW(),
    NOW() + INTERVAL '365 days',
    NOW()
)
ON CONFLICT (campaign_code) DO NOTHING;
