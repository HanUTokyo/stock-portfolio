ALTER TABLE data_review_records ADD COLUMN IF NOT EXISTS reason_code VARCHAR(64);
ALTER TABLE data_review_audit_logs ADD COLUMN IF NOT EXISTS reason_code VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_data_review_records_source_status_updated
    ON data_review_records (source_name, review_status, updated_at DESC);
