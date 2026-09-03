-- Remove links that cannot represent a member profile in the user's household.
update family_members
set linked_user_id = null
where linked_user_id is not null
  and not exists (
      select 1
      from app_users
      where app_users.id = family_members.linked_user_id
        and app_users.household_id = family_members.household_id
  );

-- V2 used the mutable, non-unique display name "Kevin". Clear every demo link
-- before deterministically choosing the oldest profile by primary key.
update family_members
set linked_user_id = null
where linked_user_id in (
    select id
    from app_users
    where username = 'demo'
);

-- A valid legacy household may have no profile at all. Create one using fixed,
-- safe values; the following update then selects it as the household minimum.
insert into family_members (household_id, linked_user_id, name, role_label, created_at)
select app_users.household_id, null, '演示用户', '所有者', app_users.created_at
from app_users
where app_users.username = 'demo'
  and not exists (
      select 1
      from family_members
      where family_members.household_id = app_users.household_id
  );

update family_members
set linked_user_id = (
    select id
    from app_users
    where username = 'demo'
)
where id = (
    select chosen.profile_id
    from (
        select min(family_members.id) as profile_id
        from family_members
        join app_users on app_users.household_id = family_members.household_id
        where app_users.username = 'demo'
    ) chosen
);

-- One login account maps to at most one profile inside a household. H2's
-- standard unique-index semantics permit any number of unlinked NULL profiles.
create unique index uk_family_members_household_linked_user
    on family_members (household_id, linked_user_id);

-- Preserve the household boundary at the database layer, not only in services.
alter table app_users add constraint uk_app_users_id_household
    unique (id, household_id);

alter table family_members add constraint fk_family_members_linked_user_household
    foreign key (linked_user_id, household_id) references app_users (id, household_id);
