-- =============================================================================
-- FantaFormula 1 - post-deployment verification for season archival security
--
-- Read-only. Run with:
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f supabase/tests/verify_archive_season_hardening.sql
--
-- Exit status is non-zero if any assertion fails (the DO block raises).
-- This script verifies PostgreSQL state, not live Supabase/PostgREST behavior.
-- =============================================================================
do $verify$
declare
    v_oid oid := to_regprocedure('public.archive_season(uuid,uuid,text,jsonb)');
    v_def text;
    v_marker text;
    v_markers text[] := array[
        'v_user uuid := auth.uid()',
        'if not public.is_league_admin(p_league)',
        'for update',
        'if not v_active then',
        'userId duplicati',
        'posizioni duplicate',
        'posizioni non contigue',
        'v_total_points < 0',
        'v_best_points < 0',
        $marker$coalesce(nullif(btrim(v_season_name), ''), 'Stagione')$marker$
    ];
    v_fn_owner name;
    v_table_owner name;
    v_config text[];
    v_security_definer boolean;
begin
    if v_oid is null then
        raise exception 'VERIFY FAIL: public.archive_season(...) non esiste';
    end if;

    select p.prosecdef, p.proconfig
    into v_security_definer, v_config
    from pg_proc p
    where p.oid = v_oid;

    if not coalesce(v_security_definer, false) then
        raise exception 'VERIFY FAIL: archive_season non SECURITY DEFINER';
    end if;
    if v_config is null or not (v_config @> array['search_path=public']) then
        raise exception 'VERIFY FAIL: archive_season search_path non e'' public';
    end if;

    select pg_get_functiondef(v_oid) into v_def;

    foreach v_marker in array v_markers loop
        if position(v_marker in v_def) = 0 then
            raise exception 'VERIFY FAIL: archive_season privo del controllo "%"', v_marker;
        end if;
    end loop;

    if position('btrim(p_name)' in v_def) > 0 then
        raise exception 'VERIFY FAIL: archive_season usa p_name invece del nome DB';
    end if;

    select r.rolname
    into v_fn_owner
    from pg_proc p
    join pg_roles r on r.oid = p.proowner
    where p.oid = v_oid;

    select r.rolname
    into v_table_owner
    from pg_class c
    join pg_roles r on r.oid = c.relowner
    where c.oid = 'public.seasons'::regclass;

    if v_fn_owner is distinct from v_table_owner then
        raise exception
            'VERIFY FAIL: owner archive_season (%) diverso da owner seasons (%)',
            v_fn_owner, v_table_owner;
    end if;

    select r.rolname
    into v_table_owner
    from pg_class c
    join pg_roles r on r.oid = c.relowner
    where c.oid = 'public.season_snapshots'::regclass;

    if v_fn_owner is distinct from v_table_owner then
        raise exception
            'VERIFY FAIL: owner archive_season (%) diverso da owner season_snapshots (%)',
            v_fn_owner, v_table_owner;
    end if;

    if has_table_privilege('authenticated', 'public.season_snapshots', 'INSERT')
       or has_table_privilege('authenticated', 'public.season_snapshots', 'UPDATE')
       or has_table_privilege('authenticated', 'public.season_snapshots', 'DELETE')
       or has_table_privilege('authenticated', 'public.season_snapshots', 'TRUNCATE')
       or has_table_privilege('authenticated', 'public.season_snapshots', 'REFERENCES')
       or has_table_privilege('authenticated', 'public.season_snapshots', 'TRIGGER') then
        raise exception 'VERIFY FAIL: privilegi DML/DDL residui su season_snapshots';
    end if;
    if not has_table_privilege('authenticated', 'public.season_snapshots', 'SELECT') then
        raise exception 'VERIFY FAIL: authenticated non puo'' leggere season_snapshots';
    end if;

    if has_table_privilege('authenticated', 'public.seasons', 'UPDATE')
       or has_column_privilege('authenticated', 'public.seasons', 'is_active', 'UPDATE') then
        raise exception 'VERIFY FAIL: authenticated puo'' modificare seasons.is_active';
    end if;
    if not has_column_privilege('authenticated', 'public.seasons', 'name', 'UPDATE')
       or not has_column_privilege('authenticated', 'public.seasons', 'start_date', 'UPDATE')
       or not has_column_privilege('authenticated', 'public.seasons', 'end_date', 'UPDATE') then
        raise exception 'VERIFY FAIL: mancano i grant colonnari legittimi su seasons';
    end if;
    if has_table_privilege('authenticated', 'public.seasons', 'TRUNCATE')
       or has_table_privilege('authenticated', 'public.seasons', 'REFERENCES')
       or has_table_privilege('authenticated', 'public.seasons', 'TRIGGER') then
        raise exception 'VERIFY FAIL: privilegi residui su seasons';
    end if;

    if current_setting('server_version_num')::int >= 170000 then
        if has_table_privilege('authenticated', 'public.season_snapshots', 'MAINTAIN')
           or has_table_privilege('authenticated', 'public.seasons', 'MAINTAIN')
           or has_table_privilege('authenticated', 'public.leagues', 'MAINTAIN') then
            raise exception 'VERIFY FAIL: privilegio MAINTAIN residuo';
        end if;
    end if;

    if has_function_privilege(
        'anon',
        'public.archive_season(uuid,uuid,text,jsonb)',
        'EXECUTE'
    ) then
        raise exception 'VERIFY FAIL: anon puo'' eseguire archive_season';
    end if;
    if not has_function_privilege(
        'authenticated',
        'public.archive_season(uuid,uuid,text,jsonb)',
        'EXECUTE'
    ) then
        raise exception 'VERIFY FAIL: authenticated non puo'' eseguire archive_season';
    end if;
    if to_regprocedure('public.harden_season_archive_acl()') is null then
        raise exception 'VERIFY FAIL: helper ACL assente';
    end if;
    if has_function_privilege(
        'authenticated',
        'public.harden_season_archive_acl()',
        'EXECUTE'
    ) then
        raise exception 'VERIFY FAIL: helper ACL eseguibile dai client';
    end if;
    if to_regprocedure('public.complete_partial_season_archive(uuid,uuid)') is null then
        raise exception 'VERIFY FAIL: RPC di recupero legacy assente';
    end if;
    if to_regprocedure('public.season_snapshot_shape_is_valid(jsonb)') is null then
        raise exception 'VERIFY FAIL: validatore snapshot legacy assente';
    end if;
    if has_function_privilege(
        'authenticated',
        'public.season_snapshot_shape_is_valid(jsonb)',
        'EXECUTE'
    ) then
        raise exception 'VERIFY FAIL: validatore snapshot legacy eseguibile dai client';
    end if;

    if not exists (
        select 1
        from pg_event_trigger
        where evtname = 'archive_season_hardening_guard'
          and evtenabled = 'O'
    ) then
        raise exception 'VERIFY FAIL: event trigger di hardening assente o disabilitato';
    end if;

    if exists (
        select 1
        from pg_constraint
        where conrelid = 'public.season_snapshots'::regclass
          and conname in (
              'season_snapshots_season_id_fkey',
              'season_snapshots_league_id_fkey',
              'season_snapshots_season_league_fk'
          )
          and confdeltype <> 'r'
    ) then
        raise exception 'VERIFY FAIL: una FK snapshot non e'' RESTRICT';
    end if;

    if to_regclass('public.season_archive_repairs') is null then
        raise exception 'VERIFY FAIL: tabella audit season_archive_repairs assente';
    end if;
    if has_table_privilege('authenticated', 'public.season_archive_repairs', 'SELECT')
       or has_table_privilege('authenticated', 'public.season_archive_repairs', 'INSERT')
       or has_table_privilege('authenticated', 'public.season_archive_repairs', 'UPDATE')
       or has_table_privilege('authenticated', 'public.season_archive_repairs', 'DELETE')
       or has_table_privilege('authenticated', 'public.season_archive_repairs', 'TRUNCATE') then
        raise exception 'VERIFY FAIL: tabella audit accessibile ai client';
    end if;

    if has_schema_privilege('authenticated', 'public', 'CREATE')
       or has_schema_privilege('anon', 'public', 'CREATE') then
        raise exception 'VERIFY FAIL: ruolo client puo'' creare oggetti in public';
    end if;

    raise notice 'VERIFY OK: archive_season hardened, ACL minime, FK RESTRICT, guard attivo';
end;
$verify$;
