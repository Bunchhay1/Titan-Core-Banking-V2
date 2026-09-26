CREATE TABLE scheduled_transactions (
    id BIGSERIAL PRIMARY KEY,
    amount DECIMAL(19, 2) NOT NULL,
    from_account_id BIGINT NOT NULL,
    to_account_number VARCHAR(20) NOT NULL, -- Target can be internal or external reference
    frequency VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    start_date TIMESTAMP NOT NULL,
    next_execution_date TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_scheduled_from_account FOREIGN KEY (from_account_id) REFERENCES accounts (id)
);

CREATE INDEX idx_scheduled_next_exec ON scheduled_transactions(next_execution_date);
CREATE INDEX idx_scheduled_status ON scheduled_transactions(status);