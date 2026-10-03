-- Currency dimension only; foreign account creation remains closed.
alter table ledger_accounts add currency varchar(3) not null default 'CNY';
alter table ledger_accounts add constraint ck_ledger_account_currency check (currency in ('CNY','HKD','USD'));
alter table ledger_accounts add constraint uk_ledger_account_currency unique (household_id,account_code,currency);
alter table ledger_entries add currency varchar(3) not null default 'CNY';
alter table ledger_entries add constraint ck_ledger_entry_currency check (currency in ('CNY','HKD','USD'));
alter table ledger_entries add constraint fk_ledger_entry_currency foreign key (household_id,account_code,currency) references ledger_accounts(household_id,account_code,currency);
