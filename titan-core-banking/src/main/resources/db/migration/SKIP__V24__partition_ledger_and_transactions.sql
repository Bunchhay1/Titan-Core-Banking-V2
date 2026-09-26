-- ==========================================
-- TASK 1: PostgreSQL Table Partitioning
-- Partition ledger_entry and transactions by created_at (monthly)
-- ==========================================

-- Step 1: Rename existing tables
ALTER TABLE ledger_entry RENAME TO ledger_entry_old;
ALTER TABLE transactions RENAME TO transactions_old;

-- Step 2: Create partitioned ledger_entry table
CREATE TABLE ledger_entry (
    id BIGSERIAL,
    transaction_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    entry_type VARCHAR(10) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount DECIMAL(19, 4) NOT NULL CHECK (amount > 0),
    entry_date TIMESTAMP NOT NULL,
    description VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100) NOT NULL,
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- Step 3: Create partitioned transactions table
CREATE TABLE transactions (
    id BIGSERIAL,
    idempotency_key VARCHAR(255) UNIQUE,
    transaction_reference VARCHAR(255),
    transaction_type VARCHAR(50),
    amount DECIMAL(19, 4),
    status VARCHAR(50),
    note TEXT,
    timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    from_account_id BIGINT,
    to_account_id BIGINT,
    PRIMARY KEY (id, timestamp)
) PARTITION BY RANGE (timestamp);

-- Step 4: Create initial partitions (2026 Q1-Q4)
CREATE TABLE ledger_entry_2026_01 PARTITION OF ledger_entry
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
CREATE TABLE ledger_entry_2026_02 PARTITION OF ledger_entry
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
CREATE TABLE ledger_entry_2026_03 PARTITION OF ledger_entry
    FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');
CREATE TABLE ledger_entry_2026_04 PARTITION OF ledger_entry
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
CREATE TABLE ledger_entry_2026_05 PARTITION OF ledger_entry
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
CREATE TABLE ledger_entry_2026_06 PARTITION OF ledger_entry
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
CREATE TABLE ledger_entry_2026_07 PARTITION OF ledger_entry
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE ledger_entry_2026_08 PARTITION OF ledger_entry
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE ledger_entry_2026_09 PARTITION OF ledger_entry
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE ledger_entry_2026_10 PARTITION OF ledger_entry
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE ledger_entry_2026_11 PARTITION OF ledger_entry
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE ledger_entry_2026_12 PARTITION OF ledger_entry
    FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');

CREATE TABLE transactions_2026_01 PARTITION OF transactions
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
CREATE TABLE transactions_2026_02 PARTITION OF transactions
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
CREATE TABLE transactions_2026_03 PARTITION OF transactions
    FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');
CREATE TABLE transactions_2026_04 PARTITION OF transactions
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
CREATE TABLE transactions_2026_05 PARTITION OF transactions
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
CREATE TABLE transactions_2026_06 PARTITION OF transactions
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
CREATE TABLE transactions_2026_07 PARTITION OF transactions
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE transactions_2026_08 PARTITION OF transactions
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE transactions_2026_09 PARTITION OF transactions
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE transactions_2026_10 PARTITION OF transactions
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE transactions_2026_11 PARTITION OF transactions
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE transactions_2026_12 PARTITION OF transactions
    FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');

-- Step 5: Migrate existing data
INSERT INTO ledger_entry SELECT * FROM ledger_entry_old;
INSERT INTO transactions SELECT * FROM transactions_old;

-- Step 6: Recreate indexes on partitioned tables
CREATE INDEX idx_ledger_transaction ON ledger_entry(transaction_id);
CREATE INDEX idx_ledger_account_date ON ledger_entry(account_id, entry_date DESC);
CREATE INDEX idx_ledger_entry_date ON ledger_entry(entry_date DESC);
CREATE INDEX idx_transactions_reference ON transactions(transaction_reference);
CREATE INDEX idx_transactions_status ON transactions(status);

-- Step 7: Drop old tables
DROP TABLE ledger_entry_old;
DROP TABLE transactions_old;

-- Step 8: Create function to auto-create future partitions
CREATE OR REPLACE FUNCTION create_monthly_partitions()
RETURNS void AS $$
DECLARE
    start_date DATE;
    end_date DATE;
    partition_name TEXT;
BEGIN
    start_date := DATE_TRUNC('month', CURRENT_DATE + INTERVAL '1 month');
    end_date := start_date + INTERVAL '1 month';
    
    partition_name := 'ledger_entry_' || TO_CHAR(start_date, 'YYYY_MM');
    EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF ledger_entry FOR VALUES FROM (%L) TO (%L)',
                   partition_name, start_date, end_date);
    
    partition_name := 'transactions_' || TO_CHAR(start_date, 'YYYY_MM');
    EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF transactions FOR VALUES FROM (%L) TO (%L)',
                   partition_name, start_date, end_date);
END;
$$ LANGUAGE plpgsql;

COMMENT ON TABLE ledger_entry IS 'Partitioned by created_at (monthly) - billions of rows optimized';
COMMENT ON TABLE transactions IS 'Partitioned by timestamp (monthly) - billions of rows optimized';
