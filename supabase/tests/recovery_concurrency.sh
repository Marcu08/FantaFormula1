#!/usr/bin/env bash
# =============================================================================
# FantaFormula 1 - reproducible concurrency test for complete_partial_season_archive
#
# MANUAL/CI TEST. Requires a disposable database with the final migrations
# (including 20261109220000_season_archive_recovery.sql) applied.
#
# It creates a valid legacy partial state (snapshot + active season), starts
# two recovery sessions so the second waits on the season row lock, and checks
# that exactly one succeeds, one audit row exists and the season is inactive.
# Fixtures are removed at exit.
#
# Usage:
#   DATABASE_URL='postgresql://postgres@127.0.0.1:5432/ff1' \
#     ./supabase/tests/recovery_concurrency.sh
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
SEASON=""

cleanup() {
    if [ -n "$SEASON" ]; then
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

INVITE="R$(printf '%s' "$ADMIN" | cut -c1-5 | tr 'a-f' 'A-F')"
STANDINGS="{\"rows\":[{\"position\":1,\"userId\":\"$ADMIN\",\"displayName\":\"Recovery Admin\",\"totalPoints\":0.0,\"raceWins\":0,\"bestRacePoints\":0.0}]}"

"$PSQL" "$DATABASE_URL" -v ON_ERROR_STOP=1 -q <<SQL
insert into auth.users (id, email, raw_user_meta_data)
values (
    '$ADMIN',
    'recovery-concurrency-$ADMIN@example.test',
    '{"display_name":"Recovery Admin"}'::jsonb
);

insert into public.leagues (id, name, invite_code, created_by)
values ('$LEAGUE', 'Recovery Concurrency', '$INVITE', '$ADMIN');

insert into public.league_members (league_id, user_id, role)
values ('$LEAGUE', '$ADMIN', 'admin');

insert into public.seasons (id, league_id, name, is_active)
values ('$SEASON', '$LEAGUE', '2026 Recovery Concurrency', true);

insert into public.season_snapshots (
    season_id, league_id, season_name, standings, archived_by
) values (
    '$SEASON', '$LEAGUE', '2026 Recovery Concurrency',
    '$STANDINGS'::jsonb, '$ADMIN'
);
SQL

if [ $? -ne 0 ]; then
    echo "FAIL: fixture setup failed" >&2
    exit 1
fi

run_recovery() {
    local log_file="$1"
    "$PSQL" "$DATABASE_URL" -v ON_ERROR_STOP=1 -Atq >"$log_file" 2>&1 <<SQL
begin;
set local role authenticated;
select set_config('request.jwt.claim.sub', '$ADMIN', true);
select public.complete_partial_season_archive('$LEAGUE', '$SEASON');
select pg_sleep(2);
commit;
SQL
}

PGOPTIONS='-c statement_timeout=20000' run_recovery "$TMP_DIR/client1.log" &
PID1=$!

sleep 0.5
PGOPTIONS='-c statement_timeout=20000' run_recovery "$TMP_DIR/client2.log" &
PID2=$!

wait "$PID1"
RC1=$?
wait "$PID2"
RC2=$?

ACTIVE="$("$PSQL" "$DATABASE_URL" -Atq -c \
    "select is_active from public.seasons where id = '$SEASON';" 2>/dev/null | tr -d '\r')"
AUDIT="$("$PSQL" "$DATABASE_URL" -Atq -c \
    "select count(*) from public.season_archive_repairs where season_id = '$SEASON';" 2>/dev/null | tr -d '\r')"
SNAPSHOT="$("$PSQL" "$DATABASE_URL" -Atq -c \
    "select count(*) from public.season_snapshots where season_id = '$SEASON' and standings = '$STANDINGS'::jsonb;" 2>/dev/null | tr -d '\r')"

echo "client1 exit=$RC1"
echo "client2 exit=$RC2"
echo "season_active=$ACTIVE"
echo "audit_rows=$AUDIT"
echo "snapshot_unchanged=$SNAPSHOT"

FAIL=0
if [ "$RC1" -ne 0 ] || [ "$RC2" -eq 0 ]; then
    echo "FAIL: expected client 1 to succeed and client 2 to be rejected" >&2
    cat "$TMP_DIR/client1.log" >&2
    cat "$TMP_DIR/client2.log" >&2
    FAIL=1
fi
if ! grep -qE 'attiva|non e' "$TMP_DIR/client2.log"; then
    echo "FAIL: client 2 did not fail with the expected inactive-season error" >&2
    cat "$TMP_DIR/client2.log" >&2
    FAIL=1
fi
if [ "$ACTIVE" != "f" ]; then
    echo "FAIL: expected inactive season, got '$ACTIVE'" >&2
    FAIL=1
fi
if [ "$AUDIT" != "1" ]; then
    echo "FAIL: expected exactly one audit row, got '$AUDIT'" >&2
    FAIL=1
fi
if [ "$SNAPSHOT" != "1" ]; then
    echo "FAIL: snapshot changed or missing" >&2
    FAIL=1
fi

if [ "$FAIL" -ne 0 ]; then
    exit 1
fi

echo "PASS: two concurrent recovery calls produced one audit row and one completed season"
