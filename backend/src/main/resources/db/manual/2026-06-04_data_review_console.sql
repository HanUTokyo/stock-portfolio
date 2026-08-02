CREATE TABLE IF NOT EXISTS data_review_records (
    id BIGSERIAL PRIMARY KEY,
    source_name VARCHAR(80) NOT NULL,
    record_id VARCHAR(80) NOT NULL,
    review_status VARCHAR(24) NOT NULL DEFAULT 'pending',
    reviewed_value_json TEXT,
    note TEXT,
    reviewer VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_data_review_records_source_record UNIQUE (source_name, record_id)
);

CREATE TABLE IF NOT EXISTS data_review_audit_logs (
    id BIGSERIAL PRIMARY KEY,
    source_name VARCHAR(80) NOT NULL,
    record_id VARCHAR(80) NOT NULL,
    field_name VARCHAR(120) NOT NULL,
    old_value TEXT,
    new_value TEXT,
    action VARCHAR(40) NOT NULL,
    review_status VARCHAR(24) NOT NULL,
    reviewer VARCHAR(80),
    note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_data_review_records_source_status
    ON data_review_records (source_name, review_status);

CREATE INDEX IF NOT EXISTS idx_data_review_audit_logs_source_record_created
    ON data_review_audit_logs (source_name, record_id, created_at DESC);
