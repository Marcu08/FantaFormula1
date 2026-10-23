-- =============================================================================
-- FantaFormula 1 — migrazione 0012_season_history.sql
-- Da eseguire nel SQL Editor del progetto Supabase.
--
-- F2 — Storico stagioni: alla chiusura di una stagione la classifica finale
-- viene "congelata" in una riga immutabile (`season_snapshots`), cosi' le
-- gare storiche restano intatte nel DB ma escono dalle schermate correnti e
-- una nuova stagione parte da zero.
--
-- Idempotente: puo' essere eseguita piu' volte (create table if not exists,
-- drop policy if exists prima di ogni create policy), come0001-0011.
-- Stile e helper RLS (is_league_member / is_league_admin) presi da 0001_init.
--
-- Test predisposti: supabase/tests/0012_season_history_tests.sql
-- =============================================================================

-- -----------------------------------------------------------------------------
-- season_snapshots: una riga per stagione chiusa. `standings` e' il JSON
-- serializzato dal client (classe Kotlin SeasonStandingsSnapshot, sorgente
-- di verita' del formato). Nessuna policy di UPDATE/DELETE: lo snapshot e'
-- immutabile, la classifica resta congelata.
-- -----------------------------------------------------------------------------
create table if not exists public.season_snapshots (
    season_id   uuid primary key references public.seasons (id) on delete cascade,
    league_id   uuid not null references public.leagues (id) on delete cascade,
    season_name text not null,
    standings   jsonb not null,
    archived_by uuid references public.profiles (id) on delete set null,
    archived_at timestamptz not null default now()
);

comment on table public.season_snapshots is
    'Classifica finale di una stagione chiusa: riga immutabile (nessuna policy di update/delete).';

create index if not exists idx_season_snapshots_league
    on public.season_snapshots (league_id);

alter table public.season_snapshots enable row level security;

-- -----------------------------------------------------------------------------
-- SELECT: solo i membri della lega vedono gli snapshot della propria lega.
-- (Gli estranei non vedono righe: la policy filtra, non genera errore.)
-- -----------------------------------------------------------------------------
drop policy if exists season_snapshots_select on public.season_snapshots;
create policy season_snapshots_select on public.season_snapshots
    for select to authenticated
    using (public.is_league_member(league_id));

-- -----------------------------------------------------------------------------
-- INSERT: solo l'amministratore della lega, e solo per se stesso
-- (`archived_by = auth.uid()`): lo snapshot registra sempre chi ha chiuso
-- la stagione. NIENTE policy di UPDATE/DELETE: la riga e' un congelamento.
-- -----------------------------------------------------------------------------
drop policy if exists season_snapshots_insert_admin on public.season_snapshots;
create policy season_snapshots_insert_admin on public.season_snapshots
    for insert to authenticated
    with check (
        public.is_league_admin(league_id)
        and archived_by = auth.uid()
    );
