#!/usr/bin/env bash
# =============================================================================
# FantaFormula 1 - reproducible concurrency test for archive_season
#
# MANUAL TEST. It needs a disposable PostgreSQL/Supabase database where the
# migrations up to 20261109200000_season_snapshot_integrity.sql are applied.
# The committed SQL regression suite
# (20261109_season_snapshot_integrity_tests.sql) runs inside a transaction and
# cannot exercise two sessions, so this script drives two independent psql
# clients and commits real rows.
#
# Usage:
#   DATABASE_URL='postgresql://postgres@127.0.0.1:5432/ff1_concurrency?sslmode=disable' \
#     ./supabase/tests/archive_season_concurrency.sh
#
# Optional environment variables:
#   PSQL   path to the psql binary (default: psql)
#
# The script creates one league/season/profilo with random UUIDs, starts two
# archive_season calls so that the second one waits on the season row lock,
# and verifies that exactly one snapshot exists and the season is inactive.
# Fixtures are removed at exit (also on failure).
# =============================================================================
set -u

PSQL="${PSQL:-psql}"
DATABASE_URL="${DATABASE_URL:-}"
export PGCLIENTENCODING="${PGCLIENTENCODING:-UTF8}"

if [ -z "$DATABASE_URL" ]; then
    echo "FAIL: DATABASE_URL is required (disposable, migrated database)" >&2
    exit 2
fi

if ! command -v "$PSQL" >/dev/null 2>&1; then
    echo "FAIL: psql not found (set PSQL=/path/to/psql)" >&2
    exit 2
fi

TMP_DIR="$(mktemp -d)"
ADMIN=""
LEAGUE=""

cleanup() {
    # Force UTF-8: on Windows psql can otherwise request a WIN1252 conversion
    # that is unavailable in minimal PostgreSQL distributions.
    if [ -n "$SEASON" ]; then
        # The final migration changes snapshot FKs to ON DELETE RESTRICT.
        # Test fixtures are disposable and owned by the test role, so remove
        # the audit/snapshot explicitly before deleting the parent rows.
        PGCLIENTENCODING=UTF8 "$PSQL" "$DATABASE_URL" -v ON_ERROR_STOP=0 -q -c \
            "delete from public.season_archive_repairs where season_id = '$SEASON';" \
            >/dev/null 2>&1 || true
        PGCLIENTENCODING=UTF8 "$PSQL" "$DATABASE_URL" -v ON_ERROR_STOP=0 -q -c \
            "delete from public.season_snapshots where season_id = '$SEASON';" \
            >/dev/null || echo "WARN: cleanup snapshot failed" >&2
    fi
    if [ -n "$LEAGUE" ]; then
        PGCLIENTENCODING=UTF8 "$PSQL" "$DATABASE_URL" -v ON_ERROR_STOP=0 -q -c \
            "delete from public.leagues where id = '$LEAGUE';" >/dev/null \
            || echo "WARN: cleanup league failed" >&2
    fi
    if [ -n "$ADMIN" ]; then
        PGCLIENTENCODING=UTF8 "$PSQL" "$DATABASE_URL" -v ON_ERROR_STOP=0 -q -c \
            "delete from auth.users where id = '$ADMIN';" >/dev/null \
            || echo "WARN: cleanup user failed" >&2
    fi
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT

new_uuid() {
    "$PSQL" "$DATABASE_URL" -Atq -c "select gen_random_uuid();" | tr -d '\r'
}

ADMIN="$(new_uuid)"
LEAGUE="$(new_uuid)"
SEASON="$(new_uuid)"

if [ -z "$ADMIN" ] || [ -z "$LEAGUE" ] || [ -z "$SEASON" ]; then
    echo "FAIL: cannot generate fixture UUIDs" >&2
    exit 1
fi

# Invite code must match ^[A-Z0-9]{6}$ and stay unique across parallel runs.
INVITE="C$(printf '%s' "$ADMIN" | cut -c1-5 | tr 'a-f' 'A-F')"

STANDINGS="{\"rows\":[{\"position\":1,\"userId\":\"$ADMIN\",\"displayName\":\"Concurrency Admin\",\"totalPoints\":25.0,\"raceWins\":1,\"bestRacePoints\":25.0}]}"

"$PSQL" "$DATABASE_URL" -v ON_ERROR_STOP=1 -q <<SQL
-- handle_new_user creates the profile automatically.
insert into auth.users (id, email, raw_user_meta_data)
values (
    '$ADMIN',
    'concurrency-$ADMIN@example.test',
    '{"display_name":"Concurrency Admin"}'::jsonb
);

insert into public.leagues (id, name, invite_code, created_by)
values ('$LEAGUE', 'Concurrency League', '$INVITE', '$ADMIN');

insert into public.league_members (league_id, user_id, role)
values ('$LEAGUE', '$ADMIN', 'admin');

insert into public.seasons (id, league_id, name, is_active)
values ('$SEASON', '$LEAGUE', '2026 Concurrency', true);
SQL

if [ $? -ne 0 ]; then
    echo "FAIL: fixture setup failed" >&2
    exit 1
fi

run_archive() {
    local log_file="$1"
    "$PSQL" "$DATABASE_URL" -v ON_ERROR_STOP=1 -Atq >"$log_file" 2>&1 <<SQL
begin;
set local role authenticated;
select set_config('request.jwt.claim.sub', '$ADMIN', true);
select public.archive_season('$LEAGUE', '$SEASON', '2026 Concurrency', '$STANDINGS'::jsonb);
select pg_sleep(2);
commit;
SQL
}

# First session holds the season row lock and sleeps before COMMIT.
PGOPTIONS='-c statement_timeout=20000' run_archive "$TMP_DIR/client1.log" &
PID1=$!

# Give session 1 time to acquire the lock, then start the competing call.
sleep 0.5
PGOPTIONS='-c statement_timeout=20000' run_archive "$TMP_DIR/client2.log" &
PID2=$!

wait "$PID1"
RC1=$?
wait "$PID2"
RC2=$?

SNAPSHOTS="$("$PSQL" "$DATABASE_URL" -Atq -c \
    "select count(*) from public.season_snapshots where season_id = '$SEASON';" 2>/dev/null | tr -d '\r')"
ACTIVE="$("$PSQL" "$DATABASE_URL" -Atq -c \
    "select is_active from public.seasons where id = '$SEASON';" 2>/dev/null | tr -d '\r')"

echo "client1 exit=$RC1"
echo "client2 exit=$RC2"
echo "snapshots=$SNAPSHOTS"
echo "season_active=$ACTIVE"

FAIL=0
if [ "$RC1" -ne 0 ] || [ "$RC2" -eq 0 ]; then
    echo "FAIL: expected client 1 to succeed and client 2 to be rejected" >&2
    cat "$TMP_DIR/client1.log" >&2
    cat "$TMP_DIR/client2.log" >&2
    FAIL=1
fi
if ! grep -qE 'archiviata|attiva' "$TMP_DIR/client2.log"; then
    echo "FAIL: client 2 did not fail with the already-archived/inactive error" >&2
    cat "$TMP_DIR/client2.log" >&2
    FAIL=1
fi
if [ "$SNAPSHOTS" != "1" ]; then
    echo "FAIL: expected exactly one snapshot, got $SNAPSHOTS" >&2
    FAIL=1
fi
if [ "$ACTIVE" != "f" ]; then
    echo "FAIL: expected the season to be inactive, got '$ACTIVE'" >&2
    FAIL=1
fi

if [ "$FAIL" -ne 0 ]; then
    exit 1
fi

echo "PASS: two concurrent archive_season calls produced exactly one snapshot"
