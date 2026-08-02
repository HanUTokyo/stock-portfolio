-- Web valuation engine, point-in-time fundamentals, CPI cache, and scenarios.
-- Additive migration: existing rows and review overlays are preserved.

BEGIN;

-- Position is read by every valuation request. Keep this migration safe for
-- installations that have not yet applied the earlier offline-sync migration.
ALTER TABLE public.positions
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE public.earnings_history
    ADD COLUMN IF NOT EXISTS diluted_eps NUMERIC(19,6),
    ADD COLUMN IF NOT EXISTS diluted_weighted_average_shares NUMERIC(24,4),
    ADD COLUMN IF NOT EXISTS depreciation_amortization NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS change_in_working_capital NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS net_borrowing NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS total_assets NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS fiscal_year INTEGER,
    ADD COLUMN IF NOT EXISTS fiscal_period VARCHAR(8),
    ADD COLUMN IF NOT EXISTS filing_date DATE,
    ADD COLUMN IF NOT EXISTS field_metadata TEXT;

ALTER TABLE public.price_history
    ADD COLUMN IF NOT EXISTS adjusted_close_price NUMERIC(19,4);

ALTER TABLE public.positions
    ADD COLUMN IF NOT EXISTS quote_currency VARCHAR(10),
    ADD COLUMN IF NOT EXISTS beta NUMERIC(19,6),
    ADD COLUMN IF NOT EXISTS beta_source VARCHAR(80),
    ADD COLUMN IF NOT EXISTS beta_updated_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS public.fundamental_fact_observation (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    period_end DATE NOT NULL,
    fiscal_year INTEGER,
    fiscal_period VARCHAR(8),
    field_name VARCHAR(80) NOT NULL,
    fact_value NUMERIC(30,8) NOT NULL,
    unit VARCHAR(32),
    currency_code VARCHAR(10),
    source_code VARCHAR(24) NOT NULL,
    source_date DATE NOT NULL,
    accession_number VARCHAR(32),
    form VARCHAR(16),
    captured_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_fundamental_fact_observation
        UNIQUE (symbol, period_end, field_name, source_code, source_date, accession_number, unit)
);

CREATE INDEX IF NOT EXISTS idx_fundamental_fact_symbol_field_date
    ON public.fundamental_fact_observation (symbol, field_name, source_date, period_end);

CREATE TABLE IF NOT EXISTS public.economic_observation (
    id BIGSERIAL PRIMARY KEY,
    series_id VARCHAR(40) NOT NULL,
    observation_date DATE NOT NULL,
    observation_value NUMERIC(24,8) NOT NULL,
    source_code VARCHAR(32) NOT NULL,
    source_name VARCHAR(120) NOT NULL,
    retrieved_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_economic_observation UNIQUE (series_id, observation_date)
);

CREATE INDEX IF NOT EXISTS idx_economic_observation_series_date
    ON public.economic_observation (series_id, observation_date DESC);

CREATE TABLE IF NOT EXISTS public.valuation_scenario (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    scenario_type VARCHAR(8) NOT NULL,
    model_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO',
    assumptions_json TEXT NOT NULL,
    engine_version VARCHAR(40) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_valuation_scenario_type CHECK (scenario_type IN ('BEAR', 'BASE', 'BULL')),
    CONSTRAINT ck_valuation_model_mode CHECK (model_mode = 'AUTO'),
    CONSTRAINT uq_valuation_scenario_symbol_type UNIQUE (symbol, scenario_type)
);

COMMIT;
