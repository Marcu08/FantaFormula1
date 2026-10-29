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
--      transazione, evitando lo stato parziale del vecchio flusso a due
--      chiamate client.
--
-- Idempotente. NON e' stata applicata a un database live durante l'audit.
-- =============================================================================

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

alter table public.season_snapshots
    drop constraint if exists season_snapshots_season_league_fk;
alter table public.season_snapshots
    add constraint season_snapshots_season_league_fk
        foreign key (season_id, league_id)
        references public.seasons (id, league_id)
        on delete cascade
        not valid;
-- NOT VALID: le righe storiche non vengono scandite qui, ma ogni nuova
-- riga e' controllata. Eseguire VALIDATE CONSTRAINT in un progetto di test
-- dopo aver verificato i dati esistenti.

-- 4) Archiviazione atomica -------------------------------------------------
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
begin
    if not public.is_league_admin(p_league) then
        raise exception 'Solo un amministratore puo archiviare la stagione';
    end if;

    if not exists (
        select 1
        from public.seasons
        where id = p_season and league_id = p_league
    ) then
        raise exception 'La stagione non appartiene a questa lega';
    end if;

    if p_standings is null or jsonb_typeof(p_standings) <> 'object' then
        raise exception 'La classifica archiviata deve essere un oggetto JSON';
    end if;

    insert into public.season_snapshots (
        season_id, league_id, season_name, standings, archived_by
    ) values (
        p_season,
        p_league,
        coalesce(nullif(btrim(p_name), ''), 'Stagione'),
        p_standings,
        auth.uid()
    );

    update public.seasons
    set is_active = false
    where id = p_season and league_id = p_league;
end;
$$;

comment on function public.archive_season(uuid, uuid, text, jsonb) is
    'Congela lo snapshot e disattiva la stagione in un''unica transazione (solo admin).';

revoke execute on function public.archive_season(uuid, uuid, text, jsonb) from public, anon;
grant execute on function public.archive_season(uuid, uuid, text, jsonb) to authenticated;
