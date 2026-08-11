BEGIN;

CREATE TABLE IF NOT EXISTS public.fundamental_rebuild_audit (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    as_of_date DATE NOT NULL,
    fiscal_period_key VARCHAR(24) NOT NULL,
    field_name VARCHAR(64) NOT NULL,
    before_value NUMERIC(24,4),
    after_value NUMERIC(24,4),
    trigger VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_fundamental_rebuild_audit_run
    ON public.fundamental_rebuild_audit (run_id);

CREATE INDEX IF NOT EXISTS idx_fundamental_rebuild_audit_symbol_period
    ON public.fundamental_rebuild_audit (symbol, fiscal_period_key);

ALTER TABLE public.valuation_scenario
    ADD COLUMN IF NOT EXISTS assumptions_schema_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS cash_flow_basis_at_save VARCHAR(16),
    ADD COLUMN IF NOT EXISTS migration_status VARCHAR(24) NOT NULL DEFAULT 'CURRENT';

CREATE TABLE IF NOT EXISTS public.forecast_scenario_snapshot (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL UNIQUE,
    archetype VARCHAR(48) NOT NULL,
    template_version VARCHAR(48) NOT NULL,
    snapshot_json TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMIT;
