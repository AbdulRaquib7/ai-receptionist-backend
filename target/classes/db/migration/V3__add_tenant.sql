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

-- Insert a default tenant so existing data can be assigned
INSERT INTO tenant (name, slug, twilio_phone) VALUES ('Default Practice', 'default', '+10000000000')
    ON CONFLICT (slug) DO NOTHING;

-- Add tenant_id to existing tables (nullable first, then populate, then constrain)
ALTER TABLE doctor ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES tenant(id);
ALTER TABLE patient ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES tenant(id);
ALTER TABLE appointment_slot ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES tenant(id);
ALTER TABLE appointment ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES tenant(id);
ALTER TABLE conversation_history ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES tenant(id);

-- Assign all existing rows to the default tenant
UPDATE doctor SET tenant_id = (SELECT id FROM tenant WHERE slug = 'default') WHERE tenant_id IS NULL;
UPDATE patient SET tenant_id = (SELECT id FROM tenant WHERE slug = 'default') WHERE tenant_id IS NULL;
UPDATE appointment_slot SET tenant_id = (SELECT id FROM tenant WHERE slug = 'default') WHERE tenant_id IS NULL;
UPDATE appointment SET tenant_id = (SELECT id FROM tenant WHERE slug = 'default') WHERE tenant_id IS NULL;
UPDATE conversation_history SET tenant_id = (SELECT id FROM tenant WHERE slug = 'default') WHERE tenant_id IS NULL;

-- Now make tenant_id NOT NULL (with default for conversation_history to ease transition)
ALTER TABLE doctor ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE patient ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE appointment_slot ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE appointment ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE conversation_history ALTER COLUMN tenant_id SET NOT NULL;
-- Set a static default for conversation_history.tenant_id using a DO block
-- (PostgreSQL does not allow subqueries in DEFAULT expressions)
DO $$
DECLARE
    _tid BIGINT;
BEGIN
    SELECT id INTO _tid FROM tenant WHERE slug = 'default';
    IF _tid IS NOT NULL THEN
        EXECUTE format('ALTER TABLE conversation_history ALTER COLUMN tenant_id SET DEFAULT %s', _tid);
    END IF;
END $$;

-- Seed default tenant config (AI name, business details)
INSERT INTO tenant_config (tenant_id, config_key, config_value)
SELECT t.id, k.key, k.val
FROM tenant t, (VALUES
    ('ai_name', 'Sarah'),
    ('business_hours', '9:00 AM - 10:00 PM'),
    ('business_address', ''),
    ('supported_actions', 'book, reschedule, or cancel appointments')
) AS k(key, val)
WHERE t.slug = 'default'
ON CONFLICT (tenant_id, config_key) DO NOTHING;

-- Indexes for tenant lookups
CREATE INDEX IF NOT EXISTS idx_tenant_config_tenant ON tenant_config (tenant_id);
CREATE INDEX IF NOT EXISTS idx_doctor_tenant ON doctor (tenant_id);
CREATE INDEX IF NOT EXISTS idx_patient_tenant ON patient (tenant_id);
