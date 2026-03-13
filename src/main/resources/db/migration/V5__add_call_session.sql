-- V5__add_call_session.sql
-- Call session tracking: records every inbound/outbound call with status, outcome, and summary.

CREATE TABLE IF NOT EXISTS call_session (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT       NOT NULL REFERENCES tenant(id),
    twilio_call_sid     VARCHAR(100) NOT NULL UNIQUE,
    direction           VARCHAR(20)  NOT NULL DEFAULT 'INBOUND',
    from_number         VARCHAR(30),
    to_number           VARCHAR(30),
    status              VARCHAR(20)  NOT NULL DEFAULT 'RINGING',
    outcome             VARCHAR(30),
    started_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    ended_at            TIMESTAMPTZ,
    duration_seconds    INT,
    summary             TEXT,
    metadata            JSONB,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_call_session_tenant ON call_session (tenant_id);
CREATE INDEX IF NOT EXISTS idx_call_session_call_sid ON call_session (twilio_call_sid);
CREATE INDEX IF NOT EXISTS idx_call_session_status ON call_session (status);
CREATE INDEX IF NOT EXISTS idx_call_session_started_at ON call_session (started_at);
