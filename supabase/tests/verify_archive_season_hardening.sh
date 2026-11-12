#!/usr/bin/env bash
# Post-deployment verification for the season archival hardening.
# Read-only. Exits non-zero when any SQL assertion fails.
#
# Usage:
#   DATABASE_URL='postgresql://postgres@127.0.0.1:5432/ff1' \
#     ./supabase/tests/verify_archive_season_hardening.sh
set -euo pipefail

PSQL="${PSQL:-psql}"
: "${DATABASE_URL:?DATABASE_URL is required}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PGCLIENTENCODING="${PGCLIENTENCODING:-UTF8}"

"$PSQL" "$DATABASE_URL" -v ON_ERROR_STOP=1 \
    -f "$SCRIPT_DIR/verify_archive_season_hardening.sql"
