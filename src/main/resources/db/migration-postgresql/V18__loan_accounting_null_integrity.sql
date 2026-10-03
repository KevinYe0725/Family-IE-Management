-- CHECK must be FALSE, never UNKNOWN, for partially initialized accounting tuples.
-- Existing corruption fails migration; no financial facts are inferred or repaired.
alter table loans add constraint ck_loans_accounting_complete_v18 check (
    (funding_mode is null and accounting_on is null and disbursement_account_id is null)
    or (funding_mode is not null and funding_mode = 'OPENING' and accounting_on is not null and disbursement_account_id is null)
    or (funding_mode is not null and funding_mode = 'DISBURSEMENT' and accounting_on is not null and disbursement_account_id is not null));
alter table financial_transactions add constraint ck_transactions_loan_split_complete_v18 check (
    (loan_principal_cents is null and loan_interest_cents is null)
    or (loan_principal_cents is not null and loan_interest_cents is not null
        and source_type is not null and source_type in ('LOAN_PAYMENT','LOAN_PREPAYMENT')
        and loan_principal_cents > 0 and loan_interest_cents >= 0
        and loan_principal_cents + loan_interest_cents = amount_cents));
