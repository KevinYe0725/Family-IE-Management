alter table budgets drop index uk_budgets_household_period_scope;
alter table budgets drop check ck_budgets_amount;
alter table budgets add constraint ck_budgets_amount
    check (amount_cents > 0 and amount_cents <= 99999999999);
alter table budgets add column scope_target_key bigint generated always as (
    coalesce(category_id, member_id, cast(-1 as signed))
) stored;
alter table budgets add column active_uniqueness boolean generated always as (
    case when active then true else null end
) stored;
alter table budgets add constraint uk_budgets_household_period_active_scope
    unique (household_id, period_month, scope_type, scope_target_key, active_uniqueness);

alter table budget_revisions add column old_period_month varchar(7);
alter table budget_revisions add column new_period_month varchar(7);
alter table budget_revisions add column old_scope_type varchar(16);
alter table budget_revisions add column new_scope_type varchar(16);
alter table budget_revisions add column old_category_id bigint;
alter table budget_revisions add column new_category_id bigint;
alter table budget_revisions add column old_member_id bigint;
alter table budget_revisions add column new_member_id bigint;
alter table budget_revisions add column old_active boolean;
alter table budget_revisions add column new_active boolean;

alter table budget_revisions drop check ck_budget_revisions_old_amount;
alter table budget_revisions drop check ck_budget_revisions_new_amount;
alter table budget_revisions add constraint ck_budget_revisions_old_amount
    check (old_amount_cents > 0 and old_amount_cents <= 99999999999);
alter table budget_revisions add constraint ck_budget_revisions_new_amount
    check (new_amount_cents > 0 and new_amount_cents <= 99999999999);

update budget_revisions revision
set old_period_month = (select budget.period_month from budgets budget where budget.id = revision.budget_id),
    new_period_month = (select budget.period_month from budgets budget where budget.id = revision.budget_id),
    old_scope_type = (select budget.scope_type from budgets budget where budget.id = revision.budget_id),
    new_scope_type = (select budget.scope_type from budgets budget where budget.id = revision.budget_id),
    old_category_id = (select budget.category_id from budgets budget where budget.id = revision.budget_id),
    new_category_id = (select budget.category_id from budgets budget where budget.id = revision.budget_id),
    old_member_id = (select budget.member_id from budgets budget where budget.id = revision.budget_id),
    new_member_id = (select budget.member_id from budgets budget where budget.id = revision.budget_id),
    old_active = (select budget.active from budgets budget where budget.id = revision.budget_id),
    new_active = (select budget.active from budgets budget where budget.id = revision.budget_id);

alter table budget_revisions modify column old_period_month varchar(7) not null;
alter table budget_revisions modify column new_period_month varchar(7) not null;
alter table budget_revisions modify column old_scope_type varchar(16) not null;
alter table budget_revisions modify column new_scope_type varchar(16) not null;
alter table budget_revisions modify column old_active boolean not null;
alter table budget_revisions modify column new_active boolean not null;

alter table budget_revisions add constraint ck_budget_revisions_old_period_month
    check (regexp_like(old_period_month, '^[0-9]{4}-(0[1-9]|1[0-2])$'));
alter table budget_revisions add constraint ck_budget_revisions_new_period_month
    check (regexp_like(new_period_month, '^[0-9]{4}-(0[1-9]|1[0-2])$'));
alter table budget_revisions add constraint ck_budget_revisions_old_scope check (
    (old_scope_type = 'TOTAL' and old_category_id is null and old_member_id is null)
    or (old_scope_type = 'CATEGORY' and old_category_id is not null and old_member_id is null)
    or (old_scope_type = 'MEMBER' and old_category_id is null and old_member_id is not null)
);
alter table budget_revisions add constraint ck_budget_revisions_new_scope check (
    (new_scope_type = 'TOTAL' and new_category_id is null and new_member_id is null)
    or (new_scope_type = 'CATEGORY' and new_category_id is not null and new_member_id is null)
    or (new_scope_type = 'MEMBER' and new_category_id is null and new_member_id is not null)
);
alter table budget_revisions add constraint fk_budget_revisions_old_category_household
    foreign key (old_category_id, household_id) references categories(id, household_id);
alter table budget_revisions add constraint fk_budget_revisions_new_category_household
    foreign key (new_category_id, household_id) references categories(id, household_id);
alter table budget_revisions add constraint fk_budget_revisions_old_member_household
    foreign key (old_member_id, household_id) references family_members(id, household_id);
alter table budget_revisions add constraint fk_budget_revisions_new_member_household
    foreign key (new_member_id, household_id) references family_members(id, household_id);
