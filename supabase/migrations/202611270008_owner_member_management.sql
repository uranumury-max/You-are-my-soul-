-- Owner-visible member labels and explicit member revocation controls.
alter table public.world_members
    add column if not exists display_name text not null default 'مشارك'
    check (char_length(display_name) between 1 and 24);

update public.world_members m
   set display_name = coalesce((
       select r.display_name
       from public.join_requests r
       where r.world_id = m.world_id
         and r.requester_id = m.user_id
         and r.status = 'approved'
       order by r.decided_at desc nulls last, r.created_at desc
       limit 1
   ), 'مشارك')
 where m.member_role = 'member';

create or replace function public.sync_approved_join_display_name()
returns trigger
language plpgsql
security definer
set search_path = ''
as $approved_member_label$
begin
    if new.status = 'approved' and old.status is distinct from new.status then
        update public.world_members
           set display_name = new.display_name
         where world_id = new.world_id
           and user_id = new.requester_id
           and member_role = 'member'
           and revoked_at is null;
    end if;
    return new;
end;
$approved_member_label$;

drop trigger if exists join_request_member_label_sync on public.join_requests;
create trigger join_request_member_label_sync
after update of status on public.join_requests
for each row execute function public.sync_approved_join_display_name();

-- Return only active non-owner members to the owner; never expose keys/tokens.
create or replace function public.list_world_active_members(p_world_id uuid)
returns table (
    member_user_id uuid,
    member_display_name text,
    member_joined_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $list_active_members$
begin
    if auth.uid() is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if not exists (
        select 1
        from public.worlds w
        join public.world_members owner_member
          on owner_member.world_id = w.id
         and owner_member.user_id = w.owner_id
         and owner_member.member_role = 'owner'
         and owner_member.revoked_at is null
        where w.id = p_world_id
          and w.owner_id = auth.uid()
    ) then
        raise exception 'active world owner required' using errcode = '42501';
    end if;

    return query
    select m.user_id, m.display_name, m.joined_at
    from public.world_members m
    where m.world_id = p_world_id
      and m.member_role = 'member'
      and m.revoked_at is null
    order by m.joined_at;
end;
$list_active_members$;

revoke all on function public.sync_approved_join_display_name() from public;
revoke all on function public.list_world_active_members(uuid) from public;
grant execute on function public.list_world_active_members(uuid) to authenticated;
