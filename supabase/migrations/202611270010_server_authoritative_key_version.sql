-- Server-authoritative world-key version activation.
-- Owners may activate exactly the next version only after every active member
-- has at least one registered device and every registered active device has
-- received its immutable envelope for that version.

alter table public.worlds
    add column if not exists current_key_version integer not null default 1
    check (current_key_version > 0);

create or replace function public.get_world_key_version(p_world_id uuid)
returns integer
language plpgsql
security definer
set search_path = ''
as $get_world_key_version$
declare
    v_version integer;
begin
    if auth.uid() is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;

    select w.current_key_version into v_version
    from public.worlds w
    join public.world_members m
      on m.world_id = w.id
     and m.user_id = auth.uid()
     and m.revoked_at is null
    where w.id = p_world_id;

    if not found then
        raise exception 'active world membership required' using errcode = '42501';
    end if;

    return v_version;
end;
$get_world_key_version$;

create or replace function public.activate_world_key_version(
    p_world_id uuid,
    p_expected_version integer,
    p_new_version integer
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $activate_world_key_version$
declare
    v_current integer;
begin
    if auth.uid() is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if p_world_id is null or p_expected_version is null or p_new_version is null
       or p_expected_version < 1 or p_new_version <> p_expected_version + 1 then
        raise exception 'invalid key version transition' using errcode = '22023';
    end if;

    select w.current_key_version into v_current
    from public.worlds w
    join public.world_members owner_member
      on owner_member.world_id = w.id
     and owner_member.user_id = w.owner_id
     and owner_member.member_role = 'owner'
     and owner_member.revoked_at is null
    where w.id = p_world_id
      and w.owner_id = auth.uid()
    for update of w;

    if not found then
        raise exception 'active world owner required' using errcode = '42501';
    end if;

    if v_current <> p_expected_version then
        raise exception 'world key version changed; refresh before retrying'
            using errcode = '40001';
    end if;

    -- Do not activate a version if an active member has no registered device.
    if exists (
        select 1
        from public.world_members m
        where m.world_id = p_world_id
          and m.revoked_at is null
          and not exists (
              select 1
              from public.device_profiles d
              where d.user_id = m.user_id
                and d.public_key is not null
          )
    ) then
        raise exception 'every active member must register a device key'
            using errcode = '55000';
    end if;

    -- Every active device with a registered public key must have its own
    -- immutable recipient envelope before this version becomes current.
    if exists (
        select 1
        from public.world_members m
        join public.device_profiles d on d.user_id = m.user_id
        where m.world_id = p_world_id
          and m.revoked_at is null
          and d.public_key is not null
          and not exists (
              select 1
              from public.world_member_key_envelopes e
              where e.world_id = p_world_id
                and e.user_id = d.user_id
                and e.device_id = d.device_id
                and e.key_version = p_new_version
          )
    ) then
        raise exception 'key envelope missing for an active member device'
            using errcode = '55000';
    end if;

    update public.worlds
       set current_key_version = p_new_version
     where id = p_world_id
       and current_key_version = p_expected_version;

    if not found then
        raise exception 'world key version activation lost a concurrent update'
            using errcode = '40001';
    end if;

    return true;
end;
$activate_world_key_version$;

revoke all on function public.get_world_key_version(uuid) from public;
revoke all on function public.activate_world_key_version(uuid, integer, integer) from public;
grant execute on function public.get_world_key_version(uuid) to authenticated;
grant execute on function public.activate_world_key_version(uuid, integer, integer) to authenticated;
