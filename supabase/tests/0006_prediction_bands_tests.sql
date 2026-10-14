-- =============================================================================
-- FantaFormula 1 — test delle fasce obbligatorie
-- File: supabase/tests/0006_prediction_bands_tests.sql
--
-- PREDISPOSTI, NON ESEGUITI (richiedono Supabase/test locale + migrazione 0006).
-- Eseguire come script unico nel SQL Editor; il block DO si chiude sempre con
-- un'eccezione = self-rollback (nessun dato residuo).
-- =============================================================================

do $test$
declare
    v_user uuid := '<UUID_UTENTE>';
    v_league uuid;
    v_season uuid;
    v_race uuid;
    v_errors int := 0;
    v_msg text;
begin
    insert into public.profiles (id, display_name)
    values (v_user, 'Tester Fasce')
    on conflict (id) do nothing;

    insert into public.leagues (name, invite_code, rules, created_by)
    values ('Lega Test Fasce', 'TSTFAS', '{}', v_user)
    returning id into v_league;

    insert into public.league_members (league_id, user_id, role)
    values (v_league, v_user, 'admin');

    insert into public.seasons (league_id, name, is_active)
    values (v_league, '2026', true)
    returning id into v_season;

    insert into public.races (season_id, league_id, round, name, deadline)
    values (v_season, v_league, 1, 'Gara Test', now() + interval '2 days')
    returning id into v_race;

    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_user::text, true);
    create temp table test_results (label text, passed boolean, detail text);

    -- 1. Fascia predefinita 6-10: valida.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_POSITION_RANGE',
                                   'driverIds', jsonb_build_array('VER'),
                                   'rangeMin', 6, 'rangeMax', 10)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('fascia 6-10 valida', true, null);
    exception when others then
        insert into test_results values ('fascia 6-10 valida', false, SQLERRM);
    end;

    -- 2. Banda libera 1-22: rifiutata (già sempre vinta).
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_POSITION_RANGE',
                                   'driverIds', jsonb_build_array('VER'),
                                   'rangeMin', 1, 'rangeMax', 22)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('fascia 1-22 rifiutata', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('fascia 1-22 rifiutata', true, null);
        else
            insert into test_results values ('fascia 1-22 rifiutata', false, SQLERRM);
        end if;
    end;

    -- 3. Banda personalizzata 3-8 (stretta ma non tra le fasce): rifiutata.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_POSITION_RANGE',
                                   'driverIds', jsonb_build_array('VER'),
                                   'rangeMin', 3, 'rangeMax', 8)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('fascia 3-8 rifiutata', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('fascia 3-8 rifiutata', true, null);
        else
            insert into test_results values ('fascia 3-8 rifiutata', false, SQLERRM);
        end if;
    end;

    -- 4. Fascia 1-5 e 11-15 e 16-22 (fascia lunga massima): valide.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_POSITION_RANGE',
                                   'driverIds', jsonb_build_array('VER'),
                                   'rangeMin', 16, 'rangeMax', 22)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('fascia 16-22 valida', true, null);
    exception when others then
        insert into test_results values ('fascia 16-22 valida', false, SQLERRM);
    end;

    select count(*) into v_errors from test_results where not passed;
    for v_msg in select label || coalesce(' — ' || detail, '') || ' -> ' ||
                        case when passed then 'PASS' else 'FAIL' end
                 from test_results order by label loop
        raise notice '%', v_msg;
    end loop;

    perform set_config('role', 'postgres', true);
    perform set_config('request.jwt.claim.sub', '', true);

    if v_errors > 0 then
        raise exception 'TEST FASCE: % caso/i FALLITO/i (transazione annullata)', v_errors;
    else
        raise exception 'TEST FASCE: TUTTI SUPERATI (transazione annullata: nessun dato residuo)';
    end if;
end
$test$;