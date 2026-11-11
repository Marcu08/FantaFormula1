#!/usr/bin/env bash
# =============================================================================
# FantaFormula 1 - canonical archive_season deployment entry point
#
# This wrapper is the only supported deployment/recovery path for the season
# archive security migrations. It enforces the external preflight lockout,
# applies pending migrations with the real Supabase CLI, executes the explicit
# history-independent repair, verifies twice, and restores authenticated
# EXECUTE only after the locked-down verifier passes.
#
# Required:
#   DATABASE_URL
# Optional:
#   PSQL (default: psql)
#   SUPABASE (default: supabase)
#   SUPABASE_CLI_VERSION (default: 2.114.0)
#   SUPABASE_WORKDIR (default: repository root; must contain supabase/migrations)
#   SUPABASE_SKIP_VAULT (default: 1; use --skip-vault)
#   EXPECTED_DATABASE (default: database path in DATABASE_URL)
#
# Example (disposable/local):
#   DATABASE_URL='postgresql://postgres:postgres@127.0.0.1:5432/ff1' \
#     ./supabase/scripts/deploy_archive_safe.sh
# =============================================================================
set -euo pipefail

PSQL="${PSQL:-psql}"
SUPABASE="${SUPABASE:-supabase}"
EXPECTED_CLI_VERSION="${SUPABASE_CLI_VERSION:-2.114.0}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SUPABASE_WORKDIR="${SUPABASE_WORKDIR:-$REPO_ROOT}"
SUPABASE_SKIP_VAULT="${SUPABASE_SKIP_VAULT:-1}"
ARCHIVE_REPAIR_SQL_FILE="${ARCHIVE_REPAIR_SQL_FILE:-$REPO_ROOT/supabase/repair/archive_season_repair.sql}"
ARCHIVE_VERIFIER_SQL_FILE="${ARCHIVE_VERIFIER_SQL_FILE:-$REPO_ROOT/supabase/tests/verify_archive_season_hardening.sql}"

: "${DATABASE_URL:?DATABASE_URL is required}"

if [ ! -d "$SUPABASE_WORKDIR/supabase/migrations" ]; then
    echo "FAIL: SUPABASE_WORKDIR does not contain supabase/migrations: $SUPABASE_WORKDIR" >&2
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
if ! command -v "$SUPABASE" >/dev/null 2>&1; then
    echo "FAIL: supabase CLI not found (set SUPABASE=/path/to/supabase)" >&2
    exit 2
fi
if [ ! -f "$ARCHIVE_REPAIR_SQL_FILE" ]; then
    echo "FAIL: repair SQL file not found: $ARCHIVE_REPAIR_SQL_FILE" >&2
    exit 2
fi
if [ ! -f "$ARCHIVE_VERIFIER_SQL_FILE" ]; then
    echo "FAIL: verifier SQL file not found: $ARCHIVE_VERIFIER_SQL_FILE" >&2
    exit 2
fi

CLI_VERSION="$("$SUPABASE" --version 2>/dev/null | tr -d '\r' | head -n 1)"
if [ "$CLI_VERSION" != "$EXPECTED_CLI_VERSION" ]; then
    echo "FAIL: Supabase CLI version mismatch: expected '$EXPECTED_CLI_VERSION', got '$CLI_VERSION'" >&2
    exit 2
fi

PROJECT_WORKDIR="$SUPABASE_WORKDIR"
VAULT_FLAG=()
if [ "$SUPABASE_SKIP_VAULT" = "1" ]; then
    VAULT_FLAG=(--skip-vault)
fi

run_preflight() {
    echo "==> [1/6] preflight lockout"
    EXPECTED_DATABASE="$EXPECTED_DATABASE" \
        PSQL="$PSQL" \
        DATABASE_URL="$DATABASE_URL" \
        bash "$REPO_ROOT/supabase/tests/preflight_revoke_archive_execute.sh"
}

run_repair() {
    echo "==> [3/6] explicit history-independent canonical repair"
    "$PSQL" "$DATABASE_URL" -v ON_ERROR_STOP=1 -1 \
        -f "$ARCHIVE_REPAIR_SQL_FILE"
}

run_verifier() {
    local expect_auth="$1"
    local expect_lockout="$2"
    {
        printf "set app.archive_verify_expect_auth = '%s';\n" "$expect_auth"
        printf "set app.archive_verify_expect_lockout = '%s';\n" "$expect_lockout"
        cat "$ARCHIVE_VERIFIER_SQL_FILE"
    } | "$PSQL" "$DATABASE_URL" -v ON_ERROR_STOP=1
}

fail_with_lockout() {
    local message="$1"
    echo "DEPLOY FAIL: $message" >&2
    echo "DEPLOY FAIL: re-applying persistent preflight lockout" >&2
    if ! run_preflight; then
        echo "DEPLOY CRITICAL: preflight after failure did not complete; keep application traffic blocked and inspect the database manually" >&2
        exit 1
    fi
    echo "DEPLOY FAIL: inspecting the locked-down state; keep application traffic blocked" >&2
    if run_verifier false true; then
        echo "DEPLOY LOCKED: revoked state verified, but deployment/repair is incomplete; rerun the wrapper" >&2
    else
        echo "DEPLOY FAIL: verifier rejects the state or could not verify it; investigate before retrying" >&2
    fi
    exit 1
}

# Step 1: persistent, externally committed lockout.
run_preflight

# Step 1b: remove only known remote-only legacy history versions so the real
# CLI can reach the pending canonical migrations. This is HISTORY REPAIR, not
# SQL execution. The canonical SQL is executed explicitly in step 3.
echo "==> [1b/6] inspect migration history"
LOCAL_VERSIONS="$(
    for migration_file in "$PROJECT_WORKDIR"/supabase/migrations/*.sql; do
        base="$(basename "$migration_file")"
        echo "${base%%_*}"
    done | sort -u
)"
HISTORY_EXISTS="$("$PSQL" "$DATABASE_URL" -v ON_ERROR_STOP=1 -Atq \
    -c "select case when to_regclass('supabase_migrations.schema_migrations') is null then 'f' else 't' end;" | tr -d '\r')"
if [ "$HISTORY_EXISTS" = "t" ]; then
    REMOTE_ONLY="$("$PSQL" "$DATABASE_URL" -v ON_ERROR_STOP=1 -Atq \
        -c "select version from supabase_migrations.schema_migrations order by version;" \
        | tr -d '\r' | while IFS= read -r version; do
            [ -z "$version" ] && continue
            if ! printf '%s\n' "$LOCAL_VERSIONS" | grep -Fxq "$version"; then
                printf '%s\n' "$version"
            fi
        done)"
    if [ -n "$REMOTE_ONLY" ]; then
        while IFS= read -r version; do
            [ -z "$version" ] && continue
            if [ "$version" = "20261109120000" ]; then
                echo "==> repairing known legacy migration history: $version (history only)"
                "$SUPABASE" migration repair \
                    --workdir "$PROJECT_WORKDIR" \
                    --db-url "$DATABASE_URL" \
                    --yes \
                    --status reverted \
                    "$version"
            else
                fail_with_lockout "remote migration version '$version' has no local file and is not a recognized legacy version"
            fi
        done <<< "$REMOTE_ONLY"
    fi
fi

# Step 2: real Supabase CLI migrations. The CLI wraps each migration in a
# transaction; the preflight state is independent and survives rollback.
echo "==> [2/6] supabase db push --include-all"
if ! "$SUPABASE" db push \
    --workdir "$PROJECT_WORKDIR" \
    --db-url "$DATABASE_URL" \
    --include-all \
    --yes \
    "${VAULT_FLAG[@]}"; then
    fail_with_lockout "supabase db push failed"
fi

# Step 3: deliberately execute the canonical repair even if all migration
# versions are already recorded. This is SQL execution, not history repair.
if ! run_repair; then
    fail_with_lockout "explicit canonical repair failed"
fi

# Step 4: structural/security verification while authenticated is still
# locked out. This must pass before any permission is restored.
echo "==> [4/6] locked-down verifier"
if ! run_verifier false true; then
    fail_with_lockout "locked-down verifier failed"
fi

# Step 5: restore only the intended permission and clear the deployment
# lockout, re-checking the canonical function/guard identity in the same
# transaction.
echo "==> [5/6] finalize permissions"
if ! "$PSQL" "$DATABASE_URL" -v ON_ERROR_STOP=1 -1 \
    -f "$REPO_ROOT/supabase/scripts/finalize_archive_season_deployment.sql"; then
    fail_with_lockout "finalize failed"
fi

# Step 6: final verifier with the intended authenticated grant.
echo "==> [6/6] final verifier"
if ! run_verifier true false; then
    fail_with_lockout "final verifier failed after permission restoration"
fi

echo "DEPLOY OK: canonical archive_season installed, guards active, authenticated EXECUTE verified"
