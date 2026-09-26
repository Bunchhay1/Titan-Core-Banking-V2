-- Phase 10 migrations

-- Task 2: CBDC mint records
CREATE TABLE IF NOT EXISTS cbdc_mint_record (
    id                      BIGSERIAL PRIMARY KEY,
    account_id              BIGINT NOT NULL,
    fiat_amount             NUMERIC(30,8) NOT NULL,
    fiat_currency           VARCHAR(3) NOT NULL,
    token_amount            NUMERIC(30,8) NOT NULL,
    token_symbol            VARCHAR(10) NOT NULL,
    blockchain_tx_hash      VARCHAR(66) NOT NULL,
    recipient_wallet_address VARCHAR(42) NOT NULL,
    status                  VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    confirmed_at            TIMESTAMP,
    error_message           VARCHAR(500),
    CONSTRAINT uq_cbdc_tx_hash UNIQUE (blockchain_tx_hash)
);
CREATE INDEX idx_cbdc_account ON cbdc_mint_record(account_id);

-- Task 5: Merkle archive records
CREATE TABLE IF NOT EXISTS merkle_archive (
    id              BIGSERIAL PRIMARY KEY,
    merkle_root     VARCHAR(64) NOT NULL UNIQUE,
    s3_archive_key  TEXT NOT NULL,
    entry_count     INT NOT NULL,
    first_entry_id  BIGINT NOT NULL,
    last_entry_id   BIGINT NOT NULL,
    archived_at     TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Task 7: FX hedge positions
CREATE TABLE IF NOT EXISTS fx_hedge_position (
    id              BIGSERIAL PRIMARY KEY,
    from_currency   VARCHAR(3) NOT NULL,
    to_currency     VARCHAR(3) NOT NULL,
    hedged_amount   NUMERIC(30,8) NOT NULL,
    rate_at_hedge   NUMERIC(20,8) NOT NULL,
    hedged_at       TIMESTAMP NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'OPEN'
);

-- Task 9: HTLC contracts
CREATE TABLE IF NOT EXISTS htlc_contract (
    contract_id         VARCHAR(36) PRIMARY KEY,
    sender_account_id   BIGINT NOT NULL,
    receiver_account_id BIGINT NOT NULL,
    amount              NUMERIC(30,2) NOT NULL,
    currency            VARCHAR(3) NOT NULL,
    hash_lock           VARCHAR(64) NOT NULL,
    preimage            VARCHAR(128),
    status              VARCHAR(20) NOT NULL DEFAULT 'LOCKED',
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at          TIMESTAMP NOT NULL,
    settled_at          TIMESTAMP,
    memo                VARCHAR(500)
);
CREATE INDEX idx_htlc_hash_lock ON htlc_contract(hash_lock);
CREATE INDEX idx_htlc_status_expires ON htlc_contract(status, expires_at);

-- Task 10: Lockdown audit (append-only — no DELETE/UPDATE grants in prod)
CREATE TABLE IF NOT EXISTS lockdown_audit (
    id              BIGSERIAL PRIMARY KEY,
    reason          VARCHAR(1000) NOT NULL,
    triggered_at    TIMESTAMP NOT NULL DEFAULT NOW()
);
REVOKE UPDATE, DELETE ON lockdown_audit FROM PUBLIC;
