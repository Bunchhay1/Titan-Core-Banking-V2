-- V21: Complete Outbox Table for Relay Service
-- Adds columns needed for OutboxRelayService to track published events

ALTER TABLE outbox_events 
ADD COLUMN IF NOT EXISTS published BOOLEAN DEFAULT FALSE,
ADD COLUMN IF NOT EXISTS retry_count INTEGER DEFAULT 0,
ADD COLUMN IF NOT EXISTS last_retry_at TIMESTAMP;

-- Index for efficient polling of unpublished events
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON outbox_events(published, created_at) 
WHERE published = FALSE;

-- Index for cleanup of old published events
CREATE INDEX IF NOT EXISTS idx_outbox_published_date ON outbox_events(published, created_at) 
WHERE published = TRUE;

-- Index added here (was incorrectly in V20 before outbox_events existed)
CREATE INDEX IF NOT EXISTS idx_outbox_published_created ON outbox_events(published, created_at);
