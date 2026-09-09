-- New purchase metadata is optional for legacy rows. Existing journals remain untouched.
alter table loans add column purchase_value decimal(21,2);
alter table loans add column own_contribution_account_id bigint;
alter table loans add column asset_relation varchar(16);
alter table loans add constraint fk_loan_own_contribution_account foreign key (own_contribution_account_id,household_id) references financial_accounts(id,household_id);
alter table loans add constraint ck_loans_purchase_value check (
    (purchase_value is null and own_contribution_account_id is null)
    or (purchase_value is not null and funding_mode is not null and funding_mode='FINANCED_PURCHASE'
        and purchase_value>=principal_amount
        and ((purchase_value=principal_amount and own_contribution_account_id is null)
            or (purchase_value>principal_amount and own_contribution_account_id is not null))));
alter table loans add constraint ck_loans_asset_relation check (
    asset_relation is null
    or (linked_asset_id is not null and asset_relation in ('FINANCING','COLLATERAL')
        and (purchased_asset_id is null or asset_relation='FINANCING')));
create index ix_loans_linked_asset on loans(household_id,linked_asset_id,status);
