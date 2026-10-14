-- =============================================================================
-- FantaFormula 1 — test della validazione server-side dei pronostici
-- File: supabase/tests/0004_prediction_validation_tests.sql
--
-- ⚠️  STATO: PREDISPOSTI, NON ESEGUITI. Richiedono un progetto Supabase di
--     test (o `supabase db reset` locale) con ALMENO DUE utenti registrati
--     e la migrazione 0004 applicata. Eseguire come script unico nel SQL
--     Editor con il ruolo postgres (default nel SQL Editor).
--
--     PRIMA di eseguire: sostituire '<UUID_UTENTE_A>' e '<UUID_UTENTE_B>'
--     con due utenti reali di auth.users del progetto di test.
--
--     Il blocco DO si chiude SEMPRE con un'eccezione: i NOTICE con i
--     PASS/FAIL restano visibili e la transazione viene annullata, quindi
--     nessun dato di test resta nel progetto (self-rollback).
--
-- Copertura (27 casi):
--   A. pronostico valido (completo e parziale)
--   L. upsert con contenuto NON valido su riga esistente: il percorso
--      UPDATE di ON CONFLICT DO UPDATE deve passare dal trigger
--   B. JSON malformato (contenuto non oggetto / entries non array)
--   C. tipi di campo errati (position stringa, tipo sconosciuto)
--   D. valori fuori intervallo (position 0/23/3.5/3.0, fascia min>max, soglia -1)
--   E. identificativi duplicati (piloti ripetuti, tipi ripetuti, conteggi
--      errati, driverId vuoto, elemento non stringa su tipo senza piloti)
--   F. sprint: rifiutato su gara senza sprint, valido su gara con sprint
--   J. boundary positivi: position 22, soglia 0
--   G. tipo non attivo per la lega (enabledTypes); K. enabledTypes non-array
--      degrada senza bloccare (coerenza con il client B22)
--   H. utente NON membro e invio dopo la scadenza (RLS)
--   I. tentativo di aggirare i vincoli (race/league disallineati: FK composite)
--
-- Nota: i casi usano INSERT ... ON CONFLICT DO UPDATE (upsert), la stessa
-- forma di scrittura dell'app, cosi' il vincolo unique (race_id, user_id)
-- non maschera gli errori di validazione.
-- =============================================================================

do $test$
declare
    v_user_a uuid := '<UUID_UTENTE_A>';
    v_user_b uuid := '<UUID_UTENTE_B>';
    v_league uuid;
    v_season uuid;
    v_race uuid;        -- gara normale, deadline futura
    v_race_sprint uuid; -- gara con sprint, deadline futura
    v_race_past uuid;   -- gara con deadline passata
    v_errors int := 0;
    v_msg text;
begin
    -- ------------------------------------------------------------------
    -- FIXTURE (creazione come amministratore: il proprietario bypassa RLS)
    -- ------------------------------------------------------------------
    insert into public.profiles (id, display_name)
    values (v_user_a, 'Tester A'), (v_user_b, 'Tester B')
    on conflict (id) do nothing;

    insert into public.leagues (name, invite_code, rules, created_by)
    values ('Lega Test Validazione', 'TSTVAL', '{}', v_user_a)
    returning id into v_league;

    insert into public.league_members (league_id, user_id, role)
    values (v_league, v_user_a, 'admin');

    insert into public.seasons (league_id, name, is_active)
    values (v_league, '2026', true)
    returning id into v_season;

    insert into public.races (season_id, league_id, round, name, deadline)
    values (v_season, v_league, 1, 'Gara Test', now() + interval '2 days')
    returning id into v_race;

    insert into public.races (season_id, league_id, round, name, is_sprint, deadline)
    values (v_season, v_league, 2, 'Gara Sprint Test', true, now() + interval '3 days')
    returning id into v_race_sprint;

    insert into public.races (season_id, league_id, round, name, deadline)
    values (v_season, v_league, 3, 'Gara Passata Test', now() - interval '1 hour')
    returning id into v_race_past;

    create temp table test_results (label text, passed boolean, detail text);

    -- ------------------------------------------------------------------
    -- Impersona l'utente A (membro admin della lega di test).
    -- ------------------------------------------------------------------
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_user_a::text, true);

    -- ------------------------------------------------------------------
    -- A1. Pronostico completo valido (forma identica a quella scritta
    --     dall'app: campi null espliciti perche' AppJson ha encodeDefaults).
    -- ------------------------------------------------------------------
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user_a,
            jsonb_build_object(
                'raceId', v_race,
                'entries', jsonb_build_array(
                    jsonb_build_object(
                        'type', 'WINNER',
                        'driverIds', jsonb_build_array('VER'),
                        'position', null, 'rangeMin', null, 'rangeMax', null,
                        'threshold', null, 'over', null, 'booleanValue', null
                    ),
                    jsonb_build_object(
                        'type', 'PODIUM',
                        'driverIds', jsonb_build_array('VER', 'NOR', 'LEC'),
                        'position', null, 'rangeMin', null, 'rangeMax', null,
                        'threshold', null, 'over', null, 'booleanValue', null
                    )
                ),
                'submittedAtEpochMs', (extract(epoch from now()) * 1000)::bigint
            )
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('A1 pronostico completo valido', true, null);
    exception when others then
        insert into test_results values ('A1 pronostico completo valido', false, SQLERRM);
    end;

    -- A2. Pronostico parziale ammesso (solo WINNER, decisione B15).
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user_a,
            jsonb_build_object(
                'entries', jsonb_build_array(
                    jsonb_build_object('type', 'WINNER', 'driverIds', jsonb_build_array('NOR'))
                )
            )
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('A2 pronostico parziale ammesso', true, null);
    exception when others then
        insert into test_results values ('A2 pronostico parziale ammesso', false, SQLERRM);
    end;

    -- L1. Upsert con contenuto NON valido su riga gia' esistente: anche il
    --     percorso UPDATE di ON CONFLICT DO UPDATE deve passare dal trigger.
    --     Se il trigger non scattasse sull'update, questa scrittura avrebbe
    --     successo e il caso fallirebbe.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user_a,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'TIPO_INVENTATO',
                                   'driverIds', jsonb_build_array('VER'))))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('L1 upsert non valido su riga esistente rifiutato', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('L1 upsert non valido su riga esistente rifiutato', true, null);
        else
            insert into test_results values ('L1 upsert non valido su riga esistente rifiutato', false, SQLERRM);
        end if;
    end;

    -- ------------------------------------------------------------------
    -- B1. JSON malformato: entries non array.
    -- ------------------------------------------------------------------
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (v_race, v_league, v_user_a, jsonb_build_object('entries', 'nope'))
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('B1 entries non array rifiutato', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('B1 entries non array rifiutato', true, null);
        else
            insert into test_results values ('B1 entries non array rifiutato', false, SQLERRM);
        end if;
    end;

    -- B2. Contenuto non oggetto.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (v_race, v_league, v_user_a, '[1,2,3]'::jsonb)
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('B2 contenuto non oggetto rifiutato', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('B2 contenuto non oggetto rifiutato', true, null);
        else
            insert into test_results values ('B2 contenuto non oggetto rifiutato', false, SQLERRM);
        end if;
    end;

    -- C1. Tipo di campo errato: position stringa invece di numero.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user_a,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_EXACT_POSITION',
                                   'driverIds', jsonb_build_array('VER'),
                                   'position', '3')))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('C1 position stringa rifiutata', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('C1 position stringa rifiutata', true, null);
        else
            insert into test_results values ('C1 position stringa rifiutata', false, SQLERRM);
        end if;
    end;

    -- C2. Tipo sconosciuto.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user_a,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'TIPO_INVENTATO',
                                   'driverIds', jsonb_build_array('VER'))))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('C2 tipo sconosciuto rifiutato', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('C2 tipo sconosciuto rifiutato', true, null);
        else
            insert into test_results values ('C2 tipo sconosciuto rifiutato', false, SQLERRM);
        end if;
    end;

    -- D1. Posizione fuori intervallo: 0.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user_a,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_EXACT_POSITION',
                                   'driverIds', jsonb_build_array('VER'), 'position', 0)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('D1 position 0 rifiutata', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('D1 position 0 rifiutata', true, null);
        else
            insert into test_results values ('D1 position 0 rifiutata', false, SQLERRM);
        end if;
    end;

    -- D2. Posizione 23.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user_a,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_EXACT_POSITION',
                                   'driverIds', jsonb_build_array('VER'), 'position', 23)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('D2 position 23 rifiutata', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('D2 position 23 rifiutata', true, null);
        else
            insert into test_results values ('D2 position 23 rifiutata', false, SQLERRM);
        end if;
    end;

    -- D3. Posizione non intera: 3.5.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user_a,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_EXACT_POSITION',
                                   'driverIds', jsonb_build_array('VER'), 'position', 3.5)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('D3 position 3.5 rifiutata', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('D3 position 3.5 rifiutata', true, null);
        else
            insert into test_results values ('D3 position 3.5 rifiutata', false, SQLERRM);
        end if;
    end;

    -- D4. Fascia con min > max.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user_a,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_POSITION_RANGE',
                                   'driverIds', jsonb_build_array('VER'),
                                   'rangeMin', 6, 'rangeMax', 4)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('D4 fascia min>max rifiutata', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('D4 fascia min>max rifiutata', true, null);
        else
            insert into test_results values ('D4 fascia min>max rifiutata', false, SQLERRM);
        end if;
    end;

    -- D5. Soglia negativa.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user_a,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'SAFETY_CAR_COUNT_OVER_UNDER',
                                   'threshold', -1, 'over', true)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('D5 soglia negativa rifiutata', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('D5 soglia negativa rifiutata', true, null);
        else
            insert into test_results values ('D5 soglia negativa rifiutata', false, SQLERRM);
        end if;
    end;

    -- D6. Posizione come token non intero ("3.0"): numericamente integra ma
    --     kotlinx non la decodifica in Int -> deve essere rifiutata.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user_a,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_EXACT_POSITION',
                                   'driverIds', jsonb_build_array('VER'), 'position', 3.0)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('D6 position 3.0 rifiutata', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('D6 position 3.0 rifiutata', true, null);
        else
            insert into test_results values ('D6 position 3.0 rifiutata', false, SQLERRM);
        end if;
    end;

    -- E1. Piloti duplicati (HEAD_TO_HEAD con lo stesso pilota due volte).
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user_a,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'HEAD_TO_HEAD',
                                   'driverIds', jsonb_build_array('VER', 'VER'))))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('E1 piloti duplicati rifiutati', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('E1 piloti duplicati rifiutati', true, null);
        else
            insert into test_results values ('E1 piloti duplicati rifiutati', false, SQLERRM);
        end if;
    end;

    -- E2. Tipi ripetuti nella stessa previsione.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user_a,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'WINNER', 'driverIds', jsonb_build_array('VER')),
                jsonb_build_object('type', 'WINNER', 'driverIds', jsonb_build_array('NOR'))))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('E2 tipi ripetuti rifiutati', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('E2 tipi ripetuti rifiutati', true, null);
        else
            insert into test_results values ('E2 tipi ripetuti rifiutati', false, SQLERRM);
        end if;
    end;

    -- E3. Numero di piloti errato (WINNER con 2 piloti).
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user_a,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'WINNER',
                                   'driverIds', jsonb_build_array('VER', 'NOR'))))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('E3 numero piloti errato rifiutato', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('E3 numero piloti errato rifiutato', true, null);
        else
            insert into test_results values ('E3 numero piloti errato rifiutato', false, SQLERRM);
        end if;
    end;

    -- E4. driverId vuoto.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user_a,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'WINNER', 'driverIds', jsonb_build_array(' '))))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('E4 driverId vuoto rifiutato', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('E4 driverId vuoto rifiutato', true, null);
        else
            insert into test_results values ('E4 driverId vuoto rifiutato', false, SQLERRM);
        end if;
    end;

    -- E5. driverIds con elemento non stringa su tipo senza piloti (required=0):
    --     l'app scrive "driverIds": [], un payload diretto con numeri va rifiutato.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user_a,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'SAFETY_CAR_COUNT_OVER_UNDER',
                                   'driverIds', jsonb_build_array(5),
                                   'threshold', 2, 'over', true)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('E5 driverIds non stringa su tipo senza piloti rifiutato', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('E5 driverIds non stringa su tipo senza piloti rifiutato', true, null);
        else
            insert into test_results values ('E5 driverIds non stringa su tipo senza piloti rifiutato', false, SQLERRM);
        end if;
    end;

    -- F1. SPRINT_WINNER su gara senza sprint.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user_a,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'SPRINT_WINNER',
                                   'driverIds', jsonb_build_array('VER'))))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('F1 sprint su gara senza sprint rifiutato', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('F1 sprint su gara senza sprint rifiutato', true, null);
        else
            insert into test_results values ('F1 sprint su gara senza sprint rifiutato', false, SQLERRM);
        end if;
    end;

    -- F2. SPRINT_WINNER su gara con sprint: valido.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race_sprint, v_league, v_user_a,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'SPRINT_WINNER',
                                   'driverIds', jsonb_build_array('VER'))))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('F2 sprint su gara con sprint valido', true, null);
    exception when others then
        insert into test_results values ('F2 sprint su gara con sprint valido', false, SQLERRM);
    end;

    -- J1. Boundary positivo: posizione 22 (MAX_DRIVERS 2026) valida.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user_a,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'DRIVER_EXACT_POSITION',
                                   'driverIds', jsonb_build_array('HAD'), 'position', 22)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('J1 position 22 valida', true, null);
    exception when others then
        insert into test_results values ('J1 position 22 valida', false, SQLERRM);
    end;

    -- J2. Boundary positivo: soglia 0 con over false (Under) valida.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user_a,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'PIT_STOPS_OVER_UNDER',
                                   'threshold', 0, 'over', false)))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('J2 soglia 0 valida', true, null);
    exception when others then
        insert into test_results values ('J2 soglia 0 valida', false, SQLERRM);
    end;

    -- G. Tipo non attivo per la lega (enabledTypes vuoto), poi ripristino.
    --    L'update delle regole passa: A e' admin della lega di test.
    update public.leagues set rules = '{"enabledTypes": []}' where id = v_league;
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user_a,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'WINNER', 'driverIds', jsonb_build_array('LEC'))))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('G tipo non attivo rifiutato', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like 'PRONOSTICO NON VALIDO%' then
            insert into test_results values ('G tipo non attivo rifiutato', true, null);
        else
            insert into test_results values ('G tipo non attivo rifiutato', false, SQLERRM);
        end if;
    end;
    update public.leagues set rules = '{}' where id = v_league;

    -- K1. enabledTypes presente ma NON array (degrada come il client, B22):
    --     il check viene saltato e un contenuto valido passa senza errore.
    update public.leagues set rules = '{"enabledTypes": "WINNER"}' where id = v_league;
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user_a,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'POLE', 'driverIds', jsonb_build_array('LEC'))))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('K1 enabledTypes non array degrada senza bloccare', true, null);
    exception when others then
        insert into test_results values ('K1 enabledTypes non array degrada senza bloccare', false, SQLERRM);
    end;
    update public.leagues set rules = '{}' where id = v_league;

    -- ------------------------------------------------------------------
    -- H1. Utente NON membro (B): la RLS blocca anche contenuto valido.
    -- ------------------------------------------------------------------
    perform set_config('request.jwt.claim.sub', v_user_b::text, true);
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race, v_league, v_user_b,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'WINNER', 'driverIds', jsonb_build_array('ALB'))))
        );
        insert into test_results values ('H1 non membro bloccato da RLS', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM ilike '%row-level security%' or SQLERRM ilike '%permission denied%' then
            insert into test_results values ('H1 non membro bloccato da RLS', true, null);
        else
            insert into test_results values ('H1 non membro bloccato da RLS', false, SQLERRM);
        end if;
    end;

    -- H2. Dopo la scadenza: si aggiunge B come membro, poi l'invio sulla
    --     gara con deadline passata viene bloccato dalla RLS.
    perform set_config('role', 'postgres', true);
    perform set_config('request.jwt.claim.sub', '', true);
    insert into public.league_members (league_id, user_id, role)
    values (v_league, v_user_b, 'player')
    on conflict (league_id, user_id) do nothing;
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_user_b::text, true);
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race_past, v_league, v_user_b,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'WINNER', 'driverIds', jsonb_build_array('ALB'))))
        );
        insert into test_results values ('H2 dopo deadline bloccato da RLS', false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM ilike '%row-level security%' or SQLERRM ilike '%permission denied%' then
            insert into test_results values ('H2 dopo deadline bloccato da RLS', true, null);
        else
            insert into test_results values ('H2 dopo deadline bloccato da RLS', false, SQLERRM);
        end if;
    end;

    -- I. Aggiramento via API diretta: race_id di una gara della lega con
    --    league_id di UN'ALTRA lega -> bloccato (RLS e/o FK composite di 0002).
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race_sprint,
            coalesce((select id from public.leagues where id <> v_league limit 1), gen_random_uuid()),
            v_user_b,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object('type', 'WINNER', 'driverIds', jsonb_build_array('ALB'))))
        );
        insert into test_results values ('I race/league disallineati bloccati', false, 'nessun errore sollevato');
    exception when others then
        -- Qualsiasi errore (RLS, FK composite) va bene: l'importante e' il blocco.
        insert into test_results values ('I race/league disallineati bloccati', true, null);
    end;

    -- ------------------------------------------------------------------
    -- Esito
    -- ------------------------------------------------------------------
    select count(*) into v_errors from test_results where not passed;
    for v_msg in select label || coalesce(' — ' || detail, '') || ' -> ' ||
                        case when passed then 'PASS' else 'FAIL' end
                 from test_results order by label loop
        raise notice '%', v_msg;
    end loop;

    perform set_config('role', 'postgres', true);
    perform set_config('request.jwt.claim.sub', '', true);

    -- Chiudiamo SEMPRE con un'eccezione: annulla l'intera transazione e
    -- ripristina il progetto di test allo stato precedente (self-rollback).
    if v_errors > 0 then
        raise exception 'TEST VALIDAZIONE PRONOSTICI: % caso/i FALLITO/i (transazione annullata)', v_errors;
    else
        raise exception 'TEST VALIDAZIONE PRONOSTICI: TUTTI SUPERATI (transazione annullata: nessun dato residuo)';
    end if;
end
$test$;