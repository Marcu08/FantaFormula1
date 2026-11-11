#!/usr/bin/env bash
# =============================================================================
# FantaFormula 1 - negative security tests for preflight and verifier
#
# Requires a disposable source database fully deployed by
# supabase/scripts/deploy_archive_safe.sh (default: ff1_ci).
#
# The source database is cloned for each negative test. Clones and test roles
# are disposable and removed at exit. No remote database is used.
# =============================================================================
set -euo pipefail

PSQL="${PSQL:-psql}"
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
SOURCE_DATABASE="${SOURCE_DATABASE:-ff1_ci}"
TMP_ROOT="$(mktemp -d)"
LOG_DIR="$TMP_ROOT/logs"
mkdir -p "$LOG_DIR"
CREATED_DBS=()
CREATED_ROLES=()

cleanup() {
    if [ "${#CREATED_DBS[@]}" -gt 0 ]; then
        for db in "${CREATED_DBS[@]}"; do
            "$PSQL" -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres \
                -v ON_ERROR_STOP=0 -q -c "drop database if exists \"$db\";" \
                >/dev/null 2>&1 || true
        done
    fi
    if [ "${#CREATED_ROLES[@]}" -gt 0 ]; then
        for role in "${CREATED_ROLES[@]}"; do
            "$PSQL" -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres \
                -v ON_ERROR_STOP=0 -q -c "drop role if exists \"$role\";" \
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
new_empty_db() {
    local db="$1"
    psql_admin -q -c "drop database if exists \"$db\";" >/dev/null
    psql_admin -q -c "create database \"$db\";" >/dev/null
    psql_db "$db" -q -f "$REPO_ROOT/supabase/tests/ci_setup.sql" >/dev/null
    CREATED_DBS+=("$db")
}
clone_source() {
    local db="$1"
    psql_admin -q -c "drop database if exists \"$db\";" >/dev/null
    psql_admin -q -c "create database \"$db\" template \"$SOURCE_DATABASE\";" >/dev/null
    CREATED_DBS+=("$db")
}
run_preflight() {
    local db="$1" log="$2"
    EXPECTED_DATABASE="$db" PSQL="$PSQL" DATABASE_URL="$(db_url "$db")" \
        bash "$REPO_ROOT/supabase/tests/preflight_revoke_archive_execute.sh" \
        >"$log" 2>&1
}
run_verifier() {
    local db="$1" log="$2"
    psql_db "$db" -q -f "$REPO_ROOT/supabase/tests/verify_archive_season_hardening.sql" \
        >"$log" 2>&1
}
expect_preflight_failure() {
    local db="$1" pattern="$2" label="$3"
    local log="$LOG_DIR/preflight-$db.log"
    if run_preflight "$db" "$log"; then
        fail "$label preflight unexpectedly succeeded"
    fi
    grep -Eq "$pattern" "$log" || { cat "$log" >&2; fail "$label preflight failed with unexpected diagnostic"; }
    echo "OK: $label preflight failed closed"
}
expect_verifier_failure() {
    local db="$1" pattern="$2" label="$3"
    local log="$LOG_DIR/verify-$db.log"
    if run_verifier "$db" "$log"; then
        fail "$label verifier unexpectedly succeeded"
    fi
    grep -Eq "$pattern" "$log" || { cat "$log" >&2; fail "$label verifier failed with unexpected diagnostic"; }
    echo "OK: $label verifier failed closed"
}
create_test_role() {
    local role="$1"
    psql_admin -q -c "do \$r\$ begin if not exists (select 1 from pg_roles where rolname = '$role') then create role \"$role\" nologin; end if; end \$r\$;" >/dev/null
    CREATED_ROLES+=("$role")
}
apply_weak_marker_function() {
    local db="$1"
    psql_db "$db" -q <<'SQL' >/dev/null
create or replace function public.archive_season(
    p_league uuid, p_season uuid, p_name text, p_standings jsonb
) returns void
language plpgsql security definer set search_path = public
as $weak$
begin
    -- v_user uuid := auth.uid()
    -- if not public.is_league_admin(p_league)
    -- for update
    -- if not v_active then
    -- userId duplicati
    -- posizioni duplicate
    -- posizioni non contigue
    -- v_total_points < 0
    -- v_best_points < 0
    -- coalesce(nullif(btrim(v_season_name), ''), 'Stagione')
    return;
end;
$weak$;
SQL
}

# The source must be a finalized wrapper deployment.
SOURCE_HISTORY="$(psql_db "$SOURCE_DATABASE" -Atq -c "select count(*) from supabase_migrations.schema_migrations;" | tr -d '[:space:]')"
if [ "$SOURCE_HISTORY" != "24" ]; then
    fail "source database '$SOURCE_DATABASE' must have 24 migration-history rows (found '$SOURCE_HISTORY')"
fi
SOURCE_LOCKOUT="$(psql_db "$SOURCE_DATABASE" -Atq -c "select active from public.archive_season_deploy_lockout where singleton;" | tr -d '[:space:]')"
[ "$SOURCE_LOCKOUT" = "f" ] || fail "source database '$SOURCE_DATABASE' must be finalized (lockout active)"
echo "OK: source database '$SOURCE_DATABASE' is a finalized 24-migration deployment"

echo "== preflight with no archive_season =="
DB_EMPTY=ff1_neg_empty
new_empty_db "$DB_EMPTY"
run_preflight "$DB_EMPTY" "$LOG_DIR/empty1.log" || { cat "$LOG_DIR/empty1.log" >&2; fail "empty preflight"; }
run_preflight "$DB_EMPTY" "$LOG_DIR/empty2.log" || { cat "$LOG_DIR/empty2.log" >&2; fail "empty preflight rerun"; }
[ "$(psql_db "$DB_EMPTY" -Atq -c "select active from public.archive_season_deploy_lockout where singleton;" | tr -d '[:space:]')" = "t" ] \
    || fail "empty preflight did not arm lockout"
echo "OK: empty preflight and rerun"

echo "== preflight custom role inherited by authenticated =="
DB_CUSTOM=ff1_neg_custom
clone_source "$DB_CUSTOM"
create_test_role neg_custom
create_test_role neg_custom2
psql_db "$DB_CUSTOM" -q -c "grant execute on function public.archive_season(uuid,uuid,text,jsonb) to neg_custom; grant execute on function public.archive_season(uuid,uuid,text,jsonb) to neg_custom2; grant neg_custom to authenticated; grant neg_custom2 to authenticated;"
expect_preflight_failure "$DB_CUSTOM" 'custom role grant' "custom role"
grep -q 'neg_custom' "$LOG_DIR/preflight-$DB_CUSTOM.log" || fail "custom-role diagnostic missing neg_custom"
grep -q 'neg_custom2' "$LOG_DIR/preflight-$DB_CUSTOM.log" || fail "custom-role diagnostic missing neg_custom2"
[ "$(psql_db "$DB_CUSTOM" -Atq -c "select has_function_privilege('authenticated','public.archive_season(uuid,uuid,text,jsonb)','EXECUTE');" | tr -d '[:space:]')" = "t" ] \
    || fail "custom-role preflight failure should roll back and leave original state"
psql_db "$DB_CUSTOM" -q -c "revoke execute on function public.archive_season(uuid,uuid,text,jsonb) from neg_custom; revoke execute on function public.archive_season(uuid,uuid,text,jsonb) from neg_custom2; revoke neg_custom from authenticated; revoke neg_custom2 from authenticated;"

echo "== preflight PUBLIC EXECUTE =="
DB_PUBLIC=ff1_neg_public
clone_source "$DB_PUBLIC"
psql_db "$DB_PUBLIC" -q -c "grant execute on function public.archive_season(uuid,uuid,text,jsonb) to public;"
run_preflight "$DB_PUBLIC" "$LOG_DIR/public.log" || { cat "$LOG_DIR/public.log" >&2; fail "PUBLIC preflight"; }
[ "$(psql_db "$DB_PUBLIC" -Atq -c "select has_function_privilege('authenticated','public.archive_season(uuid,uuid,text,jsonb)','EXECUTE');" | tr -d '[:space:]')" = "f" ] \
    || fail "PUBLIC preflight did not lock out authenticated"
echo "OK: PUBLIC EXECUTE revoked"

echo "== preflight direct anon grant =="
DB_ANON=ff1_neg_anon
clone_source "$DB_ANON"
psql_db "$DB_ANON" -q -c "grant execute on function public.archive_season(uuid,uuid,text,jsonb) to anon;"
run_preflight "$DB_ANON" "$LOG_DIR/anon.log" || { cat "$LOG_DIR/anon.log" >&2; fail "anon preflight"; }
[ "$(psql_db "$DB_ANON" -Atq -c "select has_function_privilege('authenticated','public.archive_season(uuid,uuid,text,jsonb)','EXECUTE');" | tr -d '[:space:]')" = "f" ] \
    || fail "anon preflight did not lock out authenticated"
[ "$(psql_db "$DB_ANON" -Atq -c "select has_function_privilege('anon','public.archive_season(uuid,uuid,text,jsonb)','EXECUTE');" | tr -d '[:space:]')" = "f" ] \
    || fail "anon preflight did not revoke anon"
echo "OK: direct anon grant revoked"

echo "== preflight direct service_role grant =="
DB_SERVICE_PRE=ff1_neg_service_pre
clone_source "$DB_SERVICE_PRE"
psql_db "$DB_SERVICE_PRE" -q -c "grant execute on function public.archive_season(uuid,uuid,text,jsonb) to service_role;"
run_preflight "$DB_SERVICE_PRE" "$LOG_DIR/service-pre.log" || { cat "$LOG_DIR/service-pre.log" >&2; fail "service_role preflight"; }
[ "$(psql_db "$DB_SERVICE_PRE" -Atq -c "select has_function_privilege('service_role','public.archive_season(uuid,uuid,text,jsonb)','EXECUTE');" | tr -d '[:space:]')" = "f" ] \
    || fail "service_role preflight did not revoke service_role"
echo "OK: direct service_role grant revoked"

echo "== preflight unexpected overload =="
DB_OVER=ff1_neg_overload
clone_source "$DB_OVER"
psql_db "$DB_OVER" -q -c "alter event trigger archive_season_hardening_guard disable; alter event trigger archive_season_mutation_guard disable;"
psql_db "$DB_OVER" -q -c "create function public.archive_season(uuid,uuid,text,jsonb,text) returns void language sql as 'select 1';"
psql_db "$DB_OVER" -q -c "alter event trigger archive_season_hardening_guard enable; alter event trigger archive_season_mutation_guard enable;"
expect_preflight_failure "$DB_OVER" 'unexpected archive_season overload' "unexpected overload"
expect_verifier_failure "$DB_OVER" 'unexpected archive_season overload' "unexpected overload"

echo "== preflight missing canonical signature =="
DB_MISSING=ff1_neg_missing
clone_source "$DB_MISSING"
psql_db "$DB_MISSING" -q -c "alter event trigger archive_season_hardening_guard disable; alter event trigger archive_season_mutation_guard disable;"
psql_db "$DB_MISSING" -q -c "drop function public.archive_season(uuid,uuid,text,jsonb); create function public.archive_season(uuid,uuid,text,jsonb,text) returns void language sql as 'select 1';"
psql_db "$DB_MISSING" -q -c "alter event trigger archive_season_hardening_guard enable; alter event trigger archive_season_mutation_guard enable;"
expect_preflight_failure "$DB_MISSING" 'canonical.*signature is missing' "missing canonical signature"
expect_verifier_failure "$DB_MISSING" 'canonical archive_season|non esiste' "missing canonical signature"

echo "== preflight incorrect owner =="
DB_OWNER=ff1_neg_owner
clone_source "$DB_OWNER"
create_test_role neg_owner
psql_db "$DB_OWNER" -q -c "alter event trigger archive_season_hardening_guard disable; alter event trigger archive_season_mutation_guard disable;"
psql_db "$DB_OWNER" -q -c "alter function public.archive_season(uuid,uuid,text,jsonb) owner to neg_owner;"
psql_db "$DB_OWNER" -q -c "alter event trigger archive_season_hardening_guard enable; alter event trigger archive_season_mutation_guard enable;"
expect_preflight_failure "$DB_OWNER" 'owner' "incorrect owner"
expect_verifier_failure "$DB_OWNER" 'owner' "incorrect owner"

echo "== preflight incorrect SECURITY DEFINER =="
DB_INVOKER=ff1_neg_invoker
clone_source "$DB_INVOKER"
psql_db "$DB_INVOKER" -q -c "alter event trigger archive_season_hardening_guard disable; alter event trigger archive_season_mutation_guard disable;"
psql_db "$DB_INVOKER" -q -c "alter function public.archive_season(uuid,uuid,text,jsonb) security invoker;"
psql_db "$DB_INVOKER" -q -c "alter event trigger archive_season_hardening_guard enable; alter event trigger archive_season_mutation_guard enable;"
expect_preflight_failure "$DB_INVOKER" 'SECURITY DEFINER' "incorrect security mode"
expect_verifier_failure "$DB_INVOKER" 'SECURITY DEFINER' "incorrect security mode"

echo "== verifier missing composite FK =="
DB_FK=ff1_neg_fk
clone_source "$DB_FK"
psql_db "$DB_FK" -q -c "alter table public.season_snapshots drop constraint season_snapshots_season_league_fk;"
expect_verifier_failure "$DB_FK" 'composite season/league FK missing' "missing composite FK"

echo "== verifier wrong FK behavior =="
DB_FK2=ff1_neg_fk2
clone_source "$DB_FK2"
psql_db "$DB_FK2" -q -c "alter table public.season_snapshots drop constraint season_snapshots_season_league_fk; alter table public.season_snapshots add constraint season_snapshots_season_league_fk foreign key (season_id, league_id) references public.seasons(id, league_id) on delete cascade not valid;"
expect_verifier_failure "$DB_FK2" 'composite season/league FK structure' "wrong FK behavior"

echo "== verifier no-op marker-comment function =="
DB_WEAK=ff1_neg_weak
clone_source "$DB_WEAK"
psql_db "$DB_WEAK" -q -c "alter event trigger archive_season_hardening_guard disable; alter event trigger archive_season_mutation_guard disable;"
apply_weak_marker_function "$DB_WEAK"
psql_db "$DB_WEAK" -q -c "alter event trigger archive_season_hardening_guard enable; alter event trigger archive_season_mutation_guard enable;"
expect_verifier_failure "$DB_WEAK" 'installed archive_season differs from canonical definition|SHA-256' "marker-comment weak function"

echo "== verifier canonical store SHA tamper =="
DB_STORE=ff1_neg_store
clone_source "$DB_STORE"
psql_db "$DB_STORE" -q -c "update public.archive_season_canonical_definition set definition_sha256='deadbeef' where singleton;"
expect_verifier_failure "$DB_STORE" 'canonical definition SHA-256' "canonical store SHA tamper"

echo "== verifier service_role grant =="
DB_SERVICE=ff1_neg_service
clone_source "$DB_SERVICE"
psql_db "$DB_SERVICE" -q -c "grant execute on function public.archive_season(uuid,uuid,text,jsonb) to service_role;"
expect_verifier_failure "$DB_SERVICE" 'service_role' "service_role grant"

echo "== verifier missing authenticated grant =="
DB_AUTH=ff1_neg_auth
clone_source "$DB_AUTH"
psql_db "$DB_AUTH" -q -c "revoke execute on function public.archive_season(uuid,uuid,text,jsonb) from authenticated;"
expect_verifier_failure "$DB_AUTH" "authenticated non puo" "missing authenticated grant"

echo "== verifier lockout table client ACL =="
DB_LOCKACL=ff1_neg_lockacl
clone_source "$DB_LOCKACL"
psql_db "$DB_LOCKACL" -q -c "grant select on public.archive_season_deploy_lockout to authenticated;"
expect_verifier_failure "$DB_LOCKACL" 'lockout table readable by client roles' "lockout table ACL"

echo "== verifier snapshot privileges for anon/service_role =="
DB_SNAPACL=ff1_neg_snapacl
clone_source "$DB_SNAPACL"
psql_db "$DB_SNAPACL" -q -c "grant delete on public.season_snapshots to service_role; grant select on public.season_snapshots to anon;"
expect_verifier_failure "$DB_SNAPACL" 'snapshot history accessible to anon/service_role' "snapshot privileges for anon/service_role"

echo "== verifier internal-table write privileges =="
DB_INTERNALACL=ff1_neg_internalacl
clone_source "$DB_INTERNALACL"
psql_db "$DB_INTERNALACL" -q -c "grant update on public.archive_season_deploy_lockout to service_role; grant delete on public.archive_season_canonical_definition to service_role;"
expect_verifier_failure "$DB_INTERNALACL" 'deployment lockout table readable|canonical definition store writable' "internal-table write privileges"

echo "== verifier disabled guard =="
DB_GUARD=ff1_neg_guard
clone_source "$DB_GUARD"
psql_db "$DB_GUARD" -q -c "alter event trigger archive_season_hardening_guard disable;"
expect_verifier_failure "$DB_GUARD" 'event trigger di hardening' "disabled guard"

echo "== verifier missing 20261109230000 history =="
DB_HIST=ff1_neg_history
clone_source "$DB_HIST"
psql_db "$DB_HIST" -q -c "delete from supabase_migrations.schema_migrations where version='20261109230000';"
expect_verifier_failure "$DB_HIST" 'cronologia migrazioni finale incompleta' "missing final migration history"

echo "PASS: preflight and verifier negative tests"
