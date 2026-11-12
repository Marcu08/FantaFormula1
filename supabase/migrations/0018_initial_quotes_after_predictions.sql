-- =============================================================================
-- FantaFormula 1 - migrazione 0018_initial_quotes_after_predictions.sql
--
-- Corregge un deadlock operativo della 0013:
--
--   se una gara ha gia' pronostici "vintage" (salvati prima che esistessero
--   le quote server) ma `race_quotes` e' ancora vuota, la vecchia
--   `publish_race_quotes` rifiutava la prima pubblicazione perche'
--   l'insieme esistente (vuoto) era diverso da quello nuovo. Il client,
--   inoltre, non tentava la pubblicazione se `hasPredictions` era true.
--
-- Nuova regola:
--   - primo insieme (race_quotes vuota): pubblicabile anche con pronostici
--     esistenti; le righe gia' salvate restano vintage e non vengono
--     rilette dal trigger;
--   - insieme gia' pubblicato non vuoto: immutabile come prima, con
--     possibilità di ripubblicare solo lo stesso insieme.
--
-- La funzione resta SECURITY DEFINER, con controllo admin e stessi grant.
-- Idempotente. NON applicare al progetto remoto durante lo sviluppo.
-- =============================================================================

create or replace function public.publish_race_quotes(
    p_race uuid,
    p_quotes jsonb
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_league uuid;
    v_entry jsonb;
    v_type text;
    v_key text;
    v_parts text[];
    v_threshold numeric;
    v_quota numeric;
    v_seen text[] := array[]::text[];
    v_normalized jsonb := '[]'::jsonb;
    v_existing jsonb := '[]'::jsonb;
    v_has_predictions boolean;
begin
    if p_quotes is null or jsonb_typeof(p_quotes) <> 'array' then
        raise exception 'QUOTE NON VALIDE: atteso un array di quote';
    end if;

    select r.league_id into v_league
    from public.races r
    where r.id = p_race;
    if v_league is null then
        raise exception 'QUOTE NON VALIDE: gara non trovata';
    end if;
    if not public.is_league_admin(v_league) then
        raise exception 'Solo un amministratore puo pubblicare le quote';
    end if;

    -- Validazione + normalizzazione (ordine stabile per il confronto).
    for v_entry in
        select e
        from jsonb_array_elements(p_quotes) e
        order by e ->> 'type', e ->> 'key'
    loop
        if jsonb_typeof(v_entry) <> 'object' then
            raise exception 'QUOTE NON VALIDE: ogni quota deve essere un oggetto';
        end if;

        v_type := v_entry ->> 'type';
        v_key := btrim(coalesce(v_entry ->> 'key', ''));
        if v_type is null
            or v_type not in ('DRIVER_DNF', 'DRIVER_POINTS_OVER_UNDER', 'HEAD_TO_HEAD') then
            raise exception 'QUOTE NON VALIDE: tipo di quota sconosciuto';
        end if;
        if v_key = '' then
            raise exception 'QUOTE NON VALIDE: chiave mancante';
        end if;

        v_quota := case
            when jsonb_typeof(v_entry -> 'quota') = 'number'
                then (v_entry ->> 'quota')::numeric
        end;
        if v_quota is null then
            raise exception 'QUOTE NON VALIDE: quota obbligatoria';
        end if;

        -- Intervalli: stessi del trigger 0010 (DNF dedicato, altri generico).
        if v_type = 'DRIVER_DNF' then
            if v_quota < 0.5 or v_quota > 8.0 then
                raise exception 'QUOTE NON VALIDE: quota fuori range per il DNF (0.5-8.0)';
            end if;
        elsif v_quota < 0.5 or v_quota > 3.0 then
            raise exception 'QUOTE NON VALIDE: quota fuori range (0.5-3.0)';
        end if;

        if v_type = 'HEAD_TO_HEAD' then
            v_parts := string_to_array(v_key, '|');
            if array_length(v_parts, 1) <> 3
                or btrim(v_parts[1]) = ''
                or btrim(v_parts[2]) = ''
                or btrim(v_parts[3]) = '' then
                raise exception 'QUOTE NON VALIDE: chiave testa a testa non valida';
            end if;
            -- Coppia canonica ordinata + pilota scelto dentro la coppia.
            if v_parts[1] >= v_parts[2] then
                raise exception 'QUOTE NON VALIDE: la coppia deve essere in ordine canonico';
            end if;
            if v_parts[3] not in (v_parts[1], v_parts[2]) then
                raise exception 'QUOTE NON VALIDE: il pilota scelto non e'' nella coppia';
            end if;
            if v_entry ? 'threshold' and jsonb_typeof(v_entry -> 'threshold') <> 'null' then
                raise exception 'QUOTE NON VALIDE: il testa a testa non ha soglia';
            end if;
            v_threshold := null;
        else
            v_parts := string_to_array(v_key, '|');
            if array_length(v_parts, 1) <> 2
                or btrim(v_parts[1]) = ''
                or v_parts[2] not in ('true', 'false') then
                raise exception 'QUOTE NON VALIDE: chiave non valida';
            end if;
            if v_type = 'DRIVER_POINTS_OVER_UNDER' then
                v_threshold := case
                    when jsonb_typeof(v_entry -> 'threshold') = 'number'
                        then (v_entry ->> 'threshold')::numeric
                end;
                if v_threshold is null
                    or v_threshold < 0.5 or v_threshold > 25
                    or (v_threshold * 2) <> floor(v_threshold * 2) then
                    raise exception 'QUOTE NON VALIDE: soglia multiplo di 0.5 tra 0.5 e 25';
                end if;
            else
                if v_entry ? 'threshold' and jsonb_typeof(v_entry -> 'threshold') <> 'null' then
                    raise exception 'QUOTE NON VALIDE: il DNF non ha soglia';
                end if;
                v_threshold := null;
            end if;
        end if;

        if (v_type || '|' || v_key) = any (v_seen) then
            raise exception 'QUOTE NON VALIDE: quota duplicata per %', v_key;
        end if;
        v_seen := v_seen || (v_type || '|' || v_key);

        -- round(...,6): confronto stabile tra ripubblicazioni (3 vs 3.0).
        v_normalized := v_normalized || jsonb_build_array(
            jsonb_build_object(
                'type', v_type,
                'key', v_key,
                'threshold', case when v_threshold is null then null else round(v_threshold, 6) end,
                'quota', round(v_quota, 6)
            )
        );
    end loop;

    select exists (
        select 1 from public.predictions p where p.race_id = p_race
    ) into v_has_predictions;

    if v_has_predictions then
        select coalesce(
                   jsonb_agg(
                       jsonb_build_object(
                           'type', q.type,
                           'key', q.key,
                           'threshold', case when q.threshold is null then null else round(q.threshold, 6) end,
                           'quota', round(q.quota, 6)
                       )
                       order by q.type, q.key
                   ),
                   '[]'::jsonb
               )
          into v_existing
          from public.race_quotes q
         where q.race_id = p_race;

        -- 0018: l'insieme vuoto non e' "immutabile". Permette la prima
        -- pubblicazione anche se esistono pronostici vintage. Solo un insieme
        -- gia' non vuoto resta bloccato.
        if v_existing <> '[]'::jsonb then
            if v_existing <> v_normalized then
                raise exception 'Non puoi modificare le quote dopo che sono stati inviati pronostici per questa gara';
            end if;
            return;
        end if;
    end if;

    -- Nessun insieme pubblicato (o nessun pronostico): sostituzione atomica.
    delete from public.race_quotes q where q.race_id = p_race;

    for v_entry in
        select e
        from jsonb_array_elements(p_quotes) e
        order by e ->> 'type', e ->> 'key'
    loop
        insert into public.race_quotes (race_id, league_id, type, key, threshold, quota)
        values (
            p_race,
            v_league,
            v_entry ->> 'type',
            btrim(v_entry ->> 'key'),
            case
                when jsonb_typeof(v_entry -> 'threshold') = 'number'
                    then (v_entry ->> 'threshold')::numeric
            end,
            (v_entry ->> 'quota')::numeric
        );
    end loop;
end;
$$;

comment on function public.publish_race_quotes(uuid, jsonb) is
    'Pubblica le quote della gara (solo admin); ammette la prima pubblicazione anche con pronostici vintage, poi l''insieme non vuoto e'' immutabile.';

revoke execute on function public.publish_race_quotes(uuid, jsonb) from public, anon;
grant execute on function public.publish_race_quotes(uuid, jsonb) to authenticated;
