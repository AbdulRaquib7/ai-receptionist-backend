-- V6__add_appointment_reminded.sql
-- Add reminded flag to track which appointments have received reminder calls.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 
        FROM information_schema.tables 
        WHERE table_name = 'appointment'
    ) THEN

        ALTER TABLE appointment
        ADD COLUMN IF NOT EXISTS reminded BOOLEAN NOT NULL DEFAULT FALSE;

        CREATE INDEX IF NOT EXISTS idx_appointment_reminded
        ON appointment (reminded, status);

    END IF;
END $$;