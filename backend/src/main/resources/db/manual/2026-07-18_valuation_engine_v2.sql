-- Valuation engine v2: company-specific growth evidence, net-debt bridge, and split-aware CAPE.
-- Additive only; no demo or reviewed rows are rewritten.

BEGIN;

ALTER TABLE public.earnings_estimates
    ADD COLUMN IF NOT EXISTS revenue_avg NUMERIC(24,4),
    ADD COLUMN IF NOT EXISTS revenue_low NUMERIC(24,4),
    ADD COLUMN IF NOT EXISTS revenue_high NUMERIC(24,4),
    ADD COLUMN IF NOT EXISTS revenue_analysts INTEGER;

ALTER TABLE public.earnings_history
    ADD COLUMN IF NOT EXISTS short_term_investments NUMERIC(24,4),
    ADD COLUMN IF NOT EXISTS noncurrent_marketable_securities NUMERIC(24,4);

CREATE TABLE IF NOT EXISTS public.stock_split (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    split_date DATE NOT NULL,
    numerator NUMERIC(19,8) NOT NULL,
    denominator NUMERIC(19,8) NOT NULL,
    source_code VARCHAR(24) NOT NULL,
    source_date DATE NOT NULL,
    retrieved_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_stock_split_positive CHECK (numerator > 0 AND denominator > 0),
    CONSTRAINT uq_stock_split_symbol_date UNIQUE (symbol, split_date)
);

CREATE INDEX IF NOT EXISTS idx_stock_split_symbol_date
    ON public.stock_split (symbol, split_date);

COMMIT;
