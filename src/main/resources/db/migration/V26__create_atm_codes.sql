-- V26: ATM Cardless Withdrawal – One-Time 12-Digit Code Table
--
-- Flow:
--   1. Customer generates a code via the mobile app.
--   2. Code is PENDING and expires after 10 minutes.
--   3. Customer enters the code at an ATM.
--   4. ATM calls /api/v1/atm/redeem → code is marked USED and balance deducted.

CREATE TABLE IF NOT EXISTS atm_codes (
    id              BIGSERIAL           PRIMARY KEY,
    code            VARCHAR(12)         NOT NULL UNIQUE,
    account_id      BIGINT              NOT NULL REFERENCES accounts(id),
    amount          NUMERIC(18, 2)      NOT NULL CHECK (amount > 0),
    status          VARCHAR(10)         NOT NULL DEFAULT 'PENDING'
                                        CHECK (status IN ('PENDING','USED','EXPIRED','CANCELLED')),
    expires_at      TIMESTAMP           NOT NULL,
    redeemed_at     TIMESTAMP,
    atm_terminal_id VARCHAR(50),
    created_at      TIMESTAMP           NOT NULL DEFAULT NOW()
);

-- Fast lookup when ATM presents the code
CREATE INDEX IF NOT EXISTS idx_atm_code_value      ON atm_codes (code);

-- Find pending codes for a given account (e.g. cancel all before new generation)
CREATE INDEX IF NOT EXISTS idx_atm_code_account_id ON atm_codes (account_id);

-- Expiry sweep job
CREATE INDEX IF NOT EXISTS idx_atm_code_expires_at ON atm_codes (expires_at) WHERE status = 'PENDING';
