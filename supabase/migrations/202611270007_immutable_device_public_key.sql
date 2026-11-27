-- Make a registered device's RSA identity immutable until an explicit,
-- audited key-rotation/recovery workflow is implemented. This prevents a
-- repeated registration call from silently replacing the recipient key and
-- stranding previously distributed world-key envelopes.
create or replace function public.register_device_public_key(
    p_device_id uuid,
    p_public_key text
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $immutable_device_key$
declare
    v_existing_key text;
    v_decoded bytea;
begin
    if auth.uid() is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if p_device_id is null
       or p_public_key is null
       or char_length(p_public_key) > 2048
       or p_public_key !~ '^[A-Za-z0-9+/]+={0,2}$' then
        raise exception 'invalid public key encoding' using errcode = '22023';
    end if;

    begin
        v_decoded := decode(p_public_key, 'base64');
    exception when others then
        raise exception 'invalid public key encoding' using errcode = '22023';
    end;
    if octet_length(v_decoded) < 256 or octet_length(v_decoded) > 1024 then
        raise exception 'invalid public key size' using errcode = '22023';
    end;

    select d.public_key
      into v_existing_key
      from public.device_profiles d
     where d.device_id = p_device_id
       and d.user_id = auth.uid()
     for update;

    if not found then
        return false;
    end if;

    if v_existing_key is not null and v_existing_key <> p_public_key then
        raise exception 'device public key is immutable; explicit key rotation required'
            using errcode = '55000';
    end if;

    update public.device_profiles
       set public_key = coalesce(public_key, p_public_key),
           last_seen_at = now()
     where device_id = p_device_id
       and user_id = auth.uid();

    return found;
end;
$immutable_device_key$;

revoke all on function public.register_device_public_key(uuid, text) from public;
grant execute on function public.register_device_public_key(uuid, text) to authenticated;
