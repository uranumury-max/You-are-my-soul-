-- Preserve the requested participant display name so the owner can identify
-- a pending request without exposing the requester's account credentials.
alter table public.join_requests
    add column if not exists display_name text not null default 'مشارك'
    check (char_length(display_name) between 1 and 24);

-- Replace the original one-argument RPC with a validated display-name variant.
drop function if exists public.request_join_by_device_code(text);

create or replace function public.request_join_by_device_code(
    p_device_code text,
    p_display_name text default 'مشارك'
)
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
    v_display_name text;
begin
    if auth.uid() is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;

    v_display_name := btrim(coalesce(p_display_name, ''));
    if char_length(v_display_name) not between 1 and 24
       or v_display_name ~ '[[:cntrl:]]' then
        raise exception 'invalid participant display name' using errcode = '22023';
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

    update public.join_requests
    set status = 'expired'
    where world_id = v_world_id
      and requester_id = auth.uid()
      and status = 'pending'
      and expires_at <= now();

    insert into public.join_requests(
        world_id, requester_id, target_device_id, display_name
    )
    values (
        v_world_id, auth.uid(), v_target_device, v_display_name
    )
    on conflict (world_id, requester_id) where status = 'pending'
    do update set
        target_device_id = excluded.target_device_id,
        display_name = excluded.display_name
    returning id into v_request_id;

    return v_request_id;
end;
$$;

revoke all on function public.request_join_by_device_code(text, text) from public;
grant execute on function public.request_join_by_device_code(text, text) to authenticated;
