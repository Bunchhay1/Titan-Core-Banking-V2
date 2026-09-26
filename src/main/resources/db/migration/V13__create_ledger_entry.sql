-- Create ledger_entry table for double-entry bookkeeping
CREATE TABLE ledger_entry (
    id BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    entry_type VARCHAR(10) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount DECIMAL(19, 4) NOT NULL CHECK (amount > 0),
    entry_date TIMESTAMP NOT NULL,
    description VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100) NOT NULL,
    
    CONSTRAINT fk_ledger_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(id),
    CONSTRAINT fk_ledger_account FOREIGN KEY (account_id) REFERENCES accounts(id)
);

-- Indexes for performance
CREATE INDEX idx_ledger_transaction ON ledger_entry(transaction_id);
CREATE INDEX idx_ledger_account_date ON ledger_entry(account_id, entry_date DESC);
CREATE INDEX idx_ledger_entry_date ON ledger_entry(entry_date DESC);

-- Comment for documentation
COMMENT ON TABLE ledger_entry IS 'Double-entry bookkeeping ledger - every transaction creates balanced debit and credit entries';
COMMENT ON COLUMN ledger_entry.entry_type IS 'DEBIT (money out) or CREDIT (money in)';
