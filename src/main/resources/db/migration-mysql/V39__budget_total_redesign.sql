-- V39: budget redesign — monthly total budget, per-budget note, member×category
-- observation rows (CATEGORY_MEMBER), legacy TOTAL removal.
-- MySQL dialect. Mirrors db/migration/V39__budget_total_redesign.sql for H2.

-- 1) Budget rows carry an optional note.
alter table budgets add column note varchar(200);

-- 2) Revisions carry old/new note for traceability.
alter table budget_revisions add column old_note varchar(200);
alter table budget_revisions add column new_note varchar(200);

-- 3) Scope model now allows (category, member) observation rows.
alter table budgets drop check ck_budgets_scope;
alter table budgets add constraint ck_budgets_scope check (
    (scope_type = 'TOTAL' and category_id is null and member_id is null)
    or (scope_type = 'CATEGORY' and category_id is not null and member_id is null)
    or (scope_type = 'MEMBER' and category_id is null and member_id is not null)
    or (scope_type = 'CATEGORY_MEMBER' and category_id is not null and member_id is not null)
);

alter table budget_revisions drop check ck_budget_revisions_old_scope;
alter table budget_revisions drop check ck_budget_revisions_new_scope;
alter table budget_revisions add constraint ck_budget_revisions_old_scope check (
    (old_scope_type = 'TOTAL' and old_category_id is null and old_member_id is null)
    or (old_scope_type = 'CATEGORY' and old_category_id is not null and old_member_id is null)
    or (old_scope_type = 'MEMBER' and old_category_id is null and old_member_id is not null)
    or (old_scope_type = 'CATEGORY_MEMBER' and old_category_id is not null and old_member_id is not null)
);
alter table budget_revisions add constraint ck_budget_revisions_new_scope check (
    (new_scope_type = 'TOTAL' and new_category_id is null and new_member_id is null)
    or (new_scope_type = 'CATEGORY' and new_category_id is not null and new_member_id is null)
    or (new_scope_type = 'MEMBER' and new_category_id is null and new_member_id is not null)
    or (new_scope_type = 'CATEGORY_MEMBER' and new_category_id is not null and new_member_id is not null)
);

-- 4) Monthly total budget: one authoritative value per household and month.
create table budget_monthly_totals (
    id bigint auto_increment primary key,
    household_id bigint not null,
    period_month varchar(7) not null,
    amount_cents bigint not null,
    version integer default 0 not null,
    updated_by bigint,
    updated_at datetime(6),
    constraint uk_budget_totals_household_month unique (household_id, period_month),
    constraint uk_budget_totals_id_household unique (id, household_id),
    constraint ck_budget_totals_period_month
        check (regexp_like(period_month, '^[0-9]{4}-(0[1-9]|1[0-2])$')),
    constraint ck_budget_totals_amount
        check (amount_cents > 0 and amount_cents <= 99999999999),
    constraint fk_budget_totals_household foreign key (household_id) references households(id)
);

-- 5) Immutable audit trail for total-budget changes.
create table budget_total_revisions (
    id bigint auto_increment primary key,
    household_id bigint not null,
    period_month varchar(7) not null,
    old_amount_cents bigint not null,
    new_amount_cents bigint not null,
    changed_by bigint not null,
    changed_at datetime(6) not null,
    constraint ck_budget_total_revisions_period_month
        check (regexp_like(period_month, '^[0-9]{4}-(0[1-9]|1[0-2])$')),
    constraint ck_budget_total_revisions_old_amount
        check (old_amount_cents > 0 and old_amount_cents <= 99999999999),
    constraint ck_budget_total_revisions_new_amount
        check (new_amount_cents > 0 and new_amount_cents <= 99999999999),
    constraint fk_budget_total_revisions_household
        foreign key (household_id) references households(id),
    constraint fk_budget_total_revisions_actor
        foreign key (changed_by, household_id) references app_users (id, household_id)
);

create index ix_budget_total_revisions_month on budget_total_revisions (household_id, period_month, id);

-- 6) Legacy TOTAL budgets: migrate the active value into the monthly total, then remove.
insert into budget_monthly_totals (household_id, period_month, amount_cents, version)
select household_id, period_month, amount_cents, 0
from budgets
where scope_type = 'TOTAL' and active = true;

delete from budget_revisions
where budget_id in (select id from budgets where scope_type = 'TOTAL');
delete from budgets where scope_type = 'TOTAL';
