-- Phase 4: Core Ledger Hardening Schema Updates

-- 1. Add UserTier to users table (if not exists)
ALTER TABLE users ADD COLUMN IF NOT EXISTS tier VARCHAR(20) NOT NULL DEFAULT 'STANDARD';

-- 2. Update audit_logs table for enhanced tracking
ALTER TABLE audit_logs 
  ADD COLUMN IF NOT EXISTS employee_role VARCHAR(20),
  ADD COLUMN IF NOT EXISTS details TEXT;

-- Update action column to support enum values
ALTER TABLE audit_logs ALTER COLUMN action TYPE VARCHAR(50);

-- 3. Add indexes for audit log queries
CREATE INDEX IF NOT EXISTS idx_audit_username ON audit_logs(username);
CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON audit_logs(timestamp);
CREATE INDEX IF NOT EXISTS idx_audit_action ON audit_logs(action);

-- 4. Add BLOCKED status support to transactions
-- (No schema change needed - enum handled by JPA)

-- 5. Add LOAN_REPAYMENT type support
-- (No schema change needed - enum handled by JPA)

-- 6. Ensure ledger_entry indexes exist (should be from earlier migrations)
CREATE INDEX IF NOT EXISTS idx_ledger_transaction ON ledger_entry(transaction_id);
CREATE INDEX IF NOT EXISTS idx_ledger_account_date ON ledger_entry(account_id, entry_date);

-- 8. Add constraint to prevent negative balances (safe - skip if exists)
DO $$ BEGIN
  ALTER TABLE accounts ADD CONSTRAINT chk_balance_non_negative 
    CHECK (balance >= 0 OR overdraft_limit IS NOT NULL);
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- 9. Add idempotency_key to transactions if not exists
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(255) UNIQUE;
CREATE INDEX IF NOT EXISTS idx_transaction_idempotency ON transactions(idempotency_key);

-- 10. Verify scheduled_transactions table exists (V4 already created it with different schema)
-- Index only if scheduled_date column exists (it may not in older schema)
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns 
             WHERE table_name='scheduled_transactions' AND column_name='scheduled_date') THEN
    CREATE INDEX IF NOT EXISTS idx_scheduled_date_status ON scheduled_transactions(scheduled_date, status);
  END IF;
END $$;
