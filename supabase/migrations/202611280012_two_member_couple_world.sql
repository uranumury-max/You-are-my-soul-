-- Enforce the product's one shared couple world: at most two active members.
-- Serialize membership changes on the world row to prevent concurrent admissions
-- from exceeding the cap. Revoked rows remain for audit/rejoin semantics.

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
