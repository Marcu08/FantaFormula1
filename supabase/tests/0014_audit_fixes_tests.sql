-- =============================================================================
-- FantaFormula 1 — test della migrazione 0014_audit_fixes.sql
--
-- PREDISPOSTI, NON ESEGUITI. Eseguire come script unico nel SQL Editor di un
-- progetto di test, dopo 0001-0014. Sostituire i due UUID segnaposto con
-- utenti reali. Il blocco termina sempre con un'eccezione: self-rollback.
-- =============================================================================

do $test$
declare
    v_admin uuid := '<UUID_ADMIN>';
    v_user  uuid := '<UUID_GIOCATORE>';
    v_league uuid;
    v_other_league uuid;
    v_season uuid;
    v_other_season uuid;
    v_race uuid;
    v_other_race uuid;
    v_count int;
    v_exists boolean;
    v_errors int := 0;
    v_msg text;
begin
    insert into public.profiles (id, display_name)
    values (v_admin, 'Admin Test 0014'), (v_user, 'Giocatore Test 0014')
    on conflict (id) do nothing;

    insert into public.leagues (name, invite_code, rules, created_by)
    values ('Lega Test 0014', 'TST14A', '{}', v_admin)
    returning id into v_league;

    insert into public.leagues (name, invite_code, rules, created_by)
    values ('Altra Lega 0014', 'TST14B', '{}', v_admin)
    returning id into v_other_league;

    insert into public.league_members (league_id, user_id, role)
    values (v_league, v_admin, 'admin'),
           (v_league, v_user, 'player'),
           (v_other_league, v_admin, 'admin');

    insert into public.seasons (league_id, name, is_active)
    values (v_league, '2026', true)
    returning id into v_season;

    insert into public.seasons (league_id, name, is_active)
    values (v_other_league, '2026-other', true)
    returning id into v_other_season;

    insert into public.races (season_id, league_id, round, name, deadline)
    values (v_season, v_league, 1, 'Gara Test 0014', now() + interval '2 days')
    returning id into v_race;
    insert into public.races (season_id, league_id, round, name, deadline)
    values (v_other_season, v_other_league, 1, 'Gara Altra 0014', now() + interval '2 days')
    returning id into v_other_race;

    create temp table test_results (label text, passed boolean, detail text);
    grant all on test_results to authenticated, anon;

    -- 1) prediction_exists non rivela il pronostico di un altro utente.
    insert into public.predictions (race_id, league_id, user_id, content)
    values (v_race, v_league, v_user, '{"entries":[]}');

    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin::text, true);

    begin
        select public.prediction_exists(v_race, v_user) into v_exists;
        insert into test_results
        values ('prediction_exists altrui non rivelato', v_exists = false, 'valore ' || v_exists::text);
    exception when others then
        insert into test_results values ('prediction_exists altrui non rivelato', false, SQLERRM);
    end;

    perform set_config('request.jwt.claim.sub', v_user::text, true);
    begin
        select public.prediction_exists(v_race, v_user) into v_exists;
        insert into test_results
        values ('prediction_exists proprio vero', v_exists = true, 'valore ' || v_exists::text);
    exception when others then
        insert into test_results values ('prediction_exists proprio vero', false, SQLERRM);
    end;

    perform set_config('request.jwt.claim.sub', v_admin::text, true);
    begin
        select public.prediction_exists(v_race, v_admin) into v_exists;
        insert into test_results
        values ('prediction_exists proprio coerente', v_exists = false, 'valore ' || v_exists::text);
    exception when others then
        insert into test_results values ('prediction_exists proprio coerente', false, SQLERRM);
    end;

    -- 2) Il creatore retrocesso non puo' cancellare la lega.
    perform set_config('role', 'postgres', true);
    update public.league_members
    set role = 'admin'
    where league_id = v_league and user_id = v_user;
    update public.league_members
    set role = 'player'
    where league_id = v_league and user_id = v_admin;

    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin::text, true);
    begin
        delete from public.leagues where id = v_league;
        get diagnostics v_count = row_count;
        insert into test_results
        values ('creatore retrocesso non cancella la lega', v_count = 0, 'righe ' || v_count);
    exception when others then
        insert into test_results values ('creatore retrocesso non cancella la lega', false, SQLERRM);
    end;

    -- 3) Snapshot con stagione di un'altra lega: FK composita rifiuta.
    perform set_config('role', 'postgres', true);
    update public.league_members
    set role = 'admin'
    where league_id = v_league and user_id = v_admin;
    update public.league_members
    set role = 'player'
    where league_id = v_league and user_id = v_user;
    begin
        insert into public.season_snapshots
            (season_id, league_id, season_name, standings, archived_by)
        values
            (v_other_season, v_league, 'Mismatch', '{"rows":[]}', v_admin);
        insert into test_results values ('snapshot stagione/lega coerente', false, 'inserimento riuscito');
    exception when others then
        insert into test_results values ('snapshot stagione/lega coerente', true, null);
    end;

    -- 4) archive_season: snapshot + disattivazione in un colpo solo.
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin::text, true);
    begin
        perform public.archive_season(v_league, v_season, '2026', '{"rows":[]}'::jsonb);
        select count(*) into v_count from public.season_snapshots where season_id = v_season;
        insert into test_results
        values ('archive_season crea snapshot', v_count = 1, 'righe ' || v_count);
        select count(*) into v_count
        from public.seasons where id = v_season and is_active = false;
        insert into test_results
        values ('archive_season disattiva stagione', v_count = 1, 'righe ' || v_count);
    exception when others then
        insert into test_results values ('archive_season atomico', false, SQLERRM);
    end;

    -- Ripristina il ruolo prima del conteggio finale.
    perform set_config('role', 'postgres', true);
    perform set_config('request.jwt.claim.sub', '', true);
    select count(*) into v_errors from test_results where not passed;
    for v_msg in select label || coalesce(' — ' || detail, '') || ' -> ' ||
                        case when passed then 'PASS' else 'FAIL' end
                 from test_results order by label loop
        raise notice '%', v_msg;
    end loop;

    if v_errors > 0 then
        raise exception 'TEST MIGRATION 0014: % caso/i FALLITO/i (transazione annullata)', v_errors;
    else
        raise exception 'TEST MIGRATION 0014: TUTTI SUPERATI (transazione annullata: nessun dato residuo)';
    end if;
end
$test$;
