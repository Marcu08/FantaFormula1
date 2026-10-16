-- =============================================================================
-- FantaFormula 1 — migration 0009_quota.sql
-- Da eseguire nel SQL Editor del progetto Supabase.
--
-- Quota CONGELATA nei pronostici (0009):
--   - PredictionEntry porta il nuovo campo opzionale `quota` (payout moltiplicatore,
--     range [0.5; 3.0]) per DRIVER_DNF, DRIVER_POINTS_OVER_UNDER e HEAD_TO_HEAD.
--   - La quota viene calcolata al momento della scrittura con le statistiche
--     reali della lega (media shrinkata, tasso di ritiro, fasce) e resta COSI'
--     dopo il salvataggio: il motore ricalcola a ogni lettura, quindi un payout
--     "live" altererebbe retroattivamente i punteggi storici. Vietato.
--   - DRIVER_POINTS_OVER_UNDER: soglia libera ma multiplo di 0.5 in [0.5; 25]
--     (mediana empirica dei punti/gara; "0.5" = "va a punti?"). Non piu'
--     l'uguaglianza con la stima pre-stagione di 0004/0007.
--   - DNF/punti/H2H: quota OBBLIGATORIA alle NUOVE scritture. Le righe gia'
--     salvate restano immuni (il trigger agisce solo su insert/update del
--     contenuto) e il client le punteggia senza quota, come prima.
--
-- Sostituisce il trigger con create or replace (0003-0008 intatti). Idempotente.
-- =============================================================================

create or replace function public.known_prediction_types()
returns text[]
language sql
immutable
as $$
    select array[
        'WINNER', 'PODIUM', 'POLE', 'FASTEST_LAP',
        'DRIVER_EXACT_POSITION', 'DRIVER_POSITION_RANGE', 'HEAD_TO_HEAD',
        'DRIVER_DNF', 'DRIVER_POINTS_OVER_UNDER', 'SPRINT_WINNER',
        'SAFETY_CAR_COUNT_OVER_UNDER', 'PIT_STOPS_OVER_UNDER'
    ];
$$;

create or replace function public.prediction_required_drivers(p_type text)
returns int
language sql
immutable
as $$
    select case p_type
        when 'HEAD_TO_HEAD' then 2
        when 'PODIUM' then 3
        when 'SAFETY_CAR_COUNT_OVER_UNDER' then 0
        when 'PIT_STOPS_OVER_UNDER' then 0
        else 1
    end;
$$;

create or replace function public.check_prediction_content()
returns trigger
language plpgsql
security invoker
set search_path = public
as $$
declare
    v_entry jsonb;
    v_type text;
    v_required int;
    v_drivers jsonb;
    v_enabled jsonb;
    v_sprint boolean;
    v_num numeric;
    v_min numeric;
    v_max numeric;
    v_quota numeric;
    v_entries jsonb;
begin
    if jsonb_typeof(new.content) <> 'object' then
        raise exception 'PRONOSTICO NON VALIDO: il contenuto deve essere un oggetto JSON';
    end if;
    v_entries := new.content -> 'entries';
    if coalesce(jsonb_typeof(v_entries), 'null') <> 'array' then
        raise exception 'PRONOSTICO NON VALIDO: "entries" deve essere un array';
    end if;
    if jsonb_array_length(v_entries) > 32 then
        raise exception 'PRONOSTICO NON VALIDO: troppi tipi di pronostico';
    end if;

    select r.is_sprint into v_sprint
    from public.races r
    where r.id = new.race_id;
    if not found then
        raise exception 'PRONOSTICO NON VALIDO: gara non trovata';
    end if;

    select l.rules -> 'enabledTypes' into v_enabled
    from public.leagues l
    where l.id = new.league_id;

    if (select count(distinct e ->> 'type') from jsonb_array_elements(v_entries) e)
        <> jsonb_array_length(v_entries) then
        raise exception 'PRONOSTICO NON VALIDO: solo un pronostico per tipo';
    end if;

    for v_entry in select * from jsonb_array_elements(v_entries) loop
        if jsonb_typeof(v_entry) <> 'object' then
            raise exception 'PRONOSTICO NON VALIDO: ogni voce deve essere un oggetto';
        end if;

        v_type := v_entry ->> 'type';
        if v_type is null or not (v_type = any (public.known_prediction_types())) then
            raise exception 'PRONOSTICO NON VALIDO: tipo di pronostico sconosciuto';
        end if;

        if jsonb_typeof(v_enabled) = 'array'
            and not (v_type in (select value from jsonb_array_elements_text(v_enabled))) then
            raise exception 'PRONOSTICO NON VALIDO: tipo non attivo per questa lega';
        end if;

        v_required := public.prediction_required_drivers(v_type);

        if v_type = 'SPRINT_WINNER' and not coalesce(v_sprint, false) then
            raise exception 'PRONOSTICO NON VALIDO: la gara non ha lo sprint';
        end if;

        v_drivers := v_entry -> 'driverIds';
        if v_required = 0 then
            if v_drivers is not null and jsonb_typeof(v_drivers) <> 'array' then
                raise exception 'PRONOSTICO NON VALIDO: driverIds deve essere un array';
            end if;
            if v_drivers is not null and jsonb_typeof(v_drivers) = 'array' then
                if exists (
                    select 1
                    from jsonb_array_elements(v_drivers) d
                    where jsonb_typeof(d) <> 'string'
                       or btrim(d #>> '{}') = ''
                ) then
                    raise exception 'PRONOSTICO NON VALIDO: pilota non valido';
                end if;
                if (select count(distinct d #>> '{}') from jsonb_array_elements(v_drivers) d)
                    <> jsonb_array_length(v_drivers) then
                    raise exception 'PRONOSTICO NON VALIDO: i piloti devono essere diversi tra loro';
                end if;
            end if;
        else
            if coalesce(jsonb_typeof(v_drivers), 'null') <> 'array'
                or jsonb_array_length(v_drivers) <> v_required then
                raise exception 'PRONOSTICO NON VALIDO: il tipo richiede il numero corretto di piloti';
            end if;
            if exists (
                select 1
                from jsonb_array_elements(v_drivers) d
                where jsonb_typeof(d) <> 'string'
                   or btrim(d #>> '{}') = ''
            ) then
                raise exception 'PRONOSTICO NON VALIDO: pilota non valido';
            end if;
            if (select count(distinct d #>> '{}') from jsonb_array_elements(v_drivers) d)
                <> jsonb_array_length(v_drivers) then
                raise exception 'PRONOSTICO NON VALIDO: i piloti devono essere diversi tra loro';
            end if;
        end if;

        -- La quota congelata e' ammessa come numero finito in [0.5; 3.0] su
        -- QUALSIASI tipo (per i non-a-quote resta null / assente). NULL e'
        -- accettato qui per compatibilita' con i client vecchi: il controllo
        -- "obbligatorio" vale solo per i tipi a quote nel ramo dedicato.
        v_quota := case
            when jsonb_typeof(v_entry -> 'quota') = 'number'
                then (v_entry ->> 'quota')::numeric
        end;
        if v_quota is not null and (v_quota < 0.5 or v_quota > 3.0) then
            raise exception 'PRONOSTICO NON VALIDO: quota fuori range (0.5-3.0)';
        end if;

        if v_type = 'DRIVER_EXACT_POSITION' then
            v_num := case
                when jsonb_typeof(v_entry -> 'position') = 'number'
                    then (v_entry ->> 'position')::numeric
            end;
            if v_num is null
                or (v_entry ->> 'position') !~ '^[0-9]{1,3}$'
                or v_num < 1 or v_num > 22 then
                raise exception 'PRONOSTICO NON VALIDO: posizione tra 1 e 22';
            end if;

        elsif v_type = 'DRIVER_POSITION_RANGE' then
            v_min := case
                when jsonb_typeof(v_entry -> 'rangeMin') = 'number'
                    then (v_entry ->> 'rangeMin')::numeric
            end;
            v_max := case
                when jsonb_typeof(v_entry -> 'rangeMax') = 'number'
                    then (v_entry ->> 'rangeMax')::numeric
            end;
            if v_min is null
                or (v_entry ->> 'rangeMin') !~ '^[0-9]{1,3}$'
                or v_min < 1 or v_min > 22
                or v_max is null
                or (v_entry ->> 'rangeMax') !~ '^[0-9]{1,3}$'
                or v_max < 1 or v_max > 22
                or not ((v_min, v_max) in ((1, 5), (6, 10), (11, 15), (16, 22))) then
                raise exception 'PRONOSTICO NON VALIDO: usa una delle fasce predefinite (1-5, 6-10, 11-15, 16-22)';
            end if;

        elsif v_type = 'DRIVER_DNF' then
            if jsonb_typeof(v_entry -> 'booleanValue') <> 'boolean' then
                raise exception 'PRONOSTICO NON VALIDO: indica se il pilota ritira';
            end if;
            -- 0009: quota congelata obbligatoria.
            if v_quota is null then
                raise exception 'PRONOSTICO NON VALIDO: quota obbligatoria per il DNF (0.5-3.0)';
            end if;

        elsif v_type = 'DRIVER_POINTS_OVER_UNDER' then
            v_num := case
                when jsonb_typeof(v_entry -> 'threshold') = 'number'
                    then (v_entry ->> 'threshold')::numeric
            end;
            if v_num is null
                or v_num < 0.5 or v_num > 25
                or (v_num * 2) <> floor(v_num * 2) then
                raise exception 'PRONOSTICO NON VALIDO: soglia multiplo di 0.5 tra 0.5 e 25';
            end if;
            if jsonb_typeof(v_entry -> 'over') <> 'boolean' then
                raise exception 'PRONOSTICO NON VALIDO: indica se sara'' sopra o sotto la soglia';
            end if;
            -- 0009: quota congelata obbligatoria.
            if v_quota is null then
                raise exception 'PRONOSTICO NON VALIDO: quota obbligatoria per i punti (0.5-3.0)';
            end if;

        elsif v_type = 'HEAD_TO_HEAD' then
            -- 0009: quota congelata obbligatoria (fasce REALI al momento del save).
            if v_quota is null then
                raise exception 'PRONOSTICO NON VALIDO: quota obbligatoria per il testa a testa (0.5-3.0)';
            end if;

        elsif v_type = 'SAFETY_CAR_COUNT_OVER_UNDER' then
            -- 0008: numero esatto di SC+VSC in rangeMin/rangeMax.
            v_min := case
                when jsonb_typeof(v_entry -> 'rangeMin') = 'number'
                    then (v_entry ->> 'rangeMin')::numeric
            end;
            v_max := case
                when jsonb_typeof(v_entry -> 'rangeMax') = 'number'
                    then (v_entry ->> 'rangeMax')::numeric
            end;
            if v_min is null or v_max is null
                or v_min < 0 or v_min > 6
                or v_max <> (case when v_min = 6 then 99 else v_min end) then
                raise exception 'PRONOSTICO NON VALIDO: scegli una delle opzioni 0, 1, 2, 3, 4, 5, 6+';
            end if;

        elsif v_type = 'PIT_STOPS_OVER_UNDER' then
            -- 0008: fascia obbligatoria sui pit stop totali.
            v_min := case
                when jsonb_typeof(v_entry -> 'rangeMin') = 'number'
                    then (v_entry ->> 'rangeMin')::numeric
            end;
            v_max := case
                when jsonb_typeof(v_entry -> 'rangeMax') = 'number'
                    then (v_entry ->> 'rangeMax')::numeric
            end;
            if v_min is null or v_max is null
                or not ((v_min, v_max) in ((0, 14), (15, 25), (26, 35), (36, 45), (46, 55), (56, 65), (66, 99))) then
                raise exception 'PRONOSTICO NON VALIDO: scegli una delle fasce 0-14, 15-25, 26-35, 36-45, 46-55, 56-65, 66+';
            end if;
        end if;
    end loop;

    return new;
end;
$$;

drop trigger if exists on_prediction_content on public.predictions;
create trigger on_prediction_content
    before insert or update of content on public.predictions
    for each row execute function public.check_prediction_content();

revoke execute on function public.known_prediction_types() from public, anon;
revoke execute on function public.prediction_required_drivers(text) from public, anon;
revoke execute on function public.check_prediction_content() from public, anon;
grant execute on function public.known_prediction_types() to authenticated;
grant execute on function public.prediction_required_drivers(text) to authenticated;