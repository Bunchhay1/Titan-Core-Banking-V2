-- Add idempotency key to transactions table
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(255);

-- Create unique index for idempotency
CREATE UNIQUE INDEX IF NOT EXISTS idx_transaction_idempotency ON transactions(idempotency_key) WHERE idempotency_key IS NOT NULL;

-- Add index for faster lookups
CREATE INDEX IF NOT EXISTS idx_transaction_status ON transactions(status);
