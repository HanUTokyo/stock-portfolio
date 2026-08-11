CREATE TABLE IF NOT EXISTS sec_share_count_evidence (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    component_type VARCHAR(48) NOT NULL,
    amount NUMERIC(30,8),
    coverage_status VARCHAR(24) NOT NULL,
    statement_role TEXT,
    source_concepts TEXT,
    accession_number VARCHAR(32),
    form VARCHAR(16),
    filed_date DATE,
    split_adjustment_factor NUMERIC(24,12),
    alignment_status TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_sec_share_count_evidence
    ON sec_share_count_evidence(symbol, period_start, period_end, component_type, accession_number);
CREATE INDEX IF NOT EXISTS idx_sec_share_count_evidence_period
    ON sec_share_count_evidence(symbol, period_end);

ALTER TABLE sec_share_count_evidence ALTER COLUMN alignment_status TYPE TEXT;
