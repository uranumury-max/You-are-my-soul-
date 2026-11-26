-- Device public-key registration for authenticated end-to-end key exchange.
-- Public keys are not secrets; private keys remain non-exportable in Android Keystore.

alter table public.device_profiles
    add column if not exists public_key text;

create or replace function public.register_device_public_key(p_device_id uuid, p_public_key text)
returns boolean
language plpgsql
security definer
set search_path = ''
as $device_public_key$
declare
    v_decoded bytea;
begin
    if auth.uid() is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if p_public_key is null
       or char_length(p_public_key) > 2048
       or p_public_key !~ '^[A-Za-z0-9+/]+={0,2}$' then
        raise exception 'invalid public key encoding' using errcode = '22023';
    end if;

    begin
        v_decoded := decode(p_public_key, 'base64');
    exception when others then
        raise exception 'invalid public key encoding' using errcode = '22023';
    end;

    -- RSA-2048 X.509 SubjectPublicKeyInfo is normally ~294 bytes.
    if octet_length(v_decoded) < 256 or octet_length(v_decoded) > 1024 then
        raise exception 'invalid public key size' using errcode = '22023';
    end if;

    update public.device_profiles
       set public_key = p_public_key,
           last_seen_at = now()
     where device_id = p_device_id
       and user_id = auth.uid();

    return found;
end;
$device_public_key$;

revoke all on function public.register_device_public_key(uuid, text) from public;
grant execute on function public.register_device_public_key(uuid, text) to authenticated;
