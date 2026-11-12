-- =============================================================================
-- FantaFormula 1 - migrazione 0016_atomic_prediction_revision.sql
--
-- Obiettivo: rendere atomico il salvataggio dei pronostici con una revisione
-- attesa. Il client invia il `submitted_at` del pronostico che ha letto; il
-- server aggiorna solo se la riga e' ancora a quella revisione.
--
-- Perche' non basta un controllo client-side:
--   leggere la revisione, verificarla e poi fare upsert lascia una finestra in
--   cui un altro dispositivo puo' scrivere. Qui il lock/condizione e
--   l'aggiornamento avvengono nella stessa transazione/statement.
--
-- Status restituiti:
--   'saved'     -> inserimento o aggiornamento riuscito;
--   'unchanged' -> il contenuto richiesto coincide gia' con il server;
--   'conflict'  -> il server ha una revisione diversa o non verificabile.
--
-- In conflitto il chiamante riceve contenuto e submitted_at correnti, cosi'
-- puo' mostrare la risoluzione manuale senza sovrascrivere automaticamente.
--
-- Idempotente. Non applicare al progetto remoto durante lo sviluppo.
-- =============================================================================

create or replace function public.save_prediction_if_revision(
    p_race uuid,
    p_league uuid,
    p_content jsonb,
    p_expected_submitted_at timestamptz default null
)
returns table (
    status text,
    submitted_at timestamptz,
    content jsonb
)
language plpgsql
security invoker
set search_path = public
as $$
declare
    v_current public.predictions%rowtype;
    v_updated public.predictions%rowtype;
    v_inserted public.predictions%rowtype;
    v_same_content boolean;
begin
    if p_race is null or p_league is null then
        raise exception 'Gara o lega mancante';
    end if;
    if p_content is null or jsonb_typeof(p_content) <> 'object' then
        raise exception 'Contenuto del pronostico non valido';
    end if;
    if not exists (
        select 1
        from public.races r
        where r.id = p_race and r.league_id = p_league
    ) then
        raise exception 'Gara e lega non coerenti';
    end if;

    -- Il lock rende atomico il confronto con la revisione attesa. La RLS
    -- limita la riga al proprietario corrente.
    select * into v_current
    from public.predictions p
    where p.race_id = p_race
      and p.user_id = auth.uid()
    for update;

    if found then
        v_same_content :=
            (v_current.content - 'submittedAtEpochMs') =
            (p_content - 'submittedAtEpochMs');

        if v_same_content then
            -- Retry o duplicato: nessuna nuova scrittura, ma il client riceve
            -- la revisione server corrente.
            return query
                select 'unchanged'::text, v_current.submitted_at, v_current.content;
            return;
        end if;

        -- Il client usa epoch millisecondi: confronta la stessa granularita'
        -- del timestamp server, altrimenti i microsecondi di Postgres
        -- renderebbero impossibile ogni salvataggio successivo al primo.
        if date_trunc('milliseconds', v_current.submitted_at)
            is not distinct from date_trunc('milliseconds', p_expected_submitted_at) then
            update public.predictions p
            set content = p_content
            where p.race_id = p_race
              and p.user_id = auth.uid()
            returning * into v_updated;

            if not found then
                -- RLS/timing: il server non ha applicato l'update. Non
                -- inventare un successo; restituisce lo stato corrente.
                select * into v_current
                from public.predictions p
                where p.race_id = p_race
                  and p.user_id = auth.uid()
                for update;

                return query
                    select 'conflict'::text,
                           v_current.submitted_at,
                           v_current.content;
                return;
            end if;

            return query
                select 'saved'::text, v_updated.submitted_at, v_updated.content;
            return;
        end if;

        -- Revisione diversa e contenuto diverso: il server vince.
        return query
            select 'conflict'::text, v_current.submitted_at, v_current.content;
        return;
    end if;

    -- Nessuna riga corrente. Una revisione attesa non-null significa che il
    -- client credeva esistesse una versione precedente: non ricrearla.
    if p_expected_submitted_at is not null then
        return query
            select 'conflict'::text, null::timestamptz, null::jsonb;
        return;
    end if;

    insert into public.predictions (race_id, league_id, user_id, content)
    values (p_race, p_league, auth.uid(), p_content)
    on conflict (race_id, user_id) do nothing
    returning * into v_inserted;

    if found then
        return query
            select 'saved'::text, v_inserted.submitted_at, v_inserted.content;
        return;
    end if;

    -- Un'altra transazione ha inserito la riga tra la SELECT e l'INSERT.
    -- Rileggi con lock: il contenuto identico e' idempotente, altrimenti
    -- conflitto.
    select * into v_current
    from public.predictions p
    where p.race_id = p_race
      and p.user_id = auth.uid()
    for update;

    if found then
        if (v_current.content - 'submittedAtEpochMs') =
           (p_content - 'submittedAtEpochMs') then
            return query
                select 'unchanged'::text, v_current.submitted_at, v_current.content;
        else
            return query
                select 'conflict'::text, v_current.submitted_at, v_current.content;
        end if;
        return;
    end if;

    -- Condizione anomala (riga non visibile/rimossa): nessun overwrite.
    return query
        select 'conflict'::text, null::timestamptz, null::jsonb;
end;
$$;

comment on function public.save_prediction_if_revision(uuid, uuid, jsonb, timestamptz) is
    'Inserisce/aggiorna un pronostico solo se submitted_at coincide con la revisione attesa.';

revoke execute on function public.save_prediction_if_revision(uuid, uuid, jsonb, timestamptz)
    from public, anon;
grant execute on function public.save_prediction_if_revision(uuid, uuid, jsonb, timestamptz)
    to authenticated;
