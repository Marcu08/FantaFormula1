-- =============================================================================
-- FantaFormula 1 — migration 0005_prediction_count.sql
-- Da eseguire nel SQL Editor del progetto Supabase.
--
-- Contatore aggregato dei pronostici per gara: consente alla dashboard di
-- mostrare "3 su 4 hanno gia' pronosticato" SENZA esporre gli identity dei
-- giocatori (la RLS nasconde i pronostici altrui prima della deadline, e deve
-- continuare a farlo: qui si pubblica solo il NUMERO).
--
-- I dato leggibili senza questo contatore restano invariati.
-- Idempotente.
-- =============================================================================

create or replace function public.prediction_count(p_race uuid)
returns bigint
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_league uuid;
    v_count bigint;
begin
    select league_id into v_league
    from public.races
    where id = p_race;

    if v_league is null then
        raise exception 'Gara non trovata';
    end if;

    if not public.is_league_member(v_league) then
        raise exception 'Solo i membri della lega possono consultare il conteggio';
    end if;

    select count(*) into v_count
    from public.predictions
    where race_id = p_race;

    return v_count;
end;
$$;

revoke execute on function public.prediction_count(uuid) from public, anon;
grant execute on function public.prediction_count(uuid) to authenticated;