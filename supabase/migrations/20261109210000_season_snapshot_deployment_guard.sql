-- =============================================================================
-- FantaFormula 1 - final season snapshot deployment guard
--
-- Questa migrazione e' la chiusura fail-closed della remediation:
--   1) riasserisce la versione hardened di archive_season;
--   2) applica una revoca completa delle ACL (TRUNCATE/REFERENCES/TRIGGER/
--      MAINTAIN inclusi) e concede solo il necessario;
--   3) cambia le FK degli snapshot da ON DELETE CASCADE a ON DELETE RESTRICT,
--      cosi' la cancellazione di season/league non puo' eliminare lo storico;
--   4) installa un DDL event trigger che rifiuta qualunque CREATE OR REPLACE
--      FUNCTION public.archive_season(...) priva dei marcatori di hardening.
--
-- La 20261109200000 resta la prima remediation; questa versione e' obbligatoria
-- dopo di essa. Il verificatore post-deploy e' in
-- supabase/tests/verify_archive_season_hardening.sql.
--
-- Richiede il ruolo di migrazione owner delle tabelle/funzioni e superuser per
-- CREATE EVENT TRIGGER (Supabase: postgres).
--
-- Idempotente. NON e' stata applicata al progetto Supabase live.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) Riasserzione della funzione hardened
-- -----------------------------------------------------------------------------
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
    'Archivia una stagione in modo atomico: verifica admin, lega, stagione attiva e standings, poi snapshot + disattivazione nella stessa transazione.';

revoke execute on function public.archive_season(uuid, uuid, text, jsonb) from public, anon;
grant execute on function public.archive_season(uuid, uuid, text, jsonb) to authenticated;

-- -----------------------------------------------------------------------------
-- 2) ACL complete e ripetibili
--
-- La funzione e' volutamente SECURITY INVOKER: la esegue solo il ruolo owner
-- (postgres) durante la migrazione o una riparazione. Non e' eseguibile dai
-- ruoli client. Revoca TUTTO e ri-concede solo SELECT/INSERT/DELETE e le
-- colonne UPDATE necessarie: TRUNCATE/REFERENCES/TRIGGER/MAINTAIN spariscono
-- anche se il progetto Supabase aveva i grant di default ALL.
-- -----------------------------------------------------------------------------
create or replace function public.harden_season_archive_acl()
returns void
language plpgsql
security invoker
set search_path = public
as $acl$
begin
    revoke all on public.season_snapshots from public, anon, authenticated;
    grant select on public.season_snapshots to authenticated;

    revoke all on public.seasons from public, anon, authenticated;
    grant select, insert, delete on public.seasons to authenticated;
    grant update (name, start_date, end_date) on public.seasons to authenticated;

    revoke all on public.leagues from public, anon, authenticated;
    grant select, insert, update, delete on public.leagues to authenticated;
end;
$acl$;

comment on function public.harden_season_archive_acl() is
    'Applica le ACL minime di seasons/season_snapshots/leagues. Eseguibile solo dal ruolo owner.';

revoke all on function public.harden_season_archive_acl() from public, anon, authenticated;

select public.harden_season_archive_acl();

-- -----------------------------------------------------------------------------
-- 3) Gli snapshot non seguono la cancellazione di season/league
--
-- La FK composita resta necessaria per l'integrita' (season_id, league_id).
-- Tutte le FK che collegano uno snapshot a un parent sono RESTRICT: eliminare
-- una stagione o una lega con storico richiede un processo esplicito e
-- autorizzato, non una cascata silenziosa.
-- -----------------------------------------------------------------------------
do $unique_guard$
begin
    if not exists (
        select 1
        from pg_constraint
        where conname = 'seasons_id_league_unique'
          and conrelid = 'public.seasons'::regclass
    ) then
        alter table public.seasons
            add constraint seasons_id_league_unique unique (id, league_id);
    end if;
end;
$unique_guard$;

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

-- -----------------------------------------------------------------------------
-- 4) Guardia DDL fail-closed
--
-- 0014_audit_fixes.sql (development) contiene una vecchia CREATE OR REPLACE
-- FUNCTION public.archive_season(...). Se viene applicata dopo questa
-- migrazione, il guard rifiuta la sostituzione e fa fallire la migrazione:
-- la funzione hardened resta attiva e il deploy si ferma invece di degradare
-- silenziosamente la sicurezza.
-- -----------------------------------------------------------------------------
create or replace function public.enforce_archive_season_hardening()
returns event_trigger
language plpgsql
security definer
set search_path = public
as $guard$
declare
    v_rec record;
    v_def text;
    v_marker text;
    v_expected oid := to_regprocedure('public.archive_season(uuid,uuid,text,jsonb)');
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
begin
    if v_expected is null then
        return;
    end if;

    for v_rec in select * from pg_event_trigger_ddl_commands() loop
        if v_rec.object_type = 'function' and v_rec.objid = v_expected then
            v_def := pg_get_functiondef(v_rec.objid);

            foreach v_marker in array v_markers loop
                if position(v_marker in v_def) = 0 then
                    raise exception
                        'archive_season hardening guard: sostituzione rifiutata, manca il marcatore "%". Usare il processo di riparazione in supabase/MIGRATIONS.md e verificare con il post-deploy script.',
                        v_marker;
                end if;
            end loop;

            -- La vecchia 0014 usa p_name per il nome dello snapshot: il nome
            -- deve invece essere sempre quello autorevole del database.
            if position('btrim(p_name)' in v_def) > 0 then
                raise exception
                    'archive_season hardening guard: sostituzione rifiutata, il nome snapshot non e'' controllato dal database. Usare il processo di riparazione in supabase/MIGRATIONS.md.';
            end if;
        end if;
    end loop;
end;
$guard$;

comment on function public.enforce_archive_season_hardening() is
    'Event trigger di sicurezza: impedisce che una migrazione piu vecchia sostituisca archive_season con una versione priva dei controlli hardened.';

revoke all on function public.enforce_archive_season_hardening() from public, anon, authenticated;

drop event trigger if exists archive_season_hardening_guard;
create event trigger archive_season_hardening_guard
    on ddl_command_end
    when tag in ('CREATE FUNCTION')
    execute function public.enforce_archive_season_hardening();

comment on event trigger archive_season_hardening_guard is
    'Fail-closed: rifiuta CREATE OR REPLACE FUNCTION archive_season con corpo non hardened.';