-- =============================================================================
-- FantaFormula 1 - explicit, history-independent archive_season repair
--
-- This file is the operator-facing repair entry point. It intentionally
-- includes the idempotent 20261109230000 migration so that the canonical
-- hardened implementation is executed even when every migration version is
-- already recorded in supabase_migrations.schema_migrations.
--
-- Run it only through supabase/scripts/deploy_archive_safe.sh, which commits
-- the external preflight lockout first and verifies the database afterward.
-- Direct psql use is still transaction-wrapped by the caller:
--
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -1 \
--     -f supabase/repair/archive_season_repair.sql
--
-- This is SQL execution, not migration-history repair. It must never be
-- invoked merely to alter supabase_migrations.schema_migrations.
-- =============================================================================

\ir ../migrations/20261109230000_archive_season_exact_protection.sql
