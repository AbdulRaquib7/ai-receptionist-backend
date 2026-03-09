-- V1__baseline.sql
-- Baseline schema matching the existing Hibernate-generated tables.
-- Flyway will record this as already applied (baseline-on-migrate=true).

CREATE TABLE IF NOT EXISTS doctor (
    id          BIGSERIAL PRIMARY KEY,
    key         VARCHAR(50)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    specialization VARCHAR(255),
    schedule_start VARCHAR(5) NOT NULL,
    schedule_end   VARCHAR(5) NOT NULL,
    active      BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS patient (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(100),
    phone        VARCHAR(20),
    twilio_phone VARCHAR(30),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS appointment_slot (
    id         BIGSERIAL PRIMARY KEY,
    doctor_id  BIGINT      NOT NULL REFERENCES doctor(id),
    slot_date  DATE        NOT NULL,
    start_time VARCHAR(10) NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    UNIQUE (doctor_id, slot_date, start_time)
);

CREATE TABLE IF NOT EXISTS appointment (
    id         BIGSERIAL PRIMARY KEY,
    patient_id BIGINT      NOT NULL REFERENCES patient(id),
    doctor_id  BIGINT      NOT NULL REFERENCES doctor(id),
    slot_id    BIGINT      NOT NULL REFERENCES appointment_slot(id),
    status     VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS conversation_history (
    id           BIGSERIAL PRIMARY KEY,
    call_sid     VARCHAR(100) NOT NULL,
    twilio_phone VARCHAR(30),
    role         VARCHAR(10)  NOT NULL,
    content      TEXT         NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
