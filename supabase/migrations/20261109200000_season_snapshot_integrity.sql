-- =============================================================================
-- FantaFormula 1 - season snapshot integrity and atomic archival
--
-- Corregge quattro problemi dell'audit:
--   1) season_snapshots poteva riferire una stagione di un'altra lega:
--      la FK su season_id controllava solo l'esistenza della stagione, non
--      la coppia (season_id, league_id).
--   2) l'archiviazione client faceva INSERT dello snapshot e UPDATE della
--      stagione in due richieste separate: un errore a meta' lasciava uno
--      stato parziale.
--   3) gli utenti autenticati potevano creare/modificare snapshot con
--      INSERT/UPDATE/DELETE diretti (P1).
--   4) is_active poteva essere cambiato con un UPDATE diretto su seasons (P2).
--
-- La migrazione:
--   - verifica i dati esistenti e fallisce esplicitamente se trova snapshot
--     con league_id diverso dalla stagione (nessuna cancellazione silenziosa);
--   - aggiunge/valida la FK composita (season_id, league_id) -> seasons;
--   - definisce public.archive_season(...) come unica RPC transazionale,
--     con lock della stagione, controlli server-side e grant minimi;
--   - valida la forma della classifica: utenti/posizioni unici, posizioni
--     contigue 1..N, valori non negativi e partecipazione alla stagione (P5);
--   - revoca INSERT/UPDATE/DELETE diretti su season_snapshots e UPDATE su
--     seasons.is_active, lasciando alla RPC SECURITY DEFINER la scrittura (P1/P2).
--
-- Ordinamento (P4): la versione 20261109200000 e' successiva sia alle
-- migrazioni main 0001-0012 sia alle development 0013-0020 sia alla
-- precedente 20261109120000. L'hardening viene quindi sempre applicato per
-- ultimo e non puo' essere sostituito da 0014_audit_fixes.sql.
--
-- Riparazione per ambienti che avessero gia' registrato la vecchia versione
-- 20261109120000 (non deployed da questo branch):
--   supabase migration repair --status reverted 20261109120000
--   supabase db push
-- La procedura e' stata verificata con Supabase CLI 2.114.0.
--
-- Idempotente. NON applicata automaticamente al progetto Supabase live.
-- =============================================================================

-- The external preflight is committed by the deployment wrapper before this
-- migration starts. Do not replace this gate with a transaction-local REVOKE.
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

-- -----------------------------------------------------------------------------
-- 1) Controllo dati esistenti
-- -----------------------------------------------------------------------------
do $integrity$
declare
    v_bad_count int;
    v_bad_ids text;
    v_partial_count int;
    v_partial_ids text;
begin
    select count(*), string_agg(s.season_id::text, ', ' order by s.season_id)
    into v_bad_count, v_bad_ids
    from public.season_snapshots s
    join public.seasons se on se.id = s.season_id
    where s.league_id <> se.league_id;

    if v_bad_count > 0 then
        raise exception
            'Trovati % snapshot con league_id diverso dalla stagione (season_id: %). Bonifica manuale necessaria prima della migrazione.',
            v_bad_count,
            v_bad_ids;
    end if;

    -- Uno snapshot con stagione ancora attiva e' lo stato parziale lasciato
    -- dal vecchio flusso a due chiamate. Non lo modifichiamo automaticamente:
    -- va verificato e completato a mano.
    select count(*), string_agg(s.season_id::text, ', ' order by s.season_id)
    into v_partial_count, v_partial_ids
    from public.season_snapshots s
    join public.seasons se on se.id = s.season_id
    where se.is_active;

    if v_partial_count > 0 then
        raise warning
            'Trovati % snapshot con stagione ancora attiva (season_id: %). Verificare manualmente: lo snapshot esiste ma is_active e'' ancora true.',
            v_partial_count,
            v_partial_ids;
    end if;
end;
$integrity$;

-- -----------------------------------------------------------------------------
-- 2) Chiave unica composita su seasons (se manca)
--    La 0002 la crea gia' su main; il guard copre installazioni parziali.
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

-- -----------------------------------------------------------------------------
-- 3) FK composita: lo snapshot deve usare la stessa lega della stagione
--    La FK singola su season_id resta: la composita la rafforza.
-- -----------------------------------------------------------------------------
alter table public.season_snapshots
    drop constraint if exists season_snapshots_season_league_fk;

alter table public.season_snapshots
    add constraint season_snapshots_season_league_fk
        foreign key (season_id, league_id)
        references public.seasons (id, league_id)
        on delete cascade
        not valid;

-- Il controllo sopra ha gia' verificato le righe esistenti; la validazione
-- blocca definitivamente eventuali dati incoerenti.
alter table public.season_snapshots
    validate constraint season_snapshots_season_league_fk;

-- -----------------------------------------------------------------------------
-- 4) Archiviazione atomica
--
-- p_league e p_season identificano la stagione; p_name e' mantenuto per
-- compatibilita' con il client ma il nome autorevole e' quello del DB.
-- p_standings e' il JSON prodotto dal client (SeasonStandingsSnapshot):
-- non e' possibile ricalcolare server-side il motore Kotlin, quindi si
-- valida struttura e appartenenza degli utenti alla lega.
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

revoke execute on function public.archive_season(uuid, uuid, text, jsonb)
    from public, anon, authenticated, service_role;

-- authenticated is restored only by the canonical deployment wrapper after the
-- locked-down verifier, never by this migration.

-- -----------------------------------------------------------------------------
-- 5) Chiusura dei percorsi legacy
--
-- Gli snapshot sono immutabili e si creano solo dalla RPC SECURITY DEFINER.
-- La RPC e' di proprieta' del ruolo di migrazione (owner della tabella), quindi
-- conserva INSERT/UPDATE anche dopo la revoca ai ruoli client.
-- -----------------------------------------------------------------------------
drop policy if exists season_snapshots_insert_admin on public.season_snapshots;
revoke insert, update, delete on public.season_snapshots
    from public, anon, authenticated;
-- La lettura resta disponibile ai membri tramite la policy SELECT.
grant select on public.season_snapshots to authenticated;

-- Su seasons l'app non aggiorna metadati in main; il grant colonnare tiene
-- aperta la modifica legittima di nome/date e chiude is_active ai client.
revoke update on public.seasons from public, anon, authenticated;
grant update (name, start_date, end_date) on public.seasons to authenticated;
