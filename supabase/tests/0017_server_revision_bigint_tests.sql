-- =============================================================================
-- FantaFormula 1 - test della migrazione 0017_server_revision_bigint.sql
--
-- PREDISPOSTI, NON ESEGUITI. Richiedono un progetto Supabase di test con
-- 0001-0017 applicate e un utente reale. Sostituire '<UUID_USER>' con l'UUID.
-- Il blocco termina sempre con un'eccezione: self-rollback.
--
-- Copre in singola sessione:
--  - inserimento con revision assegnata dal server;
--  - retry identico -> unchanged con la stessa revision;
--  - update con revision corrente -> saved e revision incrementata;
--  - update con revision obsoleta -> conflict con revision corrente;
--  - riga eliminata + revision attesa -> conflict null;
--  - reinserimento esplicito con revision attesa null -> saved;
--  - protezione da revision inviata dal client (insert/update);
--  - submitted_at resta valorizzato come audit.
--
-- I casi di concorrenza reale (due insert/update simultanei) non sono
-- eseguibili in un singolo DO block: vanno eseguiti con due sessioni psql o
-- dblink su un database isolato. La matrice e' nel report Phase 1.3B.
-- =============================================================================

do $test$
declare
    v_user uuid := '<UUID_USER>';
    v_league uuid;
    v_season uuid;
    v_race uuid;
    v_race_direct uuid;
    v_status text;
    v_revision bigint;
    v_old_revision bigint;
    v_content jsonb;
    v_submitted_at timestamptz;
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
    values (v_user, 'Utente Test 0017')
    on conflict (id) do nothing;

    insert into public.leagues (name, invite_code, rules, created_by)
    values ('Lega Test 0017', 'TST17A', '{}', v_user)
    returning id into v_league;

    insert into public.league_members (league_id, user_id, role)
    values (v_league, v_user, 'admin');

    insert into public.seasons (league_id, name, is_active)
    values (v_league, '2026', true)
    returning id into v_season;

    insert into public.races (season_id, league_id, round, name, deadline)
    values (v_season, v_league, 1, 'Gara Test 0017', now() + interval '2 days')
    returning id into v_race;

    insert into public.races (season_id, league_id, round, name, deadline)
    values (v_season, v_league, 2, 'Gara Diretta 0017', now() + interval '2 days')
    returning id into v_race_direct;

    create temp table test_results (label text, passed boolean, detail text);
    grant all on test_results to authenticated, anon;

    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_user::text, true);

    -- 1. Inserimento: la revision e' assegnata dal server.
    select status, revision, submitted_at, content
      into v_status, v_revision, v_submitted_at, v_content
      from public.save_prediction_if_revision(
          v_race, v_league, v_content_1, null::bigint
      );

    if v_status = 'saved' and v_revision is not null and v_revision > 0 then
        insert into test_results values ('inserimento revision server', true, null);
        v_old_revision := v_revision;
    else
        insert into test_results values ('inserimento revision server', false,
                                         coalesce(v_status, 'status nullo'));
    end if;

    if v_submitted_at is not null then
        insert into test_results values ('submitted_at audit valorizzato', true, null);
    else
        insert into test_results values ('submitted_at audit valorizzato', false, null);
    end if;

    -- 2. Retry identico: unchanged e stessa revision.
    select status, revision
      into v_status, v_revision
      from public.save_prediction_if_revision(
          v_race, v_league, v_content_1, v_old_revision
      );

    if v_status = 'unchanged' and v_revision = v_old_revision then
        insert into test_results values ('retry identico unchanged', true, null);
    else
        insert into test_results values ('retry identico unchanged', false,
                                         coalesce(v_status, 'status nullo'));
    end if;

    -- 3. Update con revision corrente: saved e revision incrementata.
    select status, revision, content
      into v_status, v_revision, v_content
      from public.save_prediction_if_revision(
          v_race, v_league, v_content_2, v_old_revision
      );

    if v_status = 'saved' and v_revision > v_old_revision and v_content = v_content_2 then
        insert into test_results values ('update revision incrementata', true, null);
        v_old_revision := v_revision;
    else
        insert into test_results values ('update revision incrementata', false,
                                         coalesce(v_status, 'status nullo'));
    end if;

    -- 4. Revision obsoleta: conflict con la revision corrente.
    select status, revision
      into v_status, v_revision
      from public.save_prediction_if_revision(
          v_race, v_league, v_content_1, v_old_revision - 1
      );

    if v_status = 'conflict' and v_revision = v_old_revision then
        insert into test_results values ('revision obsoleta conflict', true, null);
    else
        insert into test_results values ('revision obsoleta conflict', false,
                                         coalesce(v_status, 'status nullo'));
    end if;

    -- 5. Riga eliminata: revision attesa non-null -> conflict null.
    perform set_config('role', 'postgres', true);
    delete from public.predictions
     where race_id = v_race and user_id = v_user;
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_user::text, true);

    select status, revision, content
      into v_status, v_revision, v_content
      from public.save_prediction_if_revision(
          v_race, v_league, v_content_1, v_old_revision
      );

    if v_status = 'conflict' and v_revision is null and v_content is null then
        insert into test_results values ('riga eliminata non ricreata', true, null);
    else
        insert into test_results values ('riga eliminata non ricreata', false,
                                         coalesce(v_status, 'status nullo'));
    end if;

    -- 6. Reinserimento esplicito con expected null.
    select status, revision
      into v_status, v_revision
      from public.save_prediction_if_revision(
          v_race, v_league, v_content_1, null::bigint
      );

    if v_status = 'saved' and v_revision is not null then
        insert into test_results values ('reinserimento esplicito', true, null);
    else
        insert into test_results values ('reinserimento esplicito', false,
                                         coalesce(v_status, 'status nullo'));
    end if;

    -- 7. Protezione inserimento: revision inviata dal client sovrascritta.
    perform set_config('role', 'postgres', true);
    perform set_config('request.jwt.claim.sub', '', true);

    insert into public.predictions (race_id, league_id, user_id, content, revision)
    values (v_race_direct, v_league, v_user, v_content_1, 999)
    returning revision into v_revision;

    if v_revision is distinct from 999 and v_revision > 0 then
        insert into test_results values ('revision client insert sovrascritta', true, null);
    else
        insert into test_results values ('revision client insert sovrascritta', false,
                                         'revision ' || coalesce(v_revision::text, 'null'));
    end if;

    -- 8. Protezione update: revision inviata dal client sovrascritta.
    update public.predictions
       set revision = 999
     where race_id = v_race_direct and user_id = v_user
    returning revision into v_revision;

    if v_revision is distinct from 999 and v_revision > 0 then
        insert into test_results values ('revision client update sovrascritta', true, null);
    else
        insert into test_results values ('revision client update sovrascritta', false,
                                         'revision ' || coalesce(v_revision::text, 'null'));
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
        raise exception 'TEST MIGRATION 0017: almeno un caso fallito (transazione annullata)';
    else
        raise exception 'TEST MIGRATION 0017: TUTTI SUPERATI (transazione annullata)';
    end if;
end
$test$;
