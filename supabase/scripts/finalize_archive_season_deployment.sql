-- =============================================================================
-- FantaFormula 1 - finalize archive_season deployment
--
-- Run only after the locked-down verifier has passed. This file re-checks the
-- canonical function/guard identity itself, clears the deployment lockout and
-- grants the only intended client permission, all in one transaction.
--
-- If any check fails, the transaction rolls back: EXECUTE stays revoked and
-- the lockout stays active.
-- =============================================================================

set search_path = public, extensions;

do $archive_finalize_gate$
declare
    v_oid oid := to_regprocedure('public.archive_season(uuid,uuid,text,jsonb)');
    v_guard_oid oid := to_regprocedure('public.enforce_archive_season_hardening()');
    v_expected text;
    v_expected_sha text;
    v_actual text;
    v_active boolean;
begin
    if v_oid is null then
        raise exception 'FINALIZE FAIL: canonical archive_season missing';
    end if;
    if v_guard_oid is null then
        raise exception 'FINALIZE FAIL: archive_season guard missing';
    end if;

    select definition, definition_sha256
    into v_expected, v_expected_sha
    from public.archive_season_canonical_definition
    where singleton;

    v_actual := replace(replace(pg_get_functiondef(v_oid), E'\r\n', E'\n'), E'\r', E'\n');
    if v_expected is null or v_actual is distinct from v_expected then
        raise exception 'FINALIZE FAIL: installed function differs from canonical definition';
    end if;
    if encode(digest(v_actual, 'sha256'), 'hex') is distinct from v_expected_sha then
        raise exception 'FINALIZE FAIL: canonical SHA-256 mismatch';
    end if;

    if exists (
        select 1
        from pg_proc p
        join pg_namespace n on n.oid = p.pronamespace
        where n.nspname = 'public'
          and p.proname = 'archive_season'
          and p.oid <> v_oid
    ) then
        raise exception 'FINALIZE FAIL: unexpected archive_season overload remains';
    end if;

    if not exists (
        select 1
        from pg_event_trigger
        where evtname = 'archive_season_hardening_guard'
          and evtenabled = 'O'
          and evtfoid = v_guard_oid
          and evttags is not null
          and 'CREATE FUNCTION' = any(evttags)
          and 'CREATE PROCEDURE' = any(evttags)
    ) then
        raise exception 'FINALIZE FAIL: create-function/procedure guard missing or disabled';
    end if;

    if not exists (
        select 1
        from pg_event_trigger
        where evtname = 'archive_season_mutation_guard'
          and evtenabled = 'O'
          and evtfoid = v_guard_oid
          and evttags is not null
          and 'ALTER FUNCTION' = any(evttags)
          and 'DROP FUNCTION' = any(evttags)
          and 'ALTER ROUTINE' = any(evttags)
          and 'DROP ROUTINE' = any(evttags)
          and 'ALTER PROCEDURE' = any(evttags)
          and 'DROP PROCEDURE' = any(evttags)
    ) then
        raise exception 'FINALIZE FAIL: mutation guard missing or disabled';
    end if;

    select active
    into v_active
    from public.archive_season_deploy_lockout
    where singleton;

    if not coalesce(v_active, false) then
        raise exception 'FINALIZE FAIL: deployment lockout is not active; preflight was not completed';
    end if;

    if has_function_privilege('authenticated', v_oid, 'EXECUTE')
       or has_function_privilege('anon', v_oid, 'EXECUTE')
       or has_function_privilege('service_role', v_oid, 'EXECUTE') then
        raise exception 'FINALIZE FAIL: a client role still has EXECUTE before finalization';
    end if;
end;
$archive_finalize_gate$;

update public.archive_season_deploy_lockout
set active = false,
    note = 'verified deployment; authenticated granted after lockout verifier'
where singleton;

grant execute on function public.archive_season(uuid,uuid,text,jsonb)
    to authenticated;
