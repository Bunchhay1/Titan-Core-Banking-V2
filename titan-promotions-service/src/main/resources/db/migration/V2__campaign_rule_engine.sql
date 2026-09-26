-- Campaign table for dynamic rule engine
CREATE TABLE IF NOT EXISTS campaigns (
    id BIGSERIAL PRIMARY KEY,
    campaign_code VARCHAR(100) UNIQUE NOT NULL,
    name VARCHAR(255) NOT NULL,
    rule_expression TEXT NOT NULL,
    reward_amount DECIMAL(19,2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    quota_limit INTEGER,
    quota_used INTEGER NOT NULL DEFAULT 0,
    start_date TIMESTAMP NOT NULL,
    end_date TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

CREATE INDEX idx_campaigns_status ON campaigns(status);
CREATE INDEX idx_campaigns_dates ON campaigns(start_date, end_date);

-- Update applied_promotions table
ALTER TABLE applied_promotions ADD COLUMN IF NOT EXISTS campaign_id BIGINT;
ALTER TABLE applied_promotions ADD COLUMN IF NOT EXISTS reward_status VARCHAR(20) DEFAULT 'PENDING';
ALTER TABLE applied_promotions ADD COLUMN IF NOT EXISTS reward_event_id VARCHAR(100);

CREATE INDEX idx_applied_promotions_reward_event ON applied_promotions(reward_event_id);

-- Outbox table for transactional messaging
CREATE TABLE IF NOT EXISTS promotion_outbox (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(100) UNIQUE NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL,
    processed_at TIMESTAMP,
    retry_count INTEGER DEFAULT 0
);

CREATE INDEX idx_outbox_status ON promotion_outbox(status, created_at);

-- Sample campaigns
INSERT INTO campaigns (campaign_code, name, rule_expression, reward_amount, status, quota_limit, start_date, end_date, created_at)
VALUES 
    ('FIRST_1000', 'First 1000 Users Bonus', '#transactionAmount >= 50 && #currency == ''USD''', 5.00, 'ACTIVE', 1000, NOW(), NOW() + INTERVAL '30 days', NOW()),
    ('HIGH_VALUE', 'High Value Transaction Cashback', '#transactionAmount >= 500 && #transactionType == ''DEPOSIT''', 25.00, 'ACTIVE', NULL, NOW(), NOW() + INTERVAL '90 days', NOW()),
    ('DIGITAL_BANKING', 'Digital Banking Reward', '#metadata[''channel''] == ''DIGITAL_BANKING'' && #transactionAmount >= 100', 10.00, 'ACTIVE', NULL, NOW(), NOW() + INTERVAL '60 days', NOW())
ON CONFLICT (campaign_code) DO NOTHING;
