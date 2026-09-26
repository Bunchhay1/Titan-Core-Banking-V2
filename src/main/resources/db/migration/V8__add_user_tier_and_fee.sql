-- 1. Add Tier to Users (Default to STANDARD)
ALTER TABLE users
ADD COLUMN tier VARCHAR(20) NOT NULL DEFAULT 'STANDARD';

-- 2. Add Fee to Transactions (To record how much we charged)
ALTER TABLE transactions
ADD COLUMN fee DECIMAL(19, 2) NOT NULL DEFAULT 0.00;