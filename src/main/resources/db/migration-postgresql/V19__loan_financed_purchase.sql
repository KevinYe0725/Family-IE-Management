-- Explicit loan-financed purchases only. Existing financial history is unchanged.
alter table loans add column purchased_asset_id bigint;
alter table assets add column purchase_loan_id bigint;
alter table loans add constraint fk_loan_purchased_asset foreign key (purchased_asset_id,household_id) references assets(id,household_id);
alter table assets add constraint fk_asset_purchase_loan foreign key (purchase_loan_id,household_id) references loans(id,household_id);
alter table loans add constraint uk_loan_purchased_asset unique (purchased_asset_id);
alter table assets add constraint uk_asset_purchase_loan unique (purchase_loan_id);
alter table loans drop constraint ck_loans_accounting;
alter table loans drop constraint ck_loans_accounting_complete_v18;
alter table loans add constraint ck_loans_accounting check (
    (funding_mode is null and accounting_on is null and disbursement_account_id is null and purchased_asset_id is null)
    or (funding_mode is not null and funding_mode = 'OPENING' and accounting_on is not null and disbursement_account_id is null and purchased_asset_id is null)
    or (funding_mode is not null and funding_mode = 'DISBURSEMENT' and accounting_on is not null and disbursement_account_id is not null and purchased_asset_id is null)
    or (funding_mode is not null and funding_mode = 'FINANCED_PURCHASE' and accounting_on is not null and disbursement_account_id is null and purchased_asset_id is not null and linked_asset_id is not null and linked_asset_id=purchased_asset_id));
alter table loans add constraint ck_loans_accounting_complete_v18 check (
    (funding_mode is null and accounting_on is null and disbursement_account_id is null and purchased_asset_id is null)
    or (funding_mode is not null and funding_mode = 'OPENING' and accounting_on is not null and disbursement_account_id is null and purchased_asset_id is null)
    or (funding_mode is not null and funding_mode = 'DISBURSEMENT' and accounting_on is not null and disbursement_account_id is not null and purchased_asset_id is null)
    or (funding_mode is not null and funding_mode = 'FINANCED_PURCHASE' and accounting_on is not null and disbursement_account_id is null and purchased_asset_id is not null and linked_asset_id is not null and linked_asset_id=purchased_asset_id));
alter table assets alter column accounting_mode type varchar(20);
alter table assets drop constraint ck_asset_accounting;
alter table assets add constraint ck_asset_accounting check (
 (accounting_mode is null and accounting_on is null and initial_value_cents is null and funding_account_id is null and last_accounting_on is null and purchase_loan_id is null)
 or (accounting_mode is not null and accounting_mode in ('OPENING','PURCHASE','FINANCED_PURCHASE') and accounting_on is not null and initial_value_cents is not null and initial_value_cents>=0 and last_accounting_on is not null and last_accounting_on>=accounting_on
 and ((accounting_mode='OPENING' and funding_account_id is null and purchase_loan_id is null)
 or (accounting_mode='PURCHASE' and funding_account_id is not null and purchase_loan_id is null)
 or (accounting_mode='FINANCED_PURCHASE' and funding_account_id is null and purchase_loan_id is not null and initial_value_cents>0 and acquired_on is not null and acquired_on=accounting_on and purchase_value_cents is not null and purchase_value_cents=initial_value_cents))));
