create table financial_accounts (
    id bigint auto_increment primary key,
    household_id bigint not null,
    name varchar(100) not null,
    type varchar(16) not null,
    currency varchar(3) not null,
    opening_balance_cents bigint default 0 not null,
    archived_at datetime(6),
    constraint uk_financial_accounts_household_name unique (household_id, name),
    constraint uk_financial_accounts_id_household unique (id, household_id),
    constraint ck_financial_accounts_type check (type in ('CASH', 'BANK', 'WALLET')),
    constraint ck_financial_accounts_currency check (currency = 'CNY'),
    constraint fk_financial_accounts_household foreign key (household_id) references households(id)
);

create index ix_financial_accounts_household_archived
    on financial_accounts (household_id, archived_at, id);

insert into financial_accounts (household_id, name, type, currency, opening_balance_cents)
select id, '默认账户', 'CASH', 'CNY', 0
from households;

alter table categories add column parent_id bigint;
alter table categories add constraint ck_categories_not_self_parent
    check (parent_id is null or parent_id <> id);
alter table categories add constraint uk_categories_id_household unique (id, household_id);
alter table categories add constraint uk_categories_id_household_kind unique (id, household_id, kind);
alter table categories add constraint fk_categories_parent_household_kind
    foreign key (parent_id, household_id, kind) references categories (id, household_id, kind);

create index ix_categories_household_parent_kind
    on categories (household_id, parent_id, kind, id);

alter table family_members add constraint uk_family_members_id_household
    unique (id, household_id);

alter table financial_transactions add column account_id bigint;
alter table financial_transactions add column created_by_user_id bigint;
alter table financial_transactions add column source_type varchar(16) default 'MANUAL' not null;
alter table financial_transactions add column source_id bigint;

update financial_transactions transaction_row
set account_id = (
    select min(account_row.id)
    from financial_accounts account_row
    where account_row.household_id = transaction_row.household_id
      and account_row.name = '默认账户'
);

update financial_transactions transaction_row
set created_by_user_id = coalesce(
    (
        select min(profile.linked_user_id)
        from family_members profile
        join app_users linked_user
          on linked_user.id = profile.linked_user_id
         and linked_user.household_id = profile.household_id
        where profile.id = transaction_row.member_id
          and profile.household_id = transaction_row.household_id
    ),
    (
        select min(owner_membership.user_id)
        from household_memberships owner_membership
        join app_users owner_user
          on owner_user.id = owner_membership.user_id
         and owner_user.household_id = owner_membership.household_id
        where owner_membership.household_id = transaction_row.household_id
          and owner_membership.role = 'OWNER'
    ),
    (
        select min(any_membership.user_id)
        from household_memberships any_membership
        join app_users member_user
          on member_user.id = any_membership.user_id
         and member_user.household_id = any_membership.household_id
        where any_membership.household_id = transaction_row.household_id
    ),
    (
        select min(household_user.id)
        from app_users household_user
        where household_user.household_id = transaction_row.household_id
    )
);

alter table financial_transactions modify column account_id bigint not null;
alter table financial_transactions modify column created_by_user_id bigint not null;
alter table financial_transactions add constraint uk_financial_transactions_id_household
    unique (id, household_id);
alter table financial_transactions add constraint uk_financial_transactions_source
    unique (source_type, source_id);
alter table financial_transactions add constraint ck_financial_transactions_source
    check ((source_type = 'MANUAL' and source_id is null)
        or (source_type in ('RECURRING', 'LOAN') and source_id is not null));
alter table financial_transactions add constraint fk_transactions_account_household
    foreign key (account_id, household_id) references financial_accounts (id, household_id);
alter table financial_transactions add constraint fk_transactions_creator_household
    foreign key (created_by_user_id, household_id) references app_users (id, household_id);

create index ix_financial_transactions_household_account_occurred
    on financial_transactions (household_id, account_id, occurred_on, id);
create index ix_financial_transactions_household_creator_occurred
    on financial_transactions (household_id, created_by_user_id, occurred_on, id);

create table budgets (
    id bigint auto_increment primary key,
    household_id bigint not null,
    period_month varchar(7) not null,
    scope_type varchar(16) not null,
    category_id bigint,
    member_id bigint,
    amount_cents bigint not null,
    version integer default 1 not null,
    active boolean default true not null,
    constraint uk_budgets_id_household unique (id, household_id),
    constraint uk_budgets_household_period_scope unique
        (household_id, period_month, scope_type, category_id, member_id, active),
    constraint ck_budgets_period_month
        check (regexp_like(period_month, '^[0-9]{4}-(0[1-9]|1[0-2])$')),
    constraint ck_budgets_scope check (
        (scope_type = 'TOTAL' and category_id is null and member_id is null)
        or (scope_type = 'CATEGORY' and category_id is not null and member_id is null)
        or (scope_type = 'MEMBER' and category_id is null and member_id is not null)
    ),
    constraint ck_budgets_amount check (amount_cents > 0),
    constraint ck_budgets_version check (version > 0),
    constraint fk_budgets_household foreign key (household_id) references households(id),
    constraint fk_budgets_category_household foreign key (category_id, household_id)
        references categories (id, household_id),
    constraint fk_budgets_member_household foreign key (member_id, household_id)
        references family_members (id, household_id)
);

create index ix_budgets_household_period_active
    on budgets (household_id, period_month, active, id);

create table budget_revisions (
    id bigint auto_increment primary key,
    household_id bigint not null,
    budget_id bigint not null,
    old_amount_cents bigint not null,
    new_amount_cents bigint not null,
    changed_by bigint not null,
    changed_at datetime(6) not null,
    constraint ck_budget_revisions_old_amount check (old_amount_cents > 0),
    constraint ck_budget_revisions_new_amount check (new_amount_cents > 0),
    constraint fk_budget_revisions_household foreign key (household_id) references households(id),
    constraint fk_budget_revisions_budget_household foreign key (budget_id, household_id)
        references budgets(id, household_id),
    constraint fk_budget_revisions_actor_household foreign key (changed_by, household_id)
        references app_users(id, household_id)
);

create index ix_budget_revisions_budget_changed
    on budget_revisions (household_id, budget_id, changed_at, id);

create table recurring_rules (
    id bigint auto_increment primary key,
    household_id bigint not null,
    kind varchar(16) not null,
    amount_cents bigint not null,
    schedule_type varchar(16) not null,
    interval_value integer not null,
    day_of_month integer,
    next_due_on date,
    account_id bigint not null,
    member_id bigint not null,
    category_id bigint not null,
    active boolean default true not null,
    created_by bigint not null,
    constraint uk_recurring_rules_id_household unique (id, household_id),
    constraint ck_recurring_rules_kind check (kind in ('INCOME', 'EXPENSE')),
    constraint ck_recurring_rules_amount check (amount_cents > 0),
    constraint ck_recurring_rules_interval check (interval_value > 0),
    constraint ck_recurring_rules_schedule check (
        (schedule_type = 'MONTHLY' and day_of_month between 1 and 31)
        or (schedule_type = 'WEEKLY' and day_of_month is null)
    ),
    constraint fk_recurring_rules_household foreign key (household_id) references households(id),
    constraint fk_recurring_rules_account_household foreign key (account_id, household_id)
        references financial_accounts (id, household_id),
    constraint fk_recurring_rules_member_household foreign key (member_id, household_id)
        references family_members (id, household_id),
    constraint fk_recurring_rules_category_household_kind foreign key (category_id, household_id, kind)
        references categories (id, household_id, kind),
    constraint fk_recurring_rules_creator_household foreign key (created_by, household_id)
        references app_users (id, household_id)
);

create index ix_recurring_rules_household_active_due
    on recurring_rules (household_id, active, next_due_on, id);

create table recurring_occurrences (
    id bigint auto_increment primary key,
    household_id bigint not null,
    rule_id bigint not null,
    due_on date not null,
    status varchar(16) not null,
    confirmed_transaction_id bigint,
    assigned_user_id bigint,
    constraint uk_recurring_occurrences_rule_due unique (rule_id, due_on),
    constraint uk_recurring_occurrences_transaction unique (confirmed_transaction_id),
    constraint ck_recurring_occurrences_status check (
        (status = 'CONFIRMED' and confirmed_transaction_id is not null)
        or (status in ('PENDING', 'CANCELLED') and confirmed_transaction_id is null)
    ),
    constraint fk_recurring_occurrences_household foreign key (household_id) references households(id),
    constraint fk_recurring_occurrences_rule_household foreign key (rule_id, household_id)
        references recurring_rules(id, household_id),
    constraint fk_recurring_occurrences_transaction_household
        foreign key (confirmed_transaction_id, household_id)
        references financial_transactions(id, household_id),
    constraint fk_recurring_occurrences_assignee_household foreign key (assigned_user_id, household_id)
        references app_users(id, household_id)
);

create index ix_recurring_occurrences_assignee_status_due
    on recurring_occurrences (household_id, assigned_user_id, status, due_on, id);
create index ix_recurring_occurrences_status_due
    on recurring_occurrences (household_id, status, due_on, id);
