-- Manual migration: add source and quote EPS columns
-- Date: 2026-04-20

BEGIN;

ALTER TABLE public.earnings_history
    ADD COLUMN IF NOT EXISTS source_eps NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS eps_in_quote NUMERIC(19,4);

COMMIT;
