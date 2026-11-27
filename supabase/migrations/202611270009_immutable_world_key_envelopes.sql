-- Make each recipient envelope immutable within a world-key version.
-- Re-publishing identical ciphertext is idempotent; changing it requires a
-- new key version and an explicit rotation workflow.
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
as $publish_key_envelope_immutable$
declare
    v_world_owner uuid;
    v_ciphertext bytea;
    v_existing_user uuid;
    v_existing_envelope text;
begin
    if auth.uid() is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if p_world_id is null or p_user_id is null or p_device_id is null
       or p_key_version is null or p_key_version < 1
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
    on conflict (world_id, device_id, key_version) do nothing;

    select e.user_id, e.envelope
      into v_existing_user, v_existing_envelope
      from public.world_member_key_envelopes e
     where e.world_id = p_world_id
       and e.device_id = p_device_id
       and e.key_version = p_key_version
     for update;

    if not found then
        raise exception 'key envelope publication was not persisted'
            using errcode = '40001';
    end if;

    if v_existing_user <> p_user_id or v_existing_envelope <> p_envelope then
        raise exception 'key envelope version is immutable; publish a new key version'
            using errcode = '55000';
    end if;

    return true;
end;
$publish_key_envelope_immutable$;

revoke all on function public.publish_world_key_envelope(uuid, uuid, uuid, integer, text) from public;
grant execute on function public.publish_world_key_envelope(uuid, uuid, uuid, integer, text) to authenticated;
