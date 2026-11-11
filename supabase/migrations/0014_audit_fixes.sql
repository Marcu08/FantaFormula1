-- =============================================================================
-- FantaFormula 1 — migrazione 0014_audit_fixes.sql
--
-- Correzioni additive emerse dall'audit:
--   1) leagues_delete_admin: solo un admin attuale puo' cancellare la lega.
--      Il creatore retrocesso o uscito non deve poter distruggere i dati.
--   2) prediction_exists: la funzione e' SECURITY DEFINER e veniva usata
--      dalle policy; accettando un utente arbitrario permetteva a un membro
--      di scoprire se un altro aveva inviato un pronostico. Ora risponde
--      solo per auth.uid().
--   3) season_snapshots: FK composita (season_id, league_id) per impedire
--      snapshot che mescolano stagioni e leghe diverse.
--   4) archive_season: snapshot + disattivazione stagione in un'unica
--      transazione hardened. Questa versione sostituisce la vecchia
--      implementazione debole quando la migrazione e' ancora pendente.
--
-- Fail-closed: la revoca EXECUTE avviene prima di ogni altro DDL. Se la
-- migrazione si interrompe fuori transazione, authenticated non puo' eseguire
-- una vecchia archive_season rimasta da uno stato precedente. Il grant viene
-- ripristinato solo dopo la CREATE OR REPLACE hardened.
--
-- Idempotente. NON e' stata applicata a un database live durante l'audit.
-- =============================================================================

-- The external preflight is committed by the deployment wrapper before this
-- migration starts. Do not replace this gate with a transaction-local REVOKE:
-- the CLI rolls a failed migration back.
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

do $archive_execute_guard$
begin
    if to_regprocedure('public.archive_season(uuid,uuid,text,jsonb)') is not null then
        execute 'revoke execute on function public.archive_season(uuid,uuid,text,jsonb) from public, anon, authenticated';
    end if;
end
$archive_execute_guard$;

-- 1) Cancellazione lega: solo admin attuale --------------------------------
drop policy if exists leagues_delete_admin on public.leagues;
create policy leagues_delete_admin on public.leagues
    for delete to authenticated
    using (public.is_league_admin(id));

-- 2) prediction_exists: nessuna scoperta di pronostici altrui --------------
create or replace function public.prediction_exists(
    p_race uuid,
    p_user uuid
)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
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
$$;

comment on function public.prediction_exists(uuid, uuid) is
    'True se l''utente indicato coincide con auth.uid() e ha un pronostico per la gara.';

revoke execute on function public.prediction_exists(uuid, uuid) from public, anon;
grant execute on function public.prediction_exists(uuid, uuid) to authenticated;

-- 3) Coerenza stagione/lega negli snapshot ---------------------------------
-- La unique composita (id, league_id) e' gia' creata dalla 0002 e la FK
-- races_season_league_fk dipende dal suo indice: NON va droppata/ricreata.
-- La creiamo solo se manca (installazione parziale), senza toccare quella
-- esistente.
do $guard$
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
end
$guard$;

-- Non sostituire una FK gia' presente: su un database che ha gia' applicato
-- la 20261109200000 la FK composita esiste (cascade); su un database con la
-- 20261109210000 esiste in versione RESTRICT e non deve essere indebolita.
do $snapshot_fk_guard$
begin
    if not exists (
        select 1
        from pg_constraint
        where conname = 'season_snapshots_season_league_fk'
          and conrelid = 'public.season_snapshots'::regclass
    ) then
        alter table public.season_snapshots
            add constraint season_snapshots_season_league_fk
                foreign key (season_id, league_id)
                references public.seasons (id, league_id)
                on delete cascade
                not valid;
    end if;
end
$snapshot_fk_guard$;
-- NOT VALID: le righe storiche non vengono scandite qui, ma ogni nuova
-- riga e' controllata. La 20261109200000/20261109210000 completa e valida
-- la FK nei rispettivi passaggi.

-- 4) Archiviazione atomica hardened -----------------------------------------
-- Questa migrazione non deve mai installare la vecchia implementazione
-- debole quando esiste gia' una funzione hardened (D2/D3). Il corpo e' lo
-- stesso della 20261109200000; la 20261109210000 resta la chiusura finale.
--
-- La revoca EXECUTE e' gia' stata eseguita in testa alla migrazione; il grant
-- finale viene eseguito solo dopo la CREATE OR REPLACE hardened.
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

revoke execute on function public.archive_season(uuid, uuid, text, jsonb)
    from public, anon, authenticated, service_role;

-- The deployment wrapper restores authenticated only after the locked-down
-- verifier and finalize step. Do not grant EXECUTE in this migration.