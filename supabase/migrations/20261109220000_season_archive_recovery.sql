-- =============================================================================
-- FantaFormula 1 - explicit recovery for legacy partial archival states
--
-- La vecchia archiviazione client faceva due richieste separate:
--   1) INSERT dello snapshot;
--   2) UPDATE seasons.is_active = false.
-- Un errore tra le due lasciava uno snapshot valido con la stagione ancora
-- attiva. Questa migrazione NON ripara automaticamente quei dati: crea un
-- percorso esplicito, autorizzato e auditato per completare solo lo stato
-- "snapshot esistente + stagione attiva".
--
-- Non modifica mai lo snapshot storico. Per snapshot cross-league o con
-- standings malformati resta obbligatorio un intervento manuale documentato
-- in supabase/MIGRATIONS.md.
--
-- Richiede 20261109200000 e 20261109210000. Idempotente.
-- NON e' stata applicata al progetto Supabase live.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) Audit delle riparazioni
-- -----------------------------------------------------------------------------
create table if not exists public.season_archive_repairs (
    id          uuid primary key default gen_random_uuid(),
    season_id   uuid not null references public.seasons (id) on delete restrict,
    league_id   uuid not null references public.leagues (id) on delete restrict,
    repaired_by uuid references public.profiles (id) on delete set null,
    repaired_at timestamptz not null default now(),
    reason      text not null default 'legacy snapshot with active season'
);

comment on table public.season_archive_repairs is
    'Audit delle riparazioni esplicite di archivi parziali. Nessun accesso diretto ai ruoli client.';

alter table public.season_archive_repairs enable row level security;
revoke all on public.season_archive_repairs from public, anon, authenticated;

-- -----------------------------------------------------------------------------
-- 2) Validazione della forma dello snapshot legacy
--
-- Serve al recupero: non vogliamo completare uno stato parziale se lo snapshot
-- contiene duplicati, buchi, posizioni non intere o valori negativi. La stessa
-- validazione resta inline in archive_season; questa funzione e' usata solo dal
-- percorso di recupero.
-- -----------------------------------------------------------------------------
create or replace function public.season_snapshot_shape_is_valid(p_standings jsonb)
returns boolean
language plpgsql
stable
security invoker
set search_path = public
as $valid$
declare
    v_rows jsonb;
    v_row jsonb;
    v_user uuid;
    v_position numeric;
    v_wins numeric;
    v_total numeric;
    v_best numeric;
begin
    if p_standings is null
       or jsonb_typeof(p_standings) is distinct from 'object'
       or octet_length(p_standings::text) > 262144 then
        return false;
    end if;

    v_rows := p_standings -> 'rows';
    if v_rows is null
       or jsonb_typeof(v_rows) is distinct from 'array'
       or jsonb_array_length(v_rows) > 200 then
        return false;
    end if;

    for v_row in select value from jsonb_array_elements(v_rows) loop
        if jsonb_typeof(v_row) is distinct from 'object'
           or jsonb_typeof(v_row -> 'userId') is distinct from 'string'
           or jsonb_typeof(v_row -> 'displayName') is distinct from 'string'
           or jsonb_typeof(v_row -> 'position') is distinct from 'number'
           or jsonb_typeof(v_row -> 'totalPoints') is distinct from 'number'
           or jsonb_typeof(v_row -> 'raceWins') is distinct from 'number'
           or jsonb_typeof(v_row -> 'bestRacePoints') is distinct from 'number' then
            return false;
        end if;

        begin
            v_user := (v_row ->> 'userId')::uuid;
            v_position := (v_row ->> 'position')::numeric;
            v_wins := (v_row ->> 'raceWins')::numeric;
            v_total := (v_row ->> 'totalPoints')::numeric;
            v_best := (v_row ->> 'bestRacePoints')::numeric;
        exception when others then
            return false;
        end;

        if v_user is null
           or v_position <> trunc(v_position)
           or v_position < 1
           or v_wins <> trunc(v_wins)
           or v_wins < 0
           or v_total < 0
           or v_best < 0
           or btrim(v_row ->> 'displayName') = '' then
            return false;
        end if;
    end loop;

    if jsonb_array_length(v_rows) > 0 then
        if (
            select count(*) from jsonb_array_elements(v_rows)
        ) <> (
            select count(distinct (value ->> 'userId')::uuid)
            from jsonb_array_elements(v_rows)
        ) then
            return false;
        end if;

        if (
            select count(*) from jsonb_array_elements(v_rows)
        ) <> (
            select count(distinct (value ->> 'position')::int)
            from jsonb_array_elements(v_rows)
        ) then
            return false;
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
            return false;
        end if;
    end if;

    return true;
exception when others then
    return false;
end;
$valid$;

comment on function public.season_snapshot_shape_is_valid(jsonb) is
    'Validazione read-only della forma di uno snapshot legacy per il recupero. Non eseguibile dai ruoli client.';

revoke all on function public.season_snapshot_shape_is_valid(jsonb) from public, anon, authenticated;

-- -----------------------------------------------------------------------------
-- 3) RPC di completamento
--
-- Autorizzata solo all'admin della lega. Verifica che lo snapshot esista e sia
-- dello stesso league/season, che la stagione sia ancora attiva, e registra
-- l'operazione nell'audit. Non accetta standings e non modifica lo snapshot.
-- -----------------------------------------------------------------------------
create or replace function public.complete_partial_season_archive(
    p_league uuid,
    p_season uuid
)
returns void
language plpgsql
security definer
set search_path = public
as $recovery$
declare
    v_user uuid := auth.uid();
    v_active boolean;
    v_standings jsonb;
    v_count int;
begin
    if v_user is null then
        raise exception 'Devi essere autenticato per completare l''archiviazione';
    end if;

    if p_league is null or p_season is null then
        raise exception 'Lega o stagione non valida';
    end if;

    if not public.is_league_admin(p_league) then
        raise exception 'Solo un amministratore puo completare l''archiviazione';
    end if;

    -- Lock: due riparazioni concorrenti vengono serializzate.
    select s.is_active
    into v_active
    from public.seasons s
    where s.id = p_season
      and s.league_id = p_league
    for update;

    if not found then
        raise exception 'La stagione non appartiene a questa lega';
    end if;

    if not v_active then
        raise exception 'La stagione non e'' attiva: nessuna riparazione necessaria';
    end if;

    select s.standings
    into v_standings
    from public.season_snapshots s
    where s.season_id = p_season
      and s.league_id = p_league;

    if not found then
        raise exception 'Nessuno snapshot da completare: usare archive_season';
    end if;

    -- Non completiamo archivi con forma/duplicati/negativi non validi: in quel
    -- caso serve un intervento manuale (diagnostica in supabase/tests).
    if not public.season_snapshot_shape_is_valid(v_standings) then
        raise exception 'Lo snapshot esistente non e'' valido: intervento manuale necessario';
    end if;

    insert into public.season_archive_repairs (
        season_id,
        league_id,
        repaired_by,
        reason
    ) values (
        p_season,
        p_league,
        v_user,
        'legacy snapshot with active season'
    );

    update public.seasons
    set is_active = false
    where id = p_season
      and league_id = p_league;

    get diagnostics v_count = row_count;
    if v_count <> 1 then
        raise exception 'Riparazione non completata';
    end if;
end;
$recovery$;

comment on function public.complete_partial_season_archive(uuid, uuid) is
    'Completa esplicitamente un archivio legacy con snapshot esistente e stagione ancora attiva; non modifica lo snapshot e registra un audit.';

revoke execute on function public.complete_partial_season_archive(uuid, uuid) from public, anon;
grant execute on function public.complete_partial_season_archive(uuid, uuid) to authenticated;
