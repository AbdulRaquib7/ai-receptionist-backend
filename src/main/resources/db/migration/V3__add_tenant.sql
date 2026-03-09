-- V3__add_tenant.sql
-- Multi-tenancy foundation: tenant, tenant_config, and tenant_id on existing tables.

CREATE TABLE IF NOT EXISTS tenant (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(200)  NOT NULL,
    slug            VARCHAR(100)  NOT NULL UNIQUE,
    twilio_phone    VARCHAR(20)   NOT NULL UNIQUE,
    timezone        VARCHAR(50)   NOT NULL DEFAULT 'America/New_York',
    language        VARCHAR(10)   NOT NULL DEFAULT 'en',
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS tenant_config (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT        NOT NULL REFERENCES tenant(id),
    config_key      VARCHAR(100)  NOT NULL,
    config_value    TEXT          NOT NULL,
    UNIQUE (tenant_id, config_key)
);

-- Insert default tenant
INSERT INTO tenant (name, slug, twilio_phone, active)
VALUES ('Default Practice', 'default', '+10000000000', TRUE)
ON CONFLICT (slug) DO NOTHING;

----------------------------------------------------
-- Add tenant_id columns only if tables exist
----------------------------------------------------

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name='doctor') THEN
        ALTER TABLE doctor ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES tenant(id);
        UPDATE doctor SET tenant_id = (SELECT id FROM tenant WHERE slug='default') WHERE tenant_id IS NULL;
        ALTER TABLE doctor ALTER COLUMN tenant_id SET NOT NULL;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name='patient') THEN
        ALTER TABLE patient ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES tenant(id);
        UPDATE patient SET tenant_id = (SELECT id FROM tenant WHERE slug='default') WHERE tenant_id IS NULL;
        ALTER TABLE patient ALTER COLUMN tenant_id SET NOT NULL;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name='appointment_slot') THEN
        ALTER TABLE appointment_slot ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES tenant(id);
        UPDATE appointment_slot SET tenant_id = (SELECT id FROM tenant WHERE slug='default') WHERE tenant_id IS NULL;
        ALTER TABLE appointment_slot ALTER COLUMN tenant_id SET NOT NULL;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name='appointment') THEN
        ALTER TABLE appointment ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES tenant(id);
        UPDATE appointment SET tenant_id = (SELECT id FROM tenant WHERE slug='default') WHERE tenant_id IS NULL;
        ALTER TABLE appointment ALTER COLUMN tenant_id SET NOT NULL;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name='conversation_history') THEN
        ALTER TABLE conversation_history ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES tenant(id);
        UPDATE conversation_history SET tenant_id = (SELECT id FROM tenant WHERE slug='default') WHERE tenant_id IS NULL;
        ALTER TABLE conversation_history ALTER COLUMN tenant_id SET NOT NULL;
    END IF;
END $$;

----------------------------------------------------
-- Set default tenant for conversation history
----------------------------------------------------

DO $$
DECLARE
    _tid BIGINT;
BEGIN
    SELECT id INTO _tid FROM tenant WHERE slug='default';

    IF _tid IS NOT NULL AND
       EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name='conversation_history') THEN
        EXECUTE format('ALTER TABLE conversation_history ALTER COLUMN tenant_id SET DEFAULT %s', _tid);
    END IF;
END $$;

----------------------------------------------------
-- Seed tenant configuration
----------------------------------------------------

INSERT INTO tenant_config (tenant_id, config_key, config_value)
SELECT t.id, k.key, k.val
FROM tenant t,
     (VALUES
        ('ai_name', 'Sarah'),
        ('business_hours', '9:00 AM - 10:00 PM'),
        ('business_address', ''),
        ('supported_actions', 'book, reschedule, or cancel appointments')
     ) AS k(key, val)
WHERE t.slug = 'default'
ON CONFLICT (tenant_id, config_key) DO NOTHING;

----------------------------------------------------
-- Indexes
----------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_tenant_config_tenant ON tenant_config (tenant_id);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name='doctor') THEN
        CREATE INDEX IF NOT EXISTS idx_doctor_tenant ON doctor (tenant_id);
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name='patient') THEN
        CREATE INDEX IF NOT EXISTS idx_patient_tenant ON patient (tenant_id);
    END IF;
END $$;