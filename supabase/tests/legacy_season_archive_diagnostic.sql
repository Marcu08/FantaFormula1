-- =============================================================================
-- FantaFormula 1 - pre-deployment diagnostic for legacy season archives
--
-- READ-ONLY. Eseguire PRIMA di applicare 20261109210000/20261109220000:
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 \
--     -f supabase/tests/legacy_season_archive_diagnostic.sql
--
-- Riporta:
--   1) snapshot cross-league (la migrazione deve bloccarsi);
--   2) snapshot con stagione ancora attiva (stato parziale del vecchio flusso);
--   3) standings con forma radice non valida;
--   4) righe standings con campi mancanti/non validi;
--   5) utenti/posizioni duplicati, buchi, posizioni non intere, negativi.
--
-- Nessun dato viene modificato. Le procedure manuali sono in MIGRATIONS.md.
-- =============================================================================

\pset pager off

\echo '1) Snapshot cross-league (richiedono intervento manuale; la migrazione si ferma)'
select s.season_id,
       s.league_id as snapshot_league_id,
       se.league_id as season_league_id,
       s.season_name
from public.season_snapshots s
join public.seasons se on se.id = s.season_id
where s.league_id <> se.league_id
order by s.season_id;

\echo '2) Snapshot con stagione ancora attiva (recuperabili con complete_partial_season_archive)'
select s.season_id,
       s.league_id,
       s.season_name,
       s.archived_at
from public.season_snapshots s
join public.seasons se on se.id = s.season_id
where se.is_active
order by s.season_id;

\echo '3) Standings con radice non valida (intervento manuale, non riparazione automatica)'
select s.season_id,
       s.league_id,
       s.season_name,
       jsonb_typeof(s.standings) as standings_type
from public.season_snapshots s
where jsonb_typeof(s.standings) is distinct from 'object'
   or jsonb_typeof(s.standings -> 'rows') is distinct from 'array'
order by s.season_id;

\echo '4) Righe standings con campi mancanti/non validi (intervento manuale)'
with snapshots as (
    select s.season_id, s.league_id, s.season_name, s.standings -> 'rows' as rows
    from public.season_snapshots s
    where jsonb_typeof(s.standings) = 'object'
      and jsonb_typeof(s.standings -> 'rows') = 'array'
)
select distinct s.season_id, s.league_id, s.season_name
from snapshots s
where exists (
    select 1
    from jsonb_array_elements(s.rows) e
    where jsonb_typeof(e) is distinct from 'object'
       or jsonb_typeof(e -> 'userId') is distinct from 'string'
       or (e ->> 'userId') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
       or jsonb_typeof(e -> 'displayName') is distinct from 'string'
       or btrim(e ->> 'displayName') = ''
       or jsonb_typeof(e -> 'position') is distinct from 'number'
       or case when jsonb_typeof(e -> 'position') = 'number'
               then (e ->> 'position')::numeric < 1
                    or (e ->> 'position')::numeric <> trunc((e ->> 'position')::numeric)
               else true end
       or jsonb_typeof(e -> 'totalPoints') is distinct from 'number'
       or jsonb_typeof(e -> 'raceWins') is distinct from 'number'
       or jsonb_typeof(e -> 'bestRacePoints') is distinct from 'number'
       or case when jsonb_typeof(e -> 'totalPoints') = 'number'
               then (e ->> 'totalPoints')::numeric < 0 else true end
       or case when jsonb_typeof(e -> 'raceWins') = 'number'
               then (e ->> 'raceWins')::numeric < 0
                    or (e ->> 'raceWins')::numeric <> trunc((e ->> 'raceWins')::numeric)
               else true end
       or case when jsonb_typeof(e -> 'bestRacePoints') = 'number'
               then (e ->> 'bestRacePoints')::numeric < 0 else true end
)
order by s.season_id;

\echo '5) Classifiche formalmente valide ma con duplicati/buchi/posizioni non contigue'
with snapshots as (
    select s.season_id, s.league_id, s.season_name, s.standings -> 'rows' as rows
    from public.season_snapshots s
    where jsonb_typeof(s.standings) = 'object'
      and jsonb_typeof(s.standings -> 'rows') = 'array'
),
valid_rows as (
    select *
    from snapshots s
    where not exists (
        select 1
        from jsonb_array_elements(s.rows) e
        where jsonb_typeof(e) is distinct from 'object'
           or jsonb_typeof(e -> 'userId') is distinct from 'string'
           or (e ->> 'userId') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
           or jsonb_typeof(e -> 'displayName') is distinct from 'string'
           or btrim(e ->> 'displayName') = ''
           or jsonb_typeof(e -> 'position') is distinct from 'number'
           or case when jsonb_typeof(e -> 'position') = 'number'
                   then (e ->> 'position')::numeric < 1
                        or (e ->> 'position')::numeric <> trunc((e ->> 'position')::numeric)
                   else true end
           or jsonb_typeof(e -> 'totalPoints') is distinct from 'number'
           or jsonb_typeof(e -> 'raceWins') is distinct from 'number'
           or jsonb_typeof(e -> 'bestRacePoints') is distinct from 'number'
           or (case when jsonb_typeof(e -> 'totalPoints') = 'number'
                    then (e ->> 'totalPoints')::numeric < 0 else true end)
           or (case when jsonb_typeof(e -> 'raceWins') = 'number'
                    then (e ->> 'raceWins')::numeric < 0
                         or (e ->> 'raceWins')::numeric <> trunc((e ->> 'raceWins')::numeric)
                    else true end)
           or (case when jsonb_typeof(e -> 'bestRacePoints') = 'number'
                    then (e ->> 'bestRacePoints')::numeric < 0 else true end)
    )
),
metrics as (
    select s.season_id,
           s.league_id,
           s.season_name,
           jsonb_array_length(s.rows) as n,
           (select count(distinct (e ->> 'userId')::uuid)
            from jsonb_array_elements(s.rows) e) as distinct_users,
           (select count(distinct (e ->> 'position')::int)
            from jsonb_array_elements(s.rows) e) as distinct_positions,
           (select count(*)
            from jsonb_array_elements(s.rows) e
            where (e ->> 'totalPoints')::numeric < 0
               or (e ->> 'bestRacePoints')::numeric < 0
               or (e ->> 'raceWins')::numeric < 0
               or (e ->> 'raceWins')::numeric <> trunc((e ->> 'raceWins')::numeric)) as negative_rows,
           (select count(*)
            from generate_series(1, jsonb_array_length(s.rows)) g
            where not exists (
                select 1
                from jsonb_array_elements(s.rows) e
                where (e ->> 'position')::int = g
            )) as missing_positions
    from valid_rows s
)
select season_id, league_id, season_name,
       n, distinct_users, distinct_positions, negative_rows, missing_positions
from metrics
where n <> distinct_users
   or n <> distinct_positions
   or negative_rows > 0
   or missing_positions > 0
order by season_id;
