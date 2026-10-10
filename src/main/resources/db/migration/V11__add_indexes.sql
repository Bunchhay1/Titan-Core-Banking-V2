-- Ensure account number index is consistently named without duplicating indexes
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relkind = 'i' AND c.relname = 'idx_accounts_account_number'
    ) AND NOT EXISTS (
        SELECT 1 FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relkind = 'i' AND c.relname = 'idx_account_number'
    ) THEN
        ALTER INDEX idx_accounts_account_number RENAME TO idx_account_number;
    ELSIF NOT EXISTS (
        SELECT 1 FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relkind = 'i' AND c.relname = 'idx_account_number'
    ) THEN
        CREATE INDEX idx_account_number ON accounts(account_number);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_transaction_date ON transactions(created_at);
