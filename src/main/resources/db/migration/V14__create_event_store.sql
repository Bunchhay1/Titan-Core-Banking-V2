-- Create event_store table for event sourcing
CREATE TABLE event_store (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_data TEXT NOT NULL,
    version BIGINT NOT NULL,
    timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    user_id VARCHAR(100) NOT NULL,
    correlation_id VARCHAR(100),
    metadata TEXT,
    
    CONSTRAINT uq_aggregate_version UNIQUE (aggregate_id, version)
);

-- Indexes for event sourcing queries
CREATE INDEX idx_event_aggregate ON event_store(aggregate_id, aggregate_type, version);
CREATE INDEX idx_event_timestamp ON event_store(timestamp DESC);
CREATE INDEX idx_event_type ON event_store(event_type);
CREATE INDEX idx_event_correlation ON event_store(correlation_id) WHERE correlation_id IS NOT NULL;

-- Comment for documentation
COMMENT ON TABLE event_store IS 'Immutable event store for event sourcing - stores all domain events';
COMMENT ON COLUMN event_store.aggregate_id IS 'ID of the aggregate (e.g., account ID, customer ID)';
COMMENT ON COLUMN event_store.event_type IS 'Type of event (e.g., AccountCreatedEvent, MoneyDepositedEvent)';
COMMENT ON COLUMN event_store.version IS 'Version number for optimistic concurrency control';
COMMENT ON COLUMN event_store.correlation_id IS 'Links related events together (e.g., transfer has 2 events)';
