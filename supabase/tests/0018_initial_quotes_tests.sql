-- =============================================================================
-- FantaFormula 1 - test della migrazione 0018_initial_quotes_after_predictions.sql
--
-- PREDISPOSTI, NON ESEGUITI. Richiedono un progetto Supabase di test con
-- 0001-0018 applicate e un utente reale in auth.users. Sostituire
-- '<UUID_USER>' con l'UUID. Il blocco termina sempre con un'eccezione:
-- self-rollback.
--
-- Copre:
--   - prima pubblicazione delle quote quando la gara ha gia' un pronostico
--     vintage ma race_quotes e' vuota;
--   - ripubblicazione identica (no-op);
--   - modifica dell'insieme dopo la pubblicazione: rifiutata.
-- =============================================================================

do $test$
declare
    v_user uuid := '<UUID_USER>';
    v_league uuid;
    v_season uuid;
    v_race uuid;
    v_count int;
    v_errors int := 0;
    v_msg text;

    v_quote_1 jsonb := jsonb_build_array(
        jsonb_build_object(
            'type', 'DRIVER_DNF',
            'key', 'VER|true',
            'threshold', null,
            'quota', 1.5
        )
    );
    v_quote_2 jsonb := jsonb_build_array(
        jsonb_build_object(
            'type', 'DRIVER_DNF',
            'key', 'VER|true',
            'threshold', null,
            'quota', 1.6
        )
    );
begin
    insert into public.profiles (id, display_name)
    values (v_user, 'Utente Test 0018')
    on conflict (id) do nothing;

    insert into public.leagues (name, invite_code, rules, created_by)
    values ('Lega Test 0018', 'TST18A', '{}', v_user)
    returning id into v_league;

    insert into public.league_members (league_id, user_id, role)
    values (v_league, v_user, 'admin');

    insert into public.seasons (league_id, name, is_active)
    values (v_league, '2026', true)
    returning id into v_season;

    insert into public.races (season_id, league_id, round, name, deadline)
    values (v_season, v_league, 1, 'Gara Test 0018', now() + interval '2 days')
    returning id into v_race;

    -- Pronostico vintage: esiste prima delle quote, ma non contiene tipi a
    -- quota (WINNER), quindi il trigger 0013 lo accetta.
    insert into public.predictions (race_id, league_id, user_id, content)
    values (
        v_race,
        v_league,
        v_user,
        '{"entries":[{"type":"WINNER","driverIds":["VER"]}]}'::jsonb
    );

    create temp table test_results (label text, passed boolean, detail text);
    grant all on test_results to authenticated, anon;

    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_user::text, true);

    -- 1. Prima pubblicazione con pronostico esistente e race_quotes vuota.
    begin
        perform public.publish_race_quotes(v_race, v_quote_1);
        select count(*) into v_count from public.race_quotes where race_id = v_race;
        insert into test_results
        values ('prima pubblicazione con pronostico vintage', v_count = 1, 'righe ' || v_count);
    exception when others then
        insert into test_results
        values ('prima pubblicazione con pronostico vintage', false, SQLERRM);
    end;

    -- 2. Ripubblicazione identica: no-op.
    begin
        perform public.publish_race_quotes(v_race, v_quote_1);
        select count(*) into v_count from public.race_quotes where race_id = v_race;
        insert into test_results
        values ('ripubblicazione identica no-op', v_count = 1, 'righe ' || v_count);
    exception when others then
        insert into test_results
        values ('ripubblicazione identica no-op', false, SQLERRM);
    end;

    -- 3. Modifica dell'insieme dopo la pubblicazione: rifiutata.
    begin
        perform public.publish_race_quotes(v_race, v_quote_2);
        insert into test_results
        values ('modifica insieme pubblicato rifiutata', false, 'nessun errore sollevato');
    exception when others then
        insert into test_results
        values (
            'modifica insieme pubblicato rifiutata',
            SQLERRM like '%Non puoi modificare le quote%',
            SQLERRM
        );
    end;

    perform set_config('role', 'postgres', true);
    perform set_config('request.jwt.claim.sub', '', true);

    select count(*) into v_errors from test_results where not passed;
    for v_msg in
        select label || ' -> ' || case when passed then 'PASS' else 'FAIL' end
        from test_results order by label
    loop
        raise notice '%', v_msg;
    end loop;

    if v_errors > 0 then
        raise exception 'TEST MIGRATION 0018: % caso/i FALLITO/i (transazione annullata)', v_errors;
    else
        raise exception 'TEST MIGRATION 0018: TUTTI SUPERATI (transazione annullata)';
    end if;
end
$test$;
