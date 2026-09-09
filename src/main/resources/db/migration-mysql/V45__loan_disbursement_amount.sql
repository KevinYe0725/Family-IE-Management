-- Legacy null retains the historical full-principal receipt. Do not rewrite existing money.
alter table loans add column disbursement_amount decimal(21,2);
alter table loans add constraint ck_loans_disbursement_amount check (
    disbursement_amount is null
    or (funding_mode is not null and funding_mode='DISBURSEMENT'
        and disbursement_amount>0 and disbursement_amount<=principal_amount));
