-- Owner-only master access. The plaintext code is never stored; only a salted
-- bcrypt verifier is kept server-side. No verifier or privileged secret ships in
-- the Android package. Each paired world has one verifier shared across devices.

create extension if not exists pgcrypto with schema extensions;

create table if not exists public.owner_master_code_verifiers (
    world_id uuid primary key references public.worlds(id) on delete cascade,
    verifier text not null,
    configured_by uuid not null references auth.users(id) on delete restrict,
    configured_at timestamptz not null default now(),
    check (verifier like '$2%')
);

create table if not exists public.owner_master_attempt_limits (
    world_id uuid not null references public.worlds(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    window_started_at timestamptz not null default now(),
    attempts integer not null default 0 check (attempts >= 0),
    primary key (world_id, user_id)
);

create table if not exists public.owner_master_unlocks (
    world_id uuid not null references public.worlds(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    unlocked_at timestamptz not null default now(),
    primary key (world_id, user_id)
);

create table if not exists public.owner_scene_grants (
    world_id uuid not null references public.worlds(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    scene_number smallint not null check (scene_number between 1 and 21),
    granted_by uuid not null references auth.users(id) on delete restrict,
    granted_at timestamptz not null default now(),
    primary key (world_id, user_id, scene_number)
);

alter table public.owner_master_code_verifiers enable row level security;
alter table public.owner_master_attempt_limits enable row level security;
alter table public.owner_master_unlocks enable row level security;
alter table public.owner_scene_grants enable row level security;

revoke all on public.owner_master_code_verifiers from anon, authenticated;
revoke all on public.owner_master_attempt_limits from anon, authenticated;
revoke all on public.owner_master_unlocks from anon, authenticated;
revoke all on public.owner_scene_grants from anon, authenticated;

create or replace function public.configure_owner_master_code(
    p_world_id uuid,
    p_code text
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $configure_owner_master_code$
begin
    if auth.uid() is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;

    if not exists (
        select 1
          from public.worlds w
          join public.world_members m
            on m.world_id = w.id
           and m.user_id = w.owner_id
           and m.member_role = 'owner'
           and m.revoked_at is null
         where w.id = p_world_id
           and w.owner_id = auth.uid()
    ) then
        raise exception 'active world owner required' using errcode = '42501';
    end if;

    -- bcrypt processes at most 72 bytes: require printable ASCII and cap by bytes.
    if p_code is null
       or octet_length(p_code) not between 12 and 72
       or p_code ~ '[[:space:]]'
       or p_code !~ '[A-Za-z]'
       or p_code !~ '[0-9]'
       or p_code !~ '[^A-Za-z0-9]' then
        raise exception 'master code must contain letters, digits and symbols' using errcode = '22023';
    end if;

    insert into public.owner_master_code_verifiers(world_id, verifier, configured_by)
    values (p_world_id, extensions.crypt(p_code, extensions.gen_salt('bf', 12)), auth.uid())
    on conflict (world_id) do nothing;

    if not found then
        return false;
    end if;

    insert into public.owner_master_unlocks(world_id, user_id)
    values (p_world_id, auth.uid())
    on conflict (world_id, user_id) do update set unlocked_at = now();

    return true;
end;
$configure_owner_master_code$;

create or replace function public.verify_owner_master_code(
    p_world_id uuid,
    p_code text
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $verify_owner_master_code$
declare
    v_attempts integer;
    v_verifier text;
begin
    if auth.uid() is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if p_world_id is null or p_code is null or octet_length(p_code) not between 1 and 72 then
        return false;
    end if;
    if not public.is_active_world_member(p_world_id) then
        raise exception 'active world member required' using errcode = '42501';
    end if;

    insert into public.owner_master_attempt_limits(world_id, user_id, window_started_at, attempts)
    values (p_world_id, auth.uid(), now(), 1)
    on conflict (world_id, user_id) do update
       set window_started_at = case
               when public.owner_master_attempt_limits.window_started_at < now() - interval '15 minutes'
               then now() else public.owner_master_attempt_limits.window_started_at
           end,
           attempts = case
               when public.owner_master_attempt_limits.window_started_at < now() - interval '15 minutes'
               then 1 else public.owner_master_attempt_limits.attempts + 1
           end
    returning attempts into v_attempts;

    if v_attempts > 5 then
        return false;
    end if;

    select verifier into v_verifier
      from public.owner_master_code_verifiers
     where world_id = p_world_id;

    if v_verifier is null or extensions.crypt(p_code, v_verifier) <> v_verifier then
        return false;
    end if;

    insert into public.owner_master_unlocks(world_id, user_id)
    values (p_world_id, auth.uid())
    on conflict (world_id, user_id) do update set unlocked_at = now();

    return true;
end;
$verify_owner_master_code$;

create or replace function public.grant_owner_scene(
    p_world_id uuid,
    p_target_user_id uuid,
    p_scene_number integer
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $grant_owner_scene$
begin
    if auth.uid() is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if p_scene_number not between 1 and 21 or p_target_user_id is null then
        raise exception 'invalid scene grant' using errcode = '22023';
    end if;
    if not public.is_active_world_member(p_world_id) then
        raise exception 'active world member required' using errcode = '42501';
    end if;
    if not exists (
        select 1 from public.owner_master_unlocks u
         where u.world_id = p_world_id and u.user_id = auth.uid()
    ) then
        raise exception 'master authorization required' using errcode = '42501';
    end if;
    if not public.is_active_world_member_for_user(p_world_id, p_target_user_id) then
        raise exception 'target must be an active world participant' using errcode = '42501';
    end if;

    insert into public.owner_scene_grants(world_id, user_id, scene_number, granted_by)
    values (p_world_id, p_target_user_id, p_scene_number, auth.uid())
    on conflict (world_id, user_id, scene_number) do nothing;

    return true;
end;
$grant_owner_scene$;

create or replace function public.get_owner_master_state(p_world_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $get_owner_master_state$
begin
    if auth.uid() is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if not public.is_active_world_member(p_world_id) then
        raise exception 'active world member required' using errcode = '42501';
    end if;

    return jsonb_build_object(
        'unlocked',
        exists (
            select 1 from public.owner_master_unlocks u
             where u.world_id = p_world_id and u.user_id = auth.uid()
        ),
        'granted_scenes',
        coalesce((
            select jsonb_agg(g.scene_number order by g.scene_number)
              from public.owner_scene_grants g
             where g.world_id = p_world_id and g.user_id = auth.uid()
        ), '[]'::jsonb)
    );
end;
$get_owner_master_state$;

revoke all on function public.configure_owner_master_code(uuid, text) from public;
revoke all on function public.verify_owner_master_code(uuid, text) from public;
revoke all on function public.grant_owner_scene(uuid, uuid, integer) from public;
revoke all on function public.get_owner_master_state(uuid) from public;
grant execute on function public.configure_owner_master_code(uuid, text) to authenticated;
grant execute on function public.verify_owner_master_code(uuid, text) to authenticated;
grant execute on function public.grant_owner_scene(uuid, uuid, integer) to authenticated;
grant execute on function public.get_owner_master_state(uuid) to authenticated;
