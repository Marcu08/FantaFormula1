-- =============================================================================
-- FantaFormula 1 — migration 0003_security.sql
-- Da eseguire nel SQL Editor del progetto Supabase.
--
-- Correzioni di sicurezza e robustezza trovate durante l'audit (ottobre 2026):
--   1. league_rule_bool: non lancia piu' eccezioni su rules jsonb malformate
--      (prima un valore non-booleano rompeva la lettura dei pronostici di
--      tutta la lega).
--   2. profiles_select: i profili sono leggibili solo dai co-membri di una
--      lega condivisa e da se' stessi. Prima ogni utente autenticato poteva
--      leggere ogni profilo, e il nome di default deriva dal prefisso email.
--   3. save_race_result: valida che p_result sia un oggetto jsonb.
--   4. predictions.content: trigger che verifica la forma minima del jsonb
--      (oggetto con "entries" array), coerente con RacePrediction in Kotlin.
--
-- Idempotente: puo' essere eseguita piu' volte.
--
-- Verifica manuale (SQL Editor), caso per caso:
--   a) league_rule_bool robusto:
--        update leagues set rules = jsonb_set(rules, '{revealOthersAfterDeadline}', '"si"'::jsonb)
--        where id = '<lega>';  -- prima: query predictions andava in errore
--        select league_rule_bool('<lega>', 'revealOthersAfterDeadline', true);  -- -> true (default)
--        -- ripristinare il valore corretto dopo il test
--   b) profili non co-membri non leggibili:
--        -- con utente A membro della lega L1 e utente B non in L1:
--        select * from profiles where id = '<B>';  -- come A: 0 righe
--        select * from profiles where id = '<B>';  -- come B: 1 riga (se stesso)
--   c) contenuto pronostico malformato rifiutato:
--        insert into predictions (race_id, league_id, user_id, content)
--        values ('<race>', '<lega>', auth.uid(), '{"x":1}'::jsonb);  -- -> errore trigger
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. league_rule_bool robusto: restituisce il default se il valore non e'
--    un booleano JSON (o se la lega non esiste), invece di sollevare errore.
-- -----------------------------------------------------------------------------
create or replace function public.league_rule_bool(
    p_league uuid,
    p_key text,
    p_default boolean
)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select coalesce(
        (select case
                    when jsonb_typeof(l.rules -> p_key) = 'boolean'
                        then (l.rules ->> p_key)::boolean
                    else null
                end
         from public.leagues l
         where l.id = p_league),
        p_default
    );
$$;

-- -----------------------------------------------------------------------------
-- 2. Profili visibili solo a se' stessi e ai co-membri di qualche lega.
--    Il nome pubblico serve per membri, classifica e pronostici altrui:
--    in tutti i casi si tratta di giocatori della stessa lega.
-- -----------------------------------------------------------------------------
drop policy if exists profiles_select on public.profiles;
create policy profiles_select on public.profiles
    for select to authenticated
    using (
        id = auth.uid()
        or exists (
            select 1
            from public.league_members m
            where m.user_id = profiles.id
              and public.is_league_member(m.league_id)
        )
    );

-- -----------------------------------------------------------------------------
-- 3. save_race_result: p_result deve essere un oggetto jsonb non vuoto.
--    La struttura dettagliata resta responsabilita' del client (RaceResultData)
--    e della validazione chiamante; qui si blocca solo cio' che e'
--    chiaramente non conforme.
-- -----------------------------------------------------------------------------
create or replace function public.save_race_result(
    p_race uuid,
    p_result jsonb
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_league uuid;
begin
    if p_result is null or jsonb_typeof(p_result) <> 'object' then
        raise exception 'Il risultato deve essere un oggetto JSON';
    end if;

    select league_id into v_league
    from public.races
    where id = p_race;

    if v_league is null then
        raise exception 'Gara non trovata';
    end if;

    if not public.is_league_admin(v_league) then
        raise exception 'Solo un amministratore puo salvare i risultati';
    end if;

    insert into public.race_results (race_id, league_id, result, entered_by, entered_at)
    values (p_race, v_league, p_result, auth.uid(), now())
    on conflict (race_id) do update
    set result = excluded.result,
        entered_by = auth.uid(),
        entered_at = now();

    update public.races
    set status = 'completed'
    where id = p_race;
end;
$$;

-- -----------------------------------------------------------------------------
-- 4. Forma minima del contenuto dei pronostici: oggetto con "entries" array.
--    Non replica tutta la validazione delle regole (responsabilita' client +
--    validazione a destinazione), ma impedisce jsonb arbitrari che
--    degraderebbero silenziosamente la classifica.
-- -----------------------------------------------------------------------------
create or replace function public.check_prediction_content()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    if jsonb_typeof(new.content) <> 'object' then
        raise exception 'Il contenuto del pronostico deve essere un oggetto JSON';
    end if;
    if coalesce(jsonb_typeof(new.content -> 'entries'), 'null') <> 'array' then
        raise exception 'Il contenuto del pronostico deve contenere "entries" come array';
    end if;
    return new;
end;
$$;

drop trigger if exists on_prediction_content on public.predictions;
create trigger on_prediction_content
    before insert or update of content on public.predictions
    for each row execute function public.check_prediction_content();

-- -----------------------------------------------------------------------------
-- Grant: le funzioni aggiornate restano accessibili agli autenticati
-- (il revoke generico di 0002 e' gia' in vigore; qui si riafferma il grant).
-- -----------------------------------------------------------------------------
grant execute on function public.league_rule_bool(uuid, text, boolean) to authenticated;
grant execute on function public.save_race_result(uuid, jsonb) to authenticated;