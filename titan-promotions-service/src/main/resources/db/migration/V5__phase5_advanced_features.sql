-- Phase 5: Enable PostGIS extension
CREATE EXTENSION IF NOT EXISTS postgis;

-- Task 8: Partner merchants with geo-spatial data
CREATE TABLE IF NOT EXISTS partner_merchants (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    location GEOMETRY(Point, 4326) NOT NULL,
    radius_meters INTEGER NOT NULL,
    discount_percentage DECIMAL(5,2) NOT NULL,
    promo_message VARCHAR(500) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Spatial index for fast proximity queries
CREATE INDEX idx_merchant_location ON partner_merchants USING GIST(location);

-- Task 7: Event sourcing for rule audit trail
CREATE TABLE IF NOT EXISTS rule_events (
    id BIGSERIAL PRIMARY KEY,
    rule_id BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload TEXT NOT NULL,
    changed_by VARCHAR(100) NOT NULL,
    occurred_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_rule_events_rule_id ON rule_events (rule_id);

-- Task 2: Outbox pattern for reliable messaging
CREATE TABLE IF NOT EXISTS outbox_events (
    id BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    published_at TIMESTAMP,
    status VARCHAR(20) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_outbox_status ON outbox_events (status, created_at);

-- Task 6: Add A/B testing columns to applied_promotions
ALTER TABLE applied_promotions ADD COLUMN IF NOT EXISTS ab_variant VARCHAR(10);
ALTER TABLE applied_promotions ADD COLUMN IF NOT EXISTS propensity_score DECIMAL(5,2);

-- Sample data: Partner merchants in Phnom Penh
INSERT INTO partner_merchants (name, location, radius_meters, discount_percentage, promo_message) VALUES
('Brown Coffee Riverside', ST_SetSRID(ST_MakePoint(104.9282, 11.5564), 4326), 500, 10.00, '10% off your coffee! Valid for 1 hour.'),
('Amazon Cafe BKK1', ST_SetSRID(ST_MakePoint(104.9176, 11.5489), 4326), 300, 15.00, 'Flash sale: 15% discount on all items!');
