-- Disposable test fixture: legacy weak archive_season.
-- It has the canonical signature but no admin/league/standings validation.
-- Used only by disposable local lifecycle tests to reproduce legacy C1/C2/C3.

create or replace function public.archive_season(
    p_league uuid,
    p_season uuid,
    p_name text,
    p_standings jsonb
)
returns void
language plpgsql
security definer
set search_path = public
as $weak$
begin
    insert into public.season_snapshots (
        season_id, league_id, season_name, standings, archived_by
    ) values (
        p_season,
        p_league,
        coalesce(nullif(btrim(p_name), ''), 'Stagione'),
        p_standings,
        auth.uid()
    );

    update public.seasons
    set is_active = false
    where id = p_season and league_id = p_league;
end;
$weak$;

comment on function public.archive_season(uuid, uuid, text, jsonb) is
    'Disposable legacy weak fixture.';

revoke execute on function public.archive_season(uuid, uuid, text, jsonb)
    from public, anon;
grant execute on function public.archive_season(uuid, uuid, text, jsonb)
    to authenticated;
