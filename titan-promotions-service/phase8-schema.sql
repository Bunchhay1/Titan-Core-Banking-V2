-- Phase 8: Gamification, Graph Virality & Merchant Federation
-- Execute on PostgreSQL titandb

-- Task 7: Merchant Federation
CREATE TABLE IF NOT EXISTS merchant_campaigns (
  id BIGSERIAL PRIMARY KEY,
  tenant_id VARCHAR(100) NOT NULL,
  merchant_name VARCHAR(255) NOT NULL,
  merchant_account_id BIGINT NOT NULL,
  campaign_name VARCHAR(255) NOT NULL,
  total_budget DECIMAL(19,2) NOT NULL,
  remaining_budget DECIMAL(19,2) NOT NULL,
  start_date TIMESTAMP NOT NULL,
  end_date TIMESTAMP NOT NULL,
  rule_expression TEXT,
  active BOOLEAN DEFAULT true
);
CREATE INDEX IF NOT EXISTS idx_tenant_active ON merchant_campaigns(tenant_id, active);

-- Task 5: Shadow Rule Evaluation
CREATE TABLE IF NOT EXISTS shadow_evaluations (
  id BIGSERIAL PRIMARY KEY,
  rule_id BIGINT NOT NULL,
  transaction_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  matched BOOLEAN,
  theoretical_payout DECIMAL(19,2),
  evaluated_at TIMESTAMP NOT NULL,
  rule_expression TEXT
);
CREATE INDEX IF NOT EXISTS idx_rule_matched ON shadow_evaluations(rule_id, matched);
CREATE INDEX IF NOT EXISTS idx_evaluated_at ON shadow_evaluations(evaluated_at);

-- Task 10: Reward Clawbacks
CREATE TABLE IF NOT EXISTS reward_clawbacks (
  id BIGSERIAL PRIMARY KEY,
  original_transaction_id BIGINT NOT NULL UNIQUE,
  refund_transaction_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  original_promotion_id BIGINT NOT NULL,
  clawback_amount DECIMAL(19,2) NOT NULL,
  clawback_at TIMESTAMP NOT NULL,
  status VARCHAR(20) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_original_tx ON reward_clawbacks(original_transaction_id);
CREATE INDEX IF NOT EXISTS idx_account_clawback ON reward_clawbacks(account_id);

-- Sample Data
INSERT INTO merchant_campaigns (tenant_id, merchant_name, merchant_account_id, campaign_name, total_budget, remaining_budget, start_date, end_date, rule_expression, active)
VALUES ('STARBUCKS_KH', 'Starbucks Cambodia', 9999, 'Coffee Cashback March', 50000.00, 50000.00, NOW(), NOW() + INTERVAL '30 days', '#amount > 5', true);

COMMIT;
