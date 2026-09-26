-- V22: Idempotency Keys Table
-- Provides durable storage for idempotency keys (backup for Redis)

CREATE TABLE IF NOT EXISTS idempotency_keys (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    transaction_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_idempotency_transaction FOREIGN KEY (transaction_id) 
        REFERENCES transactions(id) ON DELETE CASCADE
);

-- Index for cleanup queries
CREATE INDEX IF NOT EXISTS idx_idempotency_created ON idempotency_keys(created_at);
