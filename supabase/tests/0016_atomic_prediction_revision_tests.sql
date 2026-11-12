-- =============================================================================
-- FantaFormula 1 - test della migrazione 0016_atomic_prediction_revision.sql
--
-- PREDISPOSTI, NON ESEGUITI. Richiedono un progetto Supabase di test con
-- 0001-0016 applicate. Sostituire '<UUID_USER>' con un utente reale.
-- Il blocco termina sempre con un'eccezione: self-rollback.
--
-- Copre:
--  - inserimento senza revisione;
--  - retry/duplicato identico (idempotenza);
--  - revisione obsoleta;
--  - aggiornamento sulla revisione corrente;
--  - tentativo di aggiornare una riga creduta esistente ma cancellata.
-- =============================================================================

do $test$
declare
    v_user uuid := '<UUID_USER>';
    v_league uuid;
    v_season uuid;
    v_race uuid;
    v_status text;
    v_submitted_at timestamptz;
    v_server_content jsonb;
    v_revision timestamptz;
    v_errors int := 0;

    v_content_1 jsonb := jsonb_build_object(
        'entries', jsonb_build_array(
            jsonb_build_object(
                'type', 'WINNER',
                'driverIds', jsonb_build_array('VER')
            )
        )
    );
    v_content_2 jsonb := jsonb_build_object(
        'entries', jsonb_build_array(
            jsonb_build_object(
                'type', 'WINNER',
                'driverIds', jsonb_build_array('LEC')
            )
        )
    );
begin
    insert into public.profiles (id, display_name)
    values (v_user, 'Utente Test 0016')
    on conflict (id) do nothing;

    insert into public.leagues (name, invite_code, rules, created_by)
    values ('Lega Test 0016', 'TST16A', '{}', v_user)
    returning id into v_league;

    insert into public.league_members (league_id, user_id, role)
    values (v_league, v_user, 'admin');

    insert into public.seasons (league_id, name, is_active)
    values (v_league, '2026', true)
    returning id into v_season;

    insert into public.races (season_id, league_id, round, name, deadline)
    values (v_season, v_league, 1, 'Gara Test 0016', now() + interval '2 days')
    returning id into v_race;

    create temp table test_results (label text, passed boolean, detail text);
    grant all on test_results to authenticated, anon;

    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_user::text, true);

    -- 1. Inserimento iniziale: nessuna revisione attesa.
    select status, submitted_at, content
      into v_status, v_submitted_at, v_server_content
      from public.save_prediction_if_revision(v_race, v_league, v_content_1, null::timestamptz);

    if v_status = 'saved' and v_submitted_at is not null then
        insert into test_results values ('inserimento senza revisione', true, null);
        v_revision := v_submitted_at;
    else
        insert into test_results values ('inserimento senza revisione', false,
                                         coalesce(v_status, 'status nullo'));
    end if;

    -- 2. Retry identico con la stessa revisione assente: idempotente.
    select status, submitted_at, content
      into v_status, v_submitted_at, v_server_content
      from public.save_prediction_if_revision(v_race, v_league, v_content_1, null::timestamptz);

    if v_status = 'unchanged' and v_submitted_at is not distinct from v_revision then
        insert into test_results values ('retry identico senza revisione', true, null);
    else
        insert into test_results values ('retry identico senza revisione', false,
                                         coalesce(v_status, 'status nullo'));
    end if;

    -- 3. Revisione obsoleta + contenuto diverso: conflitto.
    select status, submitted_at, content
      into v_status, v_submitted_at, v_server_content
      from public.save_prediction_if_revision(
          v_race, v_league, v_content_2, v_revision - interval '1 second'
      );

    if v_status = 'conflict' and v_server_content = v_content_1 then
        insert into test_results values ('revisione obsoleta rifiutata', true, null);
    else
        insert into test_results values ('revisione obsoleta rifiutata', false,
                                         coalesce(v_status, 'status nullo'));
    end if;

    -- 4. Aggiornamento con revisione corrente: riuscito.
    -- Simula il client, che invia il timestamp in millisecondi.
    select status, submitted_at, content
      into v_status, v_submitted_at, v_server_content
      from public.save_prediction_if_revision(
          v_race,
          v_league,
          v_content_2,
          date_trunc('milliseconds', v_revision)
      );

    if v_status = 'saved' and v_server_content = v_content_2 then
        insert into test_results values ('aggiornamento su revisione corrente', true, null);
        v_revision := v_submitted_at;
    else
        insert into test_results values ('aggiornamento su revisione corrente', false,
                                         coalesce(v_status, 'status nullo'));
    end if;

    -- 5. Duplicato identico dopo l'aggiornamento: idempotente.
    select status, submitted_at, content
      into v_status, v_submitted_at, v_server_content
      from public.save_prediction_if_revision(v_race, v_league, v_content_2, v_revision);

    if v_status = 'unchanged' and v_submitted_at is not distinct from v_revision then
        insert into test_results values ('duplicato identico idempotente', true, null);
    else
        insert into test_results values ('duplicato identico idempotente', false,
                                         coalesce(v_status, 'status nullo'));
    end if;

    -- 6. Revisione attesa ma riga cancellata: non ricreare.
    perform set_config('role', 'postgres', true);
    delete from public.predictions where race_id = v_race and user_id = v_user;
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_user::text, true);

    select status, submitted_at, content
      into v_status, v_submitted_at, v_server_content
      from public.save_prediction_if_revision(v_race, v_league, v_content_1, v_revision);

    if v_status = 'conflict' then
        insert into test_results values ('revisione attesa su riga cancellata', true, null);
    else
        insert into test_results values ('revisione attesa su riga cancellata', false,
                                         coalesce(v_status, 'status nullo'));
    end if;

    perform set_config('role', 'postgres', true);
    perform set_config('request.jwt.claim.sub', '', true);

    for v_status in
        select label || ' -> ' || case when passed then 'PASS' else 'FAIL' end
        from test_results order by label
    loop
        raise notice '%', v_status;
    end loop;

    if exists (select 1 from test_results where not passed) then
        raise exception 'TEST MIGRATION 0016: almeno un caso fallito (transazione annullata)';
    else
        raise exception 'TEST MIGRATION 0016: TUTTI SUPERATI (transazione annullata)';
    end if;
end
$test$;
