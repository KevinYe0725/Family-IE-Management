create table investment_plans (
 id bigint auto_increment primary key,
 household_id bigint not null, name varchar(100) not null,
 account_id bigint not null, account_name varchar(100) not null,
 funding_account_id bigint not null, security_id bigint not null,
 security_name varchar(200) not null, symbol varchar(64) not null, currency varchar(3) not null,
 amount decimal(13,2) not null, frequency varchar(16) not null,
 first_due_on date not null, next_due_on date not null,
 assigned_user_id bigint not null, state varchar(16) not null,
 created_by bigint not null, updated_by bigint not null,
 created_at timestamp(6) not null, updated_at timestamp(6) not null,
 constraint uk_investment_plan_household unique(id,household_id),
 constraint ck_investment_plan_amount check(amount>0 and amount<=999999999.99),
 constraint ck_investment_plan_frequency check(frequency in ('WEEKLY','BIWEEKLY','MONTHLY')),
 constraint ck_investment_plan_state check(state in ('ACTIVE','PAUSED','ENDED')),
 constraint fk_ip_household foreign key(household_id) references households(id),
 constraint fk_ip_account foreign key(account_id,household_id) references investment_accounts(id,household_id),
 constraint fk_ip_funding foreign key(funding_account_id,household_id) references financial_accounts(id,household_id),
 constraint fk_ip_security foreign key(security_id) references securities(id),
 constraint fk_ip_assignee foreign key(household_id,assigned_user_id) references household_memberships(household_id,user_id),
 constraint fk_ip_creator foreign key(created_by) references app_users(id),
 constraint fk_ip_updater foreign key(updated_by) references app_users(id)
);
create index ix_ip_due on investment_plans(household_id,state,next_due_on,id);
create table investment_plan_occurrences (
 id bigint auto_increment primary key,
 household_id bigint not null, plan_id bigint not null, plan_name varchar(100) not null,
 account_id bigint not null, account_name varchar(100) not null, funding_account_id bigint not null,
 security_id bigint not null, security_name varchar(200) not null, symbol varchar(64) not null, currency varchar(3) not null,
 amount decimal(13,2) not null, due_on date not null, assigned_user_id bigint not null,
 state varchar(16) not null, remind_at timestamp(6) not null, notification_pending boolean not null default true,
 trade_id bigint, actual_amount decimal(13,2), reason varchar(500),
 acted_by bigint, acted_at timestamp(6), snoozed_by bigint, snoozed_at timestamp(6),
 constraint uk_ip_occurrence_due unique(plan_id,due_on),
 -- A durable trade identity survives physical trade deletion after journal reversal.
 constraint uk_ip_occurrence_trade unique(trade_id),
 constraint ck_ipo_amount check(amount>0 and amount<=999999999.99),
 constraint ck_ipo_state check(
  (state='PENDING' and trade_id is null and actual_amount is null and acted_by is null and acted_at is null)
  or (state='SKIPPED' and trade_id is null and actual_amount is null and acted_by is not null and acted_at is not null)
  or (state='CONFIRMED' and trade_id is not null and actual_amount is not null and actual_amount>0 and acted_by is not null and acted_at is not null)),
 constraint fk_ipo_plan foreign key(plan_id,household_id) references investment_plans(id,household_id),
 constraint fk_ipo_account foreign key(account_id,household_id) references investment_accounts(id,household_id),
 constraint fk_ipo_funding foreign key(funding_account_id,household_id) references financial_accounts(id,household_id),
 constraint fk_ipo_security foreign key(security_id) references securities(id),
 constraint fk_ipo_assignee foreign key(household_id,assigned_user_id) references household_memberships(household_id,user_id),
 constraint fk_ipo_actor foreign key(acted_by) references app_users(id),
 constraint fk_ipo_snoozer foreign key(snoozed_by) references app_users(id)
);
create index ix_ipo_pending on investment_plan_occurrences(household_id,state,due_on,id);
