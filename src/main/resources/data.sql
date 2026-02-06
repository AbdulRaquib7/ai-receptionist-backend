-- Seed sample doctors. Idempotent: skips if doctors already exist.
INSERT INTO doctor (name, specialization, description, is_active)
SELECT 'Dr. Sarah Johnson', 'General Practice', 'Experienced general practitioner', true
WHERE NOT EXISTS (SELECT 1 FROM doctor WHERE name = 'Dr. Sarah Johnson');
INSERT INTO doctor (name, specialization, description, is_active)
SELECT 'Dr. Michael Chen', 'Cardiology', 'Heart specialist', true
WHERE NOT EXISTS (SELECT 1 FROM doctor WHERE name = 'Dr. Michael Chen');
INSERT INTO doctor (name, specialization, description, is_active)
SELECT 'Dr. Emily Davis', 'Pediatrics', 'Children''s health', true
WHERE NOT EXISTS (SELECT 1 FROM doctor WHERE name = 'Dr. Emily Davis');
