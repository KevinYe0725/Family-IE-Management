alter table investment_accounts add column funding_account_id bigint;
alter table investment_accounts add constraint fk_investment_funding_household foreign key (funding_account_id,household_id) references financial_accounts(id,household_id);
alter table investment_trades add column cash_account_id bigint;
alter table investment_trades add column accounting_confirmed boolean default false not null;
alter table investment_trades add constraint fk_trade_cash_household foreign key (cash_account_id,household_id) references financial_accounts(id,household_id);
alter table investment_trades drop constraint ck_investment_trades_type;
alter table investment_trades drop constraint ck_investment_trades_action_shape;
alter table investment_trades add constraint ck_investment_trades_type check (trade_type in ('OPENING','BUY','SELL','DIVIDEND','FEE'));
alter table investment_trades add constraint ck_investment_trades_action_shape check (
 (trade_type in ('BUY','SELL') and quantity is not null and quantity>0)
 or (trade_type='OPENING' and quantity is not null and quantity>0 and fee_cents=0)
 or (trade_type in ('DIVIDEND','FEE') and quantity is null and fee_cents=0));
alter table assets add column accounting_mode varchar(16);
alter table assets add column accounting_on date;
alter table assets add column initial_value_cents bigint;
alter table assets add column funding_account_id bigint;
alter table assets add column last_accounting_on date;
alter table assets add column disposed_on date;
alter table assets add column disposal_proceeds_cents bigint;
alter table assets add column disposal_cash_account_id bigint;
alter table assets add column disposed_by bigint;
alter table assets add constraint fk_asset_funding_household foreign key(funding_account_id,household_id) references financial_accounts(id,household_id);
alter table assets add constraint fk_asset_disposal_cash_household foreign key(disposal_cash_account_id,household_id) references financial_accounts(id,household_id);
alter table assets add constraint fk_asset_disposer_household foreign key(disposed_by,household_id) references app_users(id,household_id);
alter table assets add constraint ck_asset_accounting check (
 (accounting_mode is null and accounting_on is null and initial_value_cents is null and funding_account_id is null and last_accounting_on is null)
 or (accounting_mode is not null and accounting_mode in ('OPENING','PURCHASE') and accounting_on is not null and initial_value_cents is not null and initial_value_cents>=0 and last_accounting_on is not null and last_accounting_on>=accounting_on
 and ((accounting_mode='OPENING' and funding_account_id is null) or (accounting_mode='PURCHASE' and funding_account_id is not null))));
alter table asset_valuations drop constraint uk_asset_valuations_asset_date_source;
create index ix_asset_valuation_chronology on asset_valuations(asset_id,valued_on,id);
alter table net_worth_snapshots add column accounting_basis varchar(32) default 'LEGACY' not null;
alter table net_worth_snapshots add column valuation_estimated boolean default false not null;
alter table net_worth_snapshots add column unpriced_positions integer default 0 not null;
