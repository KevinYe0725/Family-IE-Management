-- Fail closed with named, searchable guard constraints before installing the
-- stronger relationships. Existing V6 databases can contain these invalid
-- combinations because the original Stage 1 foreign keys were ID-only.
create table v7_transaction_member_guard (
    violation_count bigint not null,
    constraint ck_v7_transaction_member_household_guard check (violation_count = 0)
);
insert into v7_transaction_member_guard (violation_count)
select count(*)
from financial_transactions transaction_row
left join family_members member_row
  on member_row.id = transaction_row.member_id
 and member_row.household_id = transaction_row.household_id
where member_row.id is null;
drop table v7_transaction_member_guard;

create table v7_transaction_category_guard (
    violation_count bigint not null,
    constraint ck_v7_transaction_category_kind_guard check (violation_count = 0)
);
insert into v7_transaction_category_guard (violation_count)
select count(*)
from financial_transactions transaction_row
left join categories category_row
  on category_row.id = transaction_row.category_id
 and category_row.household_id = transaction_row.household_id
 and category_row.kind = transaction_row.kind
where category_row.id is null;
drop table v7_transaction_category_guard;

create table v7_budget_category_guard (
    violation_count bigint not null,
    constraint ck_v7_budget_category_kind_guard check (violation_count = 0)
);
insert into v7_budget_category_guard (violation_count)
select
    (select count(*)
       from budgets budget_row
       left join categories category_row
         on category_row.id = budget_row.category_id
        and category_row.household_id = budget_row.household_id
        and category_row.kind = 'EXPENSE'
      where budget_row.category_id is not null
        and category_row.id is null)
  + (select count(*)
       from budget_revisions revision_row
       left join categories category_row
         on category_row.id = revision_row.old_category_id
        and category_row.household_id = revision_row.household_id
        and category_row.kind = 'EXPENSE'
      where revision_row.old_category_id is not null
        and category_row.id is null)
  + (select count(*)
       from budget_revisions revision_row
       left join categories category_row
         on category_row.id = revision_row.new_category_id
        and category_row.household_id = revision_row.household_id
        and category_row.kind = 'EXPENSE'
      where revision_row.new_category_id is not null
        and category_row.id is null);
drop table v7_budget_category_guard;

alter table financial_transactions add constraint fk_transactions_member_household_v7
    foreign key (member_id, household_id) references family_members (id, household_id);
alter table financial_transactions add constraint fk_transactions_category_household_kind_v7
    foreign key (category_id, household_id, kind) references categories (id, household_id, kind);

-- CATEGORY budgets and their immutable snapshots always target expense
-- categories. Nullable generated columns retain MATCH SIMPLE behavior for
-- TOTAL and MEMBER scopes.
alter table budgets add column category_kind_v7 varchar(16) generated always as (
    case when category_id is null then null else 'EXPENSE' end
);
alter table budgets add constraint fk_budgets_category_household_kind_v7
    foreign key (category_id, household_id, category_kind_v7)
    references categories (id, household_id, kind);

alter table budget_revisions add column old_category_kind_v7 varchar(16) generated always as (
    case when old_category_id is null then null else 'EXPENSE' end
);
alter table budget_revisions add column new_category_kind_v7 varchar(16) generated always as (
    case when new_category_id is null then null else 'EXPENSE' end
);
alter table budget_revisions add constraint fk_budget_revisions_old_category_kind_v7
    foreign key (old_category_id, household_id, old_category_kind_v7)
    references categories (id, household_id, kind);
alter table budget_revisions add constraint fk_budget_revisions_new_category_kind_v7
    foreign key (new_category_id, household_id, new_category_kind_v7)
    references categories (id, household_id, kind);
