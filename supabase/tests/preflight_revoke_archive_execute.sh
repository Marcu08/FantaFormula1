#!/usr/bin/env bash
# =============================================================================
# FantaFormula 1 - persistent archive_season deployment lockout
#
# This is a security preflight, not a migration-history repair. It must run and
# COMMIT before any migration that can touch archive_season. The Supabase CLI
# wraps migrations transactionally, so a REVOKE inside a failing migration is
# rolled back; this external lockout is what survives a failed deployment.
#
# The SQL layer:
#   * verifies the target database and public schema exist;
#   * validates the canonical signature, owner, SECURITY DEFINER and search_path;
#   * rejects unexpected overloads;
#   * revokes PUBLIC/anon/authenticated/service_role direct grants;
#   * refuses to continue if a custom role still grants effective access;
#   * verifies effective EXECUTE is false;
#   * records an active deployment lockout table.
#
# Required:
#   DATABASE_URL
# Optional:
#   EXPECTED_DATABASE (defaults to the database path in DATABASE_URL)
#   PSQL (defaults to psql)
# =============================================================================
set -euo pipefail

PSQL="${PSQL:-psql}"
: "${DATABASE_URL:?DATABASE_URL is required}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_FILE="$SCRIPT_DIR/../scripts/preflight_archive_execute.sql"

if [ ! -f "$SQL_FILE" ]; then
    echo "FAIL: preflight SQL not found: $SQL_FILE" >&2
    exit 2
fi

if [ -z "${EXPECTED_DATABASE:-}" ]; then
    db_part="${DATABASE_URL%%\?*}"
    EXPECTED_DATABASE="${db_part##*/}"
fi

if [ -z "$EXPECTED_DATABASE" ]; then
    echo "FAIL: cannot determine expected database name" >&2
    exit 2
fi

if ! command -v "$PSQL" >/dev/null 2>&1; then
    echo "FAIL: psql not found (set PSQL=/path/to/psql)" >&2
    exit 2
fi

ACTUAL_DATABASE="$("$PSQL" "$DATABASE_URL" -v ON_ERROR_STOP=1 -Atq -c 'select current_database();' | tr -d '\r')"
if [ "$ACTUAL_DATABASE" != "$EXPECTED_DATABASE" ]; then
    echo "FAIL: target database mismatch: expected '$EXPECTED_DATABASE', connected to '$ACTUAL_DATABASE'" >&2
    exit 1
fi

"$PSQL" "$DATABASE_URL" -v ON_ERROR_STOP=1 -1 -f "$SQL_FILE"

echo "PREFLIGHT OK: archive_season lockout active on database '$EXPECTED_DATABASE'"
