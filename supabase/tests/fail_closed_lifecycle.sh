#!/usr/bin/env bash
# =============================================================================
# FantaFormula 1 - fail-closed migration lifecycle regression
#
# Runs against a disposable PostgreSQL service. It creates separate databases
# for D2 (200000 applied, 0014 pending), D3 (210000 applied, 0014 pending),
# fresh development, and a legacy weak function. It applies migration files in
# the same order used by `supabase db push --include-all` and asserts the
# resulting database state after every meaningful interruption.
#
# Required env:
#   PGHOST, PGPORT, PGUSER, PGPASSWORD
# Optional:
#   PSQL, MIGRATIONS_DIR
#
# This is a database-state regression. The Supabase CLI-specific behavior must
# still be validated in a staging project; no live project is touched here.
# =============================================================================
set -euo pipefail

PSQL="${PSQL:-psql}"
PGHOST="${PGHOST:-127.0.0.1}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-postgres}"
PGPASSWORD="${PGPASSWORD:-postgres}"
export PGPASSWORD
MIGRATIONS_DIR="${MIGRATIONS_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../migrations" && pwd)}"
VERIFIER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PREFLIGHT="$VERIFIER_DIR/preflight_revoke_archive_execute.sh"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

psql_db() {
    local db="$1"; shift
    "$PSQL" -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$db" -v ON_ERROR_STOP=1 "$@"
}
psql_admin() {
    psql_db postgres "$@"
}

db_url() {
    printf 'postgresql://%s:%s@%s:%s/%s?sslmode=disable' \
        "$PGUSER" "$PGPASSWORD" "$PGHOST" "$PGPORT" "$1"
}

reset_db() {
    local db="$1"
    psql_admin -c "drop database if exists \"$db\"" >/dev/null
    psql_admin -c "create database \"$db\"" >/dev/null
    psql_db "$db" -f "$VERIFIER_DIR/ci_setup.sql" >/dev/null
}

apply_file() {
    local db="$1" file="$2"
    psql_db "$db" -1 -f "$MIGRATIONS_DIR/$file" >/dev/null
}

apply_main() {
    local db="$1"
    for f in 0001_init.sql 0002_fixes.sql 0003_security.sql 0004_prediction_validation.sql \
             0005_prediction_count.sql 0006_prediction_bands.sql 0007_over_under_thresholds.sql \
             0008_safety_pit_bands.sql 0009_quota.sql 0010_quota_dnf.sql 0011_fixes.sql 0012_season_history.sql; do
        apply_file "$db" "$f"
    done
}

apply_dev() {
    local db="$1"
    for f in 0013_server_quotes.sql 0014_audit_fixes.sql 0015_security_hardening.sql \
             0016_atomic_prediction_revision.sql 0017_server_revision_bigint.sql \
             0018_initial_quotes_after_predictions.sql 0019_quote_publish_lock.sql \
             0020_prediction_immutable_keys.sql; do
        apply_file "$db" "$f"
    done
}

apply_final() {
    local db="$1"
    for f in 20261109200000_season_snapshot_integrity.sql \
             20261109210000_season_snapshot_deployment_guard.sql \
             20261109220000_season_archive_recovery.sql; do
        apply_file "$db" "$f"
    done
}

assert_hardened() {
    local db="$1"
    local ok
    ok="$(psql_db "$db" -Atc "select case when position('userId duplicati' in pg_get_functiondef('public.archive_season(uuid,uuid,text,jsonb)'::regprocedure)) > 0 then 't' else 'f' end" | tr -d '\r')"
    if [ "$ok" != "t" ]; then
        echo "FAIL: $db archive_season is not hardened" >&2
        exit 1
    fi
}

assert_auth_exec() {
    local db="$1" expected="$2"
    local ok
    ok="$(psql_db "$db" -Atc "select case when has_function_privilege('authenticated','public.archive_season(uuid,uuid,text,jsonb)','EXECUTE') then 't' else 'f' end" | tr -d '\r')"
    if [ "$ok" != "$expected" ]; then
        echo "FAIL: $db authenticated EXECUTE expected $expected, got $ok" >&2
        exit 1
    fi
}

assert_verifier_ok() {
    local db="$1"
    if ! DATABASE_URL="$(db_url "$db")" bash "$VERIFIER_DIR/verify_archive_season_hardening.sh" >/dev/null 2>&1; then
        echo "FAIL: verifier failed for $db" >&2
        exit 1
    fi
}

assert_verifier_fails() {
    local db="$1"
    if DATABASE_URL="$(db_url "$db")" bash "$VERIFIER_DIR/verify_archive_season_hardening.sh" >/dev/null 2>&1; then
        echo "FAIL: verifier unexpectedly passed incomplete state for $db" >&2
        exit 1
    fi
}

expect_failure() {
    local db="$1" file="$2" label="$3"
    if psql_db "$db" -1 -f "$file" >/dev/null 2>&1; then
        echo "FAIL: expected failure did not occur: $label" >&2
        exit 1
    fi
}

write_failure_sql() {
    local file="$1"
    cat >"$file" <<'SQL'
do $inject$
begin
    raise exception 'injected later migration failure';
end;
$inject$;
SQL
}

write_weak_function_sql() {
    local file="$1"
    cat >"$file" <<'SQL'
create or replace function public.archive_season(
    p_league uuid,
    p_season uuid,
    p_name text,
    p_standings jsonb
)
returns void
language plpgsql
security definer
set search_path = public
as $weak$
begin
    return;
end;
$weak$;
SQL
}

FAIL_SQL="$TMP_DIR/fail.sql"
WEAK_SQL="$TMP_DIR/weak.sql"
write_failure_sql "$FAIL_SQL"
write_weak_function_sql "$WEAK_SQL"

# -----------------------------------------------------------------------------
# D2: 200000 applied, 0014 pending. A later failure must leave a hardened,
# authenticated-executable function, not the old weak 0014.
# -----------------------------------------------------------------------------
reset_db ff1_lifecycle_d2
apply_main ff1_lifecycle_d2
apply_file ff1_lifecycle_d2 20261109200000_season_snapshot_integrity.sql
assert_hardened ff1_lifecycle_d2

DATABASE_URL="$(db_url ff1_lifecycle_d2)" bash "$PREFLIGHT" >/dev/null
assert_auth_exec ff1_lifecycle_d2 f
apply_file ff1_lifecycle_d2 0013_server_quotes.sql
apply_file ff1_lifecycle_d2 0014_audit_fixes.sql
assert_hardened ff1_lifecycle_d2
assert_auth_exec ff1_lifecycle_d2 t

# Simulate a later migration failing after 0014.
expect_failure ff1_lifecycle_d2 "$FAIL_SQL" "D2 failure after 0014"
assert_hardened ff1_lifecycle_d2
assert_auth_exec ff1_lifecycle_d2 t
# Final migrations are incomplete: the verifier must fail closed.
assert_verifier_fails ff1_lifecycle_d2

for f in 0015_security_hardening.sql 0016_atomic_prediction_revision.sql \
         0017_server_revision_bigint.sql 0018_initial_quotes_after_predictions.sql \
         0019_quote_publish_lock.sql 0020_prediction_immutable_keys.sql \
         20261109210000_season_snapshot_deployment_guard.sql \
         20261109220000_season_archive_recovery.sql; do
    apply_file ff1_lifecycle_d2 "$f"
done
assert_verifier_ok ff1_lifecycle_d2

# -----------------------------------------------------------------------------
# D3: 210000 applied, 0014 pending. The modified 0014 contains the hardened
# body, so the event trigger accepts it and no guard disable is needed.
# -----------------------------------------------------------------------------
reset_db ff1_lifecycle_d3
apply_main ff1_lifecycle_d3
apply_file ff1_lifecycle_d3 20261109200000_season_snapshot_integrity.sql
apply_file ff1_lifecycle_d3 20261109210000_season_snapshot_deployment_guard.sql
apply_file ff1_lifecycle_d3 20261109220000_season_archive_recovery.sql
assert_hardened ff1_lifecycle_d3
assert_auth_exec ff1_lifecycle_d3 t

DATABASE_URL="$(db_url ff1_lifecycle_d3)" bash "$PREFLIGHT" >/dev/null
assert_auth_exec ff1_lifecycle_d3 f
apply_file ff1_lifecycle_d3 0013_server_quotes.sql
apply_file ff1_lifecycle_d3 0014_audit_fixes.sql
assert_hardened ff1_lifecycle_d3
assert_auth_exec ff1_lifecycle_d3 t

# Guard rejection: a weak CREATE OR REPLACE must fail and must not replace
# the hardened function. No trigger is disabled.
expect_failure ff1_lifecycle_d3 "$WEAK_SQL" "D3 weak function replacement"
assert_hardened ff1_lifecycle_d3
assert_auth_exec ff1_lifecycle_d3 t

# Interruption after 0014: a later failure must leave the hardened function.
expect_failure ff1_lifecycle_d3 "$FAIL_SQL" "D3 failure after 0014"
assert_hardened ff1_lifecycle_d3
assert_auth_exec ff1_lifecycle_d3 t
assert_verifier_ok ff1_lifecycle_d3

# Complete the development sequence; the final state remains hardened.
for f in 0015_security_hardening.sql 0016_atomic_prediction_revision.sql \
         0017_server_revision_bigint.sql 0018_initial_quotes_after_predictions.sql \
         0019_quote_publish_lock.sql 0020_prediction_immutable_keys.sql; do
    apply_file ff1_lifecycle_d3 "$f"
done
assert_verifier_ok ff1_lifecycle_d3

# -----------------------------------------------------------------------------
# Old 0014/legacy function already applied, final migrations pending.
# The preflight must make the weak function non-executable before the final
# repair; if repair is interrupted, authenticated remains locked out.
# -----------------------------------------------------------------------------
reset_db ff1_lifecycle_applied
apply_main ff1_lifecycle_applied
apply_file ff1_lifecycle_applied 20261109200000_season_snapshot_integrity.sql
psql_db ff1_lifecycle_applied -1 -f "$WEAK_SQL" >/dev/null
assert_auth_exec ff1_lifecycle_applied t
DATABASE_URL="$(db_url ff1_lifecycle_applied)" bash "$PREFLIGHT" >/dev/null
assert_auth_exec ff1_lifecycle_applied f
expect_failure ff1_lifecycle_applied "$FAIL_SQL" "applied weak function: interrupted repair"
assert_auth_exec ff1_lifecycle_applied f
assert_verifier_fails ff1_lifecycle_applied
apply_file ff1_lifecycle_applied 20261109210000_season_snapshot_deployment_guard.sql
apply_file ff1_lifecycle_applied 20261109220000_season_archive_recovery.sql
assert_hardened ff1_lifecycle_applied
assert_auth_exec ff1_lifecycle_applied t
assert_verifier_ok ff1_lifecycle_applied

# -----------------------------------------------------------------------------
# Fresh development installation.
# -----------------------------------------------------------------------------
reset_db ff1_lifecycle_dev
apply_main ff1_lifecycle_dev
apply_dev ff1_lifecycle_dev
apply_final ff1_lifecycle_dev
assert_verifier_ok ff1_lifecycle_dev

# -----------------------------------------------------------------------------
# Legacy weak function already present (old 0014/120000), then preflight
# revocation and pending 0014. The preflight makes it non-executable even if
# the migration fails; the modified 0014 installs the hardened body.
# -----------------------------------------------------------------------------
reset_db ff1_lifecycle_legacy
apply_main ff1_lifecycle_legacy
psql_db ff1_lifecycle_legacy -1 -f "$WEAK_SQL" >/dev/null
assert_auth_exec ff1_lifecycle_legacy t
DATABASE_URL="$(db_url ff1_lifecycle_legacy)" bash "$PREFLIGHT" >/dev/null
assert_auth_exec ff1_lifecycle_legacy f
apply_file ff1_lifecycle_legacy 0013_server_quotes.sql
apply_file ff1_lifecycle_legacy 0014_audit_fixes.sql
assert_hardened ff1_lifecycle_legacy
assert_auth_exec ff1_lifecycle_legacy t
for f in 0015_security_hardening.sql 0016_atomic_prediction_revision.sql \
         0017_server_revision_bigint.sql 0018_initial_quotes_after_predictions.sql \
         0019_quote_publish_lock.sql 0020_prediction_immutable_keys.sql; do
    apply_file ff1_lifecycle_legacy "$f"
done
apply_final ff1_lifecycle_legacy
assert_verifier_ok ff1_lifecycle_legacy

echo "PASS: fail-closed lifecycle (D2, D3, fresh development, legacy weak function)"
