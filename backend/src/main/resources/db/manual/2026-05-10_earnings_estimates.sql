CREATE TABLE IF NOT EXISTS earnings_estimates (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    period_type VARCHAR(20) NOT NULL,
    period_code VARCHAR(10) NOT NULL,
    period_end_date DATE NOT NULL,
    eps_avg NUMERIC(19, 4),
    eps_low NUMERIC(19, 4),
    eps_high NUMERIC(19, 4),
    number_of_analysts INTEGER,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_earnings_estimates_symbol_period UNIQUE (symbol, period_type, period_code)
);

CREATE INDEX IF NOT EXISTS idx_earnings_estimates_symbol_type_end
    ON earnings_estimates (symbol, period_type, period_end_date);

UPDATE earnings_history SET forward_eps = NULL WHERE forward_eps IS NOT NULL;
