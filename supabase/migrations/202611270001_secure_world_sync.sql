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
    if not exists (
        select 1 from public.worlds w
        where w.id = p_world_id and w.owner_id = auth.uid()
    ) then
        raise exception 'only the world owner may approve invitations' using errcode = '42501';
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
