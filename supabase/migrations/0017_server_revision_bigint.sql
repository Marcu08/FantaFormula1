-- =============================================================================
-- FantaFormula 1 - migrazione 0017_server_revision_bigint.sql
--
-- Sostituisce progressivamente `submitted_at` come revisione di concorrenza
-- con un contatore BIGINT assegnato esclusivamente dal server.
--
-- `submitted_at` resta un timestamp di audit e viene ancora aggiornato dal
-- trigger esistente `on_prediction_submit`.
--
-- La vecchia RPC a 4 argomenti con `p_expected_submitted_at timestamptz`
-- resta disponibile come percorso di compatibilita' per i client precedenti.
-- La nuova RPC ha lo stesso nome ma il quarto argomento e' `bigint` e non ha
-- default: il client nuovo invia sempre `p_expected_revision` (anche null).
-- In questo modo PostgREST non deve scegliere tra due overload quando il
-- client vecchio omette il parametro.
--
-- Idempotente. NON applicare al progetto remoto durante lo sviluppo.
-- Test predisposti: supabase/tests/0017_server_revision_bigint_tests.sql
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) Sequenza e colonna revision
-- -----------------------------------------------------------------------------
create sequence if not exists public.predictions_revision_seq;

alter table public.predictions
    add column if not exists revision bigint;

-- La revision e' assegnata solo dal trigger: nessun default lato client e
-- nessun consumo extra della sequenza prima del trigger.
alter table public.predictions
    alter column revision drop default;

alter sequence public.predictions_revision_seq
    owned by public.predictions.revision;

-- Backfill delle righe esistenti. I trigger che toccano revision/submitted_at
-- vengono disabilitati solo per questa UPDATE:
--  - `on_prediction_submit` altrimenti riscriverebbe l'audit submitted_at;
--  - `on_prediction_revision` (solo su riesecuzione) assegnerebbe un secondo
--    nextval e renderebbe il backfill meno prevedibile.
do $backfill$
declare
    v_max bigint;
    v_last bigint;
    v_called boolean;
begin
    if exists (
        select 1
        from pg_trigger
        where tgrelid = 'public.predictions'::regclass
          and tgname = 'on_prediction_submit'
          and not tgisinternal
    ) then
        execute 'alter table public.predictions disable trigger on_prediction_submit';
    end if;

    if exists (
        select 1
        from pg_trigger
        where tgrelid = 'public.predictions'::regclass
          and tgname = 'on_prediction_revision'
          and not tgisinternal
    ) then
        execute 'alter table public.predictions disable trigger on_prediction_revision';
    end if;

    -- Avanza la sequenza PRIMA del backfill: se una migrazione parziale aveva
    -- gia' assegnato revisioni, i nuovi nextval non devono collidere con il
    -- massimo esistente. Se la sequenza non e' ancora stata chiamata,
    -- is_called=false e last_value=1: va avanzata anche quando il massimo
    -- coincide con last_value.
    select coalesce(max(revision), 0) into v_max from public.predictions;
    select last_value, is_called into v_last, v_called
      from public.predictions_revision_seq;
    if v_max > v_last or (v_max = v_last and not v_called) then
        perform setval(
            'public.predictions_revision_seq',
            greatest(v_max, v_last),
            true
        );
    end if;

    update public.predictions
       set revision = nextval('public.predictions_revision_seq')
     where revision is null or revision <= 0;

    if exists (
        select 1
        from pg_trigger
        where tgrelid = 'public.predictions'::regclass
          and tgname = 'on_prediction_revision'
          and not tgisinternal
    ) then
        execute 'alter table public.predictions enable trigger on_prediction_revision';
    end if;

    if exists (
        select 1
        from pg_trigger
        where tgrelid = 'public.predictions'::regclass
          and tgname = 'on_prediction_submit'
          and not tgisinternal
    ) then
        execute 'alter table public.predictions enable trigger on_prediction_submit';
    end if;
end
$backfill$;

alter table public.predictions
    alter column revision set not null;

alter table public.predictions
    drop constraint if exists predictions_revision_positive;
alter table public.predictions
    add constraint predictions_revision_positive check (revision > 0);

comment on column public.predictions.revision is
    'Revisione di concorrenza assegnata dal server. submitted_at resta audit.';

-- La sequenza non e' accessibile direttamente dal client: la usa solo il
-- trigger SECURITY DEFINER.
revoke all on sequence public.predictions_revision_seq from public, anon, authenticated;

-- -----------------------------------------------------------------------------
-- 2) Trigger di assegnazione server-side
-- -----------------------------------------------------------------------------
create or replace function public.set_prediction_revision()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    -- Il valore inviato dal client, se presente, viene sempre sovrascritto.
    new.revision := nextval('public.predictions_revision_seq');
    return new;
end;
$$;

comment on function public.set_prediction_revision() is
    'Assegna una revision BIGINT server-side a ogni insert/update di predictions.';

revoke execute on function public.set_prediction_revision() from public, anon;
grant execute on function public.set_prediction_revision() to authenticated;

drop trigger if exists on_prediction_revision on public.predictions;
create trigger on_prediction_revision
    before insert or update on public.predictions
    for each row execute function public.set_prediction_revision();

-- -----------------------------------------------------------------------------
-- 3) Nuova RPC basata su revision BIGINT
--
-- Nota: `p_expected_revision` NON ha default. Il client nuovo lo invia sempre,
-- anche null. Il client vecchio usa l'overload con p_expected_submitted_at.
-- -----------------------------------------------------------------------------
-- Ricreata (non solo replace) per garantire che p_expected_revision non
-- abbia default: il client nuovo deve inviarlo sempre, anche null.
drop function if exists public.save_prediction_if_revision(
    uuid, uuid, jsonb, bigint
);

create or replace function public.save_prediction_if_revision(
    p_race uuid,
    p_league uuid,
    p_content jsonb,
    p_expected_revision bigint
)
returns table (
    status text,
    revision bigint,
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
            -- Retry o duplicato: nessuna nuova scrittura.
            return query
                select 'unchanged'::text,
                       v_current.revision,
                       v_current.submitted_at,
                       v_current.content;
            return;
        end if;

        if v_current.revision = p_expected_revision then
            update public.predictions p
            set content = p_content
            where p.race_id = p_race
              and p.user_id = auth.uid()
            returning * into v_updated;

            if not found then
                -- RLS/timing: il server non ha applicato l'update.
                select * into v_current
                from public.predictions p
                where p.race_id = p_race
                  and p.user_id = auth.uid()
                for update;

                return query
                    select 'conflict'::text,
                           v_current.revision,
                           v_current.submitted_at,
                           v_current.content;
                return;
            end if;

            return query
                select 'saved'::text,
                       v_updated.revision,
                       v_updated.submitted_at,
                       v_updated.content;
            return;
        end if;

        -- Revisione diversa e contenuto diverso: il server vince.
        return query
            select 'conflict'::text,
                   v_current.revision,
                   v_current.submitted_at,
                   v_current.content;
        return;
    end if;

    -- Nessuna riga corrente. Una revisione attesa non-null significa che il
    -- client credeva esistesse una versione precedente: non ricrearla.
    if p_expected_revision is not null then
        return query
            select 'conflict'::text, null::bigint, null::timestamptz, null::jsonb;
        return;
    end if;

    insert into public.predictions (race_id, league_id, user_id, content)
    values (p_race, p_league, auth.uid(), p_content)
    on conflict (race_id, user_id) do nothing
    returning * into v_inserted;

    if found then
        return query
            select 'saved'::text,
                   v_inserted.revision,
                   v_inserted.submitted_at,
                   v_inserted.content;
        return;
    end if;

    -- Un'altra transazione ha inserito la riga tra la SELECT e l'INSERT.
    select * into v_current
    from public.predictions p
    where p.race_id = p_race
      and p.user_id = auth.uid()
    for update;

    if found then
        if (v_current.content - 'submittedAtEpochMs') =
           (p_content - 'submittedAtEpochMs') then
            return query
                select 'unchanged'::text,
                       v_current.revision,
                       v_current.submitted_at,
                       v_current.content;
        else
            return query
                select 'conflict'::text,
                       v_current.revision,
                       v_current.submitted_at,
                       v_current.content;
        end if;
        return;
    end if;

    -- Condizione anomala (riga non visibile/rimossa): nessun overwrite.
    return query
        select 'conflict'::text, null::bigint, null::timestamptz, null::jsonb;
end;
$$;

comment on function public.save_prediction_if_revision(uuid, uuid, jsonb, bigint) is
    'Salvataggio atomico con revision BIGINT: saved/unchanged/conflict e revision corrente.';

revoke execute on function public.save_prediction_if_revision(uuid, uuid, jsonb, bigint)
    from public, anon;
grant execute on function public.save_prediction_if_revision(uuid, uuid, jsonb, bigint)
    to authenticated;

-- La vecchia RPC resta per i client precedenti; non viene rimossa in questa
-- fase. Deprecazione esplicita per il rollout.
comment on function public.save_prediction_if_revision(uuid, uuid, jsonb, timestamptz) is
    'DEPRECATED: compatibilita'' con client precedenti basati su submitted_at. Usare la variante con p_expected_revision bigint.';
