-- Secure, append-only synchronization foundation.
-- All user content is expected to be encrypted on-device before insertion.
-- Apply only to a Supabase project after reviewing its Auth and Storage configuration.

create extension if not exists pgcrypto with schema extensions;

create table if not exists public.worlds (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references auth.users(id) on delete restrict,
    display_name text not null default 'عالمنا الخاص'
        check (char_length(display_name) between 1 and 80),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists public.world_members (
    world_id uuid not null references public.worlds(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete restrict,
    member_role text not null default 'member' check (member_role in ('owner', 'member')),
    joined_at timestamptz not null default now(),
    revoked_at timestamptz,
    primary key (world_id, user_id)
);

create index if not exists world_members_active_user_idx
    on public.world_members(user_id, world_id) where revoked_at is null;

create table if not exists public.world_invites (
    id uuid primary key default gen_random_uuid(),
    world_id uuid not null references public.worlds(id) on delete cascade,
    token_hash text not null unique
        check (token_hash ~ '^[0-9a-f]{64}$'),
    created_by uuid not null references auth.users(id) on delete restrict,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    accepted_by uuid references auth.users(id) on delete restrict,
    accepted_at timestamptz,
    revoked_at timestamptz,
    check (expires_at > created_at)
);

create index if not exists world_invites_world_idx
    on public.world_invites(world_id, expires_at);

-- A globally ordered, append-only event stream. Payloads must be ciphertext,
-- not JSON/plaintext user content. event_id provides client-side idempotency.
create table if not exists public.world_events (
    server_seq bigint generated always as identity primary key,
    world_id uuid not null references public.worlds(id) on delete cascade,
    event_id uuid not null,
    actor_id uuid not null references auth.users(id) on delete restrict,
    event_type text not null check (
        event_type in (
            'world_update', 'memory_create', 'memory_delete',
            'message_create', 'garden_update', 'daily_answer',
            'drawing_update', 'capsule_update', 'media_add', 'settings_update'
        )
    ),
    ciphertext text not null check (
        octet_length(convert_to(ciphertext, 'UTF8')) between 32 and 1048576
    ),
    client_created_at timestamptz not null,
    server_created_at timestamptz not null default now(),
    unique (world_id, event_id)
);

create index if not exists world_events_world_cursor_idx
    on public.world_events(world_id, server_seq);

-- Create the owner membership atomically with world creation.
create or replace function public.add_world_owner_membership()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    insert into public.world_members(world_id, user_id, member_role)
    values (new.id, new.owner_id, 'owner');
    return new;
end;
$$;

drop trigger if exists worlds_add_owner_membership on public.worlds;
create trigger worlds_add_owner_membership
after insert on public.worlds
for each row execute function public.add_world_owner_membership();

alter table public.worlds enable row level security;
alter table public.world_members enable row level security;
alter table public.world_invites enable row level security;
alter table public.world_events enable row level security;

-- Helper: active membership check. Fixed search_path prevents object shadowing.
create or replace function public.is_active_world_member(p_world_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from public.world_members m
        where m.world_id = p_world_id
          and m.user_id = (select auth.uid())
          and m.revoked_at is null
    );
$$;

revoke all on function public.is_active_world_member(uuid) from public;
grant execute on function public.is_active_world_member(uuid) to authenticated;

drop policy if exists worlds_select_member on public.worlds;
create policy worlds_select_member on public.worlds
for select to authenticated
using (public.is_active_world_member(id));

drop policy if exists worlds_insert_owner on public.worlds;
create policy worlds_insert_owner on public.worlds
for insert to authenticated
with check (owner_id = (select auth.uid()));

drop policy if exists worlds_update_owner on public.worlds;
create policy worlds_update_owner on public.worlds
for update to authenticated
using (
    owner_id = (select auth.uid())
    and public.is_active_world_member(id)
)
with check (owner_id = (select auth.uid()));

drop policy if exists members_select_world on public.world_members;
create policy members_select_world on public.world_members
for select to authenticated
using (public.is_active_world_member(world_id));

-- No direct client insert/update/delete policies for membership, invitations,
-- or events. Membership changes happen only through the controlled RPC below.

drop policy if exists invites_select_owner on public.world_invites;
create policy invites_select_owner on public.world_invites
for select to authenticated
using (
    created_by = (select auth.uid())
    and public.is_active_world_member(world_id)
);

drop policy if exists events_select_member on public.world_events;
create policy events_select_member on public.world_events
for select to authenticated
using (public.is_active_world_member(world_id));

drop policy if exists events_insert_member on public.world_events;
create policy events_insert_member on public.world_events
for insert to authenticated
with check (
    actor_id = (select auth.uid())
    and public.is_active_world_member(world_id)
);

-- No UPDATE or DELETE policy is intentionally defined for world_events:
-- client accounts cannot rewrite or erase the audit/sync stream.

create or replace function public.create_world_invite(
    p_world_id uuid,
    p_token_hash text,
    p_ttl_seconds integer default 900
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_invite_id uuid;
    v_active_count integer;
begin
    if auth.uid() is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if p_token_hash !~ '^[0-9a-f]{64}$' then
        raise exception 'invalid invite token hash' using errcode = '22023';
    end if;
    if p_ttl_seconds < 60 or p_ttl_seconds > 3600 then
        raise exception 'invalid invite lifetime' using errcode = '22023';
    end if;
    -- Serialize capacity checks per world to prevent concurrent invites
    -- from admitting more than five active members.
    perform 1 from public.worlds w
    where w.id = p_world_id and w.owner_id = auth.uid()
    for update;

    if not found or not exists (
        select 1 from public.world_members m
        where m.world_id = p_world_id
          and m.user_id = auth.uid()
          and m.member_role = 'owner'
          and m.revoked_at is null
    ) then
        raise exception 'only an active world owner may approve invitations' using errcode = '42501';
    end if;

    select count(*) into v_active_count
    from public.world_members m
    where m.world_id = p_world_id and m.revoked_at is null;

    if v_active_count >= 5 then
        raise exception 'world member limit reached' using errcode = '23514';
    end if;

    insert into public.world_invites(world_id, token_hash, created_by, expires_at)
    values (p_world_id, p_token_hash, auth.uid(), now() + make_interval(secs => p_ttl_seconds))
    returning id into v_invite_id;

    return v_invite_id;
end;
$$;

create or replace function public.accept_world_invite(p_token_hash text)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_invite public.world_invites%rowtype;
    v_active_count integer;
begin
    if auth.uid() is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if p_token_hash !~ '^[0-9a-f]{64}$' then
        raise exception 'invalid invitation' using errcode = '22023';
    end if;

    select * into v_invite
    from public.world_invites i
    where i.token_hash = p_token_hash
    for update;

    if not found
       or v_invite.revoked_at is not null
       or v_invite.accepted_at is not null
       or v_invite.expires_at <= now() then
        raise exception 'invitation is invalid or expired' using errcode = '22023';
    end if;

    -- Lock the world row so concurrent accepts cannot exceed the five-member cap.
    perform 1 from public.worlds w
    where w.id = v_invite.world_id
    for update;

    select count(*) into v_active_count
    from public.world_members m
    where m.world_id = v_invite.world_id and m.revoked_at is null;

    if v_active_count >= 5 then
        raise exception 'world member limit reached' using errcode = '23514';
    end if;

    insert into public.world_members(world_id, user_id, member_role)
    values (v_invite.world_id, auth.uid(), 'member')
    on conflict (world_id, user_id) do update
        set revoked_at = null, joined_at = now(), member_role = 'member';

    update public.world_invites
    set accepted_by = auth.uid(), accepted_at = now()
    where id = v_invite.id;

    return v_invite.world_id;
end;
$$;

revoke all on function public.create_world_invite(uuid, text, integer) from public;
revoke all on function public.accept_world_invite(text) from public;
grant execute on function public.create_world_invite(uuid, text, integer) to authenticated;
grant execute on function public.accept_world_invite(text) to authenticated;

-- Private encrypted media bucket. Object names must begin with the world UUID.
insert into storage.buckets(id, name, public, file_size_limit, allowed_mime_types)
values ('world-media', 'world-media', false, 52428800, array['application/octet-stream'])
on conflict (id) do update
set public = false,
    file_size_limit = 52428800,
    allowed_mime_types = array['application/octet-stream'];

drop policy if exists world_media_read_member on storage.objects;
create policy world_media_read_member on storage.objects
for select to authenticated
using (
    bucket_id = 'world-media'
    and public.is_active_world_member((storage.foldername(name))[1]::uuid)
);

drop policy if exists world_media_insert_member on storage.objects;
create policy world_media_insert_member on storage.objects
for insert to authenticated
with check (
    bucket_id = 'world-media'
    and public.is_active_world_member((storage.foldername(name))[1]::uuid)
);

-- No client UPDATE/DELETE policy for media by default. Deletion is a future
-- owner-authorized server operation after retention and key-rotation rules exist.


-- Device-code discovery and explicit host approval.
-- The 9-digit code is a lookup hint only; authentication still comes from Auth.
create table if not exists public.device_profiles (
    device_id uuid primary key,
    user_id uuid not null references auth.users(id) on delete cascade,
    device_code text not null unique check (device_code ~ '^[0-9]{9}$'),
    created_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now()
);

create table if not exists public.join_requests (
    id uuid primary key default gen_random_uuid(),
    world_id uuid not null references public.worlds(id) on delete cascade,
    requester_id uuid not null references auth.users(id) on delete cascade,
    target_device_id uuid not null references public.device_profiles(device_id) on delete restrict,
    status text not null default 'pending'
        check (status in ('pending', 'approved', 'declined', 'expired')),
    created_at timestamptz not null default now(),
    expires_at timestamptz not null default (now() + interval '10 minutes'),
    decided_at timestamptz,
    decided_by uuid references auth.users(id) on delete restrict
);

create unique index if not exists one_pending_join_request_per_user_world
    on public.join_requests(world_id, requester_id) where status = 'pending';

create index if not exists join_requests_owner_inbox_idx
    on public.join_requests(world_id, created_at desc) where status = 'pending';

create table if not exists public.pairing_rate_limits (
    user_id uuid primary key references auth.users(id) on delete cascade,
    window_started_at timestamptz not null,
    attempts integer not null default 0 check (attempts >= 0)
);

alter table public.device_profiles enable row level security;
alter table public.join_requests enable row level security;
alter table public.pairing_rate_limits enable row level security;

drop policy if exists device_profiles_select_self on public.device_profiles;
create policy device_profiles_select_self on public.device_profiles
for select to authenticated
using (user_id = (select auth.uid()));

drop policy if exists join_requests_select_participant on public.join_requests;
create policy join_requests_select_participant on public.join_requests
for select to authenticated
using (
    requester_id = (select auth.uid())
    or exists (
        select 1 from public.worlds w
        where w.id = world_id
          and w.owner_id = (select auth.uid())
          and public.is_active_world_member(w.id)
    )
);

-- Rate-limit state is only accessible inside SECURITY DEFINER RPCs.
revoke all on public.pairing_rate_limits from anon, authenticated;

create or replace function public.register_device_code(
    p_device_id uuid,
    p_device_code text
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
begin
    if auth.uid() is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if p_device_id is null or p_device_code !~ '^[0-9]{9}$' then
        raise exception 'invalid device identity' using errcode = '22023';
    end if;

    insert into public.device_profiles(device_id, user_id, device_code, last_seen_at)
    values (p_device_id, auth.uid(), p_device_code, now())
    on conflict (device_id) do update
        set device_code = excluded.device_code,
            last_seen_at = now()
        where public.device_profiles.user_id = auth.uid();

    return found;
end;
$$;

create or replace function public.request_join_by_device_code(p_device_code text)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_attempts integer;
    v_target_user uuid;
    v_target_device uuid;
    v_world_id uuid;
    v_request_id uuid;
    v_active_count integer;
begin
    if auth.uid() is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;

    -- Five lookups per ten-minute window per authenticated account.
    insert into public.pairing_rate_limits(user_id, window_started_at, attempts)
    values (auth.uid(), now(), 1)
    on conflict (user_id) do update
    set window_started_at = case
            when public.pairing_rate_limits.window_started_at < now() - interval '10 minutes'
            then now()
            else public.pairing_rate_limits.window_started_at
        end,
        attempts = case
            when public.pairing_rate_limits.window_started_at < now() - interval '10 minutes'
            then 1
            else public.pairing_rate_limits.attempts + 1
        end
    returning attempts into v_attempts;

    if v_attempts > 5 then
        return null;
    end if;

    if p_device_code is null or p_device_code !~ '^[0-9]{9}$' then
        return null;
    end if;

    select d.user_id, d.device_id into v_target_user, v_target_device
    from public.device_profiles d
    where d.device_code = p_device_code;

    if not found or v_target_user = auth.uid() then
        return null;
    end if;

    select w.id into v_world_id
    from public.worlds w
    join public.world_members m
      on m.world_id = w.id
     and m.user_id = w.owner_id
     and m.member_role = 'owner'
     and m.revoked_at is null
    where w.owner_id = v_target_user
    order by w.created_at desc
    limit 1;

    if v_world_id is null then
        return null;
    end if;

    perform 1 from public.worlds w where w.id = v_world_id for update;

    select count(*) into v_active_count
    from public.world_members m
    where m.world_id = v_world_id and m.revoked_at is null;

    if v_active_count >= 5 then
        return null;
    end if;

    insert into public.join_requests(world_id, requester_id, target_device_id)
    values (v_world_id, auth.uid(), v_target_device)
    on conflict (world_id, requester_id) where status = 'pending'
    do nothing
    returning id into v_request_id;

    return v_request_id;
end;
$$;

create or replace function public.decide_join_request(
    p_request_id uuid,
    p_approve boolean
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_request public.join_requests%rowtype;
    v_owner_id uuid;
    v_active_count integer;
begin
    if auth.uid() is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;

    select * into v_request
    from public.join_requests r
    where r.id = p_request_id
    for update;

    if not found or v_request.status <> 'pending' or v_request.expires_at <= now() then
        return false;
    end if;

    perform 1 from public.worlds w
    where w.id = v_request.world_id
    for update;

    select w.owner_id into v_owner_id
    from public.worlds w
    where w.id = v_request.world_id;

    if v_owner_id is distinct from auth.uid()
       or not public.is_active_world_member(v_request.world_id) then
        raise exception 'only the active world owner may decide requests' using errcode = '42501';
    end if;

    if p_approve then
        select count(*) into v_active_count
        from public.world_members m
        where m.world_id = v_request.world_id and m.revoked_at is null;

        if v_active_count >= 5 then
            return false;
        end if;

        insert into public.world_members(world_id, user_id, member_role)
        values (v_request.world_id, v_request.requester_id, 'member')
        on conflict (world_id, user_id) do update
            set revoked_at = null, joined_at = now(), member_role = 'member';

        update public.join_requests
        set status = 'approved', decided_at = now(), decided_by = auth.uid()
        where id = p_request_id;
    else
        update public.join_requests
        set status = 'declined', decided_at = now(), decided_by = auth.uid()
        where id = p_request_id;
    end if;

    return true;
end;
$$;

create or replace function public.revoke_world_member(p_world_id uuid, p_member_id uuid)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
begin
    if auth.uid() is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if p_member_id = auth.uid() then
        return false;
    end if;
    if not exists (
        select 1 from public.worlds w
        where w.id = p_world_id and w.owner_id = auth.uid()
    ) or not public.is_active_world_member(p_world_id) then
        raise exception 'only the active world owner may revoke members' using errcode = '42501';
    end if;

    update public.world_members
    set revoked_at = now()
    where world_id = p_world_id
      and user_id = p_member_id
      and member_role = 'member'
      and revoked_at is null;

    return found;
end;
$$;

revoke all on function public.register_device_code(uuid, text) from public;
revoke all on function public.request_join_by_device_code(text) from public;
revoke all on function public.decide_join_request(uuid, boolean) from public;
revoke all on function public.revoke_world_member(uuid, uuid) from public;
grant execute on function public.register_device_code(uuid, text) to authenticated;
grant execute on function public.request_join_by_device_code(text) to authenticated;
grant execute on function public.decide_join_request(uuid, boolean) to authenticated;
grant execute on function public.revoke_world_member(uuid, uuid) to authenticated;
