CREATE TABLE IF NOT EXISTS sec_debt_evidence (
  id BIGSERIAL PRIMARY KEY, symbol VARCHAR(20) NOT NULL, period_end DATE NOT NULL,
  metric_type VARCHAR(24) NOT NULL, component_type VARCHAR(48) NOT NULL,
  amount NUMERIC(30,8), coverage_status VARCHAR(16) NOT NULL, selected_route VARCHAR(64) NOT NULL,
  source_concepts TEXT NOT NULL, accession_numbers TEXT, form VARCHAR(16), filed_date DATE,
  source_start DATE, source_end DATE, quarterization_method VARCHAR(32), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_sec_debt_evidence UNIQUE(symbol,period_end,metric_type,component_type,selected_route,source_concepts,accession_numbers)
);
CREATE INDEX IF NOT EXISTS idx_sec_debt_evidence_lookup ON sec_debt_evidence(symbol, period_end DESC, metric_type);
