-- =============================================================================
-- 01-create-databases.sql
-- Runs once when the Postgres container is first initialized.
-- Creates all service databases alongside the default titandb.
-- =============================================================================

-- titandb is already created by POSTGRES_DB env var

-- Notifications service DB
SELECT 'CREATE DATABASE notificationdb OWNER postgres'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'notificationdb')\gexec

-- Promotions service DB
SELECT 'CREATE DATABASE promotiondb OWNER postgres'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'promotiondb')\gexec

-- Enable PostGIS on promotiondb (required by V5 Flyway migration)
\c promotiondb
CREATE EXTENSION IF NOT EXISTS postgis;

-- Loans service DB
SELECT 'CREATE DATABASE loansdb OWNER postgres'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'loansdb')\gexec

-- Titan System Control Panel DB
SELECT 'CREATE DATABASE titan_systemdb OWNER postgres'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'titan_systemdb')\gexec
