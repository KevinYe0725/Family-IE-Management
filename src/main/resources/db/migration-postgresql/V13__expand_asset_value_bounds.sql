alter table assets drop constraint ck_assets_purchase_value;
alter table assets drop constraint ck_assets_current_value;
alter table asset_valuations drop constraint ck_asset_valuations_value;

alter table assets add constraint ck_assets_purchase_value check (
    purchase_value_cents is null
    or (purchase_value_cents >= 0 and purchase_value_cents <= 999999999999)
);
alter table assets add constraint ck_assets_current_value check (
    current_value_cents >= 0 and current_value_cents <= 999999999999
);
alter table asset_valuations add constraint ck_asset_valuations_value check (
    value_cents >= 0 and value_cents <= 999999999999
);
