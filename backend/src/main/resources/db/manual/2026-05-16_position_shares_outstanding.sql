-- Manual migration: cache shares outstanding for DCF valuation
-- Date: 2026-05-16
-- Target DB: PostgreSQL
--
-- shares_outstanding is the automatically fetched market-data value.
-- shares_outstanding_override is an optional manual correction used when
-- issuance, buybacks, splits, or vendor lag make the fetched value unsuitable.

BEGIN;

ALTER TABLE public.positions
    ADD COLUMN IF NOT EXISTS shares_outstanding NUMERIC(24,4),
    ADD COLUMN IF NOT EXISTS shares_outstanding_override NUMERIC(24,4),
    ADD COLUMN IF NOT EXISTS shares_outstanding_source VARCHAR(40),
    ADD COLUMN IF NOT EXISTS shares_outstanding_updated_at TIMESTAMP(6) WITH TIME ZONE;

COMMIT;
