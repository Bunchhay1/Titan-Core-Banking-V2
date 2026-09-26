CREATE TABLE fixed_deposits (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    principal_amount DECIMAL(19, 2) NOT NULL,
    interest_rate DECIMAL(5, 4) NOT NULL, -- e.g. 0.0700 (7%)
    maturity_amount DECIMAL(19, 2) NOT NULL,
    start_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    maturity_date TIMESTAMP NOT NULL,
    status VARCHAR(30) NOT NULL,

    CONSTRAINT fk_fd_account FOREIGN KEY (account_id) REFERENCES accounts (id)
);

CREATE INDEX idx_fd_account ON fixed_deposits(account_id);
CREATE INDEX idx_fd_status ON fixed_deposits(status);