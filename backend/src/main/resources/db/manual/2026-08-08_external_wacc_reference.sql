CREATE TABLE IF NOT EXISTS external_wacc_reference (
  id BIGSERIAL PRIMARY KEY,
  symbol VARCHAR(20) NOT NULL,
  provider VARCHAR(32) NOT NULL,
  rate_pct NUMERIC(12,6),
  source_url TEXT NOT NULL,
  provider_as_of DATE,
  retrieved_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  status VARCHAR(16) NOT NULL,
  error_message TEXT,
  CONSTRAINT uq_external_wacc_reference UNIQUE(symbol, provider)
);
CREATE INDEX IF NOT EXISTS idx_external_wacc_reference_symbol ON external_wacc_reference(symbol, retrieved_at DESC);
