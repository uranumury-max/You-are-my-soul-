-- Route all client event writes through one idempotent, membership-checked RPC.
-- The previous direct INSERT policy allowed authenticated members to bypass
-- server-side validation and per-account write throttling.

drop policy if exists events_insert_member on public.world_events;

create table if not exists public.world_event_rate_limits (
    user_id uuid primary key references auth.users(id) on delete cascade,
    window_started_at timestamptz not null,
    event_count integer not null default 0 check (event_count >= 0)
);

alter table public.world_event_rate_limits enable row level security;
revoke all on public.world_event_rate_limits from anon, authenticated;

create or replace function public.append_world_event(
    p_world_id uuid,
    p_event_id uuid,
    p_event_type text,
    p_ciphertext text,
    p_client_created_at timestamptz
)
returns bigint
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_actor_id uuid;
    v_count integer;
    v_server_seq bigint;
    v_existing public.world_events%rowtype;
begin
    v_actor_id := auth.uid();
    if v_actor_id is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;

    if p_world_id is null or p_event_id is null
       or (p_client_created_at is null or p_client_created_at < timestamptz '2000-01-01 00:00:00+00' or p_client_created_at > now() + interval '24 hours') then
        raise exception 'invalid event metadata' using errcode = '22023';
    end if;

    if p_event_type is null or p_event_type not in (
        'world_update', 'memory_create', 'memory_delete',
        'message_create', 'garden_update', 'daily_answer',
        'drawing_update', 'capsule_update', 'media_add', 'settings_update'
    ) then
        raise exception 'unsupported event type' using errcode = '22023';
    end if;

    -- Accept only the versioned AES-GCM envelope emitted by WorldContentCipher.
    -- This prevents accidental plaintext/raw-Base64 writes from entering the event log.
    if p_ciphertext is null
       or char_length(p_ciphertext) < 47
       or p_ciphertext !~ '^wce:v1:[1-9][0-9]*:[A-Za-z0-9+/]+

    if not exists (
        select 1 from public.world_members m
        where m.world_id = p_world_id
          and m.user_id = v_actor_id
          and m.revoked_at is null
    ) then
        raise exception 'active world membership required' using errcode = '42501';
    end if;

    -- Serialize retries of the same event ID. Without this transaction-scoped
    -- lock, two simultaneous first attempts could both pass the idempotency
    -- lookup and the losing insert could consume rate-limit quota.
    perform pg_advisory_xact_lock(
        hashtextextended(p_world_id::text || ':' || p_event_id::text, 0)
    );

    -- Limit one authenticated account to 120 new events per minute window.
    -- Retries of an already committed event do not consume additional quota.
    select e.server_seq into v_server_seq
    from public.world_events e
    where e.world_id = p_world_id and e.event_id = p_event_id;

    if v_server_seq is not null then
        select * into v_existing
        from public.world_events e
        where e.world_id = p_world_id and e.event_id = p_event_id;

        if v_existing.actor_id <> v_actor_id
           or v_existing.event_type <> p_event_type
           or v_existing.ciphertext <> p_ciphertext
           or v_existing.client_created_at <> p_client_created_at then
            raise exception 'event ID reused with different content' using errcode = '23505';
        end if;
        return v_server_seq;
    end if;

    insert into public.world_event_rate_limits(user_id, window_started_at, event_count)
    values (v_actor_id, now(), 1)
    on conflict (user_id) do update
    set window_started_at = case
            when public.world_event_rate_limits.window_started_at <= now() - interval '1 minute'
            then now()
            else public.world_event_rate_limits.window_started_at
        end,
        event_count = case
            when public.world_event_rate_limits.window_started_at <= now() - interval '1 minute'
            then 1
            else public.world_event_rate_limits.event_count + 1
        end
    returning event_count into v_count;

    if v_count > 120 then
        raise exception 'event rate limit exceeded' using errcode = '53300';
    end if;

    insert into public.world_events(
        world_id, event_id, actor_id, event_type, ciphertext, client_created_at
    )
    values (
        p_world_id, p_event_id, v_actor_id, p_event_type, p_ciphertext, p_client_created_at
    )
    on conflict (world_id, event_id) do nothing
    returning server_seq into v_server_seq;

    if v_server_seq is null then
        select * into v_existing
        from public.world_events e
        where e.world_id = p_world_id and e.event_id = p_event_id;

        if v_existing.actor_id <> v_actor_id
           or v_existing.event_type <> p_event_type
           or v_existing.ciphertext <> p_ciphertext
           or v_existing.client_created_at <> p_client_created_at then
            raise exception 'event ID reused with different content' using errcode = '23505';
        end if;
        return v_existing.server_seq;
    end if;

    return v_server_seq;
end;
$$;

revoke all on function public.append_world_event(uuid, uuid, text, text, timestamptz) from public;
grant execute on function public.append_world_event(uuid, uuid, text, text, timestamptz) to authenticated;

       or octet_length(convert_to(p_ciphertext, 'UTF8')) not between 32 and 1048576 then
        raise exception 'invalid encrypted payload envelope' using errcode = '22023';
    end if;

    if not exists (
        select 1 from public.world_members m
        where m.world_id = p_world_id
          and m.user_id = v_actor_id
          and m.revoked_at is null
    ) then
        raise exception 'active world membership required' using errcode = '42501';
    end if;

    -- Serialize retries of the same event ID. Without this transaction-scoped
    -- lock, two simultaneous first attempts could both pass the idempotency
    -- lookup and the losing insert could consume rate-limit quota.
    perform pg_advisory_xact_lock(
        hashtextextended(p_world_id::text || ':' || p_event_id::text, 0)
    );

    -- Limit one authenticated account to 120 new events per minute window.
    -- Retries of an already committed event do not consume additional quota.
    select e.server_seq into v_server_seq
    from public.world_events e
    where e.world_id = p_world_id and e.event_id = p_event_id;

    if v_server_seq is not null then
        select * into v_existing
        from public.world_events e
        where e.world_id = p_world_id and e.event_id = p_event_id;

        if v_existing.actor_id <> v_actor_id
           or v_existing.event_type <> p_event_type
           or v_existing.ciphertext <> p_ciphertext
           or v_existing.client_created_at <> p_client_created_at then
            raise exception 'event ID reused with different content' using errcode = '23505';
        end if;
        return v_server_seq;
    end if;

    insert into public.world_event_rate_limits(user_id, window_started_at, event_count)
    values (v_actor_id, now(), 1)
    on conflict (user_id) do update
    set window_started_at = case
            when public.world_event_rate_limits.window_started_at <= now() - interval '1 minute'
            then now()
            else public.world_event_rate_limits.window_started_at
        end,
        event_count = case
            when public.world_event_rate_limits.window_started_at <= now() - interval '1 minute'
            then 1
            else public.world_event_rate_limits.event_count + 1
        end
    returning event_count into v_count;

    if v_count > 120 then
        raise exception 'event rate limit exceeded' using errcode = '53300';
    end if;

    insert into public.world_events(
        world_id, event_id, actor_id, event_type, ciphertext, client_created_at
    )
    values (
        p_world_id, p_event_id, v_actor_id, p_event_type, p_ciphertext, p_client_created_at
    )
    on conflict (world_id, event_id) do nothing
    returning server_seq into v_server_seq;

    if v_server_seq is null then
        select * into v_existing
        from public.world_events e
        where e.world_id = p_world_id and e.event_id = p_event_id;

        if v_existing.actor_id <> v_actor_id
           or v_existing.event_type <> p_event_type
           or v_existing.ciphertext <> p_ciphertext
           or v_existing.client_created_at <> p_client_created_at then
            raise exception 'event ID reused with different content' using errcode = '23505';
        end if;
        return v_existing.server_seq;
    end if;

    return v_server_seq;
end;
$$;

revoke all on function public.append_world_event(uuid, uuid, text, text, timestamptz) from public;
grant execute on function public.append_world_event(uuid, uuid, text, text, timestamptz) to authenticated;
