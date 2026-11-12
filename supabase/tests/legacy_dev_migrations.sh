#!/usr/bin/env bash
# =============================================================================
# FantaFormula 1 - executable wrapper for the development migration tests
#
# The original files are manual SQL-editor templates with UUID placeholders.
# This runner creates disposable auth users, substitutes the placeholders and
# runs each template in a transaction. The templates intentionally finish with
# a self-rollback exception, so exit code 3 plus a "TUTTI SUPERATI" notice is
# the expected success path; any other failure is a test failure.
#
# Requires a disposable, fully migrated local database.
# =============================================================================
set -euo pipefail

PSQL="${PSQL:-psql}"
: "${DATABASE_URL:?DATABASE_URL is required}"
url_host="${DATABASE_URL#*://}"
url_host="${url_host%%/*}"
url_host="${url_host##*@}"
case "$url_host" in
    \[*\]:*) url_host="${url_host#\[}"; url_host="${url_host%%]*}" ;;
    *) url_host="${url_host%%:*}" ;;
esac
case "$url_host" in
    127.0.0.1|localhost|::1) ;;
    *) echo "FAIL: DATABASE_URL must target disposable loopback, not '$url_host'" >&2; exit 2 ;;
esac
case "${PGHOST:-127.0.0.1}" in
    127.0.0.1|localhost|::1) ;;
    *) echo "FAIL: PGHOST must be disposable loopback, not '${PGHOST:-}'" >&2; exit 2 ;;
esac
export PGCLIENTENCODING="${PGCLIENTENCODING:-UTF8}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

new_uuid() {
    "$PSQL" "$DATABASE_URL" -Atq -c 'select gen_random_uuid();' | tr -d '\r'
}

create_user() {
    local user="$1" label="$2"
    "$PSQL" "$DATABASE_URL" -v ON_ERROR_STOP=1 -q -c \
        "insert into auth.users (id, email, raw_user_meta_data)
         values ('$user', '$label-$user@example.test', '{\"display_name\":\"$label\"}'::jsonb)
         on conflict (id) do nothing;" >/dev/null
}

run_template() {
    local name="$1" user1="$2" user2="$3"
    local source="$SCRIPT_DIR/$name"
    local adapted="$TMP_DIR/$name"
    local log="$TMP_DIR/$name.log"

    if [ ! -f "$source" ]; then
        echo "FAIL: test template missing: $source" >&2
        exit 1
    fi

    {
        echo 'begin;'
        cat "$source"
    } >"$adapted"

    if [ -n "$user1" ]; then
        sed -i "s/<UUID_USER>/$user1/g; s/<UUID_ADMIN>/$user1/g; s/<UUID_GIOCATORE>/$user2/g" "$adapted"
    fi

    set +e
    "$PSQL" "$DATABASE_URL" -v ON_ERROR_STOP=1 -f "$adapted" >"$log" 2>&1
    local rc=$?
    set -e

    if ! grep -q 'TUTTI SUPERATI' "$log"; then
        echo "FAIL: $name did not reach the self-rollback success marker (exit $rc)" >&2
        cat "$log" >&2
        exit 1
    fi
    if grep -Eq 'FALLITO|almeno un caso fallito' "$log"; then
        echo "FAIL: $name reported a failed assertion" >&2
        cat "$log" >&2
        exit 1
    fi
    if [ "$rc" -ne 3 ]; then
        echo "FAIL: $name expected self-rollback exit 3, got $rc" >&2
        cat "$log" >&2
        exit 1
    fi
    echo "OK: $name (self-rollback, all assertions passed)"
}

USER_A="$(new_uuid)"
USER_B="$(new_uuid)"
[ -n "$USER_A" ] && [ -n "$USER_B" ] || { echo "FAIL: cannot generate users" >&2; exit 1; }
create_user "$USER_A" "legacy_test_a"
create_user "$USER_B" "legacy_test_b"

# The templates were written against a Supabase project, which grants normal
# table privileges to authenticated by default. The minimal CI bootstrap does
# not emulate those defaults, so grant them on this disposable test database.
"$PSQL" "$DATABASE_URL" -v ON_ERROR_STOP=1 -q -c \
    "grant usage on schema public to authenticated;
     grant select, insert, update, delete on all tables in schema public to authenticated;" >/dev/null

run_template 0014_audit_fixes_tests.sql "$USER_A" "$USER_B"
run_template 0015_security_hardening_tests.sql "" ""
run_template 0016_atomic_prediction_revision_tests.sql "$USER_A" ""
run_template 0017_server_revision_bigint_tests.sql "$USER_A" ""
run_template 0018_initial_quotes_tests.sql "$USER_A" ""
run_template 0019_quote_publish_lock_tests.sql "$USER_A" ""

echo "PASS: development migration templates 0014-0019"
