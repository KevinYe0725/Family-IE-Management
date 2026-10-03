alter table securities add column catalog_verified boolean default false not null;

alter table market_price_snapshots drop constraint ck_market_price_snapshots_source;
alter table market_price_snapshots add constraint ck_market_price_snapshots_source
    check (source in ('TUSHARE', 'BAOSTOCK'));

create table security_catalog_state (
    id integer primary key,
    state varchar(16) not null,
    item_count integer default 0 not null,
    updated_at timestamp with time zone,
    error varchar(100),
    constraint ck_security_catalog_state_singleton check (id = 1),
    constraint ck_security_catalog_state_value check (state in ('EMPTY', 'READY', 'ERROR'))
);

insert into security_catalog_state (id,state,item_count) values (1,'EMPTY',0);

create table investment_setup (
    household_id bigint primary key,
    completed_at timestamp with time zone not null,
    completed_by bigint not null,
    constraint fk_investment_setup_household foreign key (household_id) references households(id),
    constraint fk_investment_setup_actor_household foreign key (completed_by,household_id)
        references app_users(id,household_id)
);

insert into investment_setup (household_id,completed_at,completed_by)
select trade.household_id,current_timestamp,min(trade.created_by)
from investment_trades trade
group by trade.household_id;
