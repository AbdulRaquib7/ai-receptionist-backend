-- V7__add_tenant_twilio_credentials.sql
-- Per-tenant Twilio credentials so each client/org can use their own Twilio account.
-- When NULL, the system falls back to the global TWILIO_ACCOUNT_SID / TWILIO_AUTH_TOKEN env vars.

ALTER TABLE tenant ADD COLUMN IF NOT EXISTS twilio_account_sid VARCHAR(100);
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS twilio_auth_token  VARCHAR(100);
