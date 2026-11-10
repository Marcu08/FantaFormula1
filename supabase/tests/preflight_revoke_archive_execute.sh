#!/usr/bin/env bash
# =============================================================================
# FantaFormula 1 - fail-closed preflight for archive_season EXECUTE
#
# Run this OUTSIDE the migration transaction before deploying migrations that
# may touch archive_season. It removes EXECUTE from public/anon/authenticated
# if the function exists. If a migration then fails, authenticated clients
# cannot execute an older/weaker implementation. A successful canonical
# migration (0014 hardened, 200000, 210000) restores the grant only after the
# hardened function is installed.
#
# This is a security operation, not a migration-history repair. It must be run
# by the function owner or a superuser/migration role.
#
# Usage:
#   DATABASE_URL='postgresql://...' ./supabase/tests/preflight_revoke_archive_execute.sh
# =============================================================================
set -euo pipefail

PSQL="${PSQL:-psql}"
: "${DATABASE_URL:?DATABASE_URL is required}"

"$PSQL" "$DATABASE_URL" -v ON_ERROR_STOP=1 <<'SQL'
do $archive_preflight$
begin
    if to_regprocedure('public.archive_season(uuid,uuid,text,jsonb)') is not null then
        execute 'revoke execute on function public.archive_season(uuid,uuid,text,jsonb) from public, anon, authenticated';
    end if;
end
$archive_preflight$;
SQL
