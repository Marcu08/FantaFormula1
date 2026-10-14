-- =============================================================================
-- FantaFormula 1 — migration 0004_prediction_validation.sql
-- Da eseguire nel SQL Editor del progetto Supabase.
--
-- Validazione server-side del contenuto dei pronostici, coerente con la
-- classe Kotlin com.fantaformula1.app.core.domain.PredictionValidation
-- (sorgente di verita' delle regole di dominio).
--
-- DIFFERENZE COLMATE rispetto a 0003 (che verificava solo la forma):
--   1. tipo conosciuto (enum PredictionType, nome usato come chiave jsonb)
--   2. un solo pronostico per tipo dentro la stessa previsione
--   3. tipo attivo nelle regole della lega (rules.enabledTypes, se array)
--   4. numero corretto di piloti per tipo (1 / 2 / 3 / 0)
--   5. driverIds: array di stringhe non vuote
--   6. driverIds senza duplicati
--   7. SPRINT_WINNER solo su gare con sprint (colonna races.is_sprint)
--   8. DRIVER_EXACT_POSITION: position intero tra 1 e 22
--   9. DRIVER_POSITION_RANGE: rangeMin/rangeMax interi 1..22 con min <= max
--  10. DRIVER_DNF: booleanValue presente (booleano)
--  11. over/under (punti pilota, safety car, pit stop): threshold numerico
--      >= 0 (jsonb non ammette NaN/Inf) e over booleano presente
--
-- NON IMPPOSTE (regole non esistenti nel dominio o non verificabili lato DB):
--   - appartenenza dei piloti alla gara: il catalogo piloti vive solo nel
--     client (DriversCatalog), non ci sono tabelle piloti nel database.
--   - submittedAtEpochMs vs orologio server: lo skew del dispositivo e'
--     legittimo; l'orario autorevole resta la colonna submitted_at (trigger
--     on_prediction_submit di 0002).
--   - content.raceId: la verita' e' la colonna race_id (decisione B4); il
--     valore nel JSON e' riscritto dai repository in lettura.
--   - enabledTypes malformati o assenti: come il client (B22) si degrada
--     senza bloccare (assente = tutti i tipi noti attivi; non-array = il
--     check viene saltato).
--   - soglia massima per gli over/under: il Kotlin non ne impone una.
--
-- COMPATIBILITA':
--   - AppJson scrive i campi con encodeDefaults = true: i valori null appaiono
--     come JSON null espliciti. Il validatore tratta JSON null = assente, quindi
--     accetta sia le righe vecchie (campi omessi) sia quelle nuove (null espliciti).
--   - Il trigger esistente (0003) viene sostituito con create or replace,
--     stessa firma ed eventi (INSERT e UPDATE OF content): nessun altro
--     trigger viene toccato. Con on_prediction_submit (0002) l'ordine di
--     attivazione e' alfabetico (content < submit) e non ci sono interferenze:
--     i due trigger modificano campi diversi.
--   - La RLS esistente (proprieta', appartenenza, deadline) NON viene
--     modificata: il trigger gira DOPO i check RLS e chiama le tabelle come
--     SECURITY INVOKER (l'utente scrivente e' gia' verificato membro dalle
--     policy, quindi puo' leggere gara e lega).
--   - Il limite di 32 voci e' una salvaguardia tecnica contro payload
--     degenerati (il client ne usa al massimo 12): non e' una regola di gioco.
--
-- Il client non serve ricompilare: gli errori del server (messaggi in
-- italiano, prefisso "PRONOSTICO NON VALIDO:") vengono mostrati cosi' come
-- sono dal mapping errori esistente.
--
-- TEST: vedere supabase/tests/0004_prediction_validation_tests.sql
-- (predisposti per SQL Editor / database locale, NON eseguiti qui).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Tipi di pronostico conosciuti (enum PredictionType in Kotlin).
-- Se l'enum cresce, questa lista va aggiornata con una nuova migrazione.
-- -----------------------------------------------------------------------------
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

-- Quanti piloti richiede ogni tipo (PredictionValidation.requiredDrivers).
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

-- -----------------------------------------------------------------------------
-- Trigger di validazione (sostituisce la versione minimale di 0003).
-- SECURITY INVOKER: nessun privilegio elevato; l'utente scrivente e' gia'
-- stato verificato membro della lega dalle policy RLS e puo' leggere la
-- gara (races_select) e la lega (leagues_select) di cui e' membro.
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
    v_entries jsonb;
begin
    -- 1. Forma base (come 0003): oggetto con "entries" array.
    if jsonb_typeof(new.content) <> 'object' then
        raise exception 'PRONOSTICO NON VALIDO: il contenuto deve essere un oggetto JSON';
    end if;
    v_entries := new.content -> 'entries';
    if coalesce(jsonb_typeof(v_entries), 'null') <> 'array' then
        raise exception 'PRONOSTICO NON VALIDO: "entries" deve essere un array';
    end if;
    -- Salvaguardia tecnica contro payload degenerati (il client usa <= 12 tipi).
    if jsonb_array_length(v_entries) > 32 then
        raise exception 'PRONOSTICO NON VALIDO: troppi tipi di pronostico';
    end if;

    -- 2. La gara deve esistere (e determina se c'e' lo sprint).
    select r.is_sprint into v_sprint
    from public.races r
    where r.id = new.race_id;
    if not found then
        raise exception 'PRONOSTICO NON VALIDO: gara non trovata';
    end if;

    -- 3. Tipi attivi per la lega. Assente = tutti i tipi noti (default Kotlin);
    --    non-array = check saltato (degrada come il client, B22).
    select l.rules -> 'enabledTypes' into v_enabled
    from public.leagues l
    where l.id = new.league_id;

    -- 4. Un solo pronostico per tipo.
    if (select count(distinct e ->> 'type') from jsonb_array_elements(v_entries) e)
        <> jsonb_array_length(v_entries) then
        raise exception 'PRONOSTICO NON VALIDO: solo un pronostico per tipo';
    end if;

    -- 5. Validazione di ogni voce (PredictionValidation.validateEntry).
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

        -- driverIds: array di stringhe non vuote, numero corretto, senza duplicati.
        -- Per i tipi senza piloti (required = 0) l'app scrive comunque
        -- "driverIds": [] (encodeDefaults): se l'array e' presente, anche i
        -- suoi elementi vengono validati, altrimenti un payload diretto con
        -- elementi non stringa produrrebbe righe illeggibili ai client.
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

        -- Campi specifici per tipo. JSON null = campo assente (encodeDefaults).
        -- Nota sul token numerico: oltre al valore, si richiede che il numero
        -- sia un TOKEN intero ("5", non "5.0" ne' "5e0"): un jsonb "3.0"
        -- supererebbe il confronto con floor() ma kotlinx non lo decodifica in
        -- Int, lasciando una riga illeggibile ai client. La regex e' applicata
        -- al testo normalizzato di numeric (che preserva la scala).
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
            if v_min is null
                or (v_entry ->> 'rangeMin') !~ '^[0-9]{1,3}$'
                or v_min < 1 or v_min > 22 then
                raise exception 'PRONOSTICO NON VALIDO: fascia tra 1 e 22';
            end if;
            v_max := case
                when jsonb_typeof(v_entry -> 'rangeMax') = 'number'
                    then (v_entry ->> 'rangeMax')::numeric
            end;
            if v_max is null
                or (v_entry ->> 'rangeMax') !~ '^[0-9]{1,3}$'
                or v_max < 1 or v_max > 22 then
                raise exception 'PRONOSTICO NON VALIDO: fascia tra 1 e 22';
            end if;
            if v_min > v_max then
                raise exception 'PRONOSTICO NON VALIDO: la fascia deve avere minimo minore o uguale al massimo';
            end if;

        elsif v_type = 'DRIVER_DNF' then
            if jsonb_typeof(v_entry -> 'booleanValue') <> 'boolean' then
                raise exception 'PRONOSTICO NON VALIDO: indica se il pilota ritira';
            end if;

        elsif v_type in ('DRIVER_POINTS_OVER_UNDER', 'SAFETY_CAR_COUNT_OVER_UNDER', 'PIT_STOPS_OVER_UNDER') then
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
        end if;
    end loop;

    return new;
end;
$$;

-- -----------------------------------------------------------------------------
-- Trigger: ricreato qui per rendere la migrazione autosufficiente anche se
-- 0003 non fosse stata ancora applicata (idempotente). Eventi: INSERT e
-- UPDATE OF content — gli stessi di 0003; il trigger on_prediction_submit
-- (0002) non viene toccato e non interferisce (modifica solo submitted_at).
-- -----------------------------------------------------------------------------
drop trigger if exists on_prediction_content on public.predictions;
create trigger on_prediction_content
    before insert or update of content on public.predictions
    for each row execute function public.check_prediction_content();

-- -----------------------------------------------------------------------------
-- Permessi: superficie minima, coerente con la posture di 0002. Il trigger
-- esegue la funzione automaticamente (nessun grant necessario per l'utente);
-- gli helper sono usati dentro il trigger in contesto invoker.
-- -----------------------------------------------------------------------------
revoke execute on function public.known_prediction_types() from public, anon;
revoke execute on function public.prediction_required_drivers(text) from public, anon;
revoke execute on function public.check_prediction_content() from public, anon;
grant execute on function public.known_prediction_types() to authenticated;
grant execute on function public.prediction_required_drivers(text) to authenticated;