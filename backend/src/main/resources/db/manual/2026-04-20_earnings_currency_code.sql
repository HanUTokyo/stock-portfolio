-- Manual migration: add EPS currency code for earnings normalization
-- Date: 2026-04-20

BEGIN;

ALTER TABLE public.earnings_history
    ADD COLUMN IF NOT EXISTS currency_code VARCHAR(10);

COMMIT;
