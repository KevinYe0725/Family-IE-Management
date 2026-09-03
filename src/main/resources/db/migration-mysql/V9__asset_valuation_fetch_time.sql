alter table asset_valuations add column fetched_at datetime(6);

update asset_valuations
set fetched_at = current_timestamp
where fetched_at is null;

alter table asset_valuations modify column fetched_at datetime(6) not null;

alter table asset_valuations drop index ix_asset_valuations_household_asset_date;

create index ix_asset_valuations_asset_latest
    on asset_valuations (asset_id, valued_on desc, fetched_at desc, id desc);
