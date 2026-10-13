-- =============================================================================
-- FantaFormula 1 — migration 0002_fixes.sql
-- Da eseguire nel SQL Editor del progetto Supabase.
--
-- Corregge i bug lato database elencati in prompt-fix-fantaformula1.md
-- (B5, B10, B17, B18, B21) e prepara il terreno per i risultati automatici.
-- Idempotente: puo' essere eseguita piu' volte.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- B5 / B10 — is_prediction_open / is_prediction_closed
-- Ora considerano anche lo status della gara: se e' 'closed' o 'completed'
-- i pronostici sono chiusi, anche se la deadline non e' passata.
-- -----------------------------------------------------------------------------
create or replace function public.is_prediction_open(p_race uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select exists (
        select 1 from public.races r
        where r.id = p_race
          and r.status in ('scheduled', 'open')
          and (r.deadline is null or now() < r.deadline)
    );
$$;

create or replace function public.is_prediction_closed(p_race uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select exists (
        select 1 from public.races r
        where r.id = p_race
          and (
              r.status in ('closed', 'completed')
              or (r.deadline is not null and now() >= r.deadline)
          )
    );
$$;

-- -----------------------------------------------------------------------------
-- B10 — Helper per leggere una regola booleana dalle regole della lega.
-- -----------------------------------------------------------------------------
create or replace function public.league_rule_bool(
    p_league uuid,
    p_key text,
    p_default boolean
)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select coalesce(
        (select (l.rules ->> p_key)::boolean
         from public.leagues l
         where l.id = p_league),
        p_default
    );
$$;

-- -----------------------------------------------------------------------------
-- B10 — Helper per sapere se un pronostico esiste gia' (per allowEditUntilDeadline).
-- -----------------------------------------------------------------------------
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
    select exists (
        select 1 from public.predictions
        where race_id = p_race and user_id = p_user
    );
$$;

-- -----------------------------------------------------------------------------
-- B10 — Riscrittura delle policy su predictions.
-- SELECT: propri sempre; degli altri solo se revealOthersAfterDeadline e' false
--         OPPURE la gara e' chiusa.
-- UPDATE/DELETE: solo se la gara e' aperta E (allowEditUntilDeadline e' true
--                OPPURE il pronostico non esisteva ancora).
-- -----------------------------------------------------------------------------
drop policy if exists predictions_select on public.predictions;
create policy predictions_select on public.predictions
    for select to authenticated
    using (
        user_id = auth.uid()
        or (
            public.is_league_member(league_id)
            and (
                not public.league_rule_bool(league_id, 'revealOthersAfterDeadline', true)
                or public.is_prediction_closed(race_id)
            )
        )
    );

drop policy if exists predictions_update_own on public.predictions;
create policy predictions_update_own on public.predictions
    for update to authenticated
    using (
        user_id = auth.uid()
        and public.is_prediction_open(race_id)
        and public.is_league_member(league_id)
        and (
            public.league_rule_bool(league_id, 'allowEditUntilDeadline', true)
            or not public.prediction_exists(race_id, auth.uid())
        )
    )
    with check (
        user_id = auth.uid()
        and public.is_league_member(league_id)
        and public.is_prediction_open(race_id)
    );

drop policy if exists predictions_delete_own on public.predictions;
create policy predictions_delete_own on public.predictions
    for delete to authenticated
    using (
        user_id = auth.uid()
        and public.is_prediction_open(race_id)
        and (
            public.league_rule_bool(league_id, 'allowEditUntilDeadline', true)
            or not public.prediction_exists(race_id, auth.uid())
        )
    );

-- -----------------------------------------------------------------------------
-- B21 — submitted_at impostato lato server, non dal client.
-- -----------------------------------------------------------------------------
create or replace function public.set_prediction_submitted_at()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    new.submitted_at = now();
    return new;
end;
$$;

drop trigger if exists on_prediction_submit on public.predictions;
create trigger on_prediction_submit
    before insert or update on public.predictions
    for each row execute function public.set_prediction_submitted_at();

-- -----------------------------------------------------------------------------
-- B21 — RPC atomica per salvare un risultato e marcare la gara completata.
-- L'app chiamera' questa funzione invece di fare update+insert+updateStatus.
-- -----------------------------------------------------------------------------
create or replace function public.save_race_result(
    p_race uuid,
    p_result jsonb
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_league uuid;
begin
    select league_id into v_league
    from public.races
    where id = p_race;

    if v_league is null then
        raise exception 'Gara non trovata';
    end if;

    if not public.is_league_admin(v_league) then
        raise exception 'Solo un amministratore puo salvare i risultati';
    end if;

    insert into public.race_results (race_id, league_id, result, entered_by, entered_at)
    values (p_race, v_league, p_result, auth.uid(), now())
    on conflict (race_id) do update
    set result = excluded.result,
        entered_by = auth.uid(),
        entered_at = now();

    update public.races
    set status = 'completed'
    where id = p_race;
end;
$$;

-- -----------------------------------------------------------------------------
-- B17 — Impedisce di rimuovere o retrocedere l'ultimo admin di una lega.
-- -----------------------------------------------------------------------------
create or replace function public.check_last_admin_on_update()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_admins int;
begin
    if old.role = 'admin' and new.role = 'player' then
        select count(*) into v_admins
        from public.league_members
        where league_id = old.league_id and role = 'admin';
        if v_admins <= 1 then
            raise exception 'Non puoi retrocedere l''ultimo amministratore';
        end if;
    end if;
    return new;
end;
$$;

drop trigger if exists on_league_member_role_change on public.league_members;
create trigger on_league_member_role_change
    before update of role on public.league_members
    for each row execute function public.check_last_admin_on_update();

create or replace function public.check_last_admin_on_delete()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_admins int;
begin
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

drop trigger if exists on_league_member_delete on public.league_members;
create trigger on_league_member_delete
    before delete on public.league_members
    for each row execute function public.check_last_admin_on_delete();

-- -----------------------------------------------------------------------------
-- B17 — La select su leagues deve essere solo per membri, non anche per created_by.
-- (created_by e' comunque membro grazie al trigger di creazione lega.)
-- -----------------------------------------------------------------------------
drop policy if exists leagues_select on public.leagues;
create policy leagues_select on public.leagues
    for select to authenticated
    using (public.is_league_member(id));

-- -----------------------------------------------------------------------------
-- B18 — Integrita' referenziale tra leghe.
-- Aggiunge unique (id, league_id) su seasons e races, poi ricrea le FK
-- di races/predictions/race_results come chiavi esterne composite.
-- NOT VALID + VALIDATE per non bloccare se i dati esistenti sono puliti.
-- -----------------------------------------------------------------------------

-- seasons: unique (id, league_id)
alter table public.seasons
    drop constraint if exists seasons_id_league_unique;
alter table public.seasons
    add constraint seasons_id_league_unique unique (id, league_id);

-- races: unique (id, league_id)
alter table public.races
    drop constraint if exists races_id_league_unique;
alter table public.races
    add constraint races_id_league_unique unique (id, league_id);

-- races: FK composite verso seasons
alter table public.races
    drop constraint if exists races_season_id_fkey;
alter table public.races
    add constraint races_season_league_fk
        foreign key (season_id, league_id) references public.seasons(id, league_id)
        not valid;
alter table public.races validate constraint races_season_league_fk;

-- predictions: FK composite verso races
alter table public.predictions
    drop constraint if exists predictions_race_id_fkey;
alter table public.predictions
    add constraint predictions_race_league_fk
        foreign key (race_id, league_id) references public.races(id, league_id)
        not valid;
alter table public.predictions validate constraint predictions_race_league_fk;

-- race_results: FK composite verso races
alter table public.race_results
    drop constraint if exists race_results_race_id_fkey;
alter table public.race_results
    add constraint race_results_race_league_fk
        foreign key (race_id, league_id) references public.races(id, league_id)
        not valid;
alter table public.race_results validate constraint race_results_race_league_fk;

-- -----------------------------------------------------------------------------
-- Permessi: revoca generica, poi grant solo sulle RPC usate dall'app.
-- -----------------------------------------------------------------------------
revoke execute on all functions in schema public from public, anon;

grant execute on function public.create_league(text, jsonb) to authenticated;
grant execute on function public.join_league_by_code(text) to authenticated;
grant execute on function public.set_league_member_role(uuid, uuid, text) to authenticated;
grant execute on function public.create_default_season(uuid, text) to authenticated;
grant execute on function public.save_race_result(uuid, jsonb) to authenticated;
grant execute on function public.generate_invite_code() to authenticated;
grant execute on function public.is_league_member(uuid) to authenticated;
grant execute on function public.is_league_admin(uuid) to authenticated;
grant execute on function public.is_prediction_open(uuid) to authenticated;
grant execute on function public.is_prediction_closed(uuid) to authenticated;
grant execute on function public.league_rule_bool(uuid, text, boolean) to authenticated;
grant execute on function public.prediction_exists(uuid, uuid) to authenticated;
