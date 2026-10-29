-- =============================================================================
-- FantaFormula 1 — test della migrazione 0013_server_quotes.sql
-- File: supabase/tests/0013_server_quotes_tests.sql
--
-- PREDISPOSTI, NON ESEGUITI (richiedono Supabase di test + migrazioni
-- 0001-0013). Eseguire come script unico nel SQL Editor; il block DO si
-- chiude sempre con un'eccezione = self-rollback (nessun dato residuo).
--
-- Sostituire '<UUID_ADMIN>' e '<UUID_GIOCATORE>' con due utenti REALI del
-- progetto di test (profiles.id ha FK verso auth.users, come nei test 0011):
-- l'admin verra' inserito come amministratore della lega, il giocatore come
-- membro semplice.
-- =============================================================================

do $test$
declare
    v_admin uuid := '<UUID_ADMIN>';
    v_user  uuid := '<UUID_GIOCATORE>';
    v_league uuid;
    v_season uuid;
    v_race uuid;
    v_quotes jsonb;
    v_count int;
    v_errors int := 0;
    v_msg text;
begin
    -- ---------------------------------------------------------------- setup
    insert into public.profiles (id, display_name)
    values (v_admin, 'Admin Test 0013'), (v_user, 'Giocatore Test 0013')
    on conflict (id) do nothing;

    insert into public.leagues (name, invite_code, rules, created_by)
    values ('Lega Test 0013', 'TST13Q', '{}', v_admin)
    returning id into v_league;

    insert into public.league_members (league_id, user_id, role)
    values (v_league, v_admin, 'admin'),
           (v_league, v_user, 'player');

    insert into public.seasons (league_id, name, is_active)
    values (v_league, '2026', true)
    returning id into v_season;

    insert into public.races (season_id, league_id, round, name, deadline)
    values (v_season, v_league, 1, 'Gara Test 0013', now() + interval '2 days')
    returning id into v_race;

    v_quotes := jsonb_build_array(
        jsonb_build_object('type', 'DRIVER_DNF', 'key', 'PER|true', 'quota', 5.0),
        jsonb_build_object('type', 'DRIVER_DNF', 'key', 'PER|false', 'quota', 1.5),
        jsonb_build_object('type', 'DRIVER_POINTS_OVER_UNDER', 'key', 'VER|true',
                           'threshold', 12.5, 'quota', 1.6),
        jsonb_build_object('type', 'DRIVER_POINTS_OVER_UNDER', 'key', 'VER|false',
                           'threshold', 12.5, 'quota', 1.9),
        jsonb_build_object('type', 'HEAD_TO_HEAD', 'key', 'LEC|VER|LEC', 'quota', 0.8)
    );

    -- ------------------------------------------------- contesto autenticato
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin::text, true);
    create temp table test_results (label text, passed boolean, detail text);

    -- 1. Pubblicazione con quota fuori range: rifiutata (validazione jsonb).
    begin
        perform public.publish_race_quotes(
            v_race,
            jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_DNF', 'key', 'PER|true', 'quota', 9.0)
            )
        );
        insert into test_results values ('publish quota DNF 9.0 rifiutata', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like '%quota fuori range%' then
            insert into test_results values ('publish quota DNF 9.0 rifiutata', true, null);
        else
            insert into test_results values ('publish quota DNF 9.0 rifiutata', false, SQLERRM);
        end if;
    end;

    -- 2. Coppia testa a testa non canonica: rifiutata.
    begin
        perform public.publish_race_quotes(
            v_race,
            jsonb_build_array(
                jsonb_build_object('type', 'HEAD_TO_HEAD', 'key', 'VER|LEC|LEC', 'quota', 0.8)
            )
        );
        insert into test_results values ('publish testa a testa non canonico rifiutato', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like '%ordine canonico%' then
            insert into test_results values ('publish testa a testa non canonico rifiutato', true, null);
        else
            insert into test_results values ('publish testa a testa non canonico rifiutato', false, SQLERRM);
        end if;
    end;

    -- 3. Pubblicazione valida (admin).
    begin
        perform public.publish_race_quotes(v_race, v_quotes);
        insert into test_results values ('publish quote valide (admin)', true, null);
    exception when others then
        insert into test_results values ('publish quote valide (admin)', false, SQLERRM);
    end;

    -- 4. Un membro legge le quote (RLS SELECT).
    perform set_config('request.jwt.claim.sub', v_user::text, true);
    begin
        select count(*) into v_count
        from public.race_quotes
        where race_id = v_race;
        insert into test_results values ('membro legge le quote (5 righe)',
                                         v_count = 5, 'lette ' || v_count);
    exception when others then
        insert into test_results values ('membro legge le quote (5 righe)', false, SQLERRM);
    end;

    -- 5. Un NON admin non puo' pubblicare.
    begin
        perform public.publish_race_quotes(v_race, v_quotes);
        insert into test_results values ('publish da non-admin rifiutato', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like '%amministratore%' then
            insert into test_results values ('publish da non-admin rifiutato', true, null);
        else
            insert into test_results values ('publish da non-admin rifiutato', false, SQLERRM);
        end if;
    end;

    -- 6. Scrittura diretta sulla tabella: rifiutata (privilegi/RLS).
    begin
        insert into public.race_quotes (race_id, league_id, type, key, quota)
        values (v_race, v_league, 'DRIVER_DNF', 'PER|true', 5.0);
        insert into test_results values ('insert diretto su race_quotes rifiutato', false, 'inserimento riuscito');
    exception when others then
        insert into test_results values ('insert diretto su race_quotes rifiutato', true, null);
    end;

    -- 7. Quota alterata rispetto a quella pubblicata: rifiutata.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_DNF',
                                   'driverIds', jsonb_build_array('PER'),
                                   'booleanValue', true,
                                   'quota', 8.0)))
        );
        insert into test_results values ('quota alterata rifiutata', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like '%quota non valida%' then
            insert into test_results values ('quota alterata rifiutata', true, null);
        else
            insert into test_results values ('quota alterata rifiutata', false, SQLERRM);
        end if;
    end;

    -- 8. Soglia alterata rispetto a quella pubblicata: rifiutata.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_POINTS_OVER_UNDER',
                                   'driverIds', jsonb_build_array('VER'),
                                   'threshold', 13.0,
                                   'over', true,
                                   'quota', 1.6)))
        );
        insert into test_results values ('soglia alterata rifiutata', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like '%soglia non valida%' then
            insert into test_results values ('soglia alterata rifiutata', true, null);
        else
            insert into test_results values ('soglia alterata rifiutata', false, SQLERRM);
        end if;
    end;

    -- 9. Quota mancante per la scelta: rifiutata con messaggio dedicato.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_DNF',
                                   'driverIds', jsonb_build_array('HAM'),
                                   'booleanValue', true,
                                   'quota', 5.0)))
        );
        insert into test_results values ('quote non pubblicate rifiutate', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like '%quote non ancora pubblicate per questa gara%' then
            insert into test_results values ('quote non pubblicate rifiutate', true, null);
        else
            insert into test_results values ('quote non pubblicate rifiutate', false, SQLERRM);
        end if;
    end;

    -- 10. Pronostico corretto (quota e soglia pubblicate): accettato.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_DNF',
                                   'driverIds', jsonb_build_array('PER'),
                                   'booleanValue', true,
                                   'quota', 5.0),
                jsonb_build_object('type', 'DRIVER_POINTS_OVER_UNDER',
                                   'driverIds', jsonb_build_array('VER'),
                                   'threshold', 12.5,
                                   'over', true,
                                   'quota', 1.6),
                jsonb_build_object('type', 'HEAD_TO_HEAD',
                                   'driverIds', jsonb_build_array('LEC', 'VER'),
                                   'quota', 0.8)))
        );
        insert into test_results values ('pronostico con quote corrette accettato', true, null);
    exception when others then
        insert into test_results values ('pronostico con quote corrette accettato', false, SQLERRM);
    end;

    -- 11. Ripubblicare lo STESSO insieme dopo i pronostici: no-op consentito.
    perform set_config('request.jwt.claim.sub', v_admin::text, true);
    begin
        perform public.publish_race_quotes(v_race, v_quotes);
        insert into test_results values ('ripubblicazione identica consentita', true, null);
    exception when others then
        insert into test_results values ('ripubblicazione identica consentita', false, SQLERRM);
    end;

    -- 12. Modificare una quota dopo i pronostici: rifiutato (immutabilita').
    begin
        perform public.publish_race_quotes(
            v_race,
            jsonb_set(v_quotes, '{0,quota}', '6.0'::jsonb)
        );
        insert into test_results values ('modifica quote dopo pronostici rifiutata', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like '%Non puoi modificare le quote%' then
            insert into test_results values ('modifica quote dopo pronostici rifiutata', true, null);
        else
            insert into test_results values ('modifica quote dopo pronostici rifiutata', false, SQLERRM);
        end if;
    end;

    -- ------------------------------------------------------------ esiti
    select count(*) into v_errors from test_results where not passed;
    for v_msg in select label || coalesce(' — ' || detail, '') || ' -> ' ||
                        case when passed then 'PASS' else 'FAIL' end
                 from test_results order by label loop
        raise notice '%', v_msg;
    end loop;

    perform set_config('role', 'postgres', true);
    perform set_config('request.jwt.claim.sub', '', true);

    if v_errors > 0 then
        raise exception 'TEST MIGRATION 0013: % caso/i FALLITO/i (transazione annullata)', v_errors;
    else
        raise exception 'TEST MIGRATION 0013: TUTTI SUPERATI (transazione annullata: nessun dato residuo)';
    end if;
end
$test$;
