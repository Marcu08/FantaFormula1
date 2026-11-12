#!/usr/bin/env bash
# =============================================================================
# FantaFormula 1 - real Supabase CLI lifecycle regression
#
# Requires a disposable PostgreSQL 17.5 service and Supabase CLI 2.114.0.
# Every database is created below and dropped on exit. No remote URL is used.
#
# Covered:
#   fresh installation
#   D2 upgrade
#   D3 upgrade
#   legacy C1 (old 20261109120000 + recorded 0014)
#   legacy C2 (recorded 200000 + recorded weak 0014)
#   legacy C3 (all versions recorded + weak function + missing composite FK)
#   preflight omission refusal
#   failure inside 0014 with preflight
#   failure inside 20261109210000 with preflight
#   failure after 0014 / before permissions
#   retry after each failure
#   repeated wrapper invocation
# =============================================================================
set -euo pipefail

PSQL="${PSQL:-psql}"
SUPABASE="${SUPABASE:-supabase}"
SUPABASE_CLI_VERSION="${SUPABASE_CLI_VERSION:-2.114.0}"
PGHOST="${PGHOST:-127.0.0.1}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-postgres}"
PGPASSWORD="${PGPASSWORD:-postgres}"
export PGPASSWORD
export PGSSLMODE="${PGSSLMODE:-disable}"
case "$PGHOST" in
    127.0.0.1|localhost|::1)
        ;;
    *)
        echo "FAIL: PGHOST must be a disposable loopback instance, not '$PGHOST'" >&2
        exit 2
        ;;
esac
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MIGRATIONS_DIR="$REPO_ROOT/supabase/migrations"
PYTHON_BIN="${PYTHON_BIN:-}"
if [ -z "$PYTHON_BIN" ]; then
    if python3 -c 'import sys' >/dev/null 2>&1; then
        PYTHON_BIN=python3
    elif python -c 'import sys' >/dev/null 2>&1; then
        PYTHON_BIN=python
    else
        echo "FAIL: python3/python is required for repair-failure fixture generation" >&2
        exit 2
    fi
fi
TMP_ROOT="$(mktemp -d)"
LOG_DIR="$TMP_ROOT/logs"
mkdir -p "$LOG_DIR"
CREATED_DBS=()

cleanup() {
    if [ "${#CREATED_DBS[@]}" -gt 0 ]; then
        for db in "${CREATED_DBS[@]}"; do
            "$PSQL" -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres \
                -v ON_ERROR_STOP=0 -q -c "drop database if exists \"$db\";" \
                >/dev/null 2>&1 || true
        done
    fi
    rm -rf "$TMP_ROOT"
}
trap cleanup EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

psql_admin() {
    "$PSQL" -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -v ON_ERROR_STOP=1 "$@"
}
psql_db() {
    local db="$1"; shift
    "$PSQL" -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$db" -v ON_ERROR_STOP=1 "$@"
}
db_url() {
    printf 'postgresql://%s:%s@%s:%s/%s' \
        "$PGUSER" "$PGPASSWORD" "$PGHOST" "$PGPORT" "$1"
}
new_db() {
    local db="$1"
    psql_admin -q -c "drop database if exists \"$db\";" >/dev/null
    psql_admin -q -c "create database \"$db\";" >/dev/null
    psql_db "$db" -q -f "$REPO_ROOT/supabase/tests/ci_setup.sql" >/dev/null
    CREATED_DBS+=("$db")
}
clone_db() {
    local source="$1" target="$2"
    psql_admin -q -c "drop database if exists \"$target\";" >/dev/null
    psql_admin -q -c "create database \"$target\" template \"$source\";" >/dev/null
    CREATED_DBS+=("$target")
}
make_workdir() {
    local dest="$1"; shift
    rm -rf "$dest"
    mkdir -p "$dest/supabase/migrations"
    for f in "$@"; do
        cp "$MIGRATIONS_DIR/$f" "$dest/supabase/migrations/"
    done
}
make_full_workdir() {
    local dest="$1"
    rm -rf "$dest"
    mkdir -p "$dest/supabase/migrations"
    cp "$MIGRATIONS_DIR"/*.sql "$dest/supabase/migrations/"
}
add_failure() {
    local file="$1" message="$2"
    cat >>"$file" <<SQL

do \$injected_failure\$
begin
    raise exception '$message';
end;
\$injected_failure\$;
SQL
}
run_preflight() {
    local db="$1"
    EXPECTED_DATABASE="$db" PSQL="$PSQL" DATABASE_URL="$(db_url "$db")" \
        bash "$REPO_ROOT/supabase/tests/preflight_revoke_archive_execute.sh"
}
direct_push() {
    local workdir="$1" db="$2"
    "$SUPABASE" db push \
        --workdir "$workdir" \
        --db-url "$(db_url "$db")" \
        --yes --skip-vault
}
run_wrapper() {
    local workdir="$1" db="$2" log="$3"
    local repair_file="${4:-$REPO_ROOT/supabase/repair/archive_season_repair.sql}"
    local verifier_file="${5:-$REPO_ROOT/supabase/tests/verify_archive_season_hardening.sql}"
    SUPABASE_WORKDIR="$workdir" \
    EXPECTED_DATABASE="$db" \
    PSQL="$PSQL" \
    SUPABASE="$SUPABASE" \
    SUPABASE_CLI_VERSION="$SUPABASE_CLI_VERSION" \
    FF1_DEPLOY_TEST_HOOKS=1 \
    ARCHIVE_REPAIR_SQL_FILE="$repair_file" \
    ARCHIVE_VERIFIER_SQL_FILE="$verifier_file" \
    DATABASE_URL="$(db_url "$db")" \
        bash "$REPO_ROOT/supabase/scripts/deploy_archive_safe.sh" >"$log" 2>&1
}
run_verifier() {
    local db="$1" expect_auth="$2" expect_lockout="$3"
    local tmp="$TMP_ROOT/verify-$db.sql"
    {
        printf "set app.archive_verify_expect_auth = '%s';\n" "$expect_auth"
        printf "set app.archive_verify_expect_lockout = '%s';\n" "$expect_lockout"
        cat "$REPO_ROOT/supabase/tests/verify_archive_season_hardening.sql"
    } >"$tmp"
    psql_db "$db" -q -f "$tmp"
}
apply_weak_fixture() {
    local db="$1"
    psql_db "$db" -q -f "$REPO_ROOT/supabase/tests/fixtures/weak_archive_season.sql" >/dev/null
}
make_weak_all_history_state() {
    local db="$1"
    clone_db "$DB_FRESH" "$db"
    psql_db "$db" -q -c "alter event trigger archive_season_hardening_guard disable; alter event trigger archive_season_mutation_guard disable;"
    apply_weak_fixture "$db"
    psql_db "$db" -q -c "alter table public.season_snapshots drop constraint season_snapshots_season_league_fk; alter event trigger archive_season_hardening_guard enable; alter event trigger archive_season_mutation_guard enable;"
}
insert_history() {
    local db="$1" version="$2" name="$3"
    psql_db "$db" -q -c \
        "insert into supabase_migrations.schema_migrations(version,name,statements)
         values ('$version','$name','{}') on conflict (version) do nothing;" >/dev/null
}
history_count() {
    local db="$1"
    psql_db "$db" -Atq -c "select count(*) from supabase_migrations.schema_migrations;" | tr -d '[:space:]'
}
history_has() {
    local db="$1" version="$2"
    [ "$(psql_db "$db" -Atq -c "select exists(select 1 from supabase_migrations.schema_migrations where version='$version');" | tr -d '[:space:]')" = "t" ]
}
auth_exec() {
    local db="$1"
    psql_db "$db" -Atq -c "select has_function_privilege('authenticated','public.archive_season(uuid,uuid,text,jsonb)','EXECUTE');" | tr -d '[:space:]'
}
service_exec() {
    local db="$1"
    psql_db "$db" -Atq -c "select has_function_privilege('service_role','public.archive_season(uuid,uuid,text,jsonb)','EXECUTE');" | tr -d '[:space:]'
}
function_sha() {
    local db="$1"
    psql_db "$db" -Atq -c "set search_path=public,extensions; select encode(digest(pg_get_functiondef('public.archive_season(uuid,uuid,text,jsonb)'::regprocedure),'sha256'),'hex');" | tail -n 1 | tr -d '[:space:]'
}
function_is_hardened() {
    local db="$1"
    [ "$(psql_db "$db" -Atq -c "select position('userId duplicati' in pg_get_functiondef('public.archive_season(uuid,uuid,text,jsonb)'::regprocedure)) > 0;" | tr -d '[:space:]')" = "t" ]
}
lockout_active() {
    local db="$1"
    psql_db "$db" -Atq -c "select active from public.archive_season_deploy_lockout where singleton;" | tr -d '[:space:]'
}
assert_true() { [ "$1" = "t" ] || fail "$2 (got '$1')"; }
assert_false() { [ "$1" = "f" ] || fail "$2 (got '$1')"; }
assert_eq() { [ "$1" = "$2" ] || fail "$3 (expected '$2', got '$1')"; }
assert_history_count() {
    local db="$1" expected="$2" label="$3"
    local actual
    actual="$(history_count "$db")"
    assert_eq "$actual" "$expected" "$label migration history count"
}
assert_final_db() {
    local db="$1" label="$2"
    assert_history_count "$db" 24 "$label"
    assert_true "$(function_is_hardened "$db" && echo t || echo f)" "$label function must be hardened"
    assert_true "$(auth_exec "$db")" "$label authenticated EXECUTE"
    assert_false "$(service_exec "$db")" "$label service_role EXECUTE"
    assert_eq "$(lockout_active "$db")" "f" "$label lockout inactive"
    run_verifier "$db" true false >/dev/null || fail "$label final verifier failed"
}
expect_wrapper_failure() {
    local workdir="$1" db="$2" log="$3" label="$4"
    local repair_file="${5:-$REPO_ROOT/supabase/repair/archive_season_repair.sql}"
    local verifier_file="${6:-$REPO_ROOT/supabase/tests/verify_archive_season_hardening.sql}"
    if run_wrapper "$workdir" "$db" "$log" "$repair_file" "$verifier_file"; then
        fail "$label wrapper unexpectedly succeeded"
    fi
    echo "OK: $label failed closed"
}
expect_raw_push_refused() {
    local workdir="$1" db="$2" log="$3" label="$4"
    if direct_push "$workdir" "$db" >"$log" 2>&1; then
        fail "$label raw db push unexpectedly succeeded"
    fi
    grep -q 'deployment preflight required' "$log" \
        || fail "$label raw db push did not fail with the preflight-required diagnostic"
    echo "OK: $label raw db push refused"
}

FULL="$TMP_ROOT/full"
make_full_workdir "$FULL"
BASE_MAIN="$TMP_ROOT/base-main"
make_workdir "$BASE_MAIN" 0001_init.sql 0002_fixes.sql 0003_security.sql \
    0004_prediction_validation.sql 0005_prediction_count.sql 0006_prediction_bands.sql \
    0007_over_under_thresholds.sql 0008_safety_pit_bands.sql 0009_quota.sql \
    0010_quota_dnf.sql 0011_fixes.sql 0012_season_history.sql
BASE_D2="$TMP_ROOT/base-d2"
make_workdir "$BASE_D2" 0001_init.sql 0002_fixes.sql 0003_security.sql \
    0004_prediction_validation.sql 0005_prediction_count.sql 0006_prediction_bands.sql \
    0007_over_under_thresholds.sql 0008_safety_pit_bands.sql 0009_quota.sql \
    0010_quota_dnf.sql 0011_fixes.sql 0012_season_history.sql \
    20261109200000_season_snapshot_integrity.sql
BASE_D3="$TMP_ROOT/base-d3"
make_workdir "$BASE_D3" 0001_init.sql 0002_fixes.sql 0003_security.sql \
    0004_prediction_validation.sql 0005_prediction_count.sql 0006_prediction_bands.sql \
    0007_over_under_thresholds.sql 0008_safety_pit_bands.sql 0009_quota.sql \
    0010_quota_dnf.sql 0011_fixes.sql 0012_season_history.sql \
    20261109200000_season_snapshot_integrity.sql \
    20261109210000_season_snapshot_deployment_guard.sql \
    20261109220000_season_archive_recovery.sql
BASE_C1="$TMP_ROOT/base-c1"
make_workdir "$BASE_C1" 0001_init.sql 0002_fixes.sql 0003_security.sql \
    0004_prediction_validation.sql 0005_prediction_count.sql 0006_prediction_bands.sql \
    0007_over_under_thresholds.sql 0008_safety_pit_bands.sql 0009_quota.sql \
    0010_quota_dnf.sql 0011_fixes.sql 0012_season_history.sql 0013_server_quotes.sql
BASE_C2="$TMP_ROOT/base-c2"
make_workdir "$BASE_C2" 0001_init.sql 0002_fixes.sql 0003_security.sql \
    0004_prediction_validation.sql 0005_prediction_count.sql 0006_prediction_bands.sql \
    0007_over_under_thresholds.sql 0008_safety_pit_bands.sql 0009_quota.sql \
    0010_quota_dnf.sql 0011_fixes.sql 0012_season_history.sql 0013_server_quotes.sql \
    20261109200000_season_snapshot_integrity.sql

FAIL0014="$TMP_ROOT/fail0014"
make_full_workdir "$FAIL0014"
add_failure "$FAIL0014/supabase/migrations/0014_audit_fixes.sql" "injected 0014 failure"
WEAK0014="$TMP_ROOT/weak0014"
make_full_workdir "$WEAK0014"
cp "$REPO_ROOT/supabase/tests/fixtures/weak_archive_season.sql" \
    "$WEAK0014/supabase/migrations/0014_audit_fixes.sql"
REPAIR_FAIL_TOP="$TMP_ROOT/repair_fail_top.sql"
REPAIR_FAIL_MID="$TMP_ROOT/repair_fail_mid.sql"
VERIFIER_FAIL_FINAL="$TMP_ROOT/verifier_fail_final.sql"
cat >"$VERIFIER_FAIL_FINAL" <<'SQL'
do $inject$
begin
    if current_setting('app.archive_verify_expect_auth', true) = 'true' then
        raise exception 'injected final verifier failure';
    end if;
end;
$inject$;
SQL
"$PYTHON_BIN" - "$MIGRATIONS_DIR/20261109230000_archive_season_exact_protection.sql" \
    "$REPAIR_FAIL_TOP" "top" "injected repair failure before replacement" <<'PY'
import sys
src, dst, where, message = sys.argv[1:5]
text = open(src, encoding="utf-8").read()
needle = "-- 0) The external preflight must be committed and active. A raw `db push`"
inject = ("do $inject$ begin\n"
          f"    raise exception '{message}';\n"
          "end;\n"
          "$inject$;\n\n")
if needle not in text:
    raise SystemExit(f"needle not found in {src}")
open(dst, "w", encoding="utf-8", newline="\n").write(text.replace(needle, inject + needle, 1))
PY
"$PYTHON_BIN" - "$MIGRATIONS_DIR/20261109230000_archive_season_exact_protection.sql" \
    "$REPAIR_FAIL_MID" "mid" "injected repair failure before ACL" <<'PY'
import sys
src, dst, where, message = sys.argv[1:5]
text = open(src, encoding="utf-8").read()
needle = "-- 7) Restore table ACLs and revoke every client path to the archive RPC."
inject = ("do $inject$ begin\n"
          f"    raise exception '{message}';\n"
          "end;\n"
          "$inject$;\n\n")
if needle not in text:
    raise SystemExit(f"needle not found in {src}")
open(dst, "w", encoding="utf-8", newline="\n").write(text.replace(needle, inject + needle, 1))
PY
FAIL210="$TMP_ROOT/fail210"
make_full_workdir "$FAIL210"
add_failure "$FAIL210/supabase/migrations/20261109210000_season_snapshot_deployment_guard.sql" "injected 210000 failure"
FAIL0015="$TMP_ROOT/fail0015"
make_full_workdir "$FAIL0015"
add_failure "$FAIL0015/supabase/migrations/0015_security_hardening.sql" "injected 0015 failure"

echo "== Fresh installation =="
DB_FRESH=ff1_cli_fresh
new_db "$DB_FRESH"
run_wrapper "$FULL" "$DB_FRESH" "$LOG_DIR/fresh.log" || { tail -n 30 "$LOG_DIR/fresh.log" >&2; fail "fresh wrapper"; }
assert_final_db "$DB_FRESH" "fresh"
echo "OK: fresh installation"

echo "== Repeated invocation =="
run_wrapper "$FULL" "$DB_FRESH" "$LOG_DIR/fresh-rerun.log" || { tail -n 30 "$LOG_DIR/fresh-rerun.log" >&2; fail "fresh rerun"; }
assert_final_db "$DB_FRESH" "fresh-rerun"
echo "OK: repeated invocation"

echo "== Repair failure on an already canonical, finalized database =="
DB_LOCKEDREPAIR=ff1_cli_lockedrepair
clone_db "$DB_FRESH" "$DB_LOCKEDREPAIR"
if run_wrapper "$FULL" "$DB_LOCKEDREPAIR" "$LOG_DIR/lockedrepair.log" "$REPAIR_FAIL_TOP"; then
    fail "repair failure on canonical database unexpectedly succeeded"
fi
grep -q 'DEPLOY LOCKED: revoked state verified' "$LOG_DIR/lockedrepair.log" \
    || { tail -n 40 "$LOG_DIR/lockedrepair.log" >&2; fail "locked verifier success was not reported as locked state"; }
assert_true "$(function_is_hardened "$DB_LOCKEDREPAIR" && echo t || echo f)" "canonical repair failure must keep canonical body"
assert_false "$(auth_exec "$DB_LOCKEDREPAIR")" "canonical repair failure must leave authenticated locked out"
assert_eq "$(lockout_active "$DB_LOCKEDREPAIR")" "t" "canonical repair failure lockout"
run_verifier "$DB_LOCKEDREPAIR" false true >/dev/null || fail "locked verifier should pass canonical locked state"
run_wrapper "$FULL" "$DB_LOCKEDREPAIR" "$LOG_DIR/lockedrepair-retry.log" || { tail -n 30 "$LOG_DIR/lockedrepair-retry.log" >&2; fail "locked repair retry"; }
assert_final_db "$DB_LOCKEDREPAIR" "locked repair retry"
echo "OK: canonical repair failure reported locked, retry succeeded"

echo "== Failure after permission restoration (final verifier injection) =="
DB_FINALFAIL=ff1_cli_finalfail
clone_db "$DB_FRESH" "$DB_FINALFAIL"
expect_wrapper_failure "$FULL" "$DB_FINALFAIL" "$LOG_DIR/finalfail.log" "final verifier failure" \
    "$REPO_ROOT/supabase/repair/archive_season_repair.sql" "$VERIFIER_FAIL_FINAL"
grep -q 'injected final verifier failure' "$LOG_DIR/finalfail.log" \
    || fail "final verifier injection did not fire"
assert_true "$(function_is_hardened "$DB_FINALFAIL" && echo t || echo f)" "final verifier failure must keep canonical body"
assert_false "$(auth_exec "$DB_FINALFAIL")" "final verifier failure must re-lock authenticated"
assert_eq "$(lockout_active "$DB_FINALFAIL")" "t" "final verifier failure lockout"
run_verifier "$DB_FINALFAIL" false true >/dev/null || fail "locked verifier after final-verifier failure"
run_wrapper "$FULL" "$DB_FINALFAIL" "$LOG_DIR/finalfail-retry.log" || { tail -n 30 "$LOG_DIR/finalfail-retry.log" >&2; fail "final verifier failure retry"; }
assert_final_db "$DB_FINALFAIL" "final verifier failure retry"
echo "OK: final-verifier failure re-locked and retry succeeded"

echo "== D2 upgrade =="
DB_D2=ff1_cli_d2
new_db "$DB_D2"
run_preflight "$DB_D2" >"$LOG_DIR/d2-preflight.log" 2>&1
direct_push "$BASE_D2" "$DB_D2" >"$LOG_DIR/d2-base.log" 2>&1 || { tail -n 20 "$LOG_DIR/d2-base.log" >&2; fail "D2 base"; }
assert_history_count "$DB_D2" 13 "D2 base"
run_wrapper "$FULL" "$DB_D2" "$LOG_DIR/d2.log" || { tail -n 30 "$LOG_DIR/d2.log" >&2; fail "D2 wrapper"; }
assert_final_db "$DB_D2" "D2"
echo "OK: D2 upgrade"

echo "== D3 upgrade =="
DB_D3=ff1_cli_d3
new_db "$DB_D3"
run_preflight "$DB_D3" >"$LOG_DIR/d3-preflight.log" 2>&1
direct_push "$BASE_D3" "$DB_D3" >"$LOG_DIR/d3-base.log" 2>&1 || { tail -n 20 "$LOG_DIR/d3-base.log" >&2; fail "D3 base"; }
assert_history_count "$DB_D3" 15 "D3 base"
run_wrapper "$FULL" "$DB_D3" "$LOG_DIR/d3.log" || { tail -n 30 "$LOG_DIR/d3.log" >&2; fail "D3 wrapper"; }
assert_final_db "$DB_D3" "D3"
echo "OK: D3 upgrade"

echo "== Legacy C1 (old 20261109120000 + recorded 0014) =="
DB_C1=ff1_cli_c1
new_db "$DB_C1"
direct_push "$BASE_C1" "$DB_C1" >"$LOG_DIR/c1-base.log" 2>&1 || { tail -n 20 "$LOG_DIR/c1-base.log" >&2; fail "C1 base"; }
apply_weak_fixture "$DB_C1"
insert_history "$DB_C1" 20261109120000 season_snapshot_integrity
insert_history "$DB_C1" 0014 audit_fixes
run_wrapper "$FULL" "$DB_C1" "$LOG_DIR/c1.log" || { tail -n 40 "$LOG_DIR/c1.log" >&2; fail "C1 wrapper"; }
assert_final_db "$DB_C1" "C1"
echo "OK: C1 recovery"

echo "== Legacy C2 (200000 recorded + weak 0014 recorded) =="
DB_C2=ff1_cli_c2
new_db "$DB_C2"
run_preflight "$DB_C2" >"$LOG_DIR/c2-preflight.log" 2>&1
direct_push "$BASE_C2" "$DB_C2" >"$LOG_DIR/c2-base.log" 2>&1 || { tail -n 20 "$LOG_DIR/c2-base.log" >&2; fail "C2 base"; }
apply_weak_fixture "$DB_C2"
insert_history "$DB_C2" 0014 audit_fixes
run_wrapper "$FULL" "$DB_C2" "$LOG_DIR/c2.log" || { tail -n 40 "$LOG_DIR/c2.log" >&2; fail "C2 wrapper"; }
assert_final_db "$DB_C2" "C2"
echo "OK: C2 recovery"

echo "== Legacy C3 (all versions recorded + weak + missing composite FK) =="
DB_C3=ff1_cli_c3
make_weak_all_history_state "$DB_C3"
assert_false "$(function_is_hardened "$DB_C3" && echo t || echo f)" "C3 must start weak"
assert_true "$(auth_exec "$DB_C3")" "C3 weak must start executable"
run_wrapper "$FULL" "$DB_C3" "$LOG_DIR/c3.log" || { tail -n 40 "$LOG_DIR/c3.log" >&2; fail "C3 wrapper"; }
assert_final_db "$DB_C3" "C3"
assert_eq "$(psql_db "$DB_C3" -Atq -c "select count(*) from pg_constraint where conname='season_snapshots_season_league_fk';" | tr -d '[:space:]')" "1" "C3 composite FK restored"
echo "OK: C3 recovery"

echo "== C3 repair failure before function replacement =="
DB_RFAIL_TOP=ff1_cli_rfail_top
make_weak_all_history_state "$DB_RFAIL_TOP"
expect_wrapper_failure "$FULL" "$DB_RFAIL_TOP" "$LOG_DIR/rfail-top.log" "repair failure before replacement" "$REPAIR_FAIL_TOP"
grep -q 'injected repair failure before replacement' "$LOG_DIR/rfail-top.log" \
    || fail "repair top failure did not use the injected failure"
assert_history_count "$DB_RFAIL_TOP" 24 "repair top failure"
assert_false "$(function_is_hardened "$DB_RFAIL_TOP" && echo t || echo f)" "repair top failure leaves prior weak body"
assert_false "$(auth_exec "$DB_RFAIL_TOP")" "repair top failure must leave authenticated locked out"
assert_eq "$(lockout_active "$DB_RFAIL_TOP")" "t" "repair top failure lockout"
run_wrapper "$FULL" "$DB_RFAIL_TOP" "$LOG_DIR/rfail-top-retry.log" || { tail -n 30 "$LOG_DIR/rfail-top-retry.log" >&2; fail "repair top retry"; }
assert_final_db "$DB_RFAIL_TOP" "repair top retry"
echo "OK: repair failure before replacement and retry"

echo "== Interruption after repair commit, before finalize =="
DB_PARTIAL=ff1_cli_partial
make_weak_all_history_state "$DB_PARTIAL"
run_preflight "$DB_PARTIAL" >"$LOG_DIR/partial-preflight.log" 2>&1
direct_push "$FULL" "$DB_PARTIAL" >"$LOG_DIR/partial-push.log" 2>&1
psql_db "$DB_PARTIAL" -q -1 -f "$REPO_ROOT/supabase/repair/archive_season_repair.sql" >"$LOG_DIR/partial-repair.log" 2>&1 \
    || { tail -n 30 "$LOG_DIR/partial-repair.log" >&2; fail "partial repair"; }
assert_true "$(function_is_hardened "$DB_PARTIAL" && echo t || echo f)" "interrupted repair must install canonical body"
assert_false "$(auth_exec "$DB_PARTIAL")" "interrupted repair must leave authenticated revoked"
assert_eq "$(lockout_active "$DB_PARTIAL")" "t" "interrupted repair lockout"
run_verifier "$DB_PARTIAL" false true >/dev/null || fail "locked-down verifier after interrupted repair"
psql_db "$DB_PARTIAL" -q -1 -f "$REPO_ROOT/supabase/scripts/finalize_archive_season_deployment.sql" >"$LOG_DIR/partial-finalize.log" 2>&1 \
    || { tail -n 30 "$LOG_DIR/partial-finalize.log" >&2; fail "partial finalize"; }
assert_final_db "$DB_PARTIAL" "interrupted repair finalize"
echo "OK: interruption after repair and before finalize is safe"

echo "== C3 repair failure after function replacement, before ACL =="
DB_RFAIL_MID=ff1_cli_rfail_mid
make_weak_all_history_state "$DB_RFAIL_MID"
expect_wrapper_failure "$FULL" "$DB_RFAIL_MID" "$LOG_DIR/rfail-mid.log" "repair failure before ACL" "$REPAIR_FAIL_MID"
grep -q 'injected repair failure before ACL' "$LOG_DIR/rfail-mid.log" \
    || fail "repair mid failure did not use the injected failure"
assert_history_count "$DB_RFAIL_MID" 24 "repair mid failure"
assert_false "$(function_is_hardened "$DB_RFAIL_MID" && echo t || echo f)" "repair mid failure rolls back to prior weak body"
assert_false "$(auth_exec "$DB_RFAIL_MID")" "repair mid failure must leave authenticated locked out"
assert_eq "$(lockout_active "$DB_RFAIL_MID")" "t" "repair mid failure lockout"
run_wrapper "$FULL" "$DB_RFAIL_MID" "$LOG_DIR/rfail-mid-retry.log" || { tail -n 30 "$LOG_DIR/rfail-mid-retry.log" >&2; fail "repair mid retry"; }
assert_final_db "$DB_RFAIL_MID" "repair mid retry"
echo "OK: repair failure after replacement and retry"

echo "== D3 exact guard rejects a pending weak 0014 =="
DB_WEAKGUARD=ff1_cli_weakguard
clone_db "$DB_FRESH" "$DB_WEAKGUARD"
psql_db "$DB_WEAKGUARD" -q -c "delete from supabase_migrations.schema_migrations where version='0014';"
expect_wrapper_failure "$WEAK0014" "$DB_WEAKGUARD" "$LOG_DIR/weakguard.log" "pending weak 0014"
grep -q 'archive_season hardening guard' "$LOG_DIR/weakguard.log" \
    || fail "pending weak 0014 did not fail with the exact guard diagnostic"
assert_history_count "$DB_WEAKGUARD" 23 "pending weak 0014"
assert_true "$(function_is_hardened "$DB_WEAKGUARD" && echo t || echo f)" "pending weak 0014 must not replace canonical body"
assert_false "$(auth_exec "$DB_WEAKGUARD")" "pending weak 0014 must leave authenticated locked out"
assert_eq "$(lockout_active "$DB_WEAKGUARD")" "t" "pending weak 0014 lockout"
run_wrapper "$FULL" "$DB_WEAKGUARD" "$LOG_DIR/weakguard-retry.log" || { tail -n 30 "$LOG_DIR/weakguard-retry.log" >&2; fail "weak 0014 retry"; }
assert_final_db "$DB_WEAKGUARD" "pending weak 0014 retry"
echo "OK: exact guard rejected pending weak 0014 and retry succeeded"

echo "== Preflight omission refusal =="
DB_NOPRE=ff1_cli_nopre
new_db "$DB_NOPRE"
direct_push "$BASE_C1" "$DB_NOPRE" >"$LOG_DIR/nopre-base.log" 2>&1 || { tail -n 20 "$LOG_DIR/nopre-base.log" >&2; fail "no-preflight base"; }
apply_weak_fixture "$DB_NOPRE"
expect_raw_push_refused "$FULL" "$DB_NOPRE" "$LOG_DIR/nopre-push.log" "preflight omission"
assert_history_count "$DB_NOPRE" 13 "preflight omission"
assert_false "$(function_is_hardened "$DB_NOPRE" && echo t || echo f)" "preflight omission must not install hardened SQL"
assert_true "$(auth_exec "$DB_NOPRE")" "preflight omission leaves prior weak state unchanged"
echo "OK: preflight omission refused without executing migrations"

echo "== Failure inside 0014 with preflight + retry =="
DB_FAIL14=ff1_cli_fail14
new_db "$DB_FAIL14"
direct_push "$BASE_C1" "$DB_FAIL14" >"$LOG_DIR/fail14-base.log" 2>&1 || { tail -n 20 "$LOG_DIR/fail14-base.log" >&2; fail "fail14 base"; }
apply_weak_fixture "$DB_FAIL14"
expect_wrapper_failure "$FAIL0014" "$DB_FAIL14" "$LOG_DIR/fail14.log" "failure inside 0014"
grep -q 'injected 0014 failure' "$LOG_DIR/fail14.log" \
    || fail "failure inside 0014 did not fail with the injected diagnostic"
assert_history_count "$DB_FAIL14" 13 "failure inside 0014"
assert_false "$(function_is_hardened "$DB_FAIL14" && echo t || echo f)" "failure inside 0014 leaves weak body"
assert_false "$(auth_exec "$DB_FAIL14")" "failure inside 0014 must leave authenticated locked out"
assert_eq "$(lockout_active "$DB_FAIL14")" "t" "failure inside 0014 lockout"
run_wrapper "$FULL" "$DB_FAIL14" "$LOG_DIR/fail14-retry.log" || { tail -n 30 "$LOG_DIR/fail14-retry.log" >&2; fail "fail14 retry"; }
assert_final_db "$DB_FAIL14" "failure inside 0014 retry"
echo "OK: failure inside 0014 and retry"

echo "== Failure inside 20261109210000 with preflight + retry =="
DB_FAIL210=ff1_cli_fail210
new_db "$DB_FAIL210"
run_preflight "$DB_FAIL210" >"$LOG_DIR/fail210-preflight.log" 2>&1
direct_push "$BASE_C2" "$DB_FAIL210" >"$LOG_DIR/fail210-base.log" 2>&1 || { tail -n 20 "$LOG_DIR/fail210-base.log" >&2; fail "fail210 base"; }
apply_weak_fixture "$DB_FAIL210"
insert_history "$DB_FAIL210" 0014 audit_fixes
expect_wrapper_failure "$FAIL210" "$DB_FAIL210" "$LOG_DIR/fail210.log" "failure inside 210000"
grep -q 'injected 210000 failure' "$LOG_DIR/fail210.log" \
    || fail "failure inside 210000 did not fail with the injected diagnostic"
assert_history_count "$DB_FAIL210" 21 "failure inside 210000"
assert_false "$(function_is_hardened "$DB_FAIL210" && echo t || echo f)" "failure inside 210000 leaves weak body"
assert_false "$(auth_exec "$DB_FAIL210")" "failure inside 210000 must leave authenticated locked out"
assert_eq "$(lockout_active "$DB_FAIL210")" "t" "failure inside 210000 lockout"
run_wrapper "$FULL" "$DB_FAIL210" "$LOG_DIR/fail210-retry.log" || { tail -n 30 "$LOG_DIR/fail210-retry.log" >&2; fail "fail210 retry"; }
assert_final_db "$DB_FAIL210" "failure inside 210000 retry"
echo "OK: failure inside 210000 and retry"

echo "== Failure after 0014 / before final permissions + retry =="
DB_FAIL15=ff1_cli_fail15
new_db "$DB_FAIL15"
run_preflight "$DB_FAIL15" >"$LOG_DIR/fail15-preflight.log" 2>&1
direct_push "$BASE_D2" "$DB_FAIL15" >"$LOG_DIR/fail15-base.log" 2>&1 || { tail -n 20 "$LOG_DIR/fail15-base.log" >&2; fail "fail15 base"; }
expect_wrapper_failure "$FAIL0015" "$DB_FAIL15" "$LOG_DIR/fail15.log" "failure after 0014"
grep -q 'injected 0015 failure' "$LOG_DIR/fail15.log" \
    || fail "failure after 0014 did not fail with the injected diagnostic"
assert_history_count "$DB_FAIL15" 15 "failure after 0014"
assert_true "$(function_is_hardened "$DB_FAIL15" && echo t || echo f)" "failure after 0014 must leave hardened body"
assert_false "$(auth_exec "$DB_FAIL15")" "failure after 0014 must leave authenticated locked out"
run_wrapper "$FULL" "$DB_FAIL15" "$LOG_DIR/fail15-retry.log" || { tail -n 30 "$LOG_DIR/fail15-retry.log" >&2; fail "fail15 retry"; }
assert_final_db "$DB_FAIL15" "failure after 0014 retry"
echo "OK: failure after 0014 and retry"

echo "== Preflight with no archive_season =="
DB_EMPTY=ff1_cli_empty
new_db "$DB_EMPTY"
run_preflight "$DB_EMPTY" >"$LOG_DIR/empty-preflight.log" 2>&1 || { tail -n 20 "$LOG_DIR/empty-preflight.log" >&2; fail "empty preflight"; }
run_preflight "$DB_EMPTY" >"$LOG_DIR/empty-preflight2.log" 2>&1 || { tail -n 20 "$LOG_DIR/empty-preflight2.log" >&2; fail "empty preflight rerun"; }
assert_eq "$(lockout_active "$DB_EMPTY")" "t" "empty preflight lockout active"
echo "OK: preflight with no function"

echo "PASS: real Supabase CLI lifecycle (fresh, D2, D3, C1, C2, C3, failures, retries)"
