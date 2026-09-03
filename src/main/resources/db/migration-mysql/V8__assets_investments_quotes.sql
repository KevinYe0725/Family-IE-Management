create table assets (
    id bigint auto_increment primary key,
    household_id bigint not null,
    name varchar(100) not null,
    asset_type varchar(16) not null,
    owner_member_id bigint,
    acquired_on date,
    purchase_value_cents bigint,
    current_value_cents bigint not null,
    status varchar(16) default 'ACTIVE' not null,
    created_by bigint not null,
    archived_at datetime(6),
    constraint uk_assets_id_household unique (id, household_id),
    constraint uk_assets_id_household_type unique (id, household_id, asset_type),
    constraint ck_assets_name check (length(trim(name)) > 0),
    constraint ck_assets_type check (asset_type in ('PROPERTY', 'VEHICLE', 'OTHER')),
    constraint ck_assets_purchase_value check (
        purchase_value_cents is null
        or (purchase_value_cents >= 0 and purchase_value_cents <= 99999999999)
    ),
    constraint ck_assets_current_value check (
        current_value_cents >= 0 and current_value_cents <= 99999999999
    ),
    constraint ck_assets_status check (
        (status = 'ACTIVE' and archived_at is null)
        or (status = 'ARCHIVED' and archived_at is not null)
    ),
    constraint fk_assets_household foreign key (household_id) references households(id),
    constraint fk_assets_owner_household foreign key (owner_member_id, household_id)
        references family_members(id, household_id),
    constraint fk_assets_creator_household foreign key (created_by, household_id)
        references app_users(id, household_id)
);

create index ix_assets_household_status
    on assets (household_id, status, archived_at, id);
create index ix_assets_household_owner_status
    on assets (household_id, owner_member_id, status, id);

create table property_assets (
    asset_id bigint primary key,
    household_id bigint not null,
    asset_type varchar(16) default 'PROPERTY' not null,
    address varchar(255) not null,
    area_sqm decimal(12, 2) not null,
    usage_type varchar(32) not null,
    constraint ck_property_assets_type check (asset_type = 'PROPERTY'),
    constraint ck_property_assets_address check (length(trim(address)) > 0),
    constraint ck_property_assets_area check (area_sqm > 0),
    constraint ck_property_assets_usage check (length(trim(usage_type)) > 0),
    constraint fk_property_assets_asset_household_type
        foreign key (asset_id, household_id, asset_type)
        references assets(id, household_id, asset_type)
);

create table vehicle_assets (
    asset_id bigint primary key,
    household_id bigint not null,
    asset_type varchar(16) default 'VEHICLE' not null,
    brand_model varchar(120) not null,
    plate_hint varchar(32),
    purchase_year integer,
    constraint ck_vehicle_assets_type check (asset_type = 'VEHICLE'),
    constraint ck_vehicle_assets_brand_model check (length(trim(brand_model)) > 0),
    constraint ck_vehicle_assets_plate_hint check (
        plate_hint is null or length(trim(plate_hint)) > 0
    ),
    constraint ck_vehicle_assets_purchase_year check (
        purchase_year is null or purchase_year between 1886 and 9999
    ),
    constraint fk_vehicle_assets_asset_household_type
        foreign key (asset_id, household_id, asset_type)
        references assets(id, household_id, asset_type)
);

create table asset_valuations (
    id bigint auto_increment primary key,
    household_id bigint not null,
    asset_id bigint not null,
    valued_on date not null,
    value_cents bigint not null,
    source varchar(16) not null,
    note varchar(500),
    created_by bigint not null,
    constraint uk_asset_valuations_asset_date_source unique (asset_id, valued_on, source),
    constraint ck_asset_valuations_value check (value_cents >= 0 and value_cents <= 99999999999),
    constraint ck_asset_valuations_source check (source in ('PURCHASE', 'MANUAL')),
    constraint ck_asset_valuations_note check (note is null or length(trim(note)) > 0),
    constraint fk_asset_valuations_household foreign key (household_id) references households(id),
    constraint fk_asset_valuations_asset_household foreign key (asset_id, household_id)
        references assets(id, household_id),
    constraint fk_asset_valuations_creator_household foreign key (created_by, household_id)
        references app_users(id, household_id)
);

create index ix_asset_valuations_household_asset_date
    on asset_valuations (household_id, asset_id, valued_on desc, id desc);

create table investment_accounts (
    id bigint auto_increment primary key,
    household_id bigint not null,
    name varchar(100) not null,
    broker_name varchar(100) not null,
    currency varchar(3) not null,
    archived_at datetime(6),
    constraint uk_investment_accounts_id_household unique (id, household_id),
    constraint uk_investment_accounts_household_name unique (household_id, name),
    constraint ck_investment_accounts_name check (length(trim(name)) > 0),
    constraint ck_investment_accounts_broker check (length(trim(broker_name)) > 0),
    constraint ck_investment_accounts_currency check (currency = 'CNY'),
    constraint fk_investment_accounts_household foreign key (household_id) references households(id)
);

create index ix_investment_accounts_household_archived
    on investment_accounts (household_id, archived_at, id);

create table securities (
    id bigint auto_increment primary key,
    market varchar(2) not null,
    ts_code varchar(9) not null,
    name varchar(100) not null,
    security_type varchar(16) not null,
    active boolean default true not null,
    constraint uk_securities_market_code unique (market, ts_code),
    constraint ck_securities_market check (market in ('SH', 'SZ', 'BJ')),
    constraint ck_securities_ts_code check (
        regexp_like(ts_code, '^[0-9]{6}[.](SH|SZ|BJ)$')
        and ts_code = concat(substring(ts_code, 1, 6), '.', market)
    ),
    constraint ck_securities_name check (length(trim(name)) > 0),
    constraint ck_securities_type check (security_type = 'STOCK')
);

create index ix_securities_active_market_code
    on securities (active, market, ts_code, id);
create index ix_securities_active_name_code
    on securities (active, name, ts_code, id);

create table investment_trades (
    id bigint auto_increment primary key,
    household_id bigint not null,
    account_id bigint not null,
    security_id bigint not null,
    trade_type varchar(16) not null,
    quantity decimal(19, 4),
    price_cents bigint not null,
    fee_cents bigint default 0 not null,
    traded_on date not null,
    created_by bigint not null,
    source_type varchar(16) default 'MANUAL' not null,
    source_id varchar(100),
    constraint uk_investment_trades_household_source
        unique (household_id, source_type, source_id),
    constraint ck_investment_trades_type check (
        trade_type in ('BUY', 'SELL', 'DIVIDEND', 'FEE')
    ),
    constraint ck_investment_trades_action_shape check (
        (trade_type in ('BUY', 'SELL') and quantity is not null and quantity > 0)
        or (trade_type in ('DIVIDEND', 'FEE') and quantity is null and fee_cents = 0)
    ),
    constraint ck_investment_trades_price check (price_cents > 0 and price_cents <= 99999999999),
    constraint ck_investment_trades_fee check (fee_cents >= 0 and fee_cents <= 99999999999),
    constraint ck_investment_trades_source check (
        (source_type = 'MANUAL' and source_id is null)
        or (source_type = 'IMPORT' and source_id is not null and length(trim(source_id)) > 0)
    ),
    constraint fk_investment_trades_household foreign key (household_id) references households(id),
    constraint fk_investment_trades_account_household foreign key (account_id, household_id)
        references investment_accounts(id, household_id),
    constraint fk_investment_trades_security foreign key (security_id) references securities(id),
    constraint fk_investment_trades_creator_household foreign key (created_by, household_id)
        references app_users(id, household_id)
);

create index ix_investment_trades_household_account_date
    on investment_trades (household_id, account_id, traded_on, id);
create index ix_investment_trades_household_security_date
    on investment_trades (household_id, security_id, traded_on, id);

create table market_price_snapshots (
    id bigint auto_increment primary key,
    security_id bigint not null,
    trade_date date not null,
    open_cents bigint not null,
    high_cents bigint not null,
    low_cents bigint not null,
    close_cents bigint not null,
    pre_close_cents bigint not null,
    pct_change decimal(9, 4) not null,
    source varchar(16) not null,
    fetched_at datetime(6) not null,
    constraint uk_market_price_snapshots_security_date_source
        unique (security_id, trade_date, source),
    constraint ck_market_price_snapshots_source check (source = 'TUSHARE'),
    constraint ck_market_price_snapshots_open check (open_cents > 0 and open_cents <= 99999999999),
    constraint ck_market_price_snapshots_high check (high_cents > 0 and high_cents <= 99999999999),
    constraint ck_market_price_snapshots_low check (low_cents > 0 and low_cents <= 99999999999),
    constraint ck_market_price_snapshots_close check (close_cents > 0 and close_cents <= 99999999999),
    constraint ck_market_price_snapshots_pre_close check (
        pre_close_cents > 0 and pre_close_cents <= 99999999999
    ),
    constraint ck_market_price_snapshots_range check (
        low_cents <= high_cents
        and open_cents between low_cents and high_cents
        and close_cents between low_cents and high_cents
    ),
    constraint fk_market_price_snapshots_security foreign key (security_id) references securities(id)
);

create index ix_market_price_snapshots_security_date
    on market_price_snapshots (security_id, trade_date desc, fetched_at desc, id desc);

create table manual_price_overrides (
    id bigint auto_increment primary key,
    household_id bigint not null,
    security_id bigint not null,
    price_cents bigint not null,
    effective_on date not null,
    note varchar(500),
    created_by bigint not null,
    constraint uk_manual_price_overrides_household_security_date
        unique (household_id, security_id, effective_on),
    constraint ck_manual_price_overrides_price check (price_cents > 0 and price_cents <= 99999999999),
    constraint ck_manual_price_overrides_note check (note is null or length(trim(note)) > 0),
    constraint fk_manual_price_overrides_household foreign key (household_id) references households(id),
    constraint fk_manual_price_overrides_security foreign key (security_id) references securities(id),
    constraint fk_manual_price_overrides_creator_household foreign key (created_by, household_id)
        references app_users(id, household_id)
);

create index ix_manual_price_overrides_household_security_date
    on manual_price_overrides (household_id, security_id, effective_on desc, id desc);
