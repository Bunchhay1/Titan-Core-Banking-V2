-- 1. Add currency column with a default value of 'USD' for existing accounts
ALTER TABLE accounts
ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'USD';

-- 2. Add validation check (Optional but recommended for data integrity)
ALTER TABLE accounts
ADD CONSTRAINT chk_currency CHECK (currency IN ('USD', 'KHR', 'EUR'));