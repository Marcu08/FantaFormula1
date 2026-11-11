-- =============================================================================
-- FantaFormula 1 - persistent archive_season deployment lockout
--
-- Run only through supabase/tests/preflight_revoke_archive_execute.sh, which
-- checks the target database name and executes this file in one transaction.
--
-- This file must be committed independently BEFORE any migration starts. The
-- Supabase CLI wraps each migration transactionally, so a REVOKE inside a
-- failing migration is rolled back; this external state is what survives.
--
-- The lockout table is internal deployment state. It must never be readable or
-- writable by client roles.
-- =============================================================================

set search_path = public, extensions;

do $archive_preflight$
declare
    v_canonical oid := to_regprocedure('public.archive_season(uuid,uuid,text,jsonb)');
    v_function_count int;
    v_security_definer boolean;
    v_config text[];
    v_owner name;
    v_table_owner name;
    v_custom_roles text;
begin
    if not exists (select 1 from pg_namespace where nspname = 'public') then
        raise exception 'PREFLIGHT FAIL: public schema is missing';
    end if;

    select count(*)
    into v_function_count
    from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public'
      and p.proname = 'archive_season';

    if v_function_count > 0 and v_canonical is null then
        raise exception 'PREFLIGHT FAIL: archive_season objects exist but the canonical (uuid,uuid,text,jsonb) signature is missing';
    end if;

    if v_function_count = 0 then
        create table if not exists public.archive_season_deploy_lockout (
            singleton boolean primary key default true check (singleton),
            active boolean not null default true,
            locked_at timestamptz,
            locked_by name,
            canonical_oid oid,
            note text
        );
        revoke all on public.archive_season_deploy_lockout
            from public, anon, authenticated, service_role;

        insert into public.archive_season_deploy_lockout (
            singleton, active, locked_at, locked_by, canonical_oid, note
        ) values (
            true, true, now(), current_user, null,
            'no archive_season present at preflight; lockout armed for migrations'
        )
        on conflict (singleton) do update
        set active = true,
            locked_at = now(),
            locked_by = current_user,
            canonical_oid = excluded.canonical_oid,
            note = excluded.note;

        raise notice 'PREFLIGHT OK: no archive_season found; deployment lockout armed';
        return;
    end if;

    if exists (
        select 1
        from pg_proc p
        join pg_namespace n on n.oid = p.pronamespace
        where n.nspname = 'public'
          and p.proname = 'archive_season'
          and p.oid <> v_canonical
    ) then
        raise exception 'PREFLIGHT FAIL: unexpected archive_season overload exists; remove it through a controlled owner action before deployment';
    end if;

    select p.prosecdef, p.proconfig, r.rolname
    into v_security_definer, v_config, v_owner
    from pg_proc p
    join pg_roles r on r.oid = p.proowner
    where p.oid = v_canonical;

    if not coalesce(v_security_definer, false) then
        raise exception 'PREFLIGHT FAIL: archive_season is not SECURITY DEFINER';
    end if;

    if v_config is null or not (v_config @> array['search_path=public']) then
        raise exception 'PREFLIGHT FAIL: archive_season search_path is not public';
    end if;

    select r.rolname
    into v_table_owner
    from pg_class c
    join pg_roles r on r.oid = c.relowner
    where c.oid = 'public.seasons'::regclass;

    if v_owner is distinct from v_table_owner then
        raise exception 'PREFLIGHT FAIL: archive_season owner (%) differs from seasons owner (%)',
            v_owner, v_table_owner;
    end if;

    select r.rolname
    into v_table_owner
    from pg_class c
    join pg_roles r on r.oid = c.relowner
    where c.oid = 'public.season_snapshots'::regclass;

    if v_owner is distinct from v_table_owner then
        raise exception 'PREFLIGHT FAIL: archive_season owner (%) differs from season_snapshots owner (%)',
            v_owner, v_table_owner;
    end if;

    -- Revoke direct access from the standard client roles, PUBLIC, and the
    -- trusted service role. Custom roles are deliberately NOT modified here:
    -- if one of them still grants effective access, the preflight fails closed
    -- and requires an explicit owner decision instead of silently changing a
    -- role that may be shared with unrelated functionality.
    revoke execute on function public.archive_season(uuid,uuid,text,jsonb)
        from public, anon, authenticated, service_role;

    select string_agg(distinct coalesce(r.rolname, 'PUBLIC'), ', ')
    into v_custom_roles
    from aclexplode(
        coalesce(
            (select p.proacl from pg_proc p where p.oid = v_canonical),
            acldefault(
                'f',
                (select p.proowner from pg_proc p where p.oid = v_canonical)
            )
        )
    ) a
    left join pg_roles r on r.oid = a.grantee
    where a.privilege_type = 'EXECUTE'
      and a.grantee <> (select p.proowner from pg_proc p where p.oid = v_canonical)
      and a.grantee <> 0
      and coalesce(r.rolname, '') not in ('anon', 'authenticated', 'service_role');

    if v_custom_roles is not null then
        raise exception 'PREFLIGHT FAIL: custom role grant(s) remain on archive_season: %. Remove the grant/membership through an explicit owner action, then rerun.',
            v_custom_roles;
    end if;

    if has_function_privilege('authenticated', v_canonical, 'EXECUTE')
       or has_function_privilege('anon', v_canonical, 'EXECUTE')
       or has_function_privilege('service_role', v_canonical, 'EXECUTE') then
        raise exception 'PREFLIGHT FAIL: effective EXECUTE remains for a client role after revocation';
    end if;

    create table if not exists public.archive_season_deploy_lockout (
        singleton boolean primary key default true check (singleton),
        active boolean not null default true,
        locked_at timestamptz,
        locked_by name,
        canonical_oid oid,
        note text
    );
    revoke all on public.archive_season_deploy_lockout
        from public, anon, authenticated, service_role;

    insert into public.archive_season_deploy_lockout (
        singleton, active, locked_at, locked_by, canonical_oid, note
    ) values (
        true, true, now(), current_user, v_canonical,
        'archive_season EXECUTE revoked before migration'
    )
    on conflict (singleton) do update
    set active = true,
        locked_at = now(),
        locked_by = current_user,
        canonical_oid = excluded.canonical_oid,
        note = excluded.note;

    raise notice 'PREFLIGHT OK: archive_season EXECUTE revoked for public/anon/authenticated/service_role; lockout active';
end;
$archive_preflight$;
