-- Enforce the product's one shared couple world: at most two active members.
-- Serialize membership changes on the world row to prevent concurrent admissions
-- from exceeding the cap. Revoked rows remain for audit/rejoin semantics.


-- Align every admission RPC with the couple capacity. Preserve the existing
-- security-definer bodies, grants, idempotency and rate-limit behavior.
do $couple_admission_rpc_limits$
declare
    v_function regprocedure;
    v_definition text;
begin
    foreach v_function in array array[
        'public.create_world_invite(uuid,text,integer)'::regprocedure,
        'public.accept_world_invite(text)'::regprocedure,
        'public.request_join_by_device_code(text)'::regprocedure,
        'public.decide_join_request(uuid,boolean)'::regprocedure
    ]
    loop
        v_definition := pg_get_functiondef(v_function);
        if position('v_active_count >= 5' in v_definition) = 0 then
            raise exception 'expected existing member-capacity guard was not found in %', v_function;
        end if;
        v_definition := replace(v_definition, 'v_active_count >= 5', 'v_active_count >= 2');
        execute v_definition;
    end loop;
end;
$couple_admission_rpc_limits$;

create or replace function public.enforce_two_active_world_members()
returns trigger
language plpgsql
security definer
set search_path = ''
as $enforce_couple_capacity$
declare
    v_active_count integer;
begin
    if new.revoked_at is not null then
        return new;
    end if;

    perform 1
      from public.worlds w
     where w.id = new.world_id
     for update;

    if not found then
        raise exception 'world does not exist' using errcode = '23503';
    end if;

    select count(*) into v_active_count
      from public.world_members m
     where m.world_id = new.world_id
       and m.revoked_at is null
       and m.user_id is distinct from new.user_id;

    if v_active_count >= 2 then
        raise exception 'this shared world is limited to two active participants'
            using errcode = '23514';
    end if;

    return new;
end;
$enforce_couple_capacity$;

revoke all on function public.enforce_two_active_world_members() from public;

drop trigger if exists world_members_enforce_two_active on public.world_members;
create trigger world_members_enforce_two_active
before insert or update of world_id, user_id, revoked_at
on public.world_members
for each row
execute function public.enforce_two_active_world_members();
