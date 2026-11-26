-- Per-device encrypted world-key envelopes.
-- The server stores ciphertext only; raw world keys never leave Android.

create table if not exists public.world_member_key_envelopes (
    world_id uuid not null references public.worlds(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    device_id uuid not null references public.device_profiles(device_id) on delete cascade,
    key_version integer not null check (key_version > 0),
    envelope text not null check (
        char_length(envelope) between 350 and 1024
        and envelope like 'wke:rsa-oaep-sha256:v1:%'
    ),
    created_at timestamptz not null default now(),
    primary key (world_id, device_id, key_version),
    foreign key (world_id, user_id) references public.world_members(world_id, user_id)
        on delete cascade
);

create index if not exists world_key_envelopes_recipient_idx
    on public.world_member_key_envelopes(user_id, device_id, world_id);

alter table public.world_member_key_envelopes enable row level security;
grant select on table public.world_member_key_envelopes to authenticated;
revoke insert, update, delete on table public.world_member_key_envelopes from anon, authenticated;

drop policy if exists world_key_envelopes_read_own_device
    on public.world_member_key_envelopes;
create policy world_key_envelopes_read_own_device
on public.world_member_key_envelopes
for select to authenticated
using (
    user_id = (select auth.uid())
    and exists (
        select 1
        from public.device_profiles d
        where d.device_id = world_member_key_envelopes.device_id
          and d.user_id = (select auth.uid())
    )
    and public.is_active_world_member(world_id)
);

-- An owner can discover only public keys belonging to active members of this world.
create or replace function public.list_world_member_devices(p_world_id uuid)
returns table (
    member_user_id uuid,
    member_device_id uuid,
    member_public_key text
)
language plpgsql
security definer
set search_path = ''
as $list_member_devices$
begin
    if auth.uid() is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if not exists (
        select 1
        from public.worlds w
        join public.world_members m
          on m.world_id = w.id
         and m.user_id = auth.uid()
         and m.member_role = 'owner'
         and m.revoked_at is null
        where w.id = p_world_id
          and w.owner_id = auth.uid()
    ) then
        raise exception 'active world owner required' using errcode = '42501';
    end if;

    return query
    select d.user_id, d.device_id, d.public_key
    from public.device_profiles d
    join public.world_members m
      on m.user_id = d.user_id
     and m.world_id = p_world_id
     and m.revoked_at is null
    where d.public_key is not null
    order by d.user_id, d.last_seen_at desc;
end;
$list_member_devices$;

-- Only the active owner may publish an envelope, and only to a device
-- registered to an active member of this exact world.
create or replace function public.publish_world_key_envelope(
    p_world_id uuid,
    p_user_id uuid,
    p_device_id uuid,
    p_key_version integer,
    p_envelope text
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $publish_key_envelope$
declare
    v_world_owner uuid;
    v_ciphertext bytea;
begin
    if auth.uid() is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if p_key_version is null or p_key_version < 1
       or p_envelope is null
       or char_length(p_envelope) not between 350 and 1024
       or p_envelope !~ '^wke:rsa-oaep-sha256:v1:[A-Za-z0-9+/]+={0,2}$' then
        raise exception 'invalid key envelope' using errcode = '22023';
    end if;

    begin
        v_ciphertext := decode(
            substring(p_envelope from length('wke:rsa-oaep-sha256:v1:') + 1),
            'base64'
        );
    exception when others then
        raise exception 'invalid key envelope payload' using errcode = '22023';
    end;
    if octet_length(v_ciphertext) <> 256 then
        raise exception 'invalid RSA-2048 key envelope size' using errcode = '22023';
    end if;

    select w.owner_id into v_world_owner
    from public.worlds w
    join public.world_members owner_members
      on owner_members.world_id = w.id
     and owner_members.user_id = w.owner_id
     and owner_members.member_role = 'owner'
     and owner_members.revoked_at is null
    where w.id = p_world_id
    for update of w;

    if not found or v_world_owner <> auth.uid() then
        raise exception 'active world owner required' using errcode = '42501';
    end if;

    if not exists (
        select 1
        from public.world_members m
        join public.device_profiles d
          on d.user_id = m.user_id
         and d.device_id = p_device_id
         and d.public_key is not null
        where m.world_id = p_world_id
          and m.user_id = p_user_id
          and m.revoked_at is null
    ) then
        raise exception 'target device is not an active member device' using errcode = '42501';
    end if;

    insert into public.world_member_key_envelopes(
        world_id, user_id, device_id, key_version, envelope
    )
    values (p_world_id, p_user_id, p_device_id, p_key_version, p_envelope)
    on conflict (world_id, device_id, key_version) do update
       set user_id = excluded.user_id,
           envelope = excluded.envelope,
           created_at = now();

    return true;
end;
$publish_key_envelope$;

revoke all on function public.list_world_member_devices(uuid) from public;
revoke all on function public.publish_world_key_envelope(uuid, uuid, uuid, integer, text) from public;
grant execute on function public.list_world_member_devices(uuid) to authenticated;
grant execute on function public.publish_world_key_envelope(uuid, uuid, uuid, integer, text) to authenticated;

-- No client INSERT/UPDATE/DELETE grants or policies are created for envelopes.
