-- V2__add_indexes.sql
-- Add indexes for frequently queried columns to eliminate full table scans.

CREATE INDEX IF NOT EXISTS idx_appointment_patient_status
    ON appointment (patient_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_conversation_history_callsid
    ON conversation_history (call_sid, created_at ASC);

CREATE INDEX IF NOT EXISTS idx_appointment_slot_lookup
    ON appointment_slot (doctor_id, slot_date, status);

CREATE INDEX IF NOT EXISTS idx_patient_twilio_phone
    ON patient (twilio_phone);
