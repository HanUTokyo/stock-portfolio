-- Filing-scoped evidence for FCFF indirect-CFO bridges. Apply before enabling bridge rebuilds.
ALTER TABLE fundamental_fact_observation ADD COLUMN IF NOT EXISTS period_start DATE;
ALTER TABLE fundamental_fact_observation ADD COLUMN IF NOT EXISTS xbrl_bucket VARCHAR(48);
ALTER TABLE fundamental_fact_observation ADD COLUMN IF NOT EXISTS calculation_weight INTEGER;
CREATE INDEX IF NOT EXISTS idx_fundamental_fact_xbrl_leaf
    ON fundamental_fact_observation(symbol, field_name, period_end, source_date DESC);
