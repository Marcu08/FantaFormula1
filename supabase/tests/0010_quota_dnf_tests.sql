-- =============================================================================
-- FantaFormula 1 — test della banda di quota dedicata al DNF (0010)
-- File: supabase/tests/0010_quota_dnf_tests.sql
--
-- PREDISPOSTI, NON ESEGUITI (richiedono Supabase/test locale + migrazioni
-- 0009 e 0010). Eseguire come script unico nel SQL Editor; il block DO si
-- chiude sempre con un'eccezione = self-rollback (nessun dato residuo).
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
    values (v_user, 'Tester Quota DNF')
    on conflict (id) do nothing;

    insert into public.leagues (name, invite_code, rules, created_by)
    values ('Lega Test Quota DNF', 'TSTQDN', '{}', v_user)
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

    -- 1. DNF con quota 5.0 (possibile il lato del limite, non avviene): accettato.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_DNF',
                                   'driverIds', jsonb_build_array('PER'),
                                   'booleanValue', true,
                                   'quota', 5.0)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('DNF con quota 5.0 accettato', true, null);
    exception when others then
        insert into test_results values ('DNF con quota 5.0 accettato', false, SQLERRM);
    end;

    -- 2. DNF con quota 8.0 (esattamente al tetto): accettato.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_DNF',
                                   'driverIds', jsonb_build_array('ALB'),
                                   'booleanValue', false,
                                   'quota', 8.0)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('DNF con quota 8.0 al tetto accettato', true, null);
    exception when others then
        insert into test_results values ('DNF con quota 8.0 al tetto accettato', false, SQLERRM);
    end;

    -- 3. DNF con quota 8.1 (sopra il tetto dedicato): rifiutata.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_DNF',
                                   'driverIds', jsonb_build_array('PER'),
                                   'booleanValue', true,
                                   'quota', 8.1)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('DNF quota 8.1 rifiutata', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('DNF quota 8.1 rifiutata', true, null);
        else
            insert into test_results values ('DNF quota 8.1 rifiutata', false, SQLERRM);
        end if;
    end;

    -- 4. Punti over/under con quota 3.1 (sopra il range GENERICO): rifiutata
    --    anche se il DNF ora accetta fino a 8.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_POINTS_OVER_UNDER',
                                   'driverIds', jsonb_build_array('BOT'),
                                   'threshold', 2.5,
                                   'over', true,
                                   'quota', 3.1)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('punti quota 3.1 rifiutata', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('punti quota 3.1 rifiutata', true, null);
        else
            insert into test_results values ('punti quota 3.1 rifiutata', false, SQLERRM);
        end if;
    end;

    -- 5. Punti over/under con quota 3.0 (al limite generico): accettata.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_POINTS_OVER_UNDER',
                                   'driverIds', jsonb_build_array('BOT'),
                                   'threshold', 2.5,
                                   'over', false,
                                   'quota', 3.0)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('punti quota 3.0 accettata', true, null);
    exception when others then
        insert into test_results values ('punti quota 3.0 accettata', false, SQLERRM);
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
        raise exception 'TEST QUOTA DNF: % caso/i FALLITO/i (transazione annullata)', v_errors;
    else
        raise exception 'TEST QUOTA DNF: TUTTI SUPERATI (transazione annullata: nessun dato residuo)';
    end if;
end
$test$;
