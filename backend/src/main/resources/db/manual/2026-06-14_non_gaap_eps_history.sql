CREATE TABLE IF NOT EXISTS non_gaap_eps_history (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    as_of_date DATE NOT NULL,
    non_gaap_eps NUMERIC(19, 4) NOT NULL,
    source_label VARCHAR(255),
    source_url TEXT,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_non_gaap_eps_history_symbol_date UNIQUE (symbol, as_of_date)
);

CREATE INDEX IF NOT EXISTS idx_non_gaap_eps_history_symbol_date
    ON non_gaap_eps_history (symbol, as_of_date);
