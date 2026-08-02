-- Manual migration: position metadata for structured portfolio export
-- Date: 2026-05-23
-- Target DB: PostgreSQL

BEGIN;

ALTER TABLE public.positions
    ADD COLUMN IF NOT EXISTS asset_class VARCHAR(40),
    ADD COLUMN IF NOT EXISTS instrument_type VARCHAR(60),
    ADD COLUMN IF NOT EXISTS underlying VARCHAR(20),
    ADD COLUMN IF NOT EXISTS sector VARCHAR(80),
    ADD COLUMN IF NOT EXISTS region VARCHAR(80),
    ADD COLUMN IF NOT EXISTS metadata_updated_at TIMESTAMP(6) WITH TIME ZONE;

COMMIT;
