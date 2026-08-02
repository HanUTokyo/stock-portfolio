#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MIGRATION_DIR="$REPO_ROOT/backend/src/main/resources/db/manual"

MIGRATIONS=(
  "2026-05-04_quarterly_fundamentals.sql"
  "2026-05-10_earnings_estimates.sql"
  "2026-05-10_forward_eps_latest_only.sql"
  "2026-05-16_cash_flow_metrics.sql"
  "2026-05-16_fundamental_notes.sql"
  "2026-05-16_position_shares_outstanding.sql"
  "2026-05-23_position_metadata.sql"
  "2026-06-04_data_review_console.sql"
  "2026-06-14_non_gaap_eps_history.sql"
  "2026-06-14_transaction_quantity_scale.sql"
  "2026-07-13_android_offline_sync.sql"
  "2026-07-16_web_valuation_engine.sql"
  "2026-07-18_valuation_engine_v2.sql"
  "2026-07-25_valuation_notes.sql"
  "2026-07-26_data_review_risk_workbench.sql"
  "2026-08-01_capital_allocation_history.sql"
)

if [[ "${1:-}" == "--dry-run" ]]; then
  printf 'The following migrations would run in order:\n'
  printf '  %s\n' "${MIGRATIONS[@]}"
  exit 0
fi

if [[ $# -ne 0 ]]; then
  printf 'Usage: %s [--dry-run]\n' "$0" >&2
  exit 64
fi

: "${DATABASE_URL:?Set DATABASE_URL to the beta1 PostgreSQL connection string.}"
: "${BACKUP_PATH:?Set BACKUP_PATH to a new backup file outside the repository.}"

if [[ -e "$BACKUP_PATH" ]]; then
  printf 'Refusing to overwrite existing backup: %s\n' "$BACKUP_PATH" >&2
  exit 73
fi

command -v pg_dump >/dev/null || {
  printf 'pg_dump is required.\n' >&2
  exit 69
}
command -v psql >/dev/null || {
  printf 'psql is required.\n' >&2
  exit 69
}

for migration in "${MIGRATIONS[@]}"; do
  if [[ ! -f "$MIGRATION_DIR/$migration" ]]; then
    printf 'Missing migration: %s\n' "$MIGRATION_DIR/$migration" >&2
    exit 66
  fi
done

backup_parent="$(dirname "$BACKUP_PATH")"
if [[ ! -d "$backup_parent" ]]; then
  printf 'Backup directory does not exist: %s\n' "$backup_parent" >&2
  exit 72
fi

printf 'Creating pre-migration backup at %s\n' "$BACKUP_PATH"
pg_dump --format=custom --file="$BACKUP_PATH" "$DATABASE_URL"

for migration in "${MIGRATIONS[@]}"; do
  printf 'Applying %s\n' "$migration"
  psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f "$MIGRATION_DIR/$migration"
done

printf 'Migration complete. Preserve the backup until beta2 verification finishes.\n'
