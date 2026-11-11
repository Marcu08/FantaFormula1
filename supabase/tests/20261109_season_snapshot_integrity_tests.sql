-- =============================================================================
-- FantaFormula 1 - test season snapshot integrity + atomic archive
--
-- Eseguire su un database finalizzato dal wrapper canonico, dopo 0001-0012,
-- 20261109200000, 20261109210000, 20261109220000 e 20261109230000.
-- Il test crea dati temporanei, cambia ruolo/JWT come farebbe Supabase e
-- termina con rollback: nessun dato resta nel database.
-- =============================================================================

begin;

-- Nel progetto Supabase i grant di tabella esistono di default; nel test
-- locale rendiamo espliciti solo i permessi che la migration lascia ai client.
grant select on public.season_snapshots to authenticated;
grant select on public.seasons, public.leagues, public.league_members, public.profiles
    to authenticated;
grant update (name, start_date, end_date) on public.seasons to authenticated;

do $test$
declare
    v_admin_a    uuid := gen_random_uuid();
    v_admin_b    uuid := gen_random_uuid();
    v_member_b   uuid := gen_random_uuid();
    v_outsider   uuid := gen_random_uuid();
    v_ex_member  uuid := gen_random_uuid();
    v_ex_member_old uuid := gen_random_uuid();
    v_league_a   uuid;
    v_league_b   uuid;
    v_season_a   uuid;
    v_season_b   uuid;
    v_season_c   uuid;
    v_season_d   uuid;
    v_season_e   uuid;
    v_season_f   uuid;
    v_season_g   uuid;
    v_season_h   uuid;
    v_season_i   uuid;
    v_season_j   uuid;
    v_season_k   uuid;
    v_race_d     uuid;
    v_race_e     uuid;
    v_race_h     uuid;
    v_standings_a jsonb;
    v_standings_b jsonb;
    v_standings_bad jsonb;
    v_standings_other_league jsonb;
    v_standings_exmember jsonb;
    v_standings_exmember_old jsonb;
    v_standings_dup_user jsonb;
    v_standings_dup_user_case jsonb;
    v_standings_dup_position jsonb;
    v_standings_gap jsonb;
    v_standings_negative jsonb;
    v_standings_negative_wins jsonb;
    v_standings_negative_best jsonb;
    v_standings_missing_total jsonb;
    v_standings_bad_uuid jsonb;
    v_bad_standings jsonb;
    v_count      int;
    v_active     boolean;
    v_name       text;
    v_def        text;
    v_league     uuid;
    v_archived   uuid;
    v_state      text;
begin
    -- Utenti distinti. admin_a e' anche membro (non admin) della lega B.
    insert into auth.users (id, email)
    values
        (v_admin_a, 'admin-a-season@example.test'),
        (v_admin_b, 'admin-b-season@example.test'),
        (v_member_b, 'member-b-season@example.test'),
        (v_outsider, 'outsider-season@example.test'),
        (v_ex_member, 'ex-member-season@example.test'),
        (v_ex_member_old, 'ex-member-old-season@example.test');

    insert into public.profiles (id, display_name)
    values
        (v_admin_a, 'Admin A'),
        (v_admin_b, 'Admin B'),
        (v_member_b, 'Member B'),
        (v_outsider, 'Outsider'),
        (v_ex_member, 'Ex giocatore'),
        (v_ex_member_old, 'Ex giocatore vecchia stagione')
    on conflict (id) do nothing;

    insert into public.leagues (name, invite_code, rules, created_by)
    values ('Lega A', 'TSTSA1', '{}'::jsonb, v_admin_a)
    returning id into v_league_a;

    insert into public.leagues (name, invite_code, rules, created_by)
    values ('Lega B', 'TSTSB1', '{}'::jsonb, v_admin_b)
    returning id into v_league_b;

    insert into public.league_members (league_id, user_id, role)
    values
        (v_league_a, v_admin_a, 'admin'),
        (v_league_b, v_admin_b, 'admin'),
        (v_league_b, v_admin_a, 'player'),
        (v_league_b, v_member_b, 'player');

    insert into public.seasons (league_id, name, is_active)
    values (v_league_a, '2026 A', true)
    returning id into v_season_a;
    insert into public.seasons (league_id, name, is_active)
    values (v_league_b, '2026 B', true)
    returning id into v_season_b;
    insert into public.seasons (league_id, name, is_active)
    values (v_league_a, '2026 C', true)
    returning id into v_season_c;
    insert into public.seasons (league_id, name, is_active)
    values (v_league_a, '2026 D', true)
    returning id into v_season_d;
    insert into public.seasons (league_id, name, is_active)
    values (v_league_a, '2026 E', true)
    returning id into v_season_e;
    insert into public.seasons (league_id, name, is_active)
    values (v_league_a, '2026 F', true)
    returning id into v_season_f;
    insert into public.seasons (league_id, name, is_active)
    values (v_league_a, '2026 G', true)
    returning id into v_season_g;
    insert into public.seasons (league_id, name, is_active)
    values (v_league_a, '2026 H', true)
    returning id into v_season_h;
    insert into public.seasons (league_id, name, is_active)
    values (v_league_a, '2026 I', true)
    returning id into v_season_i;
    insert into public.seasons (league_id, name, is_active)
    values (v_league_a, '2026 J', true)
    returning id into v_season_j;
    insert into public.seasons (league_id, name, is_active)
    values (v_league_a, '2026 K', true)
    returning id into v_season_k;

    -- Le gare servono al test dell'ex-membro con pronostico storico e ai
    -- test di rollback durante l'archiviazione.
    insert into public.races (season_id, league_id, round, name, deadline)
    values
        (v_season_a, v_league_a, 1, 'Gara A', now() + interval '2 days'),
        (v_season_b, v_league_b, 1, 'Gara B', now() + interval '2 days'),
        (v_season_c, v_league_a, 1, 'Gara C', now() + interval '2 days');
    insert into public.races (season_id, league_id, round, name, deadline)
    values (v_season_d, v_league_a, 1, 'Gara D', now() + interval '2 days')
    returning id into v_race_d;
    insert into public.races (season_id, league_id, round, name, deadline)
    values (v_season_e, v_league_a, 1, 'Gara E', now() + interval '2 days')
    returning id into v_race_e;
    insert into public.races (season_id, league_id, round, name, deadline)
    values (v_season_h, v_league_a, 1, 'Gara H', now() + interval '2 days')
    returning id into v_race_h;

    -- Ex-membro con pronostico nella stagione H: recupero legittimo.
    insert into public.predictions (race_id, league_id, user_id, content)
    values (v_race_h, v_league_a, v_ex_member, '{"entries":[]}'::jsonb);

    -- Ex-membro con un pronostico storico nella stagione D: deve restare
    -- archiviabile come "Ex giocatore" (semantica di LeagueStandings).
    insert into public.predictions (race_id, league_id, user_id, content)
    values (v_race_d, v_league_a, v_ex_member, '{"entries":[]}'::jsonb);

    -- Lo stesso ex-membro ha un pronostico anche nella stagione A: serve
    -- per i test di posizioni duplicate/non contigue nella classifica A.
    insert into public.predictions (race_id, league_id, user_id, content)
    select r.id, v_league_a, v_ex_member, '{"entries":[]}'::jsonb
    from public.races r
    where r.season_id = v_season_a and r.round = 1;

    -- Ex-membro con pronostico solo in una stagione precedente: non deve
    -- poter entrare nella classifica della stagione D.
    insert into public.predictions (race_id, league_id, user_id, content)
    select r.id, v_league_a, v_ex_member_old, '{"entries":[]}'::jsonb
    from public.races r
    where r.season_id = v_season_c and r.round = 1;

    v_standings_a := jsonb_build_object(
        'rows', jsonb_build_array(
            jsonb_build_object(
                'position', 1,
                'userId', v_admin_a::text,
                'displayName', 'Admin A',
                'totalPoints', 10.0,
                'raceWins', 1,
                'bestRacePoints', 10.0
            )
        )
    );
    v_standings_b := jsonb_build_object(
        'rows', jsonb_build_array(
            jsonb_build_object(
                'position', 1,
                'userId', v_admin_b::text,
                'displayName', 'Admin B',
                'totalPoints', 20.0,
                'raceWins', 1,
                'bestRacePoints', 20.0
            )
        )
    );
    v_standings_bad := jsonb_build_object(
        'rows', jsonb_build_array(
            jsonb_build_object(
                'position', 1,
                'userId', v_outsider::text,
                'displayName', 'Outsider',
                'totalPoints', 5.0,
                'raceWins', 0,
                'bestRacePoints', 5.0
            )
        )
    );
    v_standings_other_league := jsonb_build_object(
        'rows', jsonb_build_array(
            jsonb_build_object(
                'position', 1,
                'userId', v_admin_a::text,
                'displayName', 'Admin A',
                'totalPoints', 10.0,
                'raceWins', 1,
                'bestRacePoints', 10.0
            ),
            jsonb_build_object(
                'position', 2,
                'userId', v_member_b::text,
                'displayName', 'Member B',
                'totalPoints', 5.0,
                'raceWins', 0,
                'bestRacePoints', 5.0
            )
        )
    );
    v_standings_exmember := jsonb_build_object(
        'rows', jsonb_build_array(
            jsonb_build_object(
                'position', 1,
                'userId', v_admin_a::text,
                'displayName', 'Admin A',
                'totalPoints', 10.0,
                'raceWins', 1,
                'bestRacePoints', 10.0
            ),
            jsonb_build_object(
                'position', 2,
                'userId', v_ex_member::text,
                'displayName', 'Ex giocatore',
                'totalPoints', 5.0,
                'raceWins', 0,
                'bestRacePoints', 5.0
            )
        )
    );
    v_standings_exmember_old := jsonb_build_object(
        'rows', jsonb_build_array(
            jsonb_build_object(
                'position', 1,
                'userId', v_admin_a::text,
                'displayName', 'Admin A',
                'totalPoints', 10.0,
                'raceWins', 1,
                'bestRacePoints', 10.0
            ),
            jsonb_build_object(
                'position', 2,
                'userId', v_ex_member_old::text,
                'displayName', 'Ex giocatore vecchia stagione',
                'totalPoints', 5.0,
                'raceWins', 0,
                'bestRacePoints', 5.0
            )
        )
    );
    v_standings_dup_user := jsonb_build_object(
        'rows', jsonb_build_array(
            jsonb_build_object(
                'position', 1, 'userId', v_admin_a::text,
                'displayName', 'Admin A', 'totalPoints', 10.0,
                'raceWins', 1, 'bestRacePoints', 10.0
            ),
            jsonb_build_object(
                'position', 2, 'userId', v_admin_a::text,
                'displayName', 'Admin A', 'totalPoints', 5.0,
                'raceWins', 0, 'bestRacePoints', 5.0
            )
        )
    );
    v_standings_dup_user_case := jsonb_build_object(
        'rows', jsonb_build_array(
            jsonb_build_object(
                'position', 1, 'userId', lower(v_admin_a::text),
                'displayName', 'Admin A', 'totalPoints', 10.0,
                'raceWins', 1, 'bestRacePoints', 10.0
            ),
            jsonb_build_object(
                'position', 2, 'userId', upper(v_admin_a::text),
                'displayName', 'Admin A', 'totalPoints', 5.0,
                'raceWins', 0, 'bestRacePoints', 5.0
            )
        )
    );
    v_standings_dup_position := jsonb_build_object(
        'rows', jsonb_build_array(
            jsonb_build_object(
                'position', 1, 'userId', v_admin_a::text,
                'displayName', 'Admin A', 'totalPoints', 10.0,
                'raceWins', 1, 'bestRacePoints', 10.0
            ),
            jsonb_build_object(
                'position', 1, 'userId', v_ex_member::text,
                'displayName', 'Ex giocatore', 'totalPoints', 5.0,
                'raceWins', 0, 'bestRacePoints', 5.0
            )
        )
    );
    v_standings_gap := jsonb_build_object(
        'rows', jsonb_build_array(
            jsonb_build_object(
                'position', 1, 'userId', v_admin_a::text,
                'displayName', 'Admin A', 'totalPoints', 10.0,
                'raceWins', 1, 'bestRacePoints', 10.0
            ),
            jsonb_build_object(
                'position', 3, 'userId', v_ex_member::text,
                'displayName', 'Ex giocatore', 'totalPoints', 5.0,
                'raceWins', 0, 'bestRacePoints', 5.0
            )
        )
    );
    v_standings_negative := jsonb_build_object(
        'rows', jsonb_build_array(
            jsonb_build_object(
                'position', 1, 'userId', v_admin_a::text,
                'displayName', 'Admin A', 'totalPoints', -1.0,
                'raceWins', 0, 'bestRacePoints', 0.0
            )
        )
    );
    v_standings_negative_wins := jsonb_build_object(
        'rows', jsonb_build_array(
            jsonb_build_object(
                'position', 1, 'userId', v_admin_a::text,
                'displayName', 'Admin A', 'totalPoints', 0.0,
                'raceWins', -1, 'bestRacePoints', 0.0
            )
        )
    );
    v_standings_negative_best := jsonb_build_object(
        'rows', jsonb_build_array(
            jsonb_build_object(
                'position', 1, 'userId', v_admin_a::text,
                'displayName', 'Admin A', 'totalPoints', 0.0,
                'raceWins', 0, 'bestRacePoints', -0.5
            )
        )
    );
    v_standings_missing_total := jsonb_build_object(
        'rows', jsonb_build_array(
            jsonb_build_object(
                'position', 1, 'userId', v_admin_a::text,
                'displayName', 'Admin A', 'raceWins', 0,
                'bestRacePoints', 0.0
            )
        )
    );
    v_standings_bad_uuid := jsonb_build_object(
        'rows', jsonb_build_array(
            jsonb_build_object(
                'position', 1, 'userId', 'not-a-uuid',
                'displayName', 'Admin A', 'totalPoints', 0.0,
                'raceWins', 0, 'bestRacePoints', 0.0
            )
        )
    );

    -- 1a) Authenticated non ha piu' INSERT diretto: il privilegio di tabella
    -- e' revocato e la vecchia policy INSERT e' stata rimossa.
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin_a::text, true);
    if has_table_privilege('authenticated', 'public.season_snapshots', 'INSERT') then
        raise exception 'FAIL: authenticated conserva INSERT diretto';
    end if;
    begin
        insert into public.season_snapshots (
            season_id, league_id, season_name, standings, archived_by
        ) values (
            v_season_a, v_league_a, '2026 A', v_standings_a, v_admin_a
        );
        raise exception 'FAIL: insert diretto same-league accettato';
    exception
        when insufficient_privilege then null;
        when others then
            if SQLERRM like 'FAIL:%' then
                raise;
            end if;
            raise exception 'FAIL: errore inatteso insert same-league: %', SQLERRM;
    end;

    -- 1b) Anche se un grant residuo concedesse INSERT, senza policy RLS
    -- l'INSERT diretto deve essere rifiutato. Il grant viene rimosso subito.
    perform set_config('role', 'postgres', true);
    grant insert on public.season_snapshots to authenticated;
    if not has_table_privilege('authenticated', 'public.season_snapshots', 'INSERT') then
        raise exception 'FAIL: grant temporaneo non applicato';
    end if;
    perform set_config('role', 'authenticated', true);
    begin
        insert into public.season_snapshots (
            season_id, league_id, season_name, standings, archived_by
        ) values (
            v_season_a, v_league_a, '2026 A', v_standings_a, v_admin_a
        );
        raise exception 'FAIL: insert diretto accettato con grant residuo';
    exception
        when insufficient_privilege then
            -- 42501 copre sia permission denied sia RLS: qui deve essere RLS.
            if SQLERRM not like '%row-level security%' then
                raise exception 'FAIL: errore inatteso con grant residuo: %', SQLERRM;
            end if;
        when others then
            if SQLERRM like 'FAIL:%' then
                raise;
            end if;
            raise exception 'FAIL: errore inatteso con grant residuo: %', SQLERRM;
    end;
    perform set_config('role', 'postgres', true);
    revoke insert on public.season_snapshots from authenticated;

    -- 1c) FK composita: anche il ruolo owner, che bypassa RLS, non puo'
    -- inserire una stagione della lega B con league_id A.
    begin
        insert into public.season_snapshots (
            season_id, league_id, season_name, standings, archived_by
        ) values (
            v_season_b, v_league_a, '2026 B', v_standings_b, v_admin_a
        );
        raise exception 'FAIL: insert cross-league accettato dal owner';
    exception
        when foreign_key_violation then null;
        when others then
            if SQLERRM like 'FAIL:%' then
                raise;
            end if;
            raise exception 'FAIL: errore inatteso insert cross-league: %', SQLERRM;
    end;

    select count(*) into v_count
    from public.season_snapshots
    where season_id in (v_season_a, v_season_b);
    if v_count <> 0 then
        raise exception 'FAIL: snapshot diretto presente (%)', v_count;
    end if;
    select is_active into v_active
    from public.seasons where id = v_season_b;
    if v_active is distinct from true then
        raise exception 'FAIL: stagione B modificata dal tentativo cross-league';
    end if;

    -- 2) Un membro non admin non puo' archiviare la stagione B.
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_member_b::text, true);
    begin
        perform public.archive_season(
            v_league_b, v_season_b, '2026 B', v_standings_b
        );
        raise exception 'FAIL: archiviazione non-admin accettata';
    exception when others then
        if SQLERRM like 'FAIL:%' then raise; end if;
    end;

    perform set_config('role', 'postgres', true);
    select count(*) into v_count
    from public.season_snapshots where season_id = v_season_b;
    if v_count <> 0 then
        raise exception 'FAIL: snapshot creato da non-admin (%)', v_count;
    end if;
    select is_active into v_active
    from public.seasons where id = v_season_b;
    if v_active is distinct from true then
        raise exception 'FAIL: stagione B disattivata da non-admin';
    end if;

    -- 3) Admin A prova ad archiviare la stagione B passando la lega A:
    -- l'admin check passa su A, ma la stagione non appartiene ad A.
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin_a::text, true);
    begin
        perform public.archive_season(
            v_league_a, v_season_b, '2026 B', v_standings_b
        );
        raise exception 'FAIL: stagione di altra lega archiviata';
    exception when others then
        if SQLERRM like 'FAIL:%' then raise; end if;
    end;

    perform set_config('role', 'postgres', true);
    select count(*) into v_count
    from public.season_snapshots where season_id = v_season_b;
    if v_count <> 0 then
        raise exception 'FAIL: snapshot creato con lega forgiata (%)', v_count;
    end if;

    -- 4) Classifica con un utente fuori dalla lega: rifiutata.
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin_a::text, true);
    begin
        perform public.archive_season(
            v_league_a, v_season_a, '2026 A', v_standings_bad
        );
        raise exception 'FAIL: standings con utente esterno accettate';
    exception when others then
        if SQLERRM like 'FAIL:%' then raise; end if;
    end;

    perform set_config('role', 'postgres', true);
    select count(*) into v_count
    from public.season_snapshots where season_id = v_season_a;
    if v_count <> 0 then
        raise exception 'FAIL: snapshot creato con standings invalide (%)', v_count;
    end if;
    select is_active into v_active
    from public.seasons where id = v_season_a;
    if v_active is distinct from true then
        raise exception 'FAIL: stagione A disattivata da standings invalide';
    end if;

    -- 4a) Payload incoerenti: userId/posizioni duplicati, buchi e negativi.
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin_a::text, true);
    for v_bad_standings in
        select unnest(array[
            v_standings_dup_user,
            v_standings_dup_user_case,
            v_standings_dup_position,
            v_standings_gap,
            v_standings_negative,
            v_standings_negative_wins,
            v_standings_negative_best,
            v_standings_missing_total,
            v_standings_bad_uuid,
            v_standings_exmember_old
        ])
    loop
        begin
            perform public.archive_season(
                v_league_a, v_season_a, '2026 A', v_bad_standings
            );
            raise exception 'FAIL: standings incoerenti accettate';
        exception when others then
            if SQLERRM like 'FAIL:%' then raise; end if;
        end;
    end loop;

    perform set_config('role', 'postgres', true);
    select count(*) into v_count
    from public.season_snapshots where season_id = v_season_a;
    if v_count <> 0 then
        raise exception 'FAIL: snapshot creato da standings incoerenti (%)', v_count;
    end if;
    select is_active into v_active
    from public.seasons where id = v_season_a;
    if v_active is distinct from true then
        raise exception 'FAIL: stagione A disattivata da standings incoerenti';
    end if;

    -- 4b) is_active non e' aggiornabile direttamente dai client: il grant
    -- colonnare esclude la colonna, mentre la RPC owner puo' aggiornarla.
    if has_table_privilege('authenticated', 'public.seasons', 'UPDATE')
       or has_column_privilege('authenticated', 'public.seasons', 'is_active', 'UPDATE') then
        raise exception 'FAIL: authenticated conserva UPDATE su seasons.is_active';
    end if;

    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin_a::text, true);
    begin
        update public.seasons
        set is_active = false
        where id = v_season_a;
        raise exception 'FAIL: deactivation diretta consentita';
    exception
        when insufficient_privilege then null;
        when others then
            if SQLERRM like 'FAIL:%' then raise; end if;
            raise exception 'FAIL: errore inatteso deactivation diretta: %', SQLERRM;
    end;

    -- Anche un player (non admin) non puo' deattivare la stagione: il
    -- privilegio colonnare manca prima ancora della RLS.
    perform set_config('request.jwt.claim.sub', v_member_b::text, true);
    begin
        update public.seasons
        set is_active = false
        where id = v_season_b;
        raise exception 'FAIL: deactivation diretta da player consentita';
    exception
        when insufficient_privilege then null;
        when others then
            if SQLERRM like 'FAIL:%' then raise; end if;
            raise exception 'FAIL: errore inatteso deactivation da player: %', SQLERRM;
    end;

    perform set_config('role', 'postgres', true);
    select is_active into v_active
    from public.seasons where id = v_season_b;
    if v_active is distinct from true then
        raise exception 'FAIL: player ha deattivato la stagione B';
    end if;

    -- Metadati legittimi: il nome resta modificabile dall'admin.
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin_a::text, true);
    update public.seasons
    set name = '2026 A renamed'
    where id = v_season_a;
    get diagnostics v_count = row_count;
    if v_count <> 1 then
        raise exception 'FAIL: update metadati legittimo bloccato (%)', v_count;
    end if;
    update public.seasons
    set name = '2026 A'
    where id = v_season_a;
    get diagnostics v_count = row_count;
    if v_count <> 1 then
        raise exception 'FAIL: ripristino metadati fallito (%)', v_count;
    end if;

    -- Un membro non admin ha il grant colonnare ma RLS deve bloccare.
    perform set_config('request.jwt.claim.sub', v_member_b::text, true);
    update public.seasons
    set name = 'Aggiornata da member B'
    where id = v_season_a;
    get diagnostics v_count = row_count;
    if v_count <> 0 then
        raise exception 'FAIL: non-admin ha aggiornato i metadati (%)', v_count;
    end if;

    perform set_config('role', 'postgres', true);
    select is_active into v_active
    from public.seasons where id = v_season_a;
    if v_active is distinct from true then
        raise exception 'FAIL: stagione A disattivata da update diretto';
    end if;

    -- 5) Archiviazione valida della stagione B da parte di admin B.
    -- Il nome passato dal client e' volutamente diverso: deve vincere quello
    -- autorevole del database.
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin_b::text, true);
    perform public.archive_season(
        v_league_b, v_season_b, 'Nome forgiato', v_standings_b
    );

    perform set_config('role', 'postgres', true);
    select count(*) into v_count
    from public.season_snapshots where season_id = v_season_b;
    if v_count <> 1 then
        raise exception 'FAIL: snapshot B atteso 1, trovato %', v_count;
    end if;
    select season_name, league_id, archived_by
    into v_name, v_league, v_archived
    from public.season_snapshots where season_id = v_season_b;
    if v_name is distinct from '2026 B'
       or v_league is distinct from v_league_b
       or v_archived is distinct from v_admin_b then
        raise exception 'FAIL: snapshot B con metadati errati';
    end if;
    select is_active into v_active
    from public.seasons where id = v_season_b;
    if v_active is distinct from false then
        raise exception 'FAIL: stagione B ancora attiva';
    end if;

    -- Reattivazione diretta: deve essere rifiutata dal grant colonnare.
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin_b::text, true);
    begin
        update public.seasons
        set is_active = true
        where id = v_season_b;
        raise exception 'FAIL: reactivation diretta consentita';
    exception
        when insufficient_privilege then null;
        when others then
            if SQLERRM like 'FAIL:%' then raise; end if;
            raise exception 'FAIL: errore inatteso reactivation diretta: %', SQLERRM;
    end;

    perform set_config('role', 'postgres', true);
    select is_active into v_active
    from public.seasons where id = v_season_b;
    if v_active is distinct from false then
        raise exception 'FAIL: stagione B riattivata direttamente';
    end if;

    -- 5b) Un ex-membro con un pronostico storico nella stagione resta
    -- archiviabile: la classifica include "Ex giocatore" e non va rifiutata.
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin_a::text, true);
    perform public.archive_season(
        v_league_a, v_season_d, '2026 D', v_standings_exmember
    );

    perform set_config('role', 'postgres', true);
    select count(*) into v_count
    from public.season_snapshots where season_id = v_season_d;
    if v_count <> 1 then
        raise exception 'FAIL: snapshot D atteso 1, trovato %', v_count;
    end if;
    select is_active into v_active
    from public.seasons where id = v_season_d;
    if v_active is distinct from false then
        raise exception 'FAIL: stagione D ancora attiva';
    end if;
    select jsonb_array_length(standings -> 'rows') into v_count
    from public.season_snapshots where season_id = v_season_d;
    if v_count <> 2 then
        raise exception 'FAIL: snapshot D con righe errate (%)', v_count;
    end if;

    -- 6) RLS: il membro della lega B legge lo snapshot; un estraneo no.
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin_b::text, true);
    select count(*) into v_count
    from public.season_snapshots where season_id = v_season_b;
    if v_count <> 1 then
        raise exception 'FAIL: membro B non legge lo snapshot (%)', v_count;
    end if;
    perform set_config('request.jwt.claim.sub', v_outsider::text, true);
    select count(*) into v_count
    from public.season_snapshots where season_id = v_season_b;
    if v_count <> 0 then
        raise exception 'FAIL: estraneo legge lo snapshot (%)', v_count;
    end if;

    -- 7) Snapshot immutabili: UPDATE/DELETE revocati e nessuna policy.
    if has_table_privilege('authenticated', 'public.season_snapshots', 'UPDATE')
       or has_table_privilege('authenticated', 'public.season_snapshots', 'DELETE') then
        raise exception 'FAIL: authenticated conserva UPDATE/DELETE sugli snapshot';
    end if;

    -- Simula un grant residuo: anche con il privilegio, RLS senza policy
    -- deve lasciare 0 righe modificate/cancellate.
    perform set_config('role', 'postgres', true);
    grant update, delete on public.season_snapshots to authenticated;
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin_b::text, true);
    update public.season_snapshots
    set league_id = v_league_a
    where season_id = v_season_b;
    get diagnostics v_count = row_count;
    if v_count <> 0 then
        raise exception 'FAIL: update diretto dello snapshot consentito (%)', v_count;
    end if;
    delete from public.season_snapshots where season_id = v_season_b;
    get diagnostics v_count = row_count;
    if v_count <> 0 then
        raise exception 'FAIL: delete diretto dello snapshot consentito (%)', v_count;
    end if;

    perform set_config('role', 'postgres', true);
    revoke update, delete on public.season_snapshots from authenticated;
    select count(*) into v_count
    from public.season_snapshots where season_id = v_season_b;
    if v_count <> 1 then
        raise exception 'FAIL: snapshot B perso dopo update/delete (%)', v_count;
    end if;

    -- 8) Archiviazione ripetuta: nessun duplicato, stato invariato.
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin_b::text, true);
    begin
        perform public.archive_season(
            v_league_b, v_season_b, '2026 B', v_standings_b
        );
        raise exception 'FAIL: seconda archiviazione accettata';
    exception when others then
        if SQLERRM like 'FAIL:%' then raise; end if;
    end;

    perform set_config('role', 'postgres', true);
    select count(*) into v_count
    from public.season_snapshots where season_id = v_season_b;
    if v_count <> 1 then
        raise exception 'FAIL: duplicato dopo seconda archiviazione (%)', v_count;
    end if;
    select is_active into v_active
    from public.seasons where id = v_season_b;
    if v_active is distinct from false then
        raise exception 'FAIL: stagione B riattivata dopo seconda archiviazione';
    end if;

    -- 9) Stagione inesistente: nessuna modifica.
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin_a::text, true);
    begin
        perform public.archive_season(
            v_league_a, gen_random_uuid(), '2026 X', v_standings_a
        );
        raise exception 'FAIL: stagione inesistente archiviata';
    exception when others then
        if SQLERRM like 'FAIL:%' then raise; end if;
    end;

    perform set_config('role', 'postgres', true);
    select count(*) into v_count
    from public.season_snapshots s
    join public.seasons se on se.id = s.season_id
    where se.league_id = v_league_a and se.name = '2026 A';
    if v_count <> 0 then
        raise exception 'FAIL: snapshot creato per stagione inesistente (%)', v_count;
    end if;
    select is_active into v_active
    from public.seasons where id = v_season_a;
    if v_active is distinct from true then
        raise exception 'FAIL: stagione A modificata da chiamata invalida';
    end if;

    -- 9b) Lega inesistente: nessuna modifica.
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin_a::text, true);
    begin
        perform public.archive_season(
            gen_random_uuid(), v_season_a, '2026 A', v_standings_a
        );
        raise exception 'FAIL: lega inesistente archiviata';
    exception when others then
        if SQLERRM like 'FAIL:%' then raise; end if;
    end;

    perform set_config('role', 'postgres', true);
    select count(*) into v_count
    from public.season_snapshots where season_id = v_season_a;
    if v_count <> 0 then
        raise exception 'FAIL: snapshot creato per lega inesistente (%)', v_count;
    end if;
    select is_active into v_active
    from public.seasons where id = v_season_a;
    if v_active is distinct from true then
        raise exception 'FAIL: stagione A modificata da lega inesistente';
    end if;

    -- 10) Fallimento simulato dopo l'INSERT dello snapshot: il trigger sul
    -- deactivation fa fallire l'UPDATE; l'intera RPC deve rollbackare.
    perform set_config('role', 'postgres', true);
    create or replace function public.test_fail_season_update()
    returns trigger
    language plpgsql
    as $$
    begin
        if new.id::text = current_setting('test.fail_season', true) then
            raise exception 'simulated failure during season deactivation';
        end if;
        return new;
    end;
    $$;
    grant execute on function public.test_fail_season_update() to authenticated;
    drop trigger if exists test_fail_season_update on public.seasons;
    create trigger test_fail_season_update
        before update on public.seasons
        for each row execute function public.test_fail_season_update();
    perform set_config('test.fail_season', v_season_c::text, true);

    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin_a::text, true);
    begin
        perform public.archive_season(
            v_league_a, v_season_c, '2026 C', v_standings_a
        );
        raise exception 'FAIL: archivio C non fallito';
    exception when others then
        if SQLERRM like 'FAIL:%' then raise; end if;
    end;

    perform set_config('role', 'postgres', true);
    select count(*) into v_count
    from public.season_snapshots where season_id = v_season_c;
    if v_count <> 0 then
        raise exception 'FAIL: snapshot C rimasto dopo rollback (%)', v_count;
    end if;
    select is_active into v_active
    from public.seasons where id = v_season_c;
    if v_active is distinct from true then
        raise exception 'FAIL: stagione C disattivata dopo rollback';
    end if;

    drop trigger if exists test_fail_season_update on public.seasons;
    drop function if exists public.test_fail_season_update();

    -- 10b) Fallimento durante l'INSERT dello snapshot: la stagione deve
    -- restare attiva e non deve restare alcuno snapshot parziale.
    perform set_config('role', 'postgres', true);
    create or replace function public.test_fail_snapshot_insert()
    returns trigger
    language plpgsql
    as $$
    begin
        if new.season_id::text = current_setting('test.fail_snapshot_season', true) then
            raise exception 'simulated failure during snapshot insert';
        end if;
        return new;
    end;
    $$;
    drop trigger if exists test_fail_snapshot_insert on public.season_snapshots;
    create trigger test_fail_snapshot_insert
        before insert on public.season_snapshots
        for each row execute function public.test_fail_snapshot_insert();
    perform set_config('test.fail_snapshot_season', v_season_e::text, true);

    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin_a::text, true);
    begin
        perform public.archive_season(
            v_league_a, v_season_e, '2026 E', v_standings_a
        );
        raise exception 'FAIL: archivio E non fallito';
    exception when others then
        if SQLERRM like 'FAIL:%' then raise; end if;
    end;

    perform set_config('role', 'postgres', true);
    select count(*) into v_count
    from public.season_snapshots where season_id = v_season_e;
    if v_count <> 0 then
        raise exception 'FAIL: snapshot E rimasto dopo rollback (%)', v_count;
    end if;
    select is_active into v_active
    from public.seasons where id = v_season_e;
    if v_active is distinct from true then
        raise exception 'FAIL: stagione E disattivata dopo rollback dello snapshot';
    end if;

    drop trigger if exists test_fail_snapshot_insert on public.season_snapshots;
    drop function if exists public.test_fail_snapshot_insert();

    -- 11) Grant della RPC: anon fuori, authenticated dentro.
    if has_function_privilege(
        'anon',
        'public.archive_season(uuid,uuid,text,jsonb)',
        'EXECUTE'
    ) then
        raise exception 'FAIL: anon puo eseguire archive_season';
    end if;
    if not has_function_privilege(
        'authenticated',
        'public.archive_season(uuid,uuid,text,jsonb)',
        'EXECUTE'
    ) then
        raise exception 'FAIL: authenticated non puo eseguire archive_season';
    end if;

    -- 12) Il guard DDL deve rifiutare una sostituzione debole di archive_season.
    begin
        execute $weak$
            create or replace function public.archive_season(
                p_league uuid,
                p_season uuid,
                p_name text,
                p_standings jsonb
            )
            returns void
            language plpgsql
            security definer
            set search_path = public
            as $weak_body$
            begin
                return;
            end;
            $weak_body$;
        $weak$;
        raise exception 'FAIL: guard DDL non ha bloccato la funzione debole';
    exception when others then
        if SQLERRM like 'FAIL:%' then
            raise;
        end if;
        if SQLERRM not like '%archive_season hardening guard%' then
            raise exception 'FAIL: errore inatteso del guard DDL: %', SQLERRM;
        end if;
    end;

    select pg_get_functiondef(
        'public.archive_season(uuid,uuid,text,jsonb)'::regprocedure
    ) into v_def;
    if position('userId duplicati' in v_def) = 0
       or position('coalesce(nullif(btrim(v_season_name), ''''), ''Stagione'')' in v_def) = 0 then
        raise exception 'FAIL: il guard ha lasciato una funzione non hardened';
    end if;

    -- 13) La cancellazione dei parent non deve eliminare gli snapshot.
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin_b::text, true);
    begin
        delete from public.seasons where id = v_season_b;
        raise exception 'FAIL: cancellazione stagione con snapshot consentita';
    exception
        when foreign_key_violation then null;
        when others then
            if SQLERRM like 'FAIL:%' then raise; end if;
            raise exception 'FAIL: errore inatteso cancellazione stagione: %', SQLERRM;
    end;

    begin
        delete from public.leagues where id = v_league_b;
        raise exception 'FAIL: cancellazione lega con snapshot consentita';
    exception
        when foreign_key_violation then null;
        when others then
            if SQLERRM like 'FAIL:%' then raise; end if;
            raise exception 'FAIL: errore inatteso cancellazione lega: %', SQLERRM;
    end;

    perform set_config('role', 'postgres', true);
    select count(*) into v_count
    from public.season_snapshots where season_id = v_season_b;
    if v_count <> 1 then
        raise exception 'FAIL: snapshot B perso dopo i tentativi di cancellazione (%)', v_count;
    end if;

    -- Una stagione senza snapshot deve restare cancellabile dall'admin.
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin_a::text, true);
    delete from public.seasons where id = v_season_f;
    get diagnostics v_count = row_count;
    if v_count <> 1 then
        raise exception 'FAIL: stagione senza snapshot non cancellabile (%)', v_count;
    end if;

    perform set_config('role', 'postgres', true);
    select count(*) into v_count from public.seasons where id = v_season_f;
    if v_count <> 0 then
        raise exception 'FAIL: stagione F ancora presente';
    end if;

    -- 14) Recupero esplicito dello stato legacy snapshot + stagione attiva.
    insert into public.season_snapshots (
        season_id, league_id, season_name, standings, archived_by
    ) values (
        v_season_e, v_league_a, '2026 E', v_standings_a, v_admin_a
    );

    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_member_b::text, true);
    begin
        perform public.complete_partial_season_archive(v_league_a, v_season_e);
        raise exception 'FAIL: recupero da non-admin consentito';
    exception when others then
        get stacked diagnostics v_state = returned_sqlstate;
        if SQLERRM like 'FAIL:%' then raise; end if;
        if v_state <> 'P0001' or SQLERRM not like '%amministratore%' then
            raise exception 'FAIL: errore inatteso recupero non-admin (sqlstate=%, errore=%)',
                v_state, SQLERRM;
        end if;
    end;

    perform set_config('request.jwt.claim.sub', v_admin_a::text, true);
    perform public.complete_partial_season_archive(v_league_a, v_season_e);

    perform set_config('role', 'postgres', true);
    select is_active into v_active from public.seasons where id = v_season_e;
    if v_active is distinct from false then
        raise exception 'FAIL: stagione E non completata dal recupero';
    end if;
    select count(*) into v_count
    from public.season_archive_repairs
    where season_id = v_season_e
      and league_id = v_league_a
      and repaired_by = v_admin_a;
    if v_count <> 1 then
        raise exception 'FAIL: audit recupero mancante (%)', v_count;
    end if;
    select count(*) into v_count
    from public.season_snapshots
    where season_id = v_season_e
      and standings = v_standings_a;
    if v_count <> 1 then
        raise exception 'FAIL: snapshot E modificato dal recupero';
    end if;

    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin_a::text, true);
    begin
        perform public.complete_partial_season_archive(v_league_a, v_season_e);
        raise exception 'FAIL: recupero ripetuto consentito';
    exception when others then
        get stacked diagnostics v_state = returned_sqlstate;
        if SQLERRM like 'FAIL:%' then raise; end if;
        if v_state <> 'P0001' or SQLERRM not like '%non e'' attiva%' then
            raise exception 'FAIL: errore inatteso recupero ripetuto (sqlstate=%, errore=%)',
                v_state, SQLERRM;
        end if;
    end;
    begin
        perform public.complete_partial_season_archive(v_league_a, v_season_a);
        raise exception 'FAIL: recupero senza snapshot consentito';
    exception when others then
        get stacked diagnostics v_state = returned_sqlstate;
        if SQLERRM like 'FAIL:%' then raise; end if;
        if v_state <> 'P0001' or SQLERRM not like '%Nessuno snapshot da completare%' then
            raise exception 'FAIL: errore inatteso recupero senza snapshot (sqlstate=%, errore=%)',
                v_state, SQLERRM;
        end if;
    end;

    -- Snapshot malformato: il recupero deve rifiutarlo, non completare lo stato.
    perform set_config('role', 'postgres', true);
    insert into public.season_snapshots (
        season_id, league_id, season_name, standings, archived_by
    ) values (
        v_season_g, v_league_a, '2026 G', v_standings_negative, v_admin_a
    );
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin_a::text, true);
    begin
        perform public.complete_partial_season_archive(v_league_a, v_season_g);
        raise exception 'FAIL: recupero con standings invalide consentito';
    exception when others then
        get stacked diagnostics v_state = returned_sqlstate;
        if SQLERRM like 'FAIL:%' then raise; end if;
        if v_state <> 'P0001'
           or SQLERRM not like '%non e'' valido o contiene partecipanti non eleggibili%' then
            raise exception 'FAIL: errore inatteso recupero standings invalide (sqlstate=%, errore=%)',
                v_state, SQLERRM;
        end if;
    end;

    perform set_config('role', 'postgres', true);
    select is_active into v_active from public.seasons where id = v_season_g;
    if v_active is distinct from true then
        raise exception 'FAIL: stagione G completata con snapshot invalido';
    end if;
    select count(*) into v_count
    from public.season_archive_repairs where season_id = v_season_g;
    if v_count <> 0 then
        raise exception 'FAIL: audit creato per recupero rifiutato (%)', v_count;
    end if;

    -- Ex-membro con pronostico nella stagione H: recupero legittimo.
    insert into public.season_snapshots (
        season_id, league_id, season_name, standings, archived_by
    ) values (
        v_season_h, v_league_a, '2026 H', v_standings_exmember, v_admin_a
    );
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin_a::text, true);
    perform public.complete_partial_season_archive(v_league_a, v_season_h);

    perform set_config('role', 'postgres', true);
    select is_active into v_active from public.seasons where id = v_season_h;
    if v_active is distinct from false then
        raise exception 'FAIL: ex-membro valido H non completato';
    end if;
    select count(*) into v_count
    from public.season_archive_repairs where season_id = v_season_h;
    if v_count <> 1 then
        raise exception 'FAIL: audit recupero H mancante (%)', v_count;
    end if;

    -- Outsider senza appartenenza/predizione: rifiutato senza modifiche.
    insert into public.season_snapshots (
        season_id, league_id, season_name, standings, archived_by
    ) values (
        v_season_i, v_league_a, '2026 I', v_standings_bad, v_admin_a
    );
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin_a::text, true);
    begin
        perform public.complete_partial_season_archive(v_league_a, v_season_i);
        raise exception 'FAIL: recupero con outsider consentito';
    exception when others then
        get stacked diagnostics v_state = returned_sqlstate;
        if SQLERRM like 'FAIL:%' then raise; end if;
        if v_state <> 'P0001' or SQLERRM not like '%partecipanti non eleggibili%' then
            raise exception 'FAIL: errore inatteso recupero outsider (sqlstate=%, errore=%)',
                v_state, SQLERRM;
        end if;
    end;

    perform set_config('role', 'postgres', true);
    select is_active into v_active from public.seasons where id = v_season_i;
    if v_active is distinct from true then
        raise exception 'FAIL: stagione I deattivata per outsider';
    end if;
    select count(*) into v_count
    from public.season_archive_repairs where season_id = v_season_i;
    if v_count <> 0 then
        raise exception 'FAIL: audit creato per outsider (%)', v_count;
    end if;
    select count(*) into v_count
    from public.season_snapshots
    where season_id = v_season_i and standings = v_standings_bad;
    if v_count <> 1 then
        raise exception 'FAIL: snapshot I modificato dopo rifiuto';
    end if;

    -- Misto valido + utente di un'altra lega: rifiutato.
    insert into public.season_snapshots (
        season_id, league_id, season_name, standings, archived_by
    ) values (
        v_season_j, v_league_a, '2026 J', v_standings_other_league, v_admin_a
    );
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin_a::text, true);
    begin
        perform public.complete_partial_season_archive(v_league_a, v_season_j);
        raise exception 'FAIL: recupero con utente di altra lega consentito';
    exception when others then
        get stacked diagnostics v_state = returned_sqlstate;
        if SQLERRM like 'FAIL:%' then raise; end if;
        if v_state <> 'P0001' or SQLERRM not like '%partecipanti non eleggibili%' then
            raise exception 'FAIL: errore inatteso recupero altra lega (sqlstate=%, errore=%)',
                v_state, SQLERRM;
        end if;
    end;

    perform set_config('role', 'postgres', true);
    select is_active into v_active from public.seasons where id = v_season_j;
    if v_active is distinct from true then
        raise exception 'FAIL: stagione J deattivata per utente altra lega';
    end if;
    select count(*) into v_count
    from public.season_archive_repairs where season_id = v_season_j;
    if v_count <> 0 then
        raise exception 'FAIL: audit creato per utente altra lega (%)', v_count;
    end if;
    select count(*) into v_count
    from public.season_snapshots
    where season_id = v_season_j and standings = v_standings_other_league;
    if v_count <> 1 then
        raise exception 'FAIL: snapshot J modificato dopo rifiuto';
    end if;

    -- Duplicati: rifiutati prima di audit/deattivazione.
    insert into public.season_snapshots (
        season_id, league_id, season_name, standings, archived_by
    ) values (
        v_season_k, v_league_a, '2026 K', v_standings_dup_user, v_admin_a
    );
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', v_admin_a::text, true);
    begin
        perform public.complete_partial_season_archive(v_league_a, v_season_k);
        raise exception 'FAIL: recupero con utenti duplicati consentito';
    exception when others then
        get stacked diagnostics v_state = returned_sqlstate;
        if SQLERRM like 'FAIL:%' then raise; end if;
        if v_state <> 'P0001' or SQLERRM not like '%partecipanti non eleggibili%' then
            raise exception 'FAIL: errore inatteso recupero duplicati (sqlstate=%, errore=%)',
                v_state, SQLERRM;
        end if;
    end;

    perform set_config('role', 'postgres', true);
    select is_active into v_active from public.seasons where id = v_season_k;
    if v_active is distinct from true then
        raise exception 'FAIL: stagione K deattivata per duplicati';
    end if;
    select count(*) into v_count
    from public.season_archive_repairs where season_id = v_season_k;
    if v_count <> 0 then
        raise exception 'FAIL: audit creato per duplicati (%)', v_count;
    end if;
    select count(*) into v_count
    from public.season_snapshots
    where season_id = v_season_k and standings = v_standings_dup_user;
    if v_count <> 1 then
        raise exception 'FAIL: snapshot K modificato dopo rifiuto';
    end if;

    -- 15) ACL complete, default grant simulation, TRUNCATE e service_role.
    perform set_config('role', 'postgres', true);
    grant all on public.season_snapshots, public.seasons, public.leagues
        to authenticated;
    perform public.harden_season_archive_acl();

    if has_table_privilege('authenticated', 'public.season_snapshots', 'INSERT')
       or has_table_privilege('authenticated', 'public.season_snapshots', 'UPDATE')
       or has_table_privilege('authenticated', 'public.season_snapshots', 'DELETE')
       or has_table_privilege('authenticated', 'public.season_snapshots', 'TRUNCATE')
       or has_table_privilege('authenticated', 'public.season_snapshots', 'REFERENCES')
       or has_table_privilege('authenticated', 'public.season_snapshots', 'TRIGGER') then
        raise exception 'FAIL: privilegi residui su season_snapshots';
    end if;
    if not has_table_privilege('authenticated', 'public.season_snapshots', 'SELECT') then
        raise exception 'FAIL: SELECT snapshot mancante';
    end if;

    if has_table_privilege('authenticated', 'public.seasons', 'UPDATE')
       or has_column_privilege('authenticated', 'public.seasons', 'is_active', 'UPDATE') then
        raise exception 'FAIL: UPDATE/is_active residuo su seasons';
    end if;
    if not has_column_privilege('authenticated', 'public.seasons', 'name', 'UPDATE')
       or not has_table_privilege('authenticated', 'public.seasons', 'SELECT')
       or not has_table_privilege('authenticated', 'public.seasons', 'INSERT')
       or not has_table_privilege('authenticated', 'public.seasons', 'DELETE') then
        raise exception 'FAIL: grant necessari su seasons mancanti';
    end if;
    if has_table_privilege('authenticated', 'public.seasons', 'TRUNCATE')
       or has_table_privilege('authenticated', 'public.seasons', 'REFERENCES')
       or has_table_privilege('authenticated', 'public.seasons', 'TRIGGER')
       or has_table_privilege('authenticated', 'public.leagues', 'TRUNCATE')
       or has_table_privilege('authenticated', 'public.leagues', 'REFERENCES')
       or has_table_privilege('authenticated', 'public.leagues', 'TRIGGER') then
        raise exception 'FAIL: privilegi residui su seasons/leagues';
    end if;
    if has_function_privilege(
        'authenticated',
        'public.harden_season_archive_acl()',
        'EXECUTE'
    ) then
        raise exception 'FAIL: helper ACL eseguibile da authenticated';
    end if;
    if has_function_privilege(
        'service_role',
        'public.archive_season(uuid,uuid,text,jsonb)',
        'EXECUTE'
    ) then
        raise exception 'FAIL: service_role puo eseguire archive_season senza grant esplicito';
    end if;
    if has_function_privilege(
        'authenticated',
        'public.season_snapshot_shape_is_valid(jsonb)',
        'EXECUTE'
    ) or has_function_privilege(
        'authenticated',
        'public.season_snapshot_participants_are_eligible(uuid,uuid,jsonb)',
        'EXECUTE'
    ) then
        raise exception 'FAIL: validatori legacy eseguibili da authenticated';
    end if;

    if current_setting('server_version_num')::int >= 170000 then
        if has_table_privilege('authenticated', 'public.season_snapshots', 'MAINTAIN')
           or has_table_privilege('authenticated', 'public.seasons', 'MAINTAIN')
           or has_table_privilege('authenticated', 'public.leagues', 'MAINTAIN') then
            raise exception 'FAIL: privilegio MAINTAIN residuo';
        end if;
    end if;

    if not exists (
        select 1 from pg_event_trigger
        where evtname = 'archive_season_hardening_guard'
          and evtenabled = 'O'
    ) then
        raise exception 'FAIL: event trigger di hardening assente o disabilitato';
    end if;
    if not exists (
        select 1 from pg_event_trigger
        where evtname = 'archive_season_mutation_guard'
          and evtenabled = 'O'
    ) then
        raise exception 'FAIL: event trigger di mutazione assente o disabilitato';
    end if;
    if to_regclass('public.archive_season_canonical_definition') is null then
        raise exception 'FAIL: canonical definition store assente';
    end if;
    if has_table_privilege('authenticated', 'public.archive_season_canonical_definition', 'SELECT')
       or has_table_privilege('anon', 'public.archive_season_canonical_definition', 'SELECT')
       or has_table_privilege('service_role', 'public.archive_season_canonical_definition', 'SELECT') then
        raise exception 'FAIL: canonical definition store accessibile ai client';
    end if;
    if to_regclass('public.archive_season_deploy_lockout') is null then
        raise exception 'FAIL: deployment lockout table assente';
    end if;
    if (select active from public.archive_season_deploy_lockout where singleton) is not false then
        raise exception 'FAIL: deployment lockout ancora attivo dopo finalize';
    end if;
    if has_table_privilege('authenticated', 'public.archive_season_deploy_lockout', 'SELECT')
       or has_table_privilege('anon', 'public.archive_season_deploy_lockout', 'SELECT')
       or has_table_privilege('service_role', 'public.archive_season_deploy_lockout', 'SELECT') then
        raise exception 'FAIL: deployment lockout table accessibile ai client';
    end if;
    if not exists (
        select 1 from pg_constraint
        where conrelid = 'public.season_snapshots'::regclass
          and conname = 'season_snapshots_season_id_fkey'
    ) or not exists (
        select 1 from pg_constraint
        where conrelid = 'public.season_snapshots'::regclass
          and conname = 'season_snapshots_league_id_fkey'
    ) or not exists (
        select 1 from pg_constraint
        where conrelid = 'public.season_snapshots'::regclass
          and conname = 'season_snapshots_season_league_fk'
    ) then
        raise exception 'FAIL: una FK snapshot richiesta e'' assente';
    end if;
    if exists (
        select 1 from pg_constraint
        where conrelid = 'public.season_snapshots'::regclass
          and conname in (
              'season_snapshots_season_id_fkey',
              'season_snapshots_league_id_fkey',
              'season_snapshots_season_league_fk'
          )
          and (confdeltype <> 'r' or not convalidated)
    ) then
        raise exception 'FAIL: FK snapshot non RESTRICT o non validata';
    end if;
    if has_table_privilege('authenticated', 'public.season_archive_repairs', 'SELECT')
       or has_table_privilege('authenticated', 'public.season_archive_repairs', 'INSERT')
       or has_table_privilege('authenticated', 'public.season_archive_repairs', 'UPDATE')
       or has_table_privilege('authenticated', 'public.season_archive_repairs', 'DELETE')
       or has_table_privilege('authenticated', 'public.season_archive_repairs', 'TRUNCATE') then
        raise exception 'FAIL: audit table accessibile ai client';
    end if;

    perform set_config('role', 'authenticated', true);
    begin
        execute 'truncate public.season_snapshots';
        raise exception 'FAIL: TRUNCATE snapshot consentito';
    exception
        when insufficient_privilege then null;
        when others then
            if SQLERRM like 'FAIL:%' then raise; end if;
            raise exception 'FAIL: errore inatteso TRUNCATE: %', SQLERRM;
    end;
    perform set_config('role', 'postgres', true);

    raise notice 'season snapshot integrity: cross-league, RLS e atomicità OK';
end;
$test$;

reset role;
rollback;
