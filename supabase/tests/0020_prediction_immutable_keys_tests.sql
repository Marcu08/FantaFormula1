-- =============================================================================
-- FantaFormula 1 - test della migrazione 0020_prediction_immutable_keys.sql
--
-- PREDISPOSTO, NON ESEGUITO in remoto. Richiede un database locale con
-- 0001-0020 applicate e un utente reale in auth.users. Sostituire
-- '<UUID_USER>' con l'UUID. Il blocco termina sempre con un'eccezione:
-- self-rollback.
--
-- Verifica:
--   - presenza e ordine del trigger delle chiavi immutabili;
--   - UPDATE di race_id rifiutato;
--   - UPDATE di league_id rifiutato;
--   - UPDATE del solo content ancora consentito.
--
-- La concorrenza reale tra due sessioni non e' simulata qui: per quella
-- serve un test separato con due connessioni PostgreSQL indipendenti.
-- =============================================================================

do $test$
declare
    v_user uuid := '<UUID_USER>';
    v_league uuid;
    v_league_2 uuid;
    v_season uuid;
    v_race_1 uuid;
    v_race_2 uuid;
    v_triggers text[];
    v_immutable_pos int;
    v_lock_pos int;
    v_content_pos int;
    v_errors int := 0;
    v_msg text;
    v_row public.predictions%rowtype;
begin
    insert into public.profiles (id, display_name)
    values (v_user, 'Utente Test 0020')
    on conflict (id) do nothing;

    insert into public.leagues (name, invite_code, rules, created_by)
    values ('Lega Test 0020', 'TST20A', '{}', v_user)
    returning id into v_league;
    insert into public.league_members (league_id, user_id, role)
    values (v_league, v_user, 'admin');

    insert into public.leagues (name, invite_code, rules, created_by)
    values ('Lega Test 0020 B', 'TST20B', '{}', v_user)
    returning id into v_league_2;
    insert into public.league_members (league_id, user_id, role)
    values (v_league_2, v_user, 'admin');

    insert into public.seasons (league_id, name, is_active)
    values (v_league, '2026', true)
    returning id into v_season;

    insert into public.races (season_id, league_id, round, name, deadline)
    values (v_season, v_league, 1, 'Gara Test 0020 A', now() + interval '2 days')
    returning id into v_race_1;
    insert into public.races (season_id, league_id, round, name, deadline)
    values (v_season, v_league, 2, 'Gara Test 0020 B', now() + interval '2 days')
    returning id into v_race_2;

    create temp table test_results (label text, passed boolean, detail text);
    grant all on test_results to authenticated, anon;

    -- 1) Trigger presente e ordinato prima di lock e validazione contenuto.
    select array_agg(tgname order by tgname)
      into v_triggers
      from pg_trigger
     where tgrelid = 'public.predictions'::regclass
       and not tgisinternal;
    v_immutable_pos := array_position(v_triggers, 'on_prediction_00_immutable_keys');
    v_lock_pos := array_position(v_triggers, 'on_prediction_00_lock');
    v_content_pos := array_position(v_triggers, 'on_prediction_content');
    insert into test_results
    values (
        'trigger chiavi immutabili presente e ordinato',
        v_immutable_pos is not null
            and v_lock_pos is not null
            and v_content_pos is not null
            and v_immutable_pos < v_lock_pos
            and v_lock_pos < v_content_pos,
        coalesce(array_to_string(v_triggers, ','), 'nessun trigger')
    );

    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_user::text, true);

    insert into public.predictions (race_id, league_id, user_id, content)
    values (
        v_race_1,
        v_league,
        v_user,
        '{"entries":[{"type":"WINNER","driverIds":["VER"]}]}'::jsonb
    );

    -- 2) Spostare la riga su un'altra gara deve fallire.
    begin
        update public.predictions
           set race_id = v_race_2
         where race_id = v_race_1
           and user_id = v_user;
        insert into test_results values ('update race_id rifiutato', false, 'nessun errore');
    exception when others then
        insert into test_results
        values (
            'update race_id rifiutato',
            SQLERRM like '%gara e lega non modificabili%',
            SQLERRM
        );
    end;

    -- 3) Spostare la riga su un'altra lega deve fallire.
    begin
        update public.predictions
           set league_id = v_league_2
         where race_id = v_race_1
           and user_id = v_user;
        insert into test_results values ('update league_id rifiutato', false, 'nessun errore');
    exception when others then
        insert into test_results
        values (
            'update league_id rifiutato',
            SQLERRM like '%gara e lega non modificabili%',
            SQLERRM
        );
    end;

    -- 4) L'update del solo content resta consentito.
    begin
        update public.predictions
           set content = '{"entries":[{"type":"WINNER","driverIds":["NOR"]}]}'::jsonb
         where race_id = v_race_1
           and user_id = v_user;
        select * into v_row
          from public.predictions
         where race_id = v_race_1
           and user_id = v_user;
        insert into test_results
        values (
            'update content consentito',
            v_row.race_id = v_race_1
                and v_row.league_id = v_league
                and v_row.content @> '{"entries":[{"driverIds":["NOR"]}]}'::jsonb,
            v_row.content::text
        );
    exception when others then
        insert into test_results values ('update content consentito', false, SQLERRM);
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
        raise exception 'TEST MIGRATION 0020: % caso/i FALLITO/i (transazione annullata)', v_errors;
    else
        raise exception 'TEST MIGRATION 0020: TUTTI SUPERATI (transazione annullata; concorrenza tra sessioni non simulata)';
    end if;
end
$test$;
