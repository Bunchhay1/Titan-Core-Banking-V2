-- 1. LOANS TABLE
CREATE TABLE loans (
    id BIGSERIAL PRIMARY KEY,
    amount DECIMAL(19, 2) NOT NULL,
    interest_rate DECIMAL(5, 4) NOT NULL, -- e.g., 0.0500 for 5%
    term_months INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    start_date TIMESTAMP,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT fk_loans_user FOREIGN KEY (user_id) REFERENCES users (id)
);

-- 2. REPAYMENTS TABLE (Amortization Schedule)
CREATE TABLE loan_repayments (
    id BIGSERIAL PRIMARY KEY,
    loan_id BIGINT NOT NULL,
    due_date TIMESTAMP NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING, PAID
    paid_date TIMESTAMP,
    
    CONSTRAINT fk_repayments_loan FOREIGN KEY (loan_id) REFERENCES loans (id)
);

CREATE INDEX idx_loans_user ON loans(user_id);
CREATE INDEX idx_repayments_loan ON loan_repayments(loan_id);