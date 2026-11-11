-- =============================================================================
-- FantaFormula 1 - post-deployment verification for season archival security
--
-- Read-only. Run with:
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f supabase/tests/verify_archive_season_hardening.sql
--
-- Expected deployment state may be supplied in the same psql session:
--   set app.archive_verify_expect_auth = 'false';
--   set app.archive_verify_expect_lockout = 'true';
--
-- Exit status is non-zero if any assertion fails (the DO block raises).
-- This script verifies PostgreSQL state, not live Supabase/PostgREST behavior.
-- =============================================================================
set search_path = public, extensions;

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
    v_other_oid oid;
    v_other_definer boolean;
    v_other_config text[];
    v_guard_oid oid;
    v_guard_definer boolean;
    v_guard_config text[];
    v_expect_auth text := coalesce(
        nullif(current_setting('app.archive_verify_expect_auth', true), ''),
        'true'
    );
    v_expect_lockout text := coalesce(
        nullif(current_setting('app.archive_verify_expect_lockout', true), ''),
        'false'
    );
    v_expected_sha text := '159696dd25b7b4975640a007ccec83da1fd73fc641b4619a9d5c1d52bf7babee';
    v_canonical_def text;
    v_canonical_sha text;
    v_lockout_active boolean;
    v_state text;
    v_conkey smallint[];
    v_confkey smallint[];
    v_confrelid oid;
    v_convalidated boolean;
    v_confdeltype "char";
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

    select replace(replace(pg_get_functiondef(v_oid), E'\r\n', E'\n'), E'\r', E'\n')
    into v_def;

    foreach v_marker in array v_markers loop
        if position(v_marker in v_def) = 0 then
            raise exception 'VERIFY FAIL: archive_season privo del controllo "%"', v_marker;
        end if;
    end loop;

    if position('btrim(p_name)' in v_def) > 0 then
        raise exception 'VERIFY FAIL: archive_season usa p_name invece del nome DB';
    end if;

    -- Exact canonical identity. Marker strings in comments are not accepted:
    -- the complete pg_get_functiondef output must match the reviewed canonical
    -- definition stored by 20261109230000 and the independently hardcoded
    -- SHA-256 in this verifier.
    if to_regclass('public.archive_season_canonical_definition') is null then
        raise exception 'VERIFY FAIL: canonical definition store missing';
    end if;
    if (select count(*) from public.archive_season_canonical_definition) <> 1 then
        raise exception 'VERIFY FAIL: canonical definition store must contain exactly one row';
    end if;

    select definition, definition_sha256
    into v_canonical_def, v_canonical_sha
    from public.archive_season_canonical_definition
    where singleton;

    if v_canonical_def is null then
        raise exception 'VERIFY FAIL: canonical definition missing';
    end if;
    if v_def is distinct from v_canonical_def then
        raise exception 'VERIFY FAIL: installed archive_season differs from canonical definition';
    end if;
    if v_canonical_sha is distinct from v_expected_sha then
        raise exception 'VERIFY FAIL: canonical definition SHA-256 does not match the reviewed value';
    end if;
    if encode(digest(v_def, 'sha256'), 'hex') is distinct from v_expected_sha then
        raise exception 'VERIFY FAIL: installed archive_season SHA-256 does not match the reviewed value';
    end if;

    if has_table_privilege('authenticated', 'public.archive_season_canonical_definition', 'SELECT')
       or has_table_privilege('anon', 'public.archive_season_canonical_definition', 'SELECT')
       or has_table_privilege('service_role', 'public.archive_season_canonical_definition', 'SELECT') then
        raise exception 'VERIFY FAIL: canonical definition store readable by client roles';
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
    -- service_role bypasses RLS in a Supabase project: snapshot reads are also
    -- forbidden. Do not verify only the DML subset.
    if has_table_privilege('anon', 'public.season_snapshots', 'SELECT')
       or has_table_privilege('anon', 'public.season_snapshots', 'INSERT')
       or has_table_privilege('anon', 'public.season_snapshots', 'UPDATE')
       or has_table_privilege('anon', 'public.season_snapshots', 'DELETE')
       or has_table_privilege('anon', 'public.season_snapshots', 'TRUNCATE')
       or has_table_privilege('anon', 'public.season_snapshots', 'REFERENCES')
       or has_table_privilege('anon', 'public.season_snapshots', 'TRIGGER')
       or has_table_privilege('service_role', 'public.season_snapshots', 'SELECT')
       or has_table_privilege('service_role', 'public.season_snapshots', 'INSERT')
       or has_table_privilege('service_role', 'public.season_snapshots', 'UPDATE')
       or has_table_privilege('service_role', 'public.season_snapshots', 'DELETE')
       or has_table_privilege('service_role', 'public.season_snapshots', 'TRUNCATE')
       or has_table_privilege('service_role', 'public.season_snapshots', 'REFERENCES')
       or has_table_privilege('service_role', 'public.season_snapshots', 'TRIGGER') then
        raise exception 'VERIFY FAIL: snapshot history accessible to anon/service_role';
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
           or has_table_privilege('authenticated', 'public.leagues', 'MAINTAIN')
           or has_table_privilege('anon', 'public.season_snapshots', 'MAINTAIN')
           or has_table_privilege('service_role', 'public.season_snapshots', 'MAINTAIN')
           or has_table_privilege('anon', 'public.archive_season_deploy_lockout', 'MAINTAIN')
           or has_table_privilege('service_role', 'public.archive_season_deploy_lockout', 'MAINTAIN')
           or has_table_privilege('anon', 'public.archive_season_canonical_definition', 'MAINTAIN')
           or has_table_privilege('service_role', 'public.archive_season_canonical_definition', 'MAINTAIN') then
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
    if has_function_privilege(
        'service_role',
        'public.archive_season(uuid,uuid,text,jsonb)',
        'EXECUTE'
    ) then
        raise exception 'VERIFY FAIL: service_role puo'' eseguire archive_season';
    end if;
    if v_expect_auth = 'true' then
        if not has_function_privilege(
            'authenticated',
            'public.archive_season(uuid,uuid,text,jsonb)',
            'EXECUTE'
        ) then
            raise exception 'VERIFY FAIL: authenticated non puo'' eseguire archive_season';
        end if;
    elsif v_expect_auth = 'false' then
        if has_function_privilege(
            'authenticated',
            'public.archive_season(uuid,uuid,text,jsonb)',
            'EXECUTE'
        ) then
            raise exception 'VERIFY FAIL: authenticated EXECUTE should still be revoked';
        end if;
    else
        raise exception 'VERIFY FAIL: invalid app.archive_verify_expect_auth value: %', v_expect_auth;
    end if;
    if to_regprocedure('public.harden_season_archive_acl()') is null then
        raise exception 'VERIFY FAIL: helper ACL assente';
    end if;
    if has_function_privilege(
        'authenticated',
        'public.harden_season_archive_acl()',
        'EXECUTE'
    ) or has_function_privilege(
        'service_role',
        'public.harden_season_archive_acl()',
        'EXECUTE'
    ) then
        raise exception 'VERIFY FAIL: helper ACL eseguibile dai client';
    end if;
    -- Nessun overload alternativo di archive_season deve essere eseguibile
    -- dai client: una firma piu' debole aggirerebbe i controlli della canonica.
    if exists (
        select 1
        from pg_proc p
        join pg_namespace n on n.oid = p.pronamespace
        where n.nspname = 'public'
          and p.proname = 'archive_season'
          and p.oid <> v_oid
    ) then
        raise exception 'VERIFY FAIL: unexpected archive_season overload exists';
    end if;

    v_other_oid := to_regprocedure('public.complete_partial_season_archive(uuid,uuid)');
    if v_other_oid is null then
        raise exception 'VERIFY FAIL: RPC di recupero legacy assente';
    end if;
    select p.prosecdef, p.proconfig
    into v_other_definer, v_other_config
    from pg_proc p
    where p.oid = v_other_oid;

    if not coalesce(v_other_definer, false) then
        raise exception 'VERIFY FAIL: RPC di recupero non SECURITY DEFINER';
    end if;
    if v_other_config is null
       or not (v_other_config @> array['search_path=public']) then
        raise exception 'VERIFY FAIL: RPC di recupero search_path non e'' public';
    end if;
    select r.rolname
    into v_table_owner
    from pg_proc p
    join pg_roles r on r.oid = p.proowner
    where p.oid = v_other_oid;
    if v_table_owner is distinct from v_fn_owner then
        raise exception
            'VERIFY FAIL: owner RPC recupero (%) diverso da owner archive_season (%)',
            v_table_owner, v_fn_owner;
    end if;
    if not has_function_privilege('authenticated', v_other_oid, 'EXECUTE') then
        raise exception 'VERIFY FAIL: authenticated non puo'' eseguire la RPC di recupero';
    end if;
    if has_function_privilege('anon', v_other_oid, 'EXECUTE') then
        raise exception 'VERIFY FAIL: anon puo'' eseguire la RPC di recupero';
    end if;
    if has_function_privilege('service_role', v_other_oid, 'EXECUTE') then
        raise exception 'VERIFY FAIL: service_role puo'' eseguire la RPC di recupero';
    end if;

    v_guard_oid := to_regprocedure('public.enforce_archive_season_hardening()');
    if v_guard_oid is null then
        raise exception 'VERIFY FAIL: funzione event trigger di hardening assente';
    end if;
    select p.prosecdef, p.proconfig
    into v_guard_definer, v_guard_config
    from pg_proc p
    where p.oid = v_guard_oid;

    if not coalesce(v_guard_definer, false) then
        raise exception 'VERIFY FAIL: guard DDL non SECURITY DEFINER';
    end if;
    if v_guard_config is null
       or not (v_guard_config @> array['search_path=public']) then
        raise exception 'VERIFY FAIL: guard DDL search_path non e'' public';
    end if;
    select r.rolname
    into v_table_owner
    from pg_proc p
    join pg_roles r on r.oid = p.proowner
    where p.oid = v_guard_oid;
    if v_table_owner is distinct from v_fn_owner then
        raise exception
            'VERIFY FAIL: owner guard DDL (%) diverso da owner archive_season (%)',
            v_table_owner, v_fn_owner;
    end if;
    if has_function_privilege('authenticated', v_guard_oid, 'EXECUTE')
       or has_function_privilege('anon', v_guard_oid, 'EXECUTE') then
        raise exception 'VERIFY FAIL: guard DDL eseguibile dai client';
    end if;

    if to_regprocedure('public.season_snapshot_shape_is_valid(jsonb)') is null then
        raise exception 'VERIFY FAIL: validatore snapshot legacy assente';
    end if;
    if has_function_privilege(
        'authenticated',
        'public.season_snapshot_shape_is_valid(jsonb)',
        'EXECUTE'
    ) or has_function_privilege(
        'service_role',
        'public.season_snapshot_shape_is_valid(jsonb)',
        'EXECUTE'
    ) then
        raise exception 'VERIFY FAIL: validatore snapshot legacy eseguibile dai client';
    end if;
    if to_regprocedure(
        'public.season_snapshot_participants_are_eligible(uuid,uuid,jsonb)'
    ) is null then
        raise exception 'VERIFY FAIL: validatore partecipanti legacy assente';
    end if;
    if has_function_privilege(
        'authenticated',
        'public.season_snapshot_participants_are_eligible(uuid,uuid,jsonb)',
        'EXECUTE'
    ) or has_function_privilege(
        'service_role',
        'public.season_snapshot_participants_are_eligible(uuid,uuid,jsonb)',
        'EXECUTE'
    ) then
        raise exception 'VERIFY FAIL: validatore partecipanti eseguibile dai client';
    end if;

    if not exists (
        select 1
        from pg_event_trigger
        where evtname = 'archive_season_hardening_guard'
          and evtenabled = 'O'
          and evtfoid = v_guard_oid
          and evttags is not null
          and 'CREATE FUNCTION' = any(evttags) and 'CREATE PROCEDURE' = any(evttags)
    ) then
        raise exception 'VERIFY FAIL: event trigger di hardening assente, disabilitato o non collegato al guard';
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
        raise exception 'VERIFY FAIL: event trigger di mutazione assente, disabilitato o non collegato al guard';
    end if;

    -- Behavioral guard tests. Each DDL is rolled back by the exception
    -- subtransaction whether the guard rejects or the verifier itself raises
    -- the FAIL exception.
    begin
        execute $weak_create$
            create or replace function public.archive_season(
                p_league uuid, p_season uuid, p_name text, p_standings jsonb
            ) returns void language plpgsql security definer set search_path = public
            as $weak_body$
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
            $weak_body$;
        $weak_create$;
        raise exception 'VERIFY FAIL: guard accepted a weak marker-comment function';
    exception when others then
        get stacked diagnostics v_state = returned_sqlstate;
        if SQLERRM like 'VERIFY FAIL:%' then raise; end if;
        if v_state <> 'P0001' or SQLERRM not like '%archive_season hardening guard%' then
            raise exception 'VERIFY FAIL: weak function rejection was not the expected guard error (sqlstate=%, error=%)',
                v_state, SQLERRM;
        end if;
    end;

    begin
        execute 'alter function public.archive_season(uuid,uuid,text,jsonb) set search_path = pg_catalog';
        raise exception 'VERIFY FAIL: guard accepted ALTER FUNCTION archive_season';
    exception when others then
        get stacked diagnostics v_state = returned_sqlstate;
        if SQLERRM like 'VERIFY FAIL:%' then raise; end if;
        if v_state <> 'P0001' or SQLERRM not like '%archive_season hardening guard%' then
            raise exception 'VERIFY FAIL: ALTER rejection was not the expected guard error (sqlstate=%, error=%)',
                v_state, SQLERRM;
        end if;
    end;

    begin
        execute 'drop function public.archive_season(uuid,uuid,text,jsonb)';
        raise exception 'VERIFY FAIL: guard accepted DROP FUNCTION archive_season';
    exception when others then
        get stacked diagnostics v_state = returned_sqlstate;
        if SQLERRM like 'VERIFY FAIL:%' then raise; end if;
        if v_state <> 'P0001' or SQLERRM not like '%archive_season hardening guard%' then
            raise exception 'VERIFY FAIL: DROP rejection was not the expected guard error (sqlstate=%, error=%)',
                v_state, SQLERRM;
        end if;
    end;

    begin
        execute 'alter routine public.archive_season(uuid,uuid,text,jsonb) set search_path = pg_catalog';
        raise exception 'VERIFY FAIL: guard accepted ALTER ROUTINE archive_season';
    exception when others then
        get stacked diagnostics v_state = returned_sqlstate;
        if SQLERRM like 'VERIFY FAIL:%' then raise; end if;
        if v_state <> 'P0001' or SQLERRM not like '%archive_season hardening guard%' then
            raise exception 'VERIFY FAIL: ALTER ROUTINE rejection was not the expected guard error (sqlstate=%, error=%)',
                v_state, SQLERRM;
        end if;
    end;

    begin
        execute 'drop routine public.archive_season(uuid,uuid,text,jsonb)';
        raise exception 'VERIFY FAIL: guard accepted DROP ROUTINE archive_season';
    exception when others then
        get stacked diagnostics v_state = returned_sqlstate;
        if SQLERRM like 'VERIFY FAIL:%' then raise; end if;
        if v_state <> 'P0001' or SQLERRM not like '%archive_season hardening guard%' then
            raise exception 'VERIFY FAIL: DROP ROUTINE rejection was not the expected guard error (sqlstate=%, error=%)',
                v_state, SQLERRM;
        end if;
    end;

    begin
        execute $weak_procedure$
            create procedure public.archive_season(
                p_league uuid, p_season uuid, p_name text, p_standings jsonb, p_extra text
            ) language sql as $$ select 1 $$;
        $weak_procedure$;
        raise exception 'VERIFY FAIL: guard accepted a same-named procedure';
    exception when others then
        get stacked diagnostics v_state = returned_sqlstate;
        if SQLERRM like 'VERIFY FAIL:%' then raise; end if;
        if v_state <> 'P0001' or SQLERRM not like '%archive_season hardening guard%' then
            raise exception 'VERIFY FAIL: procedure rejection was not the expected guard error (sqlstate=%, error=%)',
                v_state, SQLERRM;
        end if;
    end;

    begin
        execute $weak_overload$
            create function public.archive_season(
                p_league uuid, p_season uuid, p_name text, p_standings jsonb, p_extra text
            ) returns void language plpgsql security definer set search_path = public
            as $weak_body$ begin return; end; $weak_body$;
        $weak_overload$;
        raise exception 'VERIFY FAIL: guard accepted an unexpected archive_season overload';
    exception when others then
        get stacked diagnostics v_state = returned_sqlstate;
        if SQLERRM like 'VERIFY FAIL:%' then raise; end if;
        if v_state <> 'P0001' or SQLERRM not like '%archive_season hardening guard%' then
            raise exception 'VERIFY FAIL: overload rejection was not the expected guard error (sqlstate=%, error=%)',
                v_state, SQLERRM;
        end if;
    end;

    -- Exact FK existence and structure. A missing constraint is a failure,
    -- not a silent pass based only on the constraints that happen to exist.
    if not exists (
        select 1
        from pg_constraint
        where conname = 'season_snapshots_season_id_fkey'
          and conrelid = 'public.season_snapshots'::regclass
    ) then
        raise exception 'VERIFY FAIL: season_snapshots_season_id_fkey missing';
    end if;
    select conkey, confkey, confrelid, convalidated, confdeltype
    into v_conkey, v_confkey, v_confrelid, v_convalidated, v_confdeltype
    from pg_constraint
    where conname = 'season_snapshots_season_id_fkey'
      and conrelid = 'public.season_snapshots'::regclass;
    if v_conkey is distinct from array[
            (select attnum from pg_attribute
             where attrelid = 'public.season_snapshots'::regclass and attname = 'season_id')
        ]::smallint[]
       or v_confkey is distinct from array[
            (select attnum from pg_attribute
             where attrelid = 'public.seasons'::regclass and attname = 'id')
        ]::smallint[]
       or v_confrelid <> 'public.seasons'::regclass
       or v_confdeltype <> 'r'
       or not v_convalidated then
        raise exception 'VERIFY FAIL: season_snapshots_season_id_fkey structure is not validated RESTRICT(seasons.id)';
    end if;

    if not exists (
        select 1
        from pg_constraint
        where conname = 'season_snapshots_league_id_fkey'
          and conrelid = 'public.season_snapshots'::regclass
    ) then
        raise exception 'VERIFY FAIL: season_snapshots_league_id_fkey missing';
    end if;
    select conkey, confkey, confrelid, convalidated, confdeltype
    into v_conkey, v_confkey, v_confrelid, v_convalidated, v_confdeltype
    from pg_constraint
    where conname = 'season_snapshots_league_id_fkey'
      and conrelid = 'public.season_snapshots'::regclass;
    if v_conkey is distinct from array[
            (select attnum from pg_attribute
             where attrelid = 'public.season_snapshots'::regclass and attname = 'league_id')
        ]::smallint[]
       or v_confkey is distinct from array[
            (select attnum from pg_attribute
             where attrelid = 'public.leagues'::regclass and attname = 'id')
        ]::smallint[]
       or v_confrelid <> 'public.leagues'::regclass
       or v_confdeltype <> 'r'
       or not v_convalidated then
        raise exception 'VERIFY FAIL: season_snapshots_league_id_fkey structure is not validated RESTRICT(leagues.id)';
    end if;

    if not exists (
        select 1
        from pg_constraint
        where conname = 'season_snapshots_season_league_fk'
          and conrelid = 'public.season_snapshots'::regclass
    ) then
        raise exception 'VERIFY FAIL: composite season/league FK missing';
    end if;
    select conkey, confkey, confrelid, convalidated, confdeltype
    into v_conkey, v_confkey, v_confrelid, v_convalidated, v_confdeltype
    from pg_constraint
    where conname = 'season_snapshots_season_league_fk'
      and conrelid = 'public.season_snapshots'::regclass;
    if v_conkey is distinct from array[
            (select attnum from pg_attribute
             where attrelid = 'public.season_snapshots'::regclass and attname = 'season_id'),
            (select attnum from pg_attribute
             where attrelid = 'public.season_snapshots'::regclass and attname = 'league_id')
        ]::smallint[]
       or v_confkey is distinct from array[
            (select attnum from pg_attribute
             where attrelid = 'public.seasons'::regclass and attname = 'id'),
            (select attnum from pg_attribute
             where attrelid = 'public.seasons'::regclass and attname = 'league_id')
        ]::smallint[]
       or v_confrelid <> 'public.seasons'::regclass
       or v_confdeltype <> 'r'
       or not v_convalidated then
        raise exception 'VERIFY FAIL: composite season/league FK structure is not validated RESTRICT(seasons.id,seasons.league_id)';
    end if;

    if to_regclass('public.season_archive_repairs') is null then
        raise exception 'VERIFY FAIL: tabella audit season_archive_repairs assente';
    end if;
    if has_table_privilege('authenticated', 'public.season_archive_repairs', 'SELECT')
       or has_table_privilege('authenticated', 'public.season_archive_repairs', 'INSERT')
       or has_table_privilege('authenticated', 'public.season_archive_repairs', 'UPDATE')
       or has_table_privilege('authenticated', 'public.season_archive_repairs', 'DELETE')
       or has_table_privilege('authenticated', 'public.season_archive_repairs', 'TRUNCATE')
       or has_table_privilege('anon', 'public.season_archive_repairs', 'SELECT')
       or has_table_privilege('anon', 'public.season_archive_repairs', 'INSERT')
       or has_table_privilege('anon', 'public.season_archive_repairs', 'UPDATE')
       or has_table_privilege('anon', 'public.season_archive_repairs', 'DELETE')
       or has_table_privilege('anon', 'public.season_archive_repairs', 'TRUNCATE')
       or has_table_privilege('service_role', 'public.season_archive_repairs', 'SELECT')
       or has_table_privilege('service_role', 'public.season_archive_repairs', 'INSERT')
       or has_table_privilege('service_role', 'public.season_archive_repairs', 'UPDATE')
       or has_table_privilege('service_role', 'public.season_archive_repairs', 'DELETE')
       or has_table_privilege('service_role', 'public.season_archive_repairs', 'TRUNCATE') then
        raise exception 'VERIFY FAIL: tabella audit accessibile ai client';
    end if;

    if has_schema_privilege('authenticated', 'public', 'CREATE')
       or has_schema_privilege('anon', 'public', 'CREATE') then
        raise exception 'VERIFY FAIL: ruolo client puo'' creare oggetti in public';
    end if;

    if to_regclass('public.archive_season_deploy_lockout') is null then
        raise exception 'VERIFY FAIL: deployment lockout table missing';
    end if;
    if (select count(*) from public.archive_season_deploy_lockout) <> 1 then
        raise exception 'VERIFY FAIL: deployment lockout table must contain exactly one row';
    end if;
    select active
    into v_lockout_active
    from public.archive_season_deploy_lockout
    where singleton;
    if v_expect_lockout = 'true' then
        if not coalesce(v_lockout_active, false) then
            raise exception 'VERIFY FAIL: deployment lockout should be active';
        end if;
    elsif v_expect_lockout = 'false' then
        if coalesce(v_lockout_active, false) then
            raise exception 'VERIFY FAIL: deployment lockout should be inactive after finalization';
        end if;
    else
        raise exception 'VERIFY FAIL: invalid app.archive_verify_expect_lockout value: %', v_expect_lockout;
    end if;
    if has_table_privilege('authenticated', 'public.archive_season_deploy_lockout', 'SELECT') then
        raise exception 'VERIFY FAIL: deployment lockout table readable by client roles';
    end if;
    if has_table_privilege('anon', 'public.archive_season_deploy_lockout', 'INSERT')
       or has_table_privilege('anon', 'public.archive_season_deploy_lockout', 'UPDATE')
       or has_table_privilege('anon', 'public.archive_season_deploy_lockout', 'DELETE')
       or has_table_privilege('anon', 'public.archive_season_deploy_lockout', 'SELECT')
       or has_table_privilege('anon', 'public.archive_season_deploy_lockout', 'TRUNCATE')
       or has_table_privilege('service_role', 'public.archive_season_deploy_lockout', 'INSERT')
       or has_table_privilege('service_role', 'public.archive_season_deploy_lockout', 'UPDATE')
       or has_table_privilege('service_role', 'public.archive_season_deploy_lockout', 'DELETE')
       or has_table_privilege('service_role', 'public.archive_season_deploy_lockout', 'SELECT')
       or has_table_privilege('service_role', 'public.archive_season_deploy_lockout', 'TRUNCATE') then
        raise exception 'VERIFY FAIL: deployment lockout table readable by client roles';
    end if;
    if has_table_privilege('authenticated', 'public.archive_season_deploy_lockout', 'INSERT')
       or has_table_privilege('authenticated', 'public.archive_season_deploy_lockout', 'UPDATE')
       or has_table_privilege('authenticated', 'public.archive_season_deploy_lockout', 'DELETE')
       or has_table_privilege('authenticated', 'public.archive_season_deploy_lockout', 'TRUNCATE') then
        raise exception 'VERIFY FAIL: deployment lockout table writable by authenticated';
    end if;
    if has_table_privilege('anon', 'public.archive_season_canonical_definition', 'INSERT')
       or has_table_privilege('anon', 'public.archive_season_canonical_definition', 'UPDATE')
       or has_table_privilege('anon', 'public.archive_season_canonical_definition', 'DELETE')
       or has_table_privilege('anon', 'public.archive_season_canonical_definition', 'TRUNCATE')
       or has_table_privilege('service_role', 'public.archive_season_canonical_definition', 'INSERT')
       or has_table_privilege('service_role', 'public.archive_season_canonical_definition', 'UPDATE')
       or has_table_privilege('service_role', 'public.archive_season_canonical_definition', 'DELETE')
       or has_table_privilege('service_role', 'public.archive_season_canonical_definition', 'TRUNCATE') then
        raise exception 'VERIFY FAIL: canonical definition store writable by client roles';
    end if;
    if has_table_privilege('authenticated', 'public.archive_season_canonical_definition', 'INSERT')
       or has_table_privilege('authenticated', 'public.archive_season_canonical_definition', 'UPDATE')
       or has_table_privilege('authenticated', 'public.archive_season_canonical_definition', 'DELETE')
       or has_table_privilege('authenticated', 'public.archive_season_canonical_definition', 'TRUNCATE')
       or has_table_privilege('authenticated', 'public.archive_season_deploy_lockout', 'INSERT')
       or has_table_privilege('authenticated', 'public.archive_season_deploy_lockout', 'UPDATE')
       or has_table_privilege('authenticated', 'public.archive_season_deploy_lockout', 'DELETE')
       or has_table_privilege('authenticated', 'public.archive_season_deploy_lockout', 'TRUNCATE') then
        raise exception 'VERIFY FAIL: canonical definition store or lockout writable by authenticated';
    end if;
    if has_table_privilege('anon', 'public.archive_season_canonical_definition', 'REFERENCES')
       or has_table_privilege('anon', 'public.archive_season_canonical_definition', 'TRIGGER')
       or has_table_privilege('anon', 'public.archive_season_deploy_lockout', 'REFERENCES')
       or has_table_privilege('anon', 'public.archive_season_deploy_lockout', 'TRIGGER')
       or has_table_privilege('service_role', 'public.archive_season_canonical_definition', 'REFERENCES')
       or has_table_privilege('service_role', 'public.archive_season_canonical_definition', 'TRIGGER')
       or has_table_privilege('service_role', 'public.archive_season_deploy_lockout', 'REFERENCES')
       or has_table_privilege('service_role', 'public.archive_season_deploy_lockout', 'TRIGGER') then
        raise exception 'VERIFY FAIL: internal-table DDL privileges remain';
    end if;

    -- Se la cronologia Supabase e' presente, le migrazioni finali devono
    -- essere registrate. Questo intercetta un deploy CLI interrotto prima
    -- della chiusura (D2/D3). Nei test psql senza schema_migrations il check
    -- e' saltato; il deploy reale usa sempre la cronologia CLI.
    if exists (
        select 1 from pg_namespace where nspname = 'supabase_migrations'
    ) and to_regclass('supabase_migrations.schema_migrations') is not null then
        if not exists (
            select 1 from supabase_migrations.schema_migrations
            where version = '20261109200000'
        ) or not exists (
            select 1 from supabase_migrations.schema_migrations
            where version = '20261109210000'
        ) or not exists (
            select 1 from supabase_migrations.schema_migrations
            where version = '20261109220000'
        ) or not exists (
            select 1 from supabase_migrations.schema_migrations
            where version = '20261109230000'
        ) then
            raise exception 'VERIFY FAIL: cronologia migrazioni finale incompleta';
        end if;
    end if;

    raise notice 'VERIFY OK: archive_season hardened, ACL minime, FK RESTRICT, guard attivo';
end;
$verify$;
