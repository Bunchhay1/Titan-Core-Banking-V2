-- Phase 3: Notification Service Database Schema

-- Notification Audit Table (Task 10)
CREATE TABLE IF NOT EXISTS notification_audit (
    id BIGSERIAL PRIMARY KEY,
    transaction_id VARCHAR(255) NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    channel VARCHAR(50) NOT NULL,
    recipient VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    status VARCHAR(50) NOT NULL,
    provider VARCHAR(50),
    error_message VARCHAR(1000),
    external_id VARCHAR(255),
    attempted_at TIMESTAMP NOT NULL,
    delivered_at TIMESTAMP,
    locale VARCHAR(10) NOT NULL,
    urgent BOOLEAN NOT NULL
);

CREATE INDEX idx_notification_audit_transaction ON notification_audit(transaction_id);
CREATE INDEX idx_notification_audit_account ON notification_audit(account_id);
CREATE INDEX idx_notification_audit_status ON notification_audit(status);

-- User Preferences Table (Task 4)
CREATE TABLE IF NOT EXISTS user_preferences (
    user_id VARCHAR(255) PRIMARY KEY,
    marketing_opt_in BOOLEAN NOT NULL DEFAULT true,
    transaction_alerts_enabled BOOLEAN NOT NULL DEFAULT true,
    preferred_locale VARCHAR(10) NOT NULL DEFAULT 'en',
    sms_number VARCHAR(50) NOT NULL,
    email VARCHAR(255) NOT NULL
);

-- Sample Data
INSERT INTO user_preferences (user_id, marketing_opt_in, transaction_alerts_enabled, preferred_locale, sms_number, email)
VALUES 
    ('1234567890', true, true, 'en', '+1234567890', 'user1@titan-bank.com'),
    ('9876543210', false, true, 'km', '+855123456789', 'user2@titan-bank.com')
ON CONFLICT (user_id) DO NOTHING;
