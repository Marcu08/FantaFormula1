-- =============================================================================
-- FantaFormula 1 — test migration 0011_fixes.sql (N8, N9, R4, R8)
-- File: supabase/tests/0011_fixes_tests.sql
--
-- PREDISPOSTI, NON ESEGUITI (richiedono Supabase di test + migrazioni
-- 0001-0011). Eseguire come script unico nel SQL Editor; il block DO si
-- chiude sempre con un'eccezione = self-rollback (nessun dato residuo).
--
-- Sostituire '<UUID_ADMIN>' e '<UUID_GIOCATORE>' con due UUID a piacere
-- (non devono corrispondere ad auth.users: i profili vengono creati qui).
-- =============================================================================

do $test$
declare
    v_admin uuid := '<UUID_ADMIN>';
    v_user  uuid := '<UUID_GIOCATORE>';
    v_orphan uuid := gen_random_uuid();   -- profilo "escluso" per il test utente
    v_league_a uuid;  -- lega da eliminare (N8 + N9)
    v_league_b uuid;  -- lega per la RPC remove_league_member (R8)
    v_season_a uuid;
    v_race_a uuid;
    v_user_deleted boolean := false;
    v_user_detail text;
    v_errors int := 0;
    v_msg text;
begin
    -- ---------------------------------------------------------------- setup
    insert into public.profiles (id, display_name)
    values (v_admin, 'Admin Test 0011'), (v_user, 'Giocatore Test 0011')
    on conflict (id) do nothing;

    insert into public.leagues (name, invite_code, rules, created_by)
    values ('Lega Test 0011 A', 'TST11A', '{}', v_admin)
    returning id into v_league_a;

    insert into public.league_members (league_id, user_id, role)
    values (v_league_a, v_admin, 'admin');   -- un solo admin (N9)

    insert into public.seasons (league_id, name, is_active)
    values (v_league_a, '2026', true)
    returning id into v_season_a;

    insert into public.races (season_id, league_id, round, name, deadline)
    values (v_season_a, v_league_a, 1, 'Gara Test 0011', now() + interval '2 days')
    returning id into v_race_a;

    -- pronostico + risultato sulla gara (condizione del test N8)
    insert into public.predictions (race_id, league_id, user_id, content)
    values (
        v_race_a, v_league_a, v_user,
        jsonb_build_object('entries', jsonb_build_array(
            jsonb_build_object('type', 'WINNER', 'driverIds', jsonb_build_array('VER'))))
    );
    insert into public.race_results (race_id, league_id, result, entered_by, entered_at)
    values (v_race_a, v_league_a, '{"winnerDriverId":"VER"}'::jsonb, v_admin, now());

    insert into public.leagues (name, invite_code, rules, created_by)
    values ('Lega Test 0011 B', 'TST11B', '{}', v_admin)
    returning id into v_league_b;

    insert into public.league_members (league_id, user_id, role)
    values (v_league_b, v_admin, 'admin'),
           (v_league_b, v_user, 'player');

    -- N9: profilo admin unico di una lega che NON e' in cancellazione;
    -- l'eliminazione dell'utente (cascata su profiles -> league_members)
    -- non deve essere bloccata dal trigger. Fatto qui come postgres perche'
    -- la tabella profiles non ha policy di DELETE (il ruolo authenticate non
    -- potrebbe); l'esito viene registrato e riportato nei risultati.
    begin
        insert into public.profiles (id, display_name)
        values (v_orphan, 'Admin Escluso Test 0011');
        insert into public.leagues (name, invite_code, rules, created_by)
        values ('Lega Test 0011 C', 'TST11C', '{}', v_orphan);
        insert into public.league_members (league_id, user_id, role)
        select id, v_orphan, 'admin' from public.leagues where invite_code = 'TST11C';

        delete from public.profiles where id = v_orphan;
        v_user_deleted := true;
    exception when others then
        v_user_detail := SQLERRM;
    end;

    -- ------------------------------------------------- contesto autenticato
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin::text, true);
    create temp table test_results (label text, passed boolean, detail text);

    insert into test_results values ('N9 utente admin unico eliminato',
                                     v_user_deleted,
                                     case when v_user_deleted then null else v_user_detail end);

    -- R4: gara SENZA deadline rifiutata (trigger before insert).
    begin
        insert into public.races (season_id, league_id, round, name)
        values (v_season_a, v_league_a, 2, 'Gara Senza Deadline');
        insert into test_results values ('R4 gara senza deadline rifiutata',
                                         false, 'inserimento riuscito');
    exception when others then
        if SQLERRM like '%deadline%' then
            insert into test_results values ('R4 gara senza deadline rifiutata', true, null);
        else
            insert into test_results values ('R4 gara senza deadline rifiutata', false, SQLERRM);
        end if;
    end;

    -- R4: gara CON deadline accettata (controllo positivo).
    begin
        insert into public.races (season_id, league_id, round, name, deadline)
        values (v_season_a, v_league_a, 2, 'Gara Con Deadline', now() + interval '3 days');
        insert into test_results values ('R4 gara con deadline accettata', true, null);
    exception when others then
        insert into test_results values ('R4 gara con deadline accettata', false, SQLERRM);
    end;

    -- N8: eliminare la gara (ha pronostici E risultato) deve riuscire.
    begin
        delete from public.races where id = v_race_a;
        insert into test_results values ('N8 gara con pronostici e risultato eliminata',
                                         true, null);
    exception when others then
        insert into test_results values ('N8 gara con pronostici e risultato eliminata',
                                         false, SQLERRM);
    end;

    -- N9: eliminare la lega A (un solo admin) deve riuscire: il trigger
    -- "ultimo admin" non blocca la cancellazione a cascata.
    begin
        delete from public.leagues where id = v_league_a;
        insert into test_results values ('N9 lega con un solo admin eliminata', true, null);
    exception when others then
        insert into test_results values ('N9 lega con un solo admin eliminata', false, SQLERRM);
    end;

    -- R8a: un NON admin non puo' rimuovere membri.
    perform set_config('request.jwt.claim.sub', v_user::text, true);
    begin
        perform public.remove_league_member(v_league_b, v_admin);
        insert into test_results values ('R8 rimozione membro da non-admin rifiutata',
                                         false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like '%amministratore%' then
            insert into test_results values ('R8 rimozione membro da non-admin rifiutata',
                                             true, null);
        else
            insert into test_results values ('R8 rimozione membro da non-admin rifiutata',
                                             false, SQLERRM);
        end if;
    end;

    -- R8b: l'admin rimuove un giocatore: consentito.
    perform set_config('request.jwt.claim.sub', v_admin::text, true);
    begin
        perform public.remove_league_member(v_league_b, v_user);
        if not exists (select 1 from public.league_members
                       where league_id = v_league_b and user_id = v_user) then
            insert into test_results values ('R8 admin rimuove un giocatore', true, null);
        else
            insert into test_results values ('R8 admin rimuove un giocatore',
                                             false, 'membro ancora presente');
        end if;
    exception when others then
        insert into test_results values ('R8 admin rimuove un giocatore', false, SQLERRM);
    end;

    -- R8c: rimuovere l'ultimo admin della lega: rifiutato.
    begin
        perform public.remove_league_member(v_league_b, v_admin);
        insert into test_results values ('R8 ultimo admin non rimuovibile',
                                         false, 'nessun errore sollevato');
    exception when others then
        if SQLERRM like '%ultimo amministratore%' then
            insert into test_results values ('R8 ultimo admin non rimuovibile', true, null);
        else
            insert into test_results values ('R8 ultimo admin non rimuovibile', false, SQLERRM);
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
        raise exception 'TEST MIGRATION 0011: % caso/i FALLITO/i (transazione annullata)',
            v_errors;
    else
        raise exception 'TEST MIGRATION 0011: TUTTI SUPERATI (transazione annullata: nessun dato residuo)';
    end if;
end
$test$;
