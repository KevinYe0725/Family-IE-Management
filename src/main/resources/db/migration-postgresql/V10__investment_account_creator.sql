alter table investment_accounts add column created_by bigint;

update investment_accounts account
set created_by = (
    select min(app_user.id)
    from app_users app_user
    where app_user.household_id = account.household_id
)
where created_by is null;

alter table investment_accounts alter column created_by set not null;

alter table investment_accounts add constraint fk_investment_accounts_creator_household
    foreign key (created_by, household_id) references app_users(id, household_id);
