-- =============================================================================
-- FantaFormula 1 — migration 0007_over_under_thresholds.sql
-- Da eseguire nel SQL Editor del progetto Supabase.
--
-- Soglie ammesse per gli over/under (coerenti con la tabella quote in Rules):
--   - SAFETY_CAR_COUNT_OVER_UNDER: 0.5, 1.5, 2.5 (tetto realistico)
--   - PIT_STOPS_OVER_UNDER: 24.5, 29.5, 34.5, 39.5, 44.5, 49.5, 54.5, 59.5
--     (somma dei pit stop di TUTTI i piloti, non media)
-- DRIVER_POINTS_OVER_UNDER resta con soglia >= 0: la soglia e' la stima
-- punti/gara del pilota, applicata dal client (non e' un dato in DB).
--
-- Sostituisce il trigger con create or replace (0004/0006 restano intatti).
-- Idempotente.
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

        elsif v_type = 'DRIVER_POINTS_OVER_UNDER' then
            v_num := case
                when jsonb_typeof(v_entry -> 'threshold') = 'number'
                    then (v_entry ->> 'threshold')::numeric
            end;
            if v_num is null or v_num < 0 then
                raise exception 'PRONOSTICO NON VALIDO: soglia non valida';
            end if;
            if jsonb_typeof(v_entry -> 'over') <> 'boolean' then
                raise exception 'PRONOSTICO NON VALIDO: indica se sara'' sopra o sotto la soglia';
            end if;

        elsif v_type = 'SAFETY_CAR_COUNT_OVER_UNDER' then
            v_num := case
                when jsonb_typeof(v_entry -> 'threshold') = 'number'
                    then (v_entry ->> 'threshold')::numeric
            end;
            -- Soglie ammesse 0007: niente 330.5.
            if v_num is null or v_num not in (0.5, 1.5, 2.5) then
                raise exception 'PRONOSTICO NON VALIDO: soglia safety car ammessa solo 0.5, 1.5 o 2.5';
            end if;
            if jsonb_typeof(v_entry -> 'over') <> 'boolean' then
                raise exception 'PRONOSTICO NON VALIDO: indica se sara'' sopra o sotto la soglia';
            end if;

        elsif v_type = 'PIT_STOPS_OVER_UNDER' then
            v_num := case
                when jsonb_typeof(v_entry -> 'threshold') = 'number'
                    then (v_entry ->> 'threshold')::numeric
            end;
            -- Somma dei pit stop di tutti i piloti: soglie realistiche.
            if v_num is null
                or v_num not in (24.5, 29.5, 34.5, 39.5, 44.5, 49.5, 54.5, 59.5) then
                raise exception 'PRONOSTICO NON VALIDO: soglia pit stop ammessa solo da 24.5 a 59.5 (di 5 in 5)';
            end if;
            if jsonb_typeof(v_entry -> 'over') <> 'boolean' then
                raise exception 'PRONOSTICO NON VALIDO: indica se sara'' sopra o sotto la soglia';
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