-- Canonical, fixed-size RSA-2048 X.509 device public-key registration.
-- Reject alternate Base64 spellings and keys outside the envelope protocol.

create or replace function public.register_device_public_key(
    p_device_id uuid,
    p_public_key text
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $device_public_key_canonical$
declare
    v_decoded bytea;
    v_canonical text;
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

    -- Android Keystore RSA-2048 SubjectPublicKeyInfo is 294 DER bytes.
    -- The exchange protocol requires a 256-byte RSA ciphertext, so smaller
    -- and larger keys are incompatible and must not enter the device roster.
    if octet_length(v_decoded) <> 294 then
        raise exception 'expected RSA-2048 X.509 public key' using errcode = '22023';
    end if;

    v_canonical := replace(encode(v_decoded, 'base64'), E'\\n', '');
    if v_canonical <> p_public_key then
        raise exception 'non-canonical public key encoding' using errcode = '22023';
    end if;

    update public.device_profiles
       set public_key = p_public_key,
           last_seen_at = now()
     where device_id = p_device_id
       and user_id = auth.uid();

    return found;
end;
$device_public_key_canonical$;

revoke all on function public.register_device_public_key(uuid, text) from public;
grant execute on function public.register_device_public_key(uuid, text) to authenticated;
