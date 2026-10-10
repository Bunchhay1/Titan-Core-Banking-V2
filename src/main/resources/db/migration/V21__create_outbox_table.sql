-- Transactional Outbox Pattern Table
-- Guarantees atomicity between DB transactions and Kafka events
CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    aggregate_id VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    published BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP,
    retry_count INT DEFAULT 0,
    last_error TEXT
);

-- Index for polling unpublished events
CREATE INDEX idx_outbox_unpublished ON outbox_events(published, created_at) 
WHERE published = FALSE;

-- Index for cleanup queries
CREATE INDEX idx_outbox_published_at ON outbox_events(published_at) 
WHERE published = TRUE;

COMMENT ON TABLE outbox_events IS 'Transactional outbox for guaranteed event delivery to Kafka';
