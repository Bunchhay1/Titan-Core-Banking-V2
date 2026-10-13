-- V28: Enforce Overdraft Constraints, Account Bucket Uniqueness, and System Bank Accounts

-- 0. Ensure user security columns exist and have proper defaults
DO $$ BEGIN
    ALTER TABLE users ADD COLUMN IF NOT EXISTS account_non_locked BOOLEAN NOT NULL DEFAULT TRUE;
EXCEPTION WHEN duplicate_column THEN NULL;
END $$;

DO $$ BEGIN
    ALTER TABLE users ALTER COLUMN account_non_locked SET DEFAULT TRUE;
    UPDATE users SET account_non_locked = TRUE WHERE account_non_locked IS NULL;
EXCEPTION WHEN undefined_column THEN NULL;
END $$;

-- 1. Fix overdraft constraints on accounts table
DO $$ BEGIN
    ALTER TABLE accounts DROP CONSTRAINT IF EXISTS chk_balance_non_negative;
EXCEPTION WHEN undefined_object THEN NULL;
END $$;

DO $$ BEGIN
    ALTER TABLE accounts ADD CONSTRAINT chk_accounts_overdraft_limit_non_negative CHECK (overdraft_limit >= 0);
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    ALTER TABLE accounts ADD CONSTRAINT chk_accounts_balance_with_overdraft CHECK (balance + overdraft_limit >= 0);
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- 2. Explicit DDL for account_buckets with unique constraint
CREATE TABLE IF NOT EXISTS account_buckets (
    id BIGSERIAL PRIMARY KEY,
    parent_account_id BIGINT NOT NULL REFERENCES accounts(id),
    bucket_index INT NOT NULL,
    balance DECIMAL(30, 2) NOT NULL DEFAULT 0.00,
    currency VARCHAR(3) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DO $$ BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_account_buckets_parent_index'
    ) AND NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'idx_account_buckets_parent_idx'
    ) THEN
        ALTER TABLE account_buckets ADD CONSTRAINT uq_account_buckets_parent_index UNIQUE (parent_account_id, bucket_index);
    END IF;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes WHERE indexname = 'idx_account_buckets_parent_idx'
    ) THEN
        CREATE INDEX idx_account_buckets_parent_idx ON account_buckets(parent_account_id, bucket_index);
    END IF;
END $$;

-- 3. System bank user and fee revenue account for balanced double-entry accounting
INSERT INTO users (username, email, password, first_name, last_name, pin, role, tier, account_non_locked)
VALUES ('SYSTEM_BANK', 'system@titanbank.com', '$2a$10$7EqJtq98hPqEX7fNZaFWoO.8/k0H1z19e6hHk9lP6m6i0.dE7eXyK', 'Titan', 'System', '$2a$10$7EqJtq98hPqEX7fNZaFWoO.8/k0H1z19e6hHk9lP6m6i0.dE7eXyK', 'ROLE_ADMIN', 'VIP', TRUE)
ON CONFLICT (username) DO NOTHING;

INSERT INTO accounts (account_number, account_type, balance, user_id, currency, overdraft_limit, status)
SELECT 'BANK-FEE-REVENUE', 'SAVINGS', 0.0000, u.id, 'USD', 0.00, 'ACTIVE'
FROM users u WHERE u.username = 'SYSTEM_BANK'
ON CONFLICT (account_number) DO NOTHING;
