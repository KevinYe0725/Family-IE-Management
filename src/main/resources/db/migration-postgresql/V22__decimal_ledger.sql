-- Convert exact signed-long cents into the sole writable yuan values.
-- Application validation rejects sub-cent input before DECIMAL scale coercion.
alter table ledger_accounts add balance_amount decimal(21,2) default 0 not null;
alter table ledger_entries add debit_amount decimal(21,2) default 0 not null;
alter table ledger_entries add credit_amount decimal(21,2) default 0 not null;
update ledger_accounts set balance_amount=cast(balance_cents as decimal(21,2))/100;
update ledger_entries set debit_amount=cast(debit_cents as decimal(21,2))/100,
    credit_amount=cast(credit_cents as decimal(21,2))/100;

alter table ledger_accounts drop constraint ck_ledger_account_floor;
alter table ledger_entries drop constraint ck_ledger_entry_amount;
alter table ledger_accounts drop column balance_cents;
alter table ledger_entries drop column debit_cents;
alter table ledger_entries drop column credit_cents;

alter table ledger_accounts add balance_cents bigint generated always as (balance_amount*100) stored;
alter table ledger_entries add debit_cents bigint generated always as (debit_amount*100) stored;
alter table ledger_entries add credit_cents bigint generated always as (credit_amount*100) stored;
alter table ledger_entries alter column debit_amount drop default;
alter table ledger_entries alter column credit_amount drop default;
alter table ledger_accounts add constraint ck_ledger_account_floor check (kind not in ('CASH','LOAN') or balance_amount>=0);
alter table ledger_accounts add constraint ck_ledger_account_amount_range check (balance_amount between -92233720368547758.08 and 92233720368547758.07);
alter table ledger_entries add constraint ck_ledger_entry_amount check (
    (debit_amount>0 and credit_amount=0) or (credit_amount>0 and debit_amount=0));
alter table ledger_entries add constraint ck_ledger_entry_amount_range check (
    debit_amount between 0 and 92233720368547758.07 and credit_amount between 0 and 92233720368547758.07);
