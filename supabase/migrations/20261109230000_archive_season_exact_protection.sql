-- =============================================================================
-- FantaFormula 1 - 20261109230000_archive_season_exact_protection.sql
--
-- Final, history-independent archive_season security repair.
--
-- This migration is deliberately idempotent and may also be executed manually
-- through supabase/repair/archive_season_repair.sql. It does NOT depend on
-- migration-history state: if the final versions are already recorded but the
-- installed function is weak, this file still installs the reviewed canonical
-- body.
--
-- Security properties:
--   * preflight lockout table must be active before any DDL;
--   * unexpected overloads are rejected before the guard is changed;
--   * both DDL guards are removed only inside this controlled transaction;
--   * the canonical function is installed before any permission restoration;
--   * the canonical definition is stored for exact guard/verifier comparison;
--   * the guard rejects weak CREATE OR REPLACE, overload creation, and
--     ALTER/DROP of archive_season as function, routine or procedure;
--   * snapshot FKs are restored to validated RESTRICT;
--   * authenticated/service_role EXECUTE remains REVOKED at the end; the
--     deployment wrapper grants authenticated only after the lockout verifier
--     passes.
-- =============================================================================

set search_path = public, extensions;

-- 0) The external preflight must be committed and active. A raw `db push`
--    without the wrapper fails here before changing the archive RPC.
do $archive_preflight_gate$
begin
    if to_regclass('public.archive_season_deploy_lockout') is null then
        raise exception 'archive_season deployment preflight required: use supabase/scripts/deploy_archive_safe.sh';
    end if;
    if not exists (
        select 1
        from public.archive_season_deploy_lockout
        where singleton and active
    ) then
        raise exception 'archive_season deployment preflight required: use supabase/scripts/deploy_archive_safe.sh';
    end if;
end
$archive_preflight_gate$;

-- 1) Refuse unexpected overloads before touching the active guard.
do $archive_overload_gate$
declare
    v_expected oid := to_regprocedure('public.archive_season(uuid,uuid,text,jsonb)');
begin
    if exists (
        select 1
        from pg_proc p
        join pg_namespace n on n.oid = p.pronamespace
        where n.nspname = 'public'
          and p.proname = 'archive_season'
          and (v_expected is null or p.oid <> v_expected)
    ) then
        raise exception 'archive_season repair refused: unexpected overload in public';
    end if;
end
$archive_overload_gate$;

-- 2) Remove both guards only for the duration of this controlled repair.
drop event trigger if exists archive_season_hardening_guard;
drop event trigger if exists archive_season_mutation_guard;

-- 3) Reviewed canonical function body.
create or replace function public.archive_season(
    p_league uuid,
    p_season uuid,
    p_name text,
    p_standings jsonb
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user uuid := auth.uid();
    v_season_name text;
    v_active boolean;
    v_rows jsonb;
    v_row jsonb;
    v_row_user uuid;
    v_position numeric;
    v_race_wins numeric;
    v_total_points numeric;
    v_best_points numeric;
    v_count int;
begin
    if v_user is null then
        raise exception 'Devi essere autenticato per archiviare la stagione';
    end if;

    if p_league is null or p_season is null then
        raise exception 'Lega o stagione non valida';
    end if;

    -- Autorizzazione server-side: non ci si fida del client.
    if not public.is_league_admin(p_league) then
        raise exception 'Solo un amministratore puo archiviare la stagione';
    end if;

    -- Lock della stagione: due archival concorrenti vengono serializzati.
    select s.name, s.is_active
    into v_season_name, v_active
    from public.seasons s
    where s.id = p_season
      and s.league_id = p_league
    for update;

    if not found then
        raise exception 'La stagione non appartiene a questa lega';
    end if;

    if exists (
        select 1 from public.season_snapshots where season_id = p_season
    ) then
        raise exception 'La stagione è già stata archiviata';
    end if;

    if not v_active then
        raise exception 'La stagione non è attiva e non può essere archiviata';
    end if;

    if p_standings is null or jsonb_typeof(p_standings) is distinct from 'object' then
        raise exception 'La classifica archiviata non è valida';
    end if;
    if octet_length(p_standings::text) > 262144 then
        raise exception 'La classifica archiviata è troppo grande';
    end if;

    v_rows := p_standings -> 'rows';
    if v_rows is null or jsonb_typeof(v_rows) is distinct from 'array' then
        raise exception 'La classifica archiviata non è valida';
    end if;
    if jsonb_array_length(v_rows) > 200 then
        raise exception 'La classifica archiviata contiene troppe righe';
    end if;

    for v_row in select value from jsonb_array_elements(v_rows) loop
        if jsonb_typeof(v_row) is distinct from 'object'
           or jsonb_typeof(v_row -> 'userId') is distinct from 'string'
           or jsonb_typeof(v_row -> 'displayName') is distinct from 'string'
           or jsonb_typeof(v_row -> 'position') is distinct from 'number'
           or jsonb_typeof(v_row -> 'totalPoints') is distinct from 'number'
           or jsonb_typeof(v_row -> 'raceWins') is distinct from 'number'
           or jsonb_typeof(v_row -> 'bestRacePoints') is distinct from 'number' then
            raise exception 'La classifica archiviata non è valida';
        end if;

        begin
            v_row_user := (v_row ->> 'userId')::uuid;
            v_position := (v_row ->> 'position')::numeric;
            v_race_wins := (v_row ->> 'raceWins')::numeric;
            v_total_points := (v_row ->> 'totalPoints')::numeric;
            v_best_points := (v_row ->> 'bestRacePoints')::numeric;
        exception when others then
            raise exception 'La classifica archiviata contiene valori non validi';
        end;

        if v_row_user is null
           or v_position <> trunc(v_position)
           or v_position < 1
           or v_race_wins <> trunc(v_race_wins)
           or v_race_wins < 0
           or v_total_points < 0
           or v_best_points < 0
           or btrim(v_row ->> 'displayName') = '' then
            raise exception 'La classifica archiviata non è valida';
        end if;

        -- Un utente corrente della lega e' sempre valido. Un ex-membro resta
        -- valido se ha almeno un pronostico in una gara della stagione
        -- archiviata: e' il caso "Ex giocatore" prodotto da LeagueStandings.
        if not exists (
            select 1
            from public.league_members lm
            where lm.league_id = p_league
              and lm.user_id = v_row_user
        ) and not exists (
            select 1
            from public.predictions p
            join public.races r on r.id = p.race_id
            where p.user_id = v_row_user
              and p.league_id = p_league
              and r.season_id = p_season
              and r.league_id = p_league
        ) then
            raise exception 'La classifica archiviata contiene un utente che non appartiene alla lega o alla stagione';
        end if;
    end loop;

    -- Integrita' della classifica: niente utenti o posizioni duplicati e
    -- posizioni contigue 1..N. I punteggi restano client-side (il motore
    -- Kotlin non e' replicato in SQL), ma la forma della classifica e' qui.
    if jsonb_array_length(v_rows) > 0 then
        -- Il confronto usa il tipo uuid: lo stesso utente scritto con case
        -- diverso (maiuscole/minuscole) resta un duplicato.
        if (
            select count(*)
            from jsonb_array_elements(v_rows)
        ) <> (
            select count(distinct (value ->> 'userId')::uuid)
            from jsonb_array_elements(v_rows)
        ) then
            raise exception 'La classifica archiviata contiene userId duplicati';
        end if;

        if (
            select count(*)
            from jsonb_array_elements(v_rows)
        ) <> (
            select count(distinct (value ->> 'position')::int)
            from jsonb_array_elements(v_rows)
        ) then
            raise exception 'La classifica archiviata contiene posizioni duplicate';
        end if;

        if exists (
            select 1
            from generate_series(1, jsonb_array_length(v_rows)) as expected(position)
            where not exists (
                select 1
                from jsonb_array_elements(v_rows) as actual
                where (actual.value ->> 'position')::int = expected.position
            )
        ) then
            raise exception 'La classifica archiviata ha posizioni non contigue';
        end if;
    end if;

    insert into public.season_snapshots (
        season_id,
        league_id,
        season_name,
        standings,
        archived_by
    ) values (
        p_season,
        p_league,
        coalesce(nullif(btrim(v_season_name), ''), 'Stagione'),
        p_standings,
        v_user
    );

    update public.seasons
    set is_active = false
    where id = p_season
      and league_id = p_league;

    get diagnostics v_count = row_count;
    if v_count <> 1 then
        raise exception 'Archiviazione non completata';
    end if;
end;
$$;

comment on function public.archive_season(uuid, uuid, text, jsonb) is
    'Archivia una stagione in modo atomico e hardened: verifica admin, lega, stagione attiva e standings, poi snapshot + disattivazione nella stessa transazione.';

-- 4) Exact canonical definition store. The guard compares the entire
--    pg_get_functiondef output, not comments or marker substrings.
create table if not exists public.archive_season_canonical_definition (
    singleton boolean primary key default true check (singleton),
    definition text not null,
    definition_sha256 text not null,
    updated_at timestamptz not null default now()
);

revoke all on public.archive_season_canonical_definition
    from public, anon, authenticated, service_role;

delete from public.archive_season_canonical_definition;
insert into public.archive_season_canonical_definition (
    singleton,
    definition,
    definition_sha256
) values (
    true,
    replace(replace(pg_get_functiondef('public.archive_season(uuid,uuid,text,jsonb)'::regprocedure), E'\r\n', E'\n'), E'\r', E'\n'),
    encode(
        digest(
            replace(replace(pg_get_functiondef('public.archive_season(uuid,uuid,text,jsonb)'::regprocedure), E'\r\n', E'\n'), E'\r', E'\n'),
            'sha256'
        ),
        'hex'
    )
);

comment on table public.archive_season_canonical_definition is
    'Reviewed canonical pg_get_functiondef for archive_season. Internal: no client access.';

-- 5) Exact DDL guard. CREATE OR REPLACE is accepted only when the complete
--    function definition equals the stored canonical definition. ALTER/DROP
--    and overload creation are rejected outright.
create or replace function public.enforce_archive_season_hardening()
returns event_trigger
language plpgsql
security definer
set search_path = public
as $guard$
declare
    v_rec record;
    v_expected oid := to_regprocedure('public.archive_season(uuid,uuid,text,jsonb)');
    v_expected_def text;
    v_actual_def text;
begin
    if v_expected is null then
        raise exception 'archive_season hardening guard: canonical function missing';
    end if;

    select definition
    into v_expected_def
    from public.archive_season_canonical_definition
    where singleton;

    if v_expected_def is null then
        raise exception 'archive_season hardening guard: canonical definition missing';
    end if;

    if TG_TAG in ('ALTER FUNCTION', 'DROP FUNCTION', 'ALTER ROUTINE', 'DROP ROUTINE', 'ALTER PROCEDURE', 'DROP PROCEDURE') then
        for v_rec in select * from pg_event_trigger_ddl_commands() loop
            if v_rec.object_type in ('function', 'procedure')
               and v_rec.object_identity like 'public.archive_season(%' then
                raise exception 'archive_season hardening guard: ALTER/DROP of archive_season is not allowed; use the controlled repair procedure';
            end if;
        end loop;
        return;
    end if;

    for v_rec in select * from pg_event_trigger_ddl_commands() loop
        if v_rec.object_type in ('function', 'procedure')
           and v_rec.object_identity like 'public.archive_season(%' then
            if v_rec.objid is distinct from v_expected then
                raise exception 'archive_season hardening guard: unexpected function signature: %',
                    v_rec.object_identity;
            end if;

            v_actual_def := replace(replace(pg_get_functiondef(v_rec.objid), E'\r\n', E'\n'), E'\r', E'\n');
            if v_actual_def is distinct from v_expected_def then
                raise exception 'archive_season hardening guard: function definition differs from the reviewed canonical definition';
            end if;
        end if;
    end loop;
end;
$guard$;

comment on function public.enforce_archive_season_hardening() is
    'Event trigger: exact canonical definition required for archive_season; ALTER/DROP/overload rejected.';

revoke all on function public.enforce_archive_season_hardening()
    from public, anon, authenticated, service_role;

drop event trigger if exists archive_season_hardening_guard;
create event trigger archive_season_hardening_guard
    on ddl_command_end
    when tag in ('CREATE FUNCTION', 'CREATE PROCEDURE')
    execute function public.enforce_archive_season_hardening();

drop event trigger if exists archive_season_mutation_guard;
create event trigger archive_season_mutation_guard
    on ddl_command_end
    when tag in ('ALTER FUNCTION', 'DROP FUNCTION', 'ALTER ROUTINE', 'DROP ROUTINE', 'ALTER PROCEDURE', 'DROP PROCEDURE')
    execute function public.enforce_archive_season_hardening();

-- 6) Restore snapshot FK structure exactly, including validation.
alter table public.season_snapshots
    drop constraint if exists season_snapshots_season_id_fkey;
alter table public.season_snapshots
    add constraint season_snapshots_season_id_fkey
        foreign key (season_id) references public.seasons (id)
        on delete restrict
        not valid;
alter table public.season_snapshots
    validate constraint season_snapshots_season_id_fkey;

alter table public.season_snapshots
    drop constraint if exists season_snapshots_league_id_fkey;
alter table public.season_snapshots
    add constraint season_snapshots_league_id_fkey
        foreign key (league_id) references public.leagues (id)
        on delete restrict
        not valid;
alter table public.season_snapshots
    validate constraint season_snapshots_league_id_fkey;

alter table public.season_snapshots
    drop constraint if exists season_snapshots_season_league_fk;
alter table public.season_snapshots
    add constraint season_snapshots_season_league_fk
        foreign key (season_id, league_id)
        references public.seasons (id, league_id)
        on delete restrict
        not valid;
alter table public.season_snapshots
    validate constraint season_snapshots_season_league_fk;

-- 7) Restore table ACLs and revoke every client path to the archive RPC.
do $archive_acl_gate$
begin
    if to_regprocedure('public.harden_season_archive_acl()') is null then
        raise exception 'archive_season repair refused: harden_season_archive_acl() missing';
    end if;
end
$archive_acl_gate$;

select public.harden_season_archive_acl();

-- 6b) Reassert the non-archive security fixes that live in 0014. This is
-- deliberately independent of migration history: a database can have 0014
-- recorded while its policy/function SQL was never executed.
create or replace function public.prediction_exists(
    p_race uuid,
    p_user uuid
)
returns boolean
language sql
stable
security definer
set search_path = public
as $prediction_exists$
    -- Le policy passano auth.uid(); un client esterno puo' chiedere solo
    -- il proprio stato. Per un altro utente restituisce false, senza
    -- rivelare se la riga esiste.
    select exists (
        select 1
        from public.predictions
        where race_id = p_race
          and user_id = p_user
          and p_user = auth.uid()
    );
$prediction_exists$;

comment on function public.prediction_exists(uuid, uuid) is
    'True se l''utente indicato coincide con auth.uid() e ha un pronostico per la gara.';

revoke execute on function public.prediction_exists(uuid, uuid)
    from public, anon, service_role;
grant execute on function public.prediction_exists(uuid, uuid) to authenticated;

drop policy if exists leagues_delete_admin on public.leagues;
create policy leagues_delete_admin on public.leagues
    for delete to authenticated
    using (public.is_league_admin(id));

-- Snapshot history is private even to service_role/anon: service_role may
-- bypass RLS and therefore must not receive access through default grants.
revoke all on public.season_snapshots
    from public, anon, authenticated, service_role;
grant select on public.season_snapshots to authenticated;

revoke all on public.season_archive_repairs
    from public, anon, authenticated, service_role;

revoke all on public.archive_season_canonical_definition
    from public, anon, authenticated, service_role;
revoke all on public.archive_season_deploy_lockout
    from public, anon, authenticated, service_role;
revoke all on function public.enforce_archive_season_hardening()
    from public, anon, authenticated, service_role;
revoke all on function public.harden_season_archive_acl()
    from public, anon, authenticated, service_role;
revoke all on function public.season_snapshot_shape_is_valid(jsonb)
    from public, anon, authenticated, service_role;
revoke all on function public.season_snapshot_participants_are_eligible(uuid,uuid,jsonb)
    from public, anon, authenticated, service_role;
revoke execute on function public.complete_partial_season_archive(uuid,uuid)
    from public, anon, service_role;

revoke execute on function public.archive_season(uuid,uuid,text,jsonb)
    from public, anon, authenticated, service_role;

-- Deliberately NO grant to authenticated here. The wrapper verifies the
-- locked-down state first, then runs finalize_archive_season_deployment.sql.

-- 8) Assert the repaired state before committing.
do $archive_repair_assert$
declare
    v_oid oid := to_regprocedure('public.archive_season(uuid,uuid,text,jsonb)');
    v_def text;
    v_expected text;
    v_expected_sha text;
    v_owner name;
    v_table_owner name;
    v_config text[];
    v_definer boolean;
    v_guard_oid oid := to_regprocedure('public.enforce_archive_season_hardening()');
    v_prediction_oid oid := to_regprocedure('public.prediction_exists(uuid,uuid)');
    v_prediction_def text;
    v_prediction_sha text;
    v_policy_expr text;
begin
    if v_oid is null then
        raise exception 'REPAIR FAIL: canonical archive_season missing';
    end if;

    select p.prosecdef, p.proconfig
    into v_definer, v_config
    from pg_proc p
    where p.oid = v_oid;

    if not coalesce(v_definer, false) then
        raise exception 'REPAIR FAIL: archive_season is not SECURITY DEFINER';
    end if;
    if v_config is null or not (v_config @> array['search_path=public']) then
        raise exception 'REPAIR FAIL: archive_season search_path is not public';
    end if;

    select definition, definition_sha256
    into v_expected, v_expected_sha
    from public.archive_season_canonical_definition
    where singleton;

    v_def := replace(replace(pg_get_functiondef(v_oid), E'\r\n', E'\n'), E'\r', E'\n');
    if v_expected is null or v_def is distinct from v_expected then
        raise exception 'REPAIR FAIL: installed function differs from canonical definition';
    end if;
    if encode(digest(v_def, 'sha256'), 'hex') is distinct from v_expected_sha then
        raise exception 'REPAIR FAIL: canonical SHA-256 mismatch';
    end if;

    select r.rolname
    into v_owner
    from pg_proc p
    join pg_roles r on r.oid = p.proowner
    where p.oid = v_oid;

    select r.rolname
    into v_table_owner
    from pg_class c
    join pg_roles r on r.oid = c.relowner
    where c.oid = 'public.season_snapshots'::regclass;

    if v_owner is distinct from v_table_owner then
        raise exception 'REPAIR FAIL: archive_season owner (%) differs from season_snapshots owner (%)',
            v_owner, v_table_owner;
    end if;

    if exists (
        select 1
        from pg_proc p
        join pg_namespace n on n.oid = p.pronamespace
        where n.nspname = 'public'
          and p.proname = 'archive_season'
          and p.oid <> v_oid
    ) then
        raise exception 'REPAIR FAIL: unexpected archive_season overload remains';
    end if;

    if has_function_privilege('authenticated', v_oid, 'EXECUTE')
       or has_function_privilege('anon', v_oid, 'EXECUTE')
       or has_function_privilege('service_role', v_oid, 'EXECUTE') then
        raise exception 'REPAIR FAIL: client role still has EXECUTE on archive_season';
    end if;

    if has_table_privilege('authenticated', 'public.archive_season_canonical_definition', 'SELECT')
       or has_table_privilege('anon', 'public.archive_season_canonical_definition', 'SELECT')
       or has_table_privilege('service_role', 'public.archive_season_canonical_definition', 'SELECT')
       or has_table_privilege('authenticated', 'public.archive_season_deploy_lockout', 'SELECT')
       or has_table_privilege('anon', 'public.archive_season_deploy_lockout', 'SELECT')
       or has_table_privilege('service_role', 'public.archive_season_deploy_lockout', 'SELECT') then
        raise exception 'REPAIR FAIL: internal deployment tables are readable by client roles';
    end if;

    if has_table_privilege('anon', 'public.season_snapshots', 'SELECT')
       or has_table_privilege('service_role', 'public.season_snapshots', 'SELECT')
       or has_table_privilege('anon', 'public.season_snapshots', 'INSERT')
       or has_table_privilege('service_role', 'public.season_snapshots', 'INSERT')
       or has_table_privilege('anon', 'public.season_snapshots', 'UPDATE')
       or has_table_privilege('service_role', 'public.season_snapshots', 'UPDATE')
       or has_table_privilege('anon', 'public.season_snapshots', 'DELETE')
       or has_table_privilege('service_role', 'public.season_snapshots', 'DELETE')
       or has_table_privilege('anon', 'public.season_snapshots', 'TRUNCATE')
       or has_table_privilege('service_role', 'public.season_snapshots', 'TRUNCATE') then
        raise exception 'REPAIR FAIL: snapshot history accessible to anon/service_role';
    end if;
    if not has_table_privilege('authenticated', 'public.season_snapshots', 'SELECT') then
        raise exception 'REPAIR FAIL: authenticated snapshot SELECT missing';
    end if;

    if v_prediction_oid is null then
        raise exception 'REPAIR FAIL: prediction_exists missing';
    end if;
    select p.prosecdef, p.proconfig
    into v_definer, v_config
    from pg_proc p
    where p.oid = v_prediction_oid;
    if not coalesce(v_definer, false)
       or v_config is null
       or not (v_config @> array['search_path=public']) then
        raise exception 'REPAIR FAIL: prediction_exists security properties invalid';
    end if;
    v_prediction_def := replace(
        replace(pg_get_functiondef(v_prediction_oid), E'\r\n', E'\n'),
        E'\r', E'\n'
    );
    v_prediction_sha := encode(digest(v_prediction_def, 'sha256'), 'hex');
    if v_prediction_sha <> 'f27a51970636ecbb7cf0dcb3134545f22e0d8acfafaf9bf5b6591520e4d98189' then
        raise exception 'REPAIR FAIL: prediction_exists canonical SHA-256 mismatch';
    end if;
    if not has_function_privilege('authenticated', v_prediction_oid, 'EXECUTE')
       or has_function_privilege('anon', v_prediction_oid, 'EXECUTE')
       or has_function_privilege('service_role', v_prediction_oid, 'EXECUTE') then
        raise exception 'REPAIR FAIL: prediction_exists ACL invalid';
    end if;
    if exists (
        select 1
        from pg_proc p
        cross join lateral aclexplode(
            coalesce(p.proacl, acldefault('f', p.proowner))
        ) a
        where p.oid in (v_oid, v_prediction_oid)
          and a.privilege_type = 'EXECUTE'
          and a.grantee <> p.proowner
          and a.grantee <> 0
          and a.grantee not in (
              select r.oid from pg_roles r
              where r.rolname in ('anon', 'authenticated', 'service_role')
          )
    ) then
        raise exception 'REPAIR FAIL: unexpected custom grantee has EXECUTE on archive_season/prediction_exists';
    end if;

    select pg_get_expr(polqual, polrelid)
    into v_policy_expr
    from pg_policy
    where polname = 'leagues_delete_admin'
      and polrelid = 'public.leagues'::regclass;
    if v_policy_expr is distinct from 'is_league_admin(id)' then
        raise exception 'REPAIR FAIL: leagues_delete_admin policy is not admin-only';
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
        raise exception 'REPAIR FAIL: create-function/procedure guard missing or disabled';
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
        raise exception 'REPAIR FAIL: mutation guard missing or disabled';
    end if;

    raise notice 'REPAIR OK: canonical archive_season installed, guards active, EXECUTE revoked';
end;
$archive_repair_assert$;