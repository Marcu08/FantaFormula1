-- =============================================================================
-- FantaFormula 1 — migration 0011_fixes.sql
-- Da eseguire nel SQL Editor del progetto Supabase.
--
-- Corregge i bug elencati in .opencode/knowledge/prompt-v2.md
-- (N8, N9, R4, R8). Il prompt la chiama "0003_fixes.sql": nel repo esistono
-- gia' le migrazioni 0001-0010, quindi usa il numero successivo (decisions31).
-- Idempotente: puo' essere eseguita piu' volte.
--
-- Test predisposti: supabase/tests/0011_fixes_tests.sql
-- =============================================================================

-- -----------------------------------------------------------------------------
-- N8 — Le chiavi esterne composite create in 0002 non avevano
-- "on delete cascade": eliminare una gara con pronostici o con un risultato
-- falliva con violazione di vincolo. Ricreate con cascade.
-- NOT VALID + VALIDATE come in 0002 (le colonne sono gia' state verificate).
-- -----------------------------------------------------------------------------
alter table public.predictions
    drop constraint if exists predictions_race_league_fk;
alter table public.predictions
    add constraint predictions_race_league_fk
        foreign key (race_id, league_id) references public.races(id, league_id)
        on delete cascade
        not valid;
alter table public.predictions validate constraint predictions_race_league_fk;

alter table public.race_results
    drop constraint if exists race_results_race_league_fk;
alter table public.race_results
    add constraint race_results_race_league_fk
        foreign key (race_id, league_id) references public.races(id, league_id)
        on delete cascade
        not valid;
alter table public.race_results validate constraint race_results_race_league_fk;

alter table public.races
    drop constraint if exists races_season_league_fk;
alter table public.races
    add constraint races_season_league_fk
        foreign key (season_id, league_id) references public.seasons(id, league_id)
        on delete cascade
        not valid;
alter table public.races validate constraint races_season_league_fk;

-- -----------------------------------------------------------------------------
-- N9 — Il trigger "ultimo admin" bloccava la cancellazione a cascata di una
-- lega (e di un utente): il controllo scattava su righe i cui parenti erano
-- gia' stati eliminati. Se la lega o l'utente non esistono piu' il trigger
-- lascia passare. Le cancellazioni dirette restano protette.
-- (create or replace preserva le autorizzazioni e il collegamento al trigger.)
-- -----------------------------------------------------------------------------
create or replace function public.check_last_admin_on_delete()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_admins int;
begin
    -- Cancellazione a cascata: la lega non esiste piu'...
    if not exists (select 1 from public.leagues where id = old.league_id) then
        return old;
    end if;
    -- ...oppure l'utente del membro e' gia' stato eliminato (cascata da
    -- profiles/auth.users): la lega resta senza admin ma la cancellazione
    -- dell'utente non deve essere bloccata.
    if not exists (select 1 from public.profiles where id = old.user_id) then
        return old;
    end if;

    if old.role = 'admin' then
        select count(*) into v_admins
        from public.league_members
        where league_id = old.league_id and role = 'admin';
        if v_admins <= 1 then
            raise exception 'Non puoi rimuovere l''ultimo amministratore';
        end if;
    end if;
    return old;
end;
$$;

-- -----------------------------------------------------------------------------
-- R4 — Le NUOVE gare devono avere una deadline (altrimenti i pronostici
-- restano aperti per sempre e gli altri non vengono mai rivelati).
-- Vincolo via trigger "before insert" e non "not null" sulla colonna, per non
-- rompere le righe esistenti con deadline NULL (decisions31/prompt v2 R4).
-- -----------------------------------------------------------------------------
create or replace function public.require_race_deadline()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    if new.deadline is null then
        raise exception 'La deadline e'' obbligatoria per le nuove gare';
    end if;
    return new;
end;
$$;

drop trigger if exists races_require_deadline on public.races;
create trigger races_require_deadline
    before insert on public.races
    for each row execute function public.require_race_deadline();

-- -----------------------------------------------------------------------------
-- R8 — RPC per rimuovere un membro dalla lega (usata dalla UI ruoli, R3).
-- Solo admin; non rimuove l'ultimo amministratore.
-- -----------------------------------------------------------------------------
create or replace function public.remove_league_member(
    p_league uuid,
    p_user uuid
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_role text;
    v_admins int;
begin
    if not public.is_league_admin(p_league) then
        raise exception 'Solo un amministratore puo rimuovere un membro';
    end if;

    select role into v_role
    from public.league_members
    where league_id = p_league and user_id = p_user;

    if v_role is null then
        raise exception 'Membro non trovato in questa lega';
    end if;

    if v_role = 'admin' then
        select count(*) into v_admins
        from public.league_members
        where league_id = p_league and role = 'admin';
        if v_admins <= 1 then
            raise exception 'Non puoi rimuovere l''ultimo amministratore';
        end if;
    end if;

    delete from public.league_members
    where league_id = p_league and user_id = p_user;
end;
$$;

-- -----------------------------------------------------------------------------
-- Permessi: le funzioni nuove nascono con EXECUTE a PUBLIC; la revoca le
-- toglie ad anon e public (il grant ad authenticated resta quello di 0002 per
-- le funzioni gia' esistenti).
-- -----------------------------------------------------------------------------
revoke execute on function public.remove_league_member(uuid, uuid) from public, anon;
revoke execute on function public.require_race_deadline() from public, anon;
revoke execute on function public.check_last_admin_on_delete() from public, anon;
revoke execute on function public.check_last_admin_on_update() from public, anon;

grant execute on function public.remove_league_member(uuid, uuid) to authenticated;
