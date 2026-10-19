-- SPDX-License-Identifier: AGPL-3.0-only
--
-- Token/quota schema for the self-hosted CDSI PostgreSQL backend.
-- Apply before starting the service. The application intentionally does NOT
-- execute database schema changes automatically in production.
--
-- All tokens belong to an authenticated user. Row locks on the bucket
-- serialize competing prepares and redemptions across service instances.
CREATE TABLE IF NOT EXISTS cdsi_token_bucket (
    user_key TEXT PRIMARY KEY,
    amount DOUBLE PRECISION NOT NULL DEFAULT 0 CHECK (amount >= 0),
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS cdsi_token_cost (
    user_key TEXT NOT NULL REFERENCES cdsi_token_bucket(user_key) ON DELETE CASCADE,
    token_hash BYTEA NOT NULL,
    cost INTEGER NOT NULL CHECK (cost > 0),
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_key, token_hash)
);

CREATE INDEX IF NOT EXISTS cdsi_token_cost_expires_at_idx
    ON cdsi_token_cost (expires_at);

-- Expired prepared tokens are never valid. Schedule periodic cleanup on
-- production databases; the application also reaps a user's expired tokens
-- during token preparation.
-- DELETE FROM cdsi_token_cost WHERE expires_at <= now();
