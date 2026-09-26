-- Add overdraft limit column with a default of 0 (Safe default)
ALTER TABLE accounts
ADD COLUMN overdraft_limit DECIMAL(19, 2) NOT NULL DEFAULT 0.00;