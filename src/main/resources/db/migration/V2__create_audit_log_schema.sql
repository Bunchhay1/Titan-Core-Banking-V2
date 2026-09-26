-- Create Audit Logs Table
CREATE TABLE audit_logs (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(100),
    action VARCHAR(100) NOT NULL,
    ip_address VARCHAR(50),
    status VARCHAR(50),
    timestamp TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- Index for faster lookup
CREATE INDEX idx_audit_username ON audit_logs(username);
CREATE INDEX idx_audit_timestamp ON audit_logs(timestamp);