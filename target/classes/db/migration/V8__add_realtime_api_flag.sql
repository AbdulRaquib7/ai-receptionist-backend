-- V8__add_realtime_api_flag.sql
-- Enables per-tenant routing to OpenAI Realtime API pipeline (default: legacy pipeline)
ALTER TABLE tenant
    ADD COLUMN IF NOT EXISTS use_realtime_api BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN tenant.use_realtime_api IS
    'When true, inbound calls for this tenant use the OpenAI Realtime API pipeline instead of the legacy Whisper+GPT-4o+ElevenLabs pipeline.';
