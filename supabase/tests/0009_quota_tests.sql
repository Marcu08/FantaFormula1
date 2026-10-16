-- =============================================================================
-- FantaFormula 1 — test della quota congelata nei pronostici
-- File: supabase/tests/0009_quota_tests.sql
--
-- PREDISPOSTI, NON ESEGUITI (richiedono Supabase/test locale + migrazione 0009).
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
    values (v_user, 'Tester Quota')
    on conflict (id) do nothing;

    insert into public.leagues (name, invite_code, rules, created_by)
    values ('Lega Test Quota', 'TSTQT9', '{}', v_user)
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

    -- 1. DNF con quota valida: accettato.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_DNF',
                                   'driverIds', jsonb_build_array('PER'),
                                   'booleanValue', true,
                                   'quota', 2.5)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('DNF con quota 2.5 accettato', true, null);
    exception when others then
        insert into test_results values ('DNF con quota 2.5 accettato', false, SQLERRM);
    end;

    -- 2. DNF senza quota (client vecchio): rifiutato alle nuove scritture.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_DNF',
                                   'driverIds', jsonb_build_array('PER'),
                                   'booleanValue', true)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('DNF senza quota rifiutato', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('DNF senza quota rifiutato', true, null);
        else
            insert into test_results values ('DNF senza quota rifiutato', false, SQLERRM);
        end if;
    end;

    -- 3. DNF con quota fuori range (4.0): rifiutato.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_DNF',
                                   'driverIds', jsonb_build_array('PER'),
                                   'booleanValue', false,
                                   'quota', 4.0)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('DNF quota 4.0 rifiutato', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('DNF quota 4.0 rifiutato', true, null);
        else
            insert into test_results values ('DNF quota 4.0 rifiutato', false, SQLERRM);
        end if;
    end;

    -- 4. Punti soglia mediana (2.5) con quota: accettato.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_POINTS_OVER_UNDER',
                                   'driverIds', jsonb_build_array('BOT'),
                                   'threshold', 2.5,
                                   'over', true,
                                   'quota', 1.5)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('punti soglia 2.5 con quota accettato', true, null);
    exception when others then
        insert into test_results values ('punti soglia 2.5 con quota accettato', false, SQLERRM);
    end;

    -- 5. Punti soglia NON multiplo di 0.5 (2.3): rifiutato.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_POINTS_OVER_UNDER',
                                   'driverIds', jsonb_build_array('BOT'),
                                   'threshold', 2.3,
                                   'over', true,
                                   'quota', 1.5)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('punti soglia 2.3 rifiutata', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('punti soglia 2.3 rifiutata', true, null);
        else
            insert into test_results values ('punti soglia 2.3 rifiutata', false, SQLERRM);
        end if;
    end;

    -- 6. Punti soglia fuori range (26): rifiutato.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_POINTS_OVER_UNDER',
                                   'driverIds', jsonb_build_array('BOT'),
                                   'threshold', 26,
                                   'over', true,
                                   'quota', 1.5)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('punti soglia 26 rifiutata', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('punti soglia 26 rifiutata', true, null);
        else
            insert into test_results values ('punti soglia 26 rifiutata', false, SQLERRM);
        end if;
    end;

    -- 7. Testa a testa con quota (fasce reali): accettato.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'HEAD_TO_HEAD',
                                   'driverIds', jsonb_build_array('VER', 'BOR'),
                                   'quota', 0.6)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('H2H con quota consentita accettato', true, null);
    exception when others then
        insert into test_results values ('H2H con quota consentita accettato', false, SQLERRM);
    end;

    -- 8. Testa a testa senza quota: rifiutato.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'HEAD_TO_HEAD',
                                   'driverIds', jsonb_build_array('VER', 'BOR'))))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('H2H senza quota rifiutato', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('H2H senza quota rifiutato', true, null);
        else
            insert into test_results values ('H2H senza quota rifiutato', false, SQLERRM);
        end if;
    end;

    -- 9. Quota con null esplicito: rifiutata dove obbligatoria (testa a testa).
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'HEAD_TO_HEAD',
                                   'driverIds', jsonb_build_array('VER', 'BOR'),
                                   'quota', null)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('H2H quota null rifiutato', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('H2H quota null rifiutato', true, null);
        else
            insert into test_results values ('H2H quota null rifiutato', false, SQLERRM);
        end if;
    end;

    -- 10. Tipo senza quota (fascia posizione): quota assente + campo quota
    --     NO (i tipi senza quote NON ancora di quota) ma possiamo mandare
    --     un "quota" null in alta selezione. Accettato (quota null).
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_POSITION_RANGE',
                                   'driverIds', jsonb_build_array('VER'),
                                   'rangeMin', 1, 'rangeMax', 5,
                                   'quota', null)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('fascia con quota null accettata', true, null);
    exception when others then
        insert into test_results values ('fascia con quota null accettata', false, SQLERRM);
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
        raise exception 'TEST QUOTA: % caso/i FALLITO/i (transazione annullata)', v_errors;
    else
        raise exception 'TEST QUOTA: TUTTI SUPERATI (transazione annullata: nessun dato residuo)';
    end if;
end
$test$;
