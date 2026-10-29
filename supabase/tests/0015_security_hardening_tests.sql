-- =============================================================================
-- FantaFormula 1 - test della migrazione 0015_security_hardening.sql
--
-- PREDISPOSTI, NON ESEGUITI. Eseguire come script unico nel SQL Editor di un
-- progetto di test, dopo 0001-0015. Il blocco termina sempre con un'eccezione:
-- self-rollback, nessun dato residuo.
--
-- Verifica:
--   * generate_invite_code() produce 6 caratteri nel formato atteso;
--   * il vincolo sul nome profilo e il trigger sulla dimensione dei
--     pronostici esistono nello schema.
-- =============================================================================

do $test$
declare
    v_code text;
    v_errors int := 0;
begin
    v_code := public.generate_invite_code();
    if v_code !~ '^[A-Z0-9]{6}$' then
        v_errors := v_errors + 1;
        raise notice 'generate_invite_code: formato inatteso';
    end if;

    if not exists (
        select 1
        from pg_constraint
        where conname = 'profiles_display_name_length'
          and conrelid = 'public.profiles'::regclass
    ) then
        v_errors := v_errors + 1;
        raise notice 'profiles_display_name_length: vincolo assente';
    end if;

    if not exists (
        select 1
        from pg_trigger
        where tgname = 'on_prediction_size'
          and tgrelid = 'public.predictions'::regclass
          and not tgisinternal
    ) then
        v_errors := v_errors + 1;
        raise notice 'on_prediction_size: trigger assente';
    end if;

    if v_errors > 0 then
        raise exception 'TEST MIGRATION 0015: % caso/i FALLITO/i (transazione annullata)', v_errors;
    else
        raise exception 'TEST MIGRATION 0015: TUTTI SUPERATI (transazione annullata: nessun dato residuo)';
    end if;
end
$test$;
