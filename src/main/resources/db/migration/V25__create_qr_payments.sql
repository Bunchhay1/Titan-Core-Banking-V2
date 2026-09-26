-- ============================================================
-- V25: QR Code Payment Feature
-- Creates the qr_payments table that backs the QrPayment entity.
-- ============================================================

CREATE TABLE IF NOT EXISTS qr_payments (
    id                  BIGSERIAL        PRIMARY KEY,

    -- Unique token embedded in the QR image (32-char UUID without dashes)
    qr_code             VARCHAR(64)      NOT NULL UNIQUE,

    -- Account that will receive the money (mandatory)
    payee_account_id    BIGINT           NOT NULL
        REFERENCES accounts(id) ON DELETE RESTRICT,

    -- Account that paid (null until payment is made)
    payer_account_id    BIGINT
        REFERENCES accounts(id) ON DELETE RESTRICT,

    -- Optional fixed amount (NULL = open-amount QR, payer enters amount)
    amount              NUMERIC(20, 2),

    -- Currency code (e.g. USD, KHR)
    currency            VARCHAR(3),

    -- Optional memo displayed to the payer
    note                VARCHAR(255),

    -- QR lifecycle status
    status              VARCHAR(20)      NOT NULL DEFAULT 'PENDING',

    -- Settled transaction (null until paid)
    transaction_id      BIGINT
        REFERENCES transactions(id) ON DELETE SET NULL,

    -- TTL: QR expires at this timestamp
    expires_at          TIMESTAMP        NOT NULL,

    -- Audit timestamps
    created_at          TIMESTAMP        NOT NULL DEFAULT NOW(),
    paid_at             TIMESTAMP
);

-- ── Indexes ──────────────────────────────────────────────────────────────────
CREATE INDEX idx_qr_payments_status  ON qr_payments(status);
CREATE INDEX idx_qr_payments_account ON qr_payments(payee_account_id);
CREATE INDEX idx_qr_payments_expires ON qr_payments(expires_at) WHERE status = 'PENDING';
