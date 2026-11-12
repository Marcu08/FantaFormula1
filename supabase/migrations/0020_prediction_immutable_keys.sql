-- =============================================================================
-- FantaFormula 1 - migrazione 0020_prediction_immutable_keys.sql
--
-- Chiude un caso limite della 0019: `predictions.race_id` e `league_id`
-- possono essere cambiati con un UPDATE diretto del client. In quel caso il
-- trigger advisory della 0019 proteggerebbe solo la NUOVA gara, mentre la
-- riga verrebbe rimossa dalla gara precedente senza lock. Inoltre il trigger
-- `on_prediction_content` scatta solo su UPDATE OF content, quindi il
-- contenuto non verrebbe rivalidato contro le quote della nuova gara.
--
-- La soluzione minima e' rendere immutabili le due chiavi dopo l'inserimento:
--   - l'app aggiorna solo `content` tramite save_prediction_if_revision;
--   - una gara/lega diversa richiede un delete + insert, che passa dai
--     normali controlli RLS e di validazione.
--
-- Il trigger e' ordinato prima di on_prediction_content e on_prediction_00_lock.
-- Idempotente. Test: supabase/tests/0020_prediction_immutable_keys_tests.sql
-- =============================================================================

create or replace function public.prevent_prediction_key_update()
returns trigger
language plpgsql
security invoker
set search_path = public
as $$
begin
    if new.race_id is distinct from old.race_id
        or new.league_id is distinct from old.league_id then
        raise exception 'PRONOSTICO NON VALIDO: gara e lega non modificabili';
    end if;
    return new;
end;
$$;

comment on function public.prevent_prediction_key_update() is
    'Impedisce di spostare un pronostico esistente su un''altra gara o lega (0020).';

drop trigger if exists on_prediction_00_immutable_keys on public.predictions;
create trigger on_prediction_00_immutable_keys
    before update of race_id, league_id on public.predictions
    for each row execute function public.prevent_prediction_key_update();
