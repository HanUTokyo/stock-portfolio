-- Manual migration: quarterly fundamentals and derived metrics
-- Date: 2026-05-04

BEGIN;

ALTER TABLE public.earnings_history
    ALTER COLUMN basic_eps DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS ttm_eps NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS forward_eps NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS cash_flow NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS fcf NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS roe NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS roic NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS gross_margin NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS revenue NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS gross_profit NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS operating_income NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS net_income NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS stockholders_equity NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS total_debt NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS cash_and_equivalents NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS tax_provision NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS pretax_income NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS invested_capital NUMERIC(19,4);

COMMIT;
