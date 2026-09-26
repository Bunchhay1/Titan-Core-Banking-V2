-- Initial schema for promotions service
CREATE TABLE IF NOT EXISTS applied_promotions (
    id BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    promotion_type VARCHAR(50) NOT NULL,
    promotion_amount DECIMAL(19,2) NOT NULL,
    applied_at TIMESTAMP NOT NULL,
    description VARCHAR(500)
);

CREATE INDEX idx_applied_promotions_account ON applied_promotions(account_id);
CREATE INDEX idx_applied_promotions_transaction ON applied_promotions(transaction_id);
