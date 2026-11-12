-- =============================================================================
-- FantaFormula 1 - test della migrazione 0019_quote_publish_lock.sql
--
-- PREDISPOSTI, NON ESEGUITI in remoto. Richiedono un database locale con
-- 0001-0019 applicate e un utente reale in auth.users. Sostituire
-- '<UUID_USER>' con l'UUID. Il blocco termina sempre con un'eccezione:
-- self-rollback.
--
-- NOTA ONESTA: questo test verifica trigger, ordine dei trigger e casi
-- funzionali di publish_race_quotes. NON simula due sessioni concorrenti:
-- la serializzazione advisory-lock tra transazioni reali non e' verificata qui.
-- =============================================================================

do $test$
declare
    v_user uuid := '<UUID_USER>';
    v_league uuid;
    v_season uuid;
    v_race_1 uuid;
    v_race_2 uuid;
    v_triggers text[];
    v_lock_pos int;
    v_content_pos int;
    v_errors int := 0;
    v_msg text;
    v_quote_dnf jsonb := jsonb_build_array(
        jsonb_build_object(
            'type', 'DRIVER_DNF',
            'key', 'PER|true',
            'threshold', null,
            'quota', 2.5
        )
    );
    v_quote_dnf_changed jsonb := jsonb_build_array(
        jsonb_build_object(
            'type', 'DRIVER_DNF',
            'key', 'PER|true',
            'threshold', null,
            'quota', 2.6
        )
    );
begin
    insert into public.profiles (id, display_name)
    values (v_user, 'Utente Test 0019')
    on conflict (id) do nothing;

    insert into public.leagues (name, invite_code, rules, created_by)
    values ('Lega Test 0019', 'TST19A', '{}', v_user)
    returning id into v_league;

    insert into public.league_members (league_id, user_id, role)
    values (v_league, v_user, 'admin');

    insert into public.seasons (league_id, name, is_active)
    values (v_league, '2026', true)
    returning id into v_season;

    insert into public.races (season_id, league_id, round, name, deadline)
    values (v_season, v_league, 1, 'Gara Test 0019 A', now() + interval '2 days')
    returning id into v_race_1;
    insert into public.races (season_id, league_id, round, name, deadline)
    values (v_season, v_league, 2, 'Gara Test 0019 B', now() + interval '2 days')
    returning id into v_race_2;

    create temp table test_results (label text, passed boolean, detail text);
    grant all on test_results to authenticated, anon;

    -- 1) Trigger presente e ordinato prima di on_prediction_content.
    select array_agg(tgname order by tgname)
      into v_triggers
      from pg_trigger
     where tgrelid = 'public.predictions'::regclass
       and not tgisinternal;
    v_lock_pos := array_position(v_triggers, 'on_prediction_00_lock');
    v_content_pos := array_position(v_triggers, 'on_prediction_content');
    insert into test_results
    values (
        'trigger lock presente e prima di content',
        v_lock_pos is not null and v_content_pos is not null and v_lock_pos < v_content_pos,
        coalesce(array_to_string(v_triggers, ','), 'nessun trigger')
    );

    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_user::text, true);

    -- 2) Publish quote invalide continua a essere rifiutato (caso 0013).
    begin
        perform public.publish_race_quotes(
            v_race_1,
            '[{"type":"DRIVER_DNF","key":"PER|true","threshold":null,"quota":9.0}]'::jsonb
        );
        insert into test_results values ('publish quota 9.0 rifiutata', false, 'nessun errore');
    exception when others then
        insert into test_results
        values ('publish quota 9.0 rifiutata', SQLERRM like '%quota fuori range%', SQLERRM);
    end;

    -- 3) Coppia testa a testa non canonica continua a essere rifiutata (0013).
    begin
        perform public.publish_race_quotes(
            v_race_1,
            '[{"type":"HEAD_TO_HEAD","key":"VER|BOR|VER","threshold":null,"quota":1.2}]'::jsonb
        );
        insert into test_results values ('publish H2H non canonico rifiutato', false, 'nessun errore');
    exception when others then
        insert into test_results
        values ('publish H2H non canonico rifiutato', SQLERRM like '%ordine canonico%', SQLERRM);
    end;

    -- 4) Publish valida e salvataggio di un pronostico con la stessa quota.
    begin
        perform public.publish_race_quotes(v_race_1, v_quote_dnf);
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race_1,
            v_league,
            v_user,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object(
                    'type', 'DRIVER_DNF',
                    'driverIds', jsonb_build_array('PER'),
                    'booleanValue', true,
                    'quota', 2.5
                )))
        );
        insert into test_results values ('publish valida e pronostico salvato', true, null);
    exception when others then
        insert into test_results values ('publish valida e pronostico salvato', false, SQLERRM);
    end;

    -- 5) Pronostico senza quote pubblicate continua a essere rifiutato.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race_1,
            v_league,
            v_user,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object(
                    'type', 'DRIVER_DNF',
                    'driverIds', jsonb_build_array('ALB'),
                    'booleanValue', true,
                    'quota', 2.5
                )))
        );
        insert into test_results values ('quote non pubblicate rifiutate', false, 'nessun errore');
    exception when others then
        insert into test_results
        values (
            'quote non pubblicate rifiutate',
            SQLERRM like '%quote non ancora pubblicate%',
            SQLERRM
        );
    end;

    -- 6) Modifica dell'insieme dopo il primo pronostico rifiutata (0013/0018).
    begin
        perform public.publish_race_quotes(v_race_1, v_quote_dnf_changed);
        insert into test_results values ('modifica quote dopo pronostico rifiutata', false, 'nessun errore');
    exception when others then
        insert into test_results
        values (
            'modifica quote dopo pronostico rifiutata',
            SQLERRM like '%Non puoi modificare le quote%',
            SQLERRM
        );
    end;

    -- 7) Prima pubblicazione con pronostico vintage (caso 0018).
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race_2,
            v_league,
            v_user,
            '{"entries":[{"type":"WINNER","driverIds":["VER"]}]}'::jsonb
        );
        perform public.publish_race_quotes(v_race_2, v_quote_dnf);
        insert into test_results values ('prima publish con pronostico vintage', true, null);
    exception when others then
        insert into test_results values ('prima publish con pronostico vintage', false, SQLERRM);
    end;

    -- 8) Il salvataggio pronostici funziona ancora sulla gara con quote 0018.
    begin
        insert into public.predictions (race_id, league_id, user_id, content)
        values (
            v_race_2,
            v_league,
            v_user,
            jsonb_build_object('entries', jsonb_build_array(
                jsonb_build_object(
                    'type', 'DRIVER_DNF',
                    'driverIds', jsonb_build_array('PER'),
                    'booleanValue', true,
                    'quota', 2.5
                )))
        )
        on conflict (race_id, user_id) do update set content = excluded.content;
        insert into test_results values ('salvataggio pronostico con quote 0018', true, null);
    exception when others then
        insert into test_results values ('salvataggio pronostico con quote 0018', false, SQLERRM);
    end;

    perform set_config('role', 'postgres', true);
    perform set_config('request.jwt.claim.sub', '', true);

    select count(*) into v_errors from test_results where not passed;
    for v_msg in
        select label || coalesce(' - ' || detail, '') || ' -> ' ||
               case when passed then 'PASS' else 'FAIL' end
        from test_results order by label
    loop
        raise notice '%', v_msg;
    end loop;

    if v_errors > 0 then
        raise exception 'TEST MIGRATION 0019: % caso/i FALLITO/i (transazione annullata)', v_errors;
    else
        raise exception 'TEST MIGRATION 0019: TUTTI SUPERATI (transazione annullata; concorrenza tra sessioni non simulata)';
    end if;
end
$test$;
