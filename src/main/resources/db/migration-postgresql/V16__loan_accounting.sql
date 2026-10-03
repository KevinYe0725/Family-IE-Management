-- Existing loans and payment rows remain explicitly uninitialized; no inferred history.
alter table loans add column funding_mode varchar(20);
alter table loans add column accounting_on date;
alter table loans add column disbursement_account_id bigint;
alter table loans add column last_payment_on date;
alter table loans add constraint fk_loans_disbursement_household foreign key (disbursement_account_id, household_id) references financial_accounts(id, household_id);
alter table loans add constraint ck_loans_accounting check (
    (funding_mode is null and accounting_on is null and disbursement_account_id is null)
    or (funding_mode = 'OPENING' and accounting_on is not null and disbursement_account_id is null)
    or (funding_mode = 'DISBURSEMENT' and accounting_on is not null and disbursement_account_id is not null));
alter table financial_transactions add column loan_principal_cents bigint;
alter table financial_transactions add column loan_interest_cents bigint;
alter table financial_transactions add constraint ck_transactions_loan_split check (
    (loan_principal_cents is null and loan_interest_cents is null)
    or (source_type in ('LOAN_PAYMENT','LOAN_PREPAYMENT') and loan_principal_cents > 0
        and loan_interest_cents >= 0 and loan_principal_cents + loan_interest_cents = amount_cents));
