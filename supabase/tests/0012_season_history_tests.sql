-- =============================================================================
-- FantaFormula 1 — test migration 0012_season_history.sql (F2 storico stagioni)
-- File: supabase/tests/0012_season_history_tests.sql
--
-- PREDISPOSTI, NON ESEGUITI (richiedono Supabase di test + migrazioni
-- 0001-0012). Eseguire come script unico nel SQL Editor; il block DO si
-- chiude sempre con un'eccezione = self-rollback (nessun dato residuo).
--
-- Sostituire '<UUID_ADMIN>', '<UUID_GIOCATORE>' e '<UUID_ESTRANEO>' con tre
-- UUID a piacere (non devono corrispondere ad auth.users: i profili vengono
-- creati qui).
--
-- Coperti:
--   * insert da admin (archiviato da se stesso) -> consentita
--   * insert con archived_by diverso da auth.uid() -> rifiutata
--   * insert da non-membro -> rifiutata
--   * select da membro -> vede lo snapshot
--   * select da estraneo -> non vede righe
--   * update/delete -> nessuna riga toccata (snapshot immutabile)
-- =============================================================================

do $test$
declare
    v_admin     uuid := '<UUID_ADMIN>';
    v_player    uuid := '<UUID_GIOCATORE>';
    v_outsider  uuid := '<UUID_ESTRANEO>';
    v_league    uuid;
    v_season_a  uuid;   -- stagione usata dai test di insert/immutabilita'
    v_season_b  uuid;   -- stagione con snapshot pre-caricato (test select)
    v_errors    int := 0;
    v_msg       text;
begin
    -- ---------------------------------------------------------------- setup
    insert into public.profiles (id, display_name)
    values (v_admin, 'Admin Test 0012'),
           (v_player, 'Giocatore Test 0012'),
           (v_outsider, 'Estraneo Test 0012')
    on conflict (id) do nothing;

    insert into public.leagues (name, invite_code, rules, created_by)
    values ('Lega Test 0012', 'TST12A', '{}', v_admin)
    returning id into v_league;

    insert into public.league_members (league_id, user_id, role)
    values (v_league, v_admin, 'admin'),
           (v_league, v_player, 'player');

    insert into public.seasons (league_id, name, is_active)
    values (v_league, '2026', true)
    returning id into v_season_a;

    insert into public.seasons (league_id, name, is_active)
    values (v_league, '2025', false)
    returning id into v_season_b;

    -- Snapshot pre-caricato per il test della SELECT (eseguito come postgres,
    -- bypassa la RLS: la policy di insert viene testata qui sotto).
    insert into public.season_snapshots
        (season_id, league_id, season_name, standings, archived_by)
    values (
        v_season_b, v_league, '2025',
        '{"rows":[{"position":1,"userId":"u1","displayName":"Giocatore","totalPoints":42.0,"raceWins":3,"bestRacePoints":18.0}]}',
        v_admin
    );

    -- ------------------------------------------------- contesto autenticato
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin::text, true);
    create temp table test_results (label text, passed boolean, detail text);

    -- Insert con archived_by di un altro utente: rifiutato (la policy
    -- richiede archived_by = auth.uid()).
    begin
        insert into public.season_snapshots
            (season_id, league_id, season_name, standings, archived_by)
        values (v_season_a, v_league, '2026', '{"rows":[]}', v_player);
        insert into test_results values ('insert con archived_by altrui rifiutato',
                                         false, 'inserimento riuscito');
    exception when others then
        if SQLERRM like '%row-level security%' then
            insert into test_results values ('insert con archived_by altrui rifiutato',
                                             true, null);
        else
            insert into test_results values ('insert con archived_by altrui rifiutato',
                                             false, SQLERRM);
        end if;
    end;

    -- Insert da un NON membro della lega: rifiutato.
    perform set_config('request.jwt.claim.sub', v_outsider::text, true);
    begin
        insert into public.season_snapshots
            (season_id, league_id, season_name, standings, archived_by)
        values (v_season_a, v_league, '2026', '{"rows":[]}', v_outsider);
        insert into test_results values ('insert da non-membro rifiutato',
                                         false, 'inserimento riuscito');
    exception when others then
        if SQLERRM like '%row-level security%' then
            insert into test_results values ('insert da non-membro rifiutato',
                                             true, null);
        else
            insert into test_results values ('insert da non-membro rifiutato',
                                             false, SQLERRM);
        end if;
    end;

    -- Insert da admin con archived_by = auth.uid(): consentito.
    perform set_config('request.jwt.claim.sub', v_admin::text, true);
    begin
        insert into public.season_snapshots
            (season_id, league_id, season_name, standings, archived_by)
        values (v_season_a, v_league, '2026', '{"rows":[]}', v_admin);
        if exists (select 1 from public.season_snapshots
                   where season_id = v_season_a and archived_by = v_admin) then
            insert into test_results values ('insert da admin consentito', true, null);
        else
            insert into test_results values ('insert da admin consentito',
                                             false, 'riga non presente');
        end if;
    exception when others then
        insert into test_results values ('insert da admin consentito', false, SQLERRM);
    end;

    -- Select da un membro (giocatore): vede lo snapshot pre-caricato.
    perform set_config('request.jwt.claim.sub', v_player::text, true);
    begin
        if exists (select 1 from public.season_snapshots
                   where season_id = v_season_b and league_id = v_league) then
            insert into test_results values ('select da membro consentita', true, null);
        else
            insert into test_results values ('select da membro consentita',
                                             false, 'riga non visibile');
        end if;
    exception when others then
        insert into test_results values ('select da membro consentita', false, SQLERRM);
    end;

    -- Select da un estraneo: la policy filtra, nessuna riga visibile.
    perform set_config('request.jwt.claim.sub', v_outsider::text, true);
    begin
        if not exists (select 1 from public.season_snapshots where league_id = v_league) then
            insert into test_results values ('select da estraneo senza righe', true, null);
        else
            insert into test_results values ('select da estraneo senza righe',
                                             false, 'righe visibili a un estraneo');
        end if;
    exception when others then
        insert into test_results values ('select da estraneo senza righe', false, SQLERRM);
    end;

    -- Immutabilita': nessuna policy di UPDATE -> nessuna riga aggiornata.
    perform set_config('request.jwt.claim.sub', v_admin::text, true);
    begin
        update public.season_snapshots
        set season_name = 'MODIFICATA', standings = '{"rows":[]}'
        where season_id = v_season_b;
        if not found then
            insert into test_results values ('update non tocca lo snapshot', true, null);
        else
            insert into test_results values ('update non tocca lo snapshot',
                                             false, 'riga aggiornata');
        end if;
    exception when others then
        insert into test_results values ('update non tocca lo snapshot', false, SQLERRM);
    end;

    -- Immutabilita': nessuna policy di DELETE -> nessuna riga eliminata.
    begin
        delete from public.season_snapshots where season_id = v_season_b;
        if not found then
            insert into test_results values ('delete non tocca lo snapshot', true, null);
        else
            insert into test_results values ('delete non tocca lo snapshot',
                                             false, 'riga eliminata');
        end if;
    exception when others then
        insert into test_results values ('delete non tocca lo snapshot', false, SQLERRM);
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
        raise exception 'TEST MIGRATION 0012: % caso/i FALLITO/i (transazione annullata)',
            v_errors;
    else
        raise exception 'TEST MIGRATION 0012: TUTTI SUPERATI (transazione annullata: nessun dato residuo)';
    end if;
end
$test$;
