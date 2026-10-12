-- =============================================================================
-- FantaFormula 1 — migrazione iniziale (schema + RLS)
-- Da eseguire nel SQL Editor del progetto Supabase.
--
-- Formato del gioco: "solo pronostici" — niente rose/budget. Ogni giocatore
-- punta su gare e piloti; chi totalizza piu' punti vince.
-- Il motore di punteggio gira nel client Kotlin: qui si conservano solo i dati.
-- =============================================================================

create extension if not exists pgcrypto;

-- -----------------------------------------------------------------------------
-- profiles: profilo pubblico, agganciato a auth.users (riga creata da trigger)
-- -----------------------------------------------------------------------------
create table if not exists public.profiles (
    id          uuid primary key references auth.users (id) on delete cascade,
    display_name text not null,
    created_at  timestamptz not null default now()
);

comment on table public.profiles is 'Profilo pubblico di ogni giocatore.';

-- Crea automaticamente il profilo quando Supabase Auth registra un nuovo utente.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.profiles (id, display_name)
    values (
        new.id,
        coalesce(
            nullif(trim(new.raw_user_meta_data ->> 'display_name'), ''),
            split_part(coalesce(new.email, 'giocatore'), '@', 1)
        )
    )
    on conflict (id) do nothing;
    return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function public.handle_new_user();

-- -----------------------------------------------------------------------------
-- leagues: una lega condivisa tra amici. Regole intere nel jsonb `rules`.
-- Il formato di `rules` e' documentato nel README e definito in Kotlin
-- (com.fantaformula1.app.core.domain.Rules) — quella classe e' la sorgente di verita'.
-- -----------------------------------------------------------------------------
create table if not exists public.leagues (
    id          uuid primary key default gen_random_uuid(),
    name        text not null check (char_length(name) between 2 and 60),
    invite_code text not null unique check (invite_code ~ '^[A-Z0-9]{6}$'),
    rules       jsonb not null default '{}'::jsonb,
    created_by  uuid not null references public.profiles (id) on delete cascade,
    created_at  timestamptz not null default now()
);

comment on column public.leagues.rules is
    'Regole della lega (preset + parametri custom). Formato = classe Kotlin Rules.';

create table if not exists public.league_members (
    league_id  uuid not null references public.leagues (id) on delete cascade,
    user_id    uuid not null references public.profiles (id) on delete cascade,
    role       text not null default 'player' check (role in ('admin', 'player')),
    joined_at  timestamptz not null default now(),
    primary key (league_id, user_id)
);

create table if not exists public.seasons (
    id         uuid primary key default gen_random_uuid(),
    league_id  uuid not null references public.leagues (id) on delete cascade,
    name       text not null,
    start_date date,
    end_date   date,
    is_active  boolean not null default true,
    created_at timestamptz not null default now()
);

comment on table public.seasons is 'Stagione = sottoinsieme di gare di una lega (es. "2026").';

-- Una gara. `deadline` e' il momento fino al quale si possono inviare pronostici:
-- l'enforcement e' sia lato DB (RLS) sia lato client.
create table if not exists public.races (
    id         uuid primary key default gen_random_uuid(),
    season_id  uuid not null references public.seasons (id) on delete cascade,
    league_id  uuid not null references public.leagues (id) on delete cascade,
    round      int not null check (round > 0),
    name       text not null,
    country    text,
    is_sprint  boolean not null default false,
    deadline   timestamptz,
    status     text not null default 'scheduled'
               check (status in ('scheduled', 'open', 'closed', 'completed')),
    created_at timestamptz not null default now(),
    unique (season_id, round)
);

comment on column public.races.deadline is
    'Istante di chiusura pronostici. NULL = gara sempre aperta (utente deve valorizzarla).';

-- Esito della gara: TUTTI i parametri inseriti dall'admin in un unico jsonb.
-- Il formato e' la classe Kotlin RaceResultData (sorgente di verita').
create table if not exists public.race_results (
    id         uuid primary key default gen_random_uuid(),
    race_id    uuid not null unique references public.races (id) on delete cascade,
    league_id  uuid not null references public.leagues (id) on delete cascade,
    result     jsonb not null,
    entered_by uuid references public.profiles (id) on delete set null,
    entered_at timestamptz not null default now()
);

-- Una riga per utente/gara. `content` e' la classe Kotlin RacePrediction.
create table if not exists public.predictions (
    id           uuid primary key default gen_random_uuid(),
    race_id      uuid not null references public.races (id) on delete cascade,
    league_id    uuid not null references public.leagues (id) on delete cascade,
    user_id      uuid not null references public.profiles (id) on delete cascade,
    content      jsonb not null,
    submitted_at timestamptz not null default now(),
    unique (race_id, user_id)
);

create index if not exists idx_league_members_user on public.league_members (user_id);
create index if not exists idx_seasons_league on public.seasons (league_id);
create index if not exists idx_races_league on public.races (league_id);
create index if not exists idx_races_season on public.races (season_id);
create index if not exists idx_race_results_league on public.race_results (league_id);
create index if not exists idx_predictions_league_user on public.predictions (league_id, user_id);
create index if not exists idx_predictions_race on public.predictions (race_id);

-- -----------------------------------------------------------------------------
-- Helper di sicurezza (SECURITY DEFINER: evitano ricorsione tra policy RLS)
-- -----------------------------------------------------------------------------
create or replace function public.is_league_member(p_league uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select exists (
        select 1 from public.league_members
        where league_id = p_league and user_id = auth.uid()
    );
$$;

create or replace function public.is_league_admin(p_league uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select exists (
        select 1 from public.league_members
        where league_id = p_league and user_id = auth.uid() and role = 'admin'
    );
$$;

-- true se la gara e' ancora aperta ai pronostici (deadline non superata)
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
          and (r.deadline is null or now() < r.deadline)
    );
$$;

-- true se la gara ha superato la deadline (=> i pronostici di tutti sono visibili)
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
          and r.deadline is not null
          and now() >= r.deadline
    );
$$;

-- -----------------------------------------------------------------------------
-- Codice invito a 6 cifre (alphanumeric, esclusi caratteri ambigui)
-- -----------------------------------------------------------------------------
create or replace function public.generate_invite_code()
returns text
language plpgsql
volatile
as $$
declare
    v_alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    v_code text;
    v_i int;
begin
    loop
        v_code := '';
        for v_i in 1..6 loop
            v_code := v_code || substr(v_alphabet, 1 + floor(random() * length(v_alphabet))::int, 1);
        end loop;
        exit when not exists (select 1 from public.leagues where invite_code = v_code);
    end loop;
    return v_code;
end;
$$;

-- -----------------------------------------------------------------------------
-- Funzioni di business: creazione lega e unione via codice (atomiche)
-- -----------------------------------------------------------------------------
create or replace function public.create_league(p_name text, p_rules jsonb default '{}'::jsonb)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
    v_league_id uuid;
    v_code text;
begin
    if auth.uid() is null then
        raise exception 'Devi essere autenticato per creare una lega';
    end if;

    v_code := public.generate_invite_code();

    insert into public.leagues (name, invite_code, rules, created_by)
    values (trim(p_name), v_code, coalesce(p_rules, '{}'::jsonb), auth.uid())
    returning id into v_league_id;

    -- il creatore e' sempre admin
    insert into public.league_members (league_id, user_id, role)
    values (v_league_id, auth.uid(), 'admin');

    return v_league_id;
end;
$$;

create or replace function public.join_league_by_code(p_code text)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
    v_league public.leagues%rowtype;
begin
    if auth.uid() is null then
        raise exception 'Devi essere autenticato per unirti a una lega';
    end if;

    select * into v_league
    from public.leagues
    where invite_code = upper(trim(p_code));

    if not found then
        raise exception 'Codice invito non valido';
    end if;

    insert into public.league_members (league_id, user_id, role)
    values (v_league.id, auth.uid(), 'player')
    on conflict (league_id, user_id) do nothing;

    return v_league.id;
end;
$$;

-- L'admin puo' promuovere/retrocedere un membro (usato per i ruoli).
create or replace function public.set_league_member_role(
    p_league uuid,
    p_user uuid,
    p_role text
)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    if not public.is_league_admin(p_league) then
        raise exception 'Solo un amministratore puo cambiare i ruoli';
    end if;
    if p_role not in ('admin', 'player') then
        raise exception 'Ruolo non valido';
    end if;

    update public.league_members
    set role = p_role
    where league_id = p_league and user_id = p_user;
end;
$$;

-- Crea la prima stagione di una lega (chiamata dall'admin dopo la creazione).
create or replace function public.create_default_season(p_league uuid, p_name text)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
    v_season_id uuid;
begin
    if not public.is_league_admin(p_league) then
        raise exception 'Solo un amministratore puo creare una stagione';
    end if;

    insert into public.seasons (league_id, name, is_active)
    values (p_league, coalesce(nullif(trim(p_name), ''), 'Stagione'), true)
    returning id into v_season_id;

    return v_season_id;
end;
$$;

-- -----------------------------------------------------------------------------
-- Row Level Security
-- Regole generali:
--   * i membri leggono i dati della propria lega;
--   * solo l'admin scrive regole, calendario e risultati;
--   * i pronostici sono scritti solo entro la deadline e letti dagli altri
--     solo dopo la chiusura.
-- -----------------------------------------------------------------------------
alter table public.profiles        enable row level security;
alter table public.leagues         enable row level security;
alter table public.league_members  enable row level security;
alter table public.seasons         enable row level security;
alter table public.races           enable row level security;
alter table public.race_results    enable row level security;
alter table public.predictions     enable row level security;

-- profiles -------------------------------------------------------------------
drop policy if exists profiles_select on public.profiles;
create policy profiles_select on public.profiles
    for select to authenticated
    using (true);  -- i nomi servono per la classifica e la lista membri

drop policy if exists profiles_update_own on public.profiles;
create policy profiles_update_own on public.profiles
    for update to authenticated
    using (id = auth.uid())
    with check (id = auth.uid());

-- leagues --------------------------------------------------------------------
drop policy if exists leagues_select on public.leagues;
create policy leagues_select on public.leagues
    for select to authenticated
    using (created_by = auth.uid() or public.is_league_member(id));

drop policy if exists leagues_update_admin on public.leagues;
create policy leagues_update_admin on public.leagues
    for update to authenticated
    using (public.is_league_admin(id))
    with check (public.is_league_admin(id));

drop policy if exists leagues_delete_admin on public.leagues;
create policy leagues_delete_admin on public.leagues
    for delete to authenticated
    using (created_by = auth.uid());

-- league_members -------------------------------------------------------------
drop policy if exists members_select on public.league_members;
create policy members_select on public.league_members
    for select to authenticated
    using (public.is_league_member(league_id));

drop policy if exists members_delete_self on public.league_members;
create policy members_delete_self on public.league_members
    for delete to authenticated
    using (user_id = auth.uid() or public.is_league_admin(league_id));

-- seasons --------------------------------------------------------------------
drop policy if exists seasons_select on public.seasons;
create policy seasons_select on public.seasons
    for select to authenticated
    using (public.is_league_member(league_id));

drop policy if exists seasons_write_admin on public.seasons;
create policy seasons_write_admin on public.seasons
    for insert to authenticated
    with check (public.is_league_admin(league_id));

drop policy if exists seasons_update_admin on public.seasons;
create policy seasons_update_admin on public.seasons
    for update to authenticated
    using (public.is_league_admin(league_id))
    with check (public.is_league_admin(league_id));

drop policy if exists seasons_delete_admin on public.seasons;
create policy seasons_delete_admin on public.seasons
    for delete to authenticated
    using (public.is_league_admin(league_id));

-- races ----------------------------------------------------------------------
drop policy if exists races_select on public.races;
create policy races_select on public.races
    for select to authenticated
    using (public.is_league_member(league_id));

drop policy if exists races_insert_admin on public.races;
create policy races_insert_admin on public.races
    for insert to authenticated
    with check (public.is_league_admin(league_id));

drop policy if exists races_update_admin on public.races;
create policy races_update_admin on public.races
    for update to authenticated
    using (public.is_league_admin(league_id))
    with check (public.is_league_admin(league_id));

drop policy if exists races_delete_admin on public.races;
create policy races_delete_admin on public.races
    for delete to authenticated
    using (public.is_league_admin(league_id));

-- race_results ---------------------------------------------------------------
drop policy if exists race_results_select on public.race_results;
create policy race_results_select on public.race_results
    for select to authenticated
    using (public.is_league_member(league_id));

drop policy if exists race_results_write_admin on public.race_results;
create policy race_results_write_admin on public.race_results
    for insert to authenticated
    with check (public.is_league_admin(league_id));

drop policy if exists race_results_update_admin on public.race_results;
create policy race_results_update_admin on public.race_results
    for update to authenticated
    using (public.is_league_admin(league_id))
    with check (public.is_league_admin(league_id));

drop policy if exists race_results_delete_admin on public.race_results;
create policy race_results_delete_admin on public.race_results
    for delete to authenticated
    using (public.is_league_admin(league_id));

-- predictions ----------------------------------------------------------------
-- LETTURA: propri pronostici sempre; quelli degli altri solo dopo la deadline.
drop policy if exists predictions_select on public.predictions;
create policy predictions_select on public.predictions
    for select to authenticated
    using (
        user_id = auth.uid()
        or (
            public.is_league_member(league_id)
            and public.is_prediction_closed(race_id)
        )
    );

-- SCRITTURA: solo il proprio pronostico e solo entro la deadline.
drop policy if exists predictions_insert_own on public.predictions;
create policy predictions_insert_own on public.predictions
    for insert to authenticated
    with check (
        user_id = auth.uid()
        and public.is_league_member(league_id)
        and public.is_prediction_open(race_id)
    );

drop policy if exists predictions_update_own on public.predictions;
create policy predictions_update_own on public.predictions
    for update to authenticated
    using (user_id = auth.uid() and public.is_prediction_open(race_id))
    with check (
        user_id = auth.uid()
        and public.is_league_member(league_id)
        and public.is_prediction_open(race_id)
    );

drop policy if exists predictions_delete_own on public.predictions;
create policy predictions_delete_own on public.predictions
    for delete to authenticated
    using (user_id = auth.uid() and public.is_prediction_open(race_id));
