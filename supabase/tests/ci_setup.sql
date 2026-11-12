-- =============================================================================
-- Test/CI bootstrap for the SQL suite.
--
-- NON e' una migrazione di produzione. Crea il minimo Supabase-compatible
-- (ruoli client e schema auth) su un PostgreSQL vuoto, prima di applicare
-- 0001-0012 e le migrazioni di sicurezza.
-- =============================================================================
do $roles$
begin
    if not exists (select 1 from pg_roles where rolname = 'anon') then
        create role anon nologin;
    end if;
    if not exists (select 1 from pg_roles where rolname = 'authenticated') then
        create role authenticated nologin;
    end if;
    if not exists (select 1 from pg_roles where rolname = 'service_role') then
        create role service_role nologin;
    end if;
end;
$roles$;

create schema if not exists auth;

create table if not exists auth.users (
    id uuid primary key,
    email text,
    raw_user_meta_data jsonb default '{}'::jsonb
);

create or replace function auth.uid()
returns uuid
language sql
stable
as $uid$
    select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
$uid$;

grant usage on schema auth to anon, authenticated;
grant execute on function auth.uid() to anon, authenticated;
