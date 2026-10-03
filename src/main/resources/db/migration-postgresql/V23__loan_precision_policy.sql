-- Settled decimal amounts are authoritative. Preserve all old values and source identities.
alter table loans add column principal_amount decimal(21,2) default 0 not null;
alter table loans add column current_principal_amount decimal(21,2) default 0 not null;
update loans set principal_amount=cast(principal_cents as decimal(21,2))/100;
update loans set current_principal_amount=cast(current_principal_cents as decimal(21,2))/100;
alter table loans drop constraint ck_loans_current_principal;
alter table loans drop constraint ck_loans_principal;
alter table loans drop column principal_cents;
alter table loans add principal_cents bigint generated always as (principal_amount*100) stored;
alter table loans alter column principal_amount drop default;
alter table loans drop column current_principal_cents;
alter table loans add current_principal_cents bigint generated always as (current_principal_amount*100) stored;
alter table loans alter column current_principal_amount drop default;
alter table loan_installments add column principal_amount decimal(21,2) default 0 not null;
alter table loan_installments add column interest_amount decimal(21,2) default 0 not null;
update loan_installments set principal_amount=cast(principal_cents as decimal(21,2))/100;
update loan_installments set interest_amount=cast(interest_cents as decimal(21,2))/100;
alter table loan_installments drop constraint ck_loan_installments_principal;
alter table loan_installments drop constraint ck_loan_installments_interest;
alter table loan_installments drop column principal_cents;
alter table loan_installments add principal_cents bigint generated always as (principal_amount*100) stored;
alter table loan_installments alter column principal_amount drop default;
alter table loan_installments drop column interest_cents;
alter table loan_installments add interest_cents bigint generated always as (interest_amount*100) stored;
alter table loan_installments alter column interest_amount drop default;
alter table loan_prepayments add column amount decimal(21,2) default 0 not null;
alter table loan_prepayments add column interest_amount decimal(21,2) default 0 not null;
update loan_prepayments set amount=cast(amount_cents as decimal(21,2))/100;
update loan_prepayments set interest_amount=cast(interest_cents as decimal(21,2))/100;
alter table loan_prepayments drop constraint ck_loan_prepayments_amount;
alter table loan_prepayments drop constraint ck_prepayment_interest;
alter table loan_prepayments drop column amount_cents;
alter table loan_prepayments add amount_cents bigint generated always as (amount*100) stored;
alter table loan_prepayments alter column amount drop default;
alter table loan_prepayments drop column interest_cents;
alter table loan_prepayments add interest_cents bigint generated always as (interest_amount*100) stored;
alter table loan_prepayments alter column interest_amount drop default;
alter table loans add constraint ck_loans_principal check (principal_amount>0 and principal_amount<=999999999.99);
alter table loans add constraint ck_loans_current_principal check (current_principal_amount>=0 and current_principal_amount<=principal_amount);
alter table loan_installments add constraint ck_loan_installments_amount check (principal_amount>=0 and interest_amount>=0 and principal_amount+interest_amount>0 and principal_amount+interest_amount<=92233720368547758.07);
alter table loan_prepayments add constraint ck_loan_prepayments_amount check (amount>0 and amount<=92233720368547758.07);
alter table loan_prepayments add constraint ck_prepayment_interest check (interest_amount>=0 and interest_amount<=92233720368547758.07);
alter table loan_installments add precise_principal_amount decimal(30,12);
alter table loan_installments add precise_interest_amount decimal(30,12);
alter table loan_installments add interest_carry_amount decimal(30,12);
alter table loan_installments add rounding_policy varchar(40);
alter table loan_installments add custom_rate_principal_amount decimal(30,12);
alter table loan_installments add custom_rate_interest_amount decimal(30,12);
alter table loan_installments add constraint ck_installment_custom_ratio check (
 (custom_rate_principal_amount is null and custom_rate_interest_amount is null)
 or (custom_rate_principal_amount is not null and custom_rate_principal_amount>0 and custom_rate_interest_amount is not null and custom_rate_interest_amount>=0));
alter table loan_installments add constraint ck_installment_precision check (
 (precise_principal_amount is null and precise_interest_amount is null and interest_carry_amount is null and rounding_policy is null)
 or (precise_principal_amount is not null and precise_principal_amount>=0 and precise_interest_amount is not null and precise_interest_amount>=0 and interest_carry_amount is not null and rounding_policy is not null));
alter table loans add repayment_policy_revision bigint default 0 not null;
alter table loans add minimum_installment_amount decimal(21,2);
alter table loans add repayment_policy_source varchar(1000);
alter table loans add constraint ck_loan_policy_minimum check (minimum_installment_amount is null or (minimum_installment_amount>0 and minimum_installment_amount<=92233720368547758.07));
create table loan_repayment_policy_history (
 loan_id bigint not null, household_id bigint not null, revision bigint not null,
 minimum_installment_amount decimal(21,2), source_note varchar(1000), changed_by bigint not null, changed_at timestamp not null,
 primary key (loan_id,revision), foreign key (loan_id,household_id) references loans(id,household_id),
 foreign key (changed_by,household_id) references app_users(id,household_id));
alter table loan_prepayments drop constraint ck_prepayment_strategy;
alter table loan_prepayments add constraint ck_prepayment_strategy check (strategy is null or (operation_kind='PREPAYMENT' and strategy in ('REDUCE_TERM','REDUCE_PAYMENT','ADJUST_TERM')));
alter table financial_transactions drop constraint ck_transactions_loan_split;
alter table financial_transactions drop constraint ck_transactions_loan_split_complete_v18;
alter table financial_transactions add constraint ck_transactions_loan_split_v23 check (
 (loan_principal_cents is null and loan_interest_cents is null)
 or (loan_principal_cents is not null and loan_interest_cents is not null and source_type is not null
 and ((source_type='LOAN_PAYMENT' and loan_principal_cents>=0) or (source_type='LOAN_PREPAYMENT' and loan_principal_cents>0))
 and loan_interest_cents>=0 and amount_cents>0 and loan_principal_cents+loan_interest_cents=amount_cents));
