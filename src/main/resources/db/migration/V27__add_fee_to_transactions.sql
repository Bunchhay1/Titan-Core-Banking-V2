-- V27: Add fee column to transactions table
-- Required to record the actual fee charged per transfer so the ledger
-- and response can reconcile the full debit (amount + fee) vs the
-- credited amount (amount only).
--
-- Default 0 for backward compatibility with existing rows (deposits/withdrawals
-- which have no fee, and historical transfers where fee was not recorded).

ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS fee NUMERIC(30, 4) NOT NULL DEFAULT 0;

ALTER TABLE transactions
    ALTER COLUMN fee TYPE NUMERIC(30, 4);
