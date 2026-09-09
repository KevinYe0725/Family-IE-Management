-- New settlement attribution only; historical transactions retain their original cash meaning.
alter table financial_transactions add column asset_settlement_id bigint;
alter table financial_transactions add constraint fk_transaction_asset_settlement foreign key(asset_settlement_id,household_id) references assets(id,household_id);
alter table financial_transactions add constraint ck_transaction_asset_settlement check(asset_settlement_id is null or source_type in ('LOAN_PAYMENT','LOAN_PREPAYMENT'));
create index ix_transaction_asset_settlement on financial_transactions(household_id,asset_settlement_id);

-- Insert-only full-request receipt. The application exposes no update or delete operation.
create table asset_sale_receipts (
    id bigint auto_increment primary key,
    household_id bigint not null,
    asset_id bigint not null,
    actor_id bigint not null,
    request_key varchar(100) not null,
    draft_json longtext not null,
    preview_json longtext not null,
    recorded_at timestamp(6) not null,
    constraint uk_asset_sale_asset unique(household_id,asset_id),
    constraint uk_asset_sale_request unique(household_id,request_key),
    constraint fk_asset_sale_asset foreign key(asset_id,household_id) references assets(id,household_id),
    constraint fk_asset_sale_actor foreign key(actor_id,household_id) references app_users(id,household_id)
);
