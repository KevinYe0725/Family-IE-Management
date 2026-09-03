create table loans (
    id bigint auto_increment primary key,
    household_id bigint not null,
    name varchar(100) not null,
    loan_type varchar(16) not null,
    linked_asset_id bigint,
    member_id bigint,
    assigned_user_id bigint,
    payment_account_id bigint not null,
    payment_category_id bigint not null,
    payment_category_kind varchar(16) generated always as ('EXPENSE') stored,
    principal_cents bigint not null,
    annual_rate decimal(9, 6) not null,
    term_months integer not null,
    repayment_method varchar(16) not null,
    start_on date not null,
    current_principal_cents bigint not null,
    status varchar(16) not null,
    created_by bigint not null,
    archived_at datetime(6),
    constraint uk_loans_id_household unique (id, household_id),
    constraint ck_loans_name check (length(trim(name)) > 0),
    constraint ck_loans_type check (loan_type in ('MORTGAGE', 'CAR', 'OTHER')),
    constraint ck_loans_principal check (principal_cents > 0 and principal_cents <= 99999999999),
    constraint ck_loans_current_principal check (current_principal_cents >= 0 and current_principal_cents <= principal_cents),
    constraint ck_loans_rate check (annual_rate >= 0 and annual_rate <= 1),
    constraint ck_loans_term check (term_months between 1 and 360),
    constraint ck_loans_method check (repayment_method in ('EQUAL_PAYMENT', 'EQUAL_PRINCIPAL', 'CUSTOM')),
    constraint ck_loans_status check (
        (status = 'ACTIVE' and archived_at is null)
        or (status in ('ARCHIVED', 'CLOSED') and archived_at is not null)
    ),
    constraint fk_loans_household foreign key (household_id) references households(id),
    constraint fk_loans_asset_household foreign key (linked_asset_id, household_id) references assets(id, household_id),
    constraint fk_loans_member_household foreign key (member_id, household_id) references family_members(id, household_id),
    constraint fk_loans_assignee_household foreign key (assigned_user_id, household_id) references app_users(id, household_id),
    constraint fk_loans_account_household foreign key (payment_account_id, household_id) references financial_accounts(id, household_id),
    constraint fk_loans_category_household_kind foreign key (payment_category_id, household_id, payment_category_kind)
        references categories(id, household_id, kind),
    constraint fk_loans_creator_household foreign key (created_by, household_id) references app_users(id, household_id)
);
create index ix_loans_household_status on loans (household_id, status, id);
create index ix_loans_household_assignee_status on loans (household_id, assigned_user_id, status, id);

create table loan_installments (
    id bigint auto_increment primary key,
    household_id bigint not null,
    loan_id bigint not null,
    installment_no integer not null,
    due_on date not null,
    principal_cents bigint not null,
    interest_cents bigint not null,
    status varchar(16) default 'PENDING' not null,
    confirmed_transaction_id bigint,
    constraint uk_loan_installments_loan_number unique (loan_id, installment_no),
    constraint uk_loan_installments_transaction unique (confirmed_transaction_id),
    constraint ck_loan_installments_number check (installment_no > 0),
    constraint ck_loan_installments_principal check (principal_cents > 0),
    constraint ck_loan_installments_interest check (interest_cents >= 0),
    constraint ck_loan_installments_status check (
        (status = 'PAID' and confirmed_transaction_id is not null)
        or (status in ('PENDING', 'CANCELLED') and confirmed_transaction_id is null)
    ),
    constraint fk_loan_installments_household foreign key (household_id) references households(id),
    constraint fk_loan_installments_loan_household foreign key (loan_id, household_id) references loans(id, household_id),
    constraint fk_loan_installments_transaction_household foreign key (confirmed_transaction_id, household_id)
        references financial_transactions(id, household_id)
);
create index ix_loan_installments_household_status_due on loan_installments (household_id, status, due_on, id);
create index ix_loan_installments_loan_due on loan_installments (loan_id, due_on, installment_no);

create table notifications (
    id bigint auto_increment primary key,
    household_id bigint not null,
    user_id bigint,
    type varchar(32) not null,
    title varchar(160) not null,
    body varchar(1000),
    reference_type varchar(32) not null,
    reference_id bigint not null,
    due_at datetime(6),
    read_at datetime(6),
    resolved_at datetime(6),
    constraint uk_notifications_natural unique (type, reference_type, reference_id, user_id),
    constraint ck_notifications_title check (length(trim(title)) > 0),
    constraint fk_notifications_household foreign key (household_id) references households(id),
    constraint fk_notifications_user_household foreign key (user_id, household_id) references app_users(id, household_id)
);
create index ix_notifications_household_user_open_due on notifications (household_id, user_id, resolved_at, due_at, id);

create table net_worth_snapshots (
    id bigint auto_increment primary key,
    household_id bigint not null,
    snapshot_on date not null,
    asset_cents bigint not null,
    liability_cents bigint not null,
    net_worth_cents bigint not null,
    constraint uk_net_worth_snapshots_household_day unique (household_id, snapshot_on),
    constraint fk_net_worth_snapshots_household foreign key (household_id) references households(id)
);
create index ix_net_worth_snapshots_household_date on net_worth_snapshots (household_id, snapshot_on desc, id desc);
