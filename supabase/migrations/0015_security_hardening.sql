-- =============================================================================
-- FantaFormula 1 - migrazione 0015_security_hardening.sql
--
-- Hardening additivo emerso dall'audit di sicurezza:
--   1) codice invito generato con entropia crittografica (gen_random_uuid)
--      invece di random(), che e' un PRNG di sessione;
--   2) limite alla lunghezza del nome profilo, per evitare testi arbitrari
--      enormi nel campo mostrato nelle classifiche;
--   3) limite alla dimensione del JSONB dei pronostici, per evitare payload
--      abnormi che rallentano trigger e query.
--
-- Idempotente. Le righe esistenti non vengono scandite dai nuovi vincoli
-- (NOT VALID): il controllo vale per nuove righe e per gli aggiornamenti.
-- NON e' stata applicata a un database live durante l'audit.
-- =============================================================================

-- 1) Codice invito: 6 caratteri da 32 simboli, con entropia del CSPRNG di
--    PostgreSQL (gen_random_uuid, disponibile senza estensioni aggiuntive).
--    La funzione resta SECURITY DEFINER per controllare le collisioni su
--    tutte le leghe, non solo su quelle visibili al chiamante.
create or replace function public.generate_invite_code()
returns text
language plpgsql
volatile
security definer
set search_path = public
as $$
declare
    v_alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    v_hex text;
    v_code text;
    v_i int;
begin
    loop
        -- 32 caratteri: 256 / 32 = 8, quindi il modulo non introduce bias.
        v_hex := replace(gen_random_uuid()::text, '-', '');
        v_code := '';
        for v_i in 1..6 loop
            v_code := v_code || substr(
                v_alphabet,
                1 + (
                    get_byte(
                        decode(substr(v_hex, (v_i - 1) * 2 + 1, 2), 'hex'),
                        0
                    ) % length(v_alphabet)
                ),
                1
            );
        end loop;
        exit when not exists (
            select 1 from public.leagues where invite_code = v_code
        );
    end loop;
    return v_code;
end;
$$;

comment on function public.generate_invite_code() is
    'Genera un codice invito di 6 caratteri con entropia crittografica e collision check globale.';

revoke execute on function public.generate_invite_code() from public, anon;
grant execute on function public.generate_invite_code() to authenticated;

-- 2) Nome profilo: niente stringhe vuote/solo spazi e massimo 60 caratteri.
--    NOT VALID non blocca i profili storici gia' presenti.
alter table public.profiles
    drop constraint if exists profiles_display_name_length;
alter table public.profiles
    add constraint profiles_display_name_length
    check (char_length(btrim(display_name)) between 1 and 60)
    not valid;

-- 3) Pronostici: il trigger esistente valida la forma, ma non la dimensione.
--    Un payload enorme (o tante stringhe lunghe) puo' consumare CPU/memoria
--    a ogni insert/update. 128 KiB e' molto sopra il payload reale del client
--    (massimo 32 voci) e molto sotto un abuso.
create or replace function public.check_prediction_size()
returns trigger
language plpgsql
security invoker
set search_path = public
as $$
begin
    if pg_column_size(new.content) > 131072 then
        raise exception 'PRONOSTICO NON VALIDO: contenuto troppo grande';
    end if;
    return new;
end;
$$;

drop trigger if exists on_prediction_size on public.predictions;
create trigger on_prediction_size
    before insert or update of content on public.predictions
    for each row execute function public.check_prediction_size();

revoke execute on function public.check_prediction_size() from public, anon;
grant execute on function public.check_prediction_size() to authenticated;
