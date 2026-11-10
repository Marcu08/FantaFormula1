-- =============================================================================
-- FantaFormula 1 — migrazione 0013_server_quotes.sql
-- Da eseguire nel SQL Editor del progetto Supabase (dopo 0009/0010).
--
-- OBIETTIVO: spostare sul SERVER la fonte di verita' di quote e soglie dei
-- pronostici a quota (DRIVER_DNF, DRIVER_POINTS_OVER_UNDER, HEAD_TO_HEAD).
-- Prima erano calcolate dal client (OverUnderQuotes + DriverSeasonStats) e il
-- trigger controllava solo l'intervallo: un client modificato poteva scegliere
-- la quota massima o una soglia favorevole, e telefoni diversi potevano
-- mostrare quote diverse per la stessa gara (cache locale fino a 6h).
--
-- COMPONENTI:
--   1) tabella public.race_quotes (una riga per SCELTA pubblicata);
--   2) RLS: SELECT ai membri della lega, nessuna scrittura diretta;
--   3) RPC public.publish_race_quotes(p_race, p_quotes) (solo admin,
--      immutabile dopo il primo pronostico della gara);
--   4) trigger public.check_prediction_content(): quota e soglia inviate
--      devono coincidere con race_quotes; se manca la riga il pronostico
--      viene rifiutato ("quote non ancora pubblicate per questa gara").
--   Le righe GIA' presenti in predictions non passano dal trigger (che
--   scatta solo su insert/update): restano valide come "vintage".
--
-- FORMATO DELLA CHIAVE (`key`) — nota di design:
--   il client congela una quota DIVERSA per ogni lato scelto: DNF si/no,
--   punti sopra/sotto e testa a testa favorito/sorpresa (il fattore e'
--   asimmetrico: 0.6x per il favorito, 2.5x per la sorpresa). Una sola
--   quota per pilota/coppia non puo' rappresentarle entrambe, quindi la
--   chiave identifica la SCELTA (la coppia del testa a testa resta in
--   forma canonica ordinata):
--     DRIVER_DNF               -> '<driverId>|true'  / '<driverId>|false'
--     DRIVER_POINTS_OVER_UNDER -> '<driverId>|true'  / '<driverId>|false'
--                                 (true = sopra, false = sotto; la soglia
--                                  e' la colonna `threshold`, uguale ai
--                                  due lati)
--     HEAD_TO_HEAD             -> '<minId>|<maxId>|<pickedId>'
--   Esempio: 'PER|true', 'VER|false', 'LEC|VER|LEC'.
--
-- Idempotente: create table if not exists, drop/add dei vincoli, create or
-- replace delle funzioni (come 0001-0012).
-- Test predisposti: supabase/tests/0013_server_quotes_tests.sql
-- =============================================================================

-- -----------------------------------------------------------------------------
-- race_quotes: una riga per scelta pubblicata. `quota` e `threshold` sono
-- sempre numeriche (niente NULL su quota); la chiave e' la scelta esatta.
-- Il FK composito (race_id, league_id) riusa races_id_league_unique di 0002.
-- -----------------------------------------------------------------------------
create table if not exists public.race_quotes (
    race_id    uuid not null,
    league_id  uuid not null,
    type       text not null,
    key        text not null,
    threshold  numeric,
    quota      numeric not null,
    created_at timestamptz not null default now(),
    constraint race_quotes_pkey primary key (race_id, type, key)
);

comment on table public.race_quotes is
    'Quote e soglie pubblicate dal server: fonte di verita'' per i pronostici a quota (0013).';
comment on column public.race_quotes.key is
    'Scelta esatta: <driver>|true|false per DNF/punti, <min>|<max>|<picked> per il testa a testa.';

alter table public.race_quotes drop constraint if exists race_quotes_race_league_fk;
alter table public.race_quotes
    add constraint race_quotes_race_league_fk
        foreign key (race_id, league_id) references public.races (id, league_id)
        on delete cascade
        not valid;
alter table public.race_quotes validate constraint race_quotes_race_league_fk;

alter table public.race_quotes drop constraint if exists race_quotes_type_check;
alter table public.race_quotes
    add constraint race_quotes_type_check check (
        type in ('DRIVER_DNF', 'DRIVER_POINTS_OVER_UNDER', 'HEAD_TO_HEAD')
    );

alter table public.race_quotes drop constraint if exists race_quotes_key_check;
alter table public.race_quotes
    add constraint race_quotes_key_check check (
        case type
            when 'HEAD_TO_HEAD' then
                key ~ '^[A-Za-z0-9_.-]+\|[A-Za-z0-9_.-]+\|[A-Za-z0-9_.-]+$'
            else
                key ~ '^[A-Za-z0-9_.-]+\|(true|false)$'
        end
    );

alter table public.race_quotes drop constraint if exists race_quotes_quota_check;
alter table public.race_quotes
    add constraint race_quotes_quota_check check (
        case
            when type = 'DRIVER_DNF' then quota between 0.5 and 8.0
            else quota between 0.5 and 3.0
        end
    );

-- La soglia esiste SOLO per i punti over/under: multiplo di 0.5 in [0.5; 25]
-- (stesse regole del trigger 0010). DNF e testa a testa non hanno soglia.
alter table public.race_quotes drop constraint if exists race_quotes_threshold_check;
alter table public.race_quotes
    add constraint race_quotes_threshold_check check (
        case
            when type = 'DRIVER_POINTS_OVER_UNDER' then
                threshold is not null
                and threshold between 0.5 and 25
                and (threshold * 2) = floor(threshold * 2)
            else threshold is null
        end
    );

create index if not exists idx_race_quotes_league
    on public.race_quotes (league_id);

-- -----------------------------------------------------------------------------
-- RLS: i membri della lega leggono le quote; nessuna scrittura diretta.
-- L'unica via di scrittura e' la RPC SECURITY DEFINER qui sotto.
-- -----------------------------------------------------------------------------
alter table public.race_quotes enable row level security;

drop policy if exists race_quotes_select on public.race_quotes;
create policy race_quotes_select on public.race_quotes
    for select to authenticated
    using (public.is_league_member(league_id));

revoke insert, update, delete on public.race_quotes from public, anon, authenticated;
grant select on public.race_quotes to authenticated;

-- -----------------------------------------------------------------------------
-- publish_race_quotes: pubblica (o ripubblica identico) l'INSIEME delle quote
-- della gara. Regole:
--   - solo admin della lega della gara;
--   - p_quotes deve essere un array di oggetti {type,key,quota,threshold};
--   - quota e soglia validate con le stesse regole del trigger (0010);
--   - IMMUTABILITA': se esiste gia' almeno un pronostico per la gara, il
--     nuovo insieme deve essere IDENTICO a quello pubblicato (ripubblicare
--     lo stesso set e' un no-op, qualunque modifica viene rifiutata).
-- -----------------------------------------------------------------------------
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

        if v_existing <> v_normalized then
            raise exception 'Non puoi modificare le quote dopo che sono stati inviati pronostici per questa gara';
        end if;
        return;
    end if;

    -- Nessun pronostico: sostituzione atomica dell'insieme pubblicato.
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
    'Pubblica le quote della gara (solo admin); dopo il primo pronostico l''insieme e'' immutabile.';

-- -----------------------------------------------------------------------------
-- Trigger check_prediction_content (base 0010) + controllo server delle quote.
-- Per DRIVER_DNF / DRIVER_POINTS_OVER_UNDER / HEAD_TO_HEAD la quota inviata
-- (e la soglia per i punti) devono coincidere ESATTAMENTE con la riga di
-- race_quotes della scelta; se la riga non esiste il pronostico e' rifiutato.
-- -----------------------------------------------------------------------------
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

        -- Quota congelata: numero finito nel range dipendente dal TIPO.
        -- DRIVER_DNF = [0.5; 8.0] (0010); altri tipi = [0.5; 3.0]. NULL resta
        -- ammesso qui per i tipi NON a quota: per i tipi a quota il ramo
        -- dedicato la richiede obbligatoria alle nuove scritture.
        v_quota := case
            when jsonb_typeof(v_entry -> 'quota') = 'number'
                then (v_entry ->> 'quota')::numeric
        end;
        if v_quota is not null then
            if v_type = 'DRIVER_DNF' then
                if v_quota < 0.5 or v_quota > 8.0 then
                    raise exception 'PRONOSTICO NON VALIDO: quota fuori range per il DNF (0.5-8.0)';
                end if;
            elsif v_quota < 0.5 or v_quota > 3.0 then
                raise exception 'PRONOSTICO NON VALIDO: quota fuori range (0.5-3.0)';
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
            -- 0009/0010: quota congelata obbligatoria, banda DNF dedicata.
            if v_quota is null then
                raise exception 'PRONOSTICO NON VALIDO: quota obbligatoria per il DNF (0.5-8.0)';
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
            -- 0009: quota congelata obbligatoria (fasce reali al momento del save).
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

        -- 0013: la quota (e la soglia) devono essere quelle PUBBLICATE dal
        -- server per QUESTA scelta. Il lookup usa la stessa chiave definita
        -- in testa alla migrazione; se la riga manca il tipo e' rifiutato.
        if v_type in ('DRIVER_DNF', 'DRIVER_POINTS_OVER_UNDER', 'HEAD_TO_HEAD') then
            declare
                v_key text;
                v_pub_quota numeric;
                v_pub_threshold numeric;
                v_first text;
                v_second text;
                v_sent_quota numeric;
            begin
                v_sent_quota := case
                    when jsonb_typeof(v_entry -> 'quota') = 'number'
                        then (v_entry ->> 'quota')::numeric
                end;

                if v_type = 'HEAD_TO_HEAD' then
                    v_first := v_entry -> 'driverIds' ->> 0;
                    v_second := v_entry -> 'driverIds' ->> 1;
                    v_key := least(v_first, v_second) || '|' ||
                             greatest(v_first, v_second) || '|' || v_first;
                else
                    v_key := (v_entry -> 'driverIds' ->> 0) || '|' ||
                             case
                                 when v_type = 'DRIVER_DNF' then v_entry ->> 'booleanValue'
                                 else v_entry ->> 'over'
                             end;
                end if;

                select q.quota, q.threshold
                  into v_pub_quota, v_pub_threshold
                  from public.race_quotes q
                 where q.race_id = new.race_id
                   and q.type = v_type
                   and q.key = v_key;

                if not found then
                    raise exception 'PRONOSTICO NON VALIDO: quote non ancora pubblicate per questa gara';
                end if;
                if v_sent_quota is distinct from v_pub_quota then
                    raise exception 'PRONOSTICO NON VALIDO: quota non valida per questo pronostico';
                end if;
                if v_type = 'DRIVER_POINTS_OVER_UNDER' then
                    v_num := (v_entry ->> 'threshold')::numeric;
                    if v_num is distinct from v_pub_threshold then
                        raise exception 'PRONOSTICO NON VALIDO: soglia non valida per questo pronostico';
                    end if;
                end if;
            end;
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
revoke execute on function public.publish_race_quotes(uuid, jsonb) from public, anon;

grant execute on function public.known_prediction_types() to authenticated;
grant execute on function public.prediction_required_drivers(text) to authenticated;
grant execute on function public.publish_race_quotes(uuid, jsonb) to authenticated;
