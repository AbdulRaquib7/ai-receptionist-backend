-- V6__add_appointment_reminded.sql
-- Add reminded flag to track which appointments have received reminder calls.

ALTER TABLE appointment ADD COLUMN IF NOT EXISTS reminded BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_appointment_reminded ON appointment (reminded, status);
