create table households (
    id bigint auto_increment primary key,
    name varchar(255) not null,
    created_at datetime(6) not null
);

create table app_users (
    id bigint auto_increment primary key,
    household_id bigint not null,
    username varchar(255) not null,
    password_hash varchar(255) not null,
    created_at datetime(6) not null,
    constraint uk_app_users_username unique (username),
    constraint fk_app_users_household foreign key (household_id) references households(id)
);

create table family_members (
    id bigint auto_increment primary key,
    household_id bigint not null,
    name varchar(255) not null,
    role_label varchar(255) not null,
    created_at datetime(6) not null,
    constraint fk_family_members_household foreign key (household_id) references households(id)
);

create table categories (
    id bigint auto_increment primary key,
    household_id bigint not null,
    kind varchar(255) not null,
    name varchar(255) not null,
    color varchar(255) not null,
    is_default boolean not null,
    created_at datetime(6) not null,
    constraint uk_categories_household_kind_name unique (household_id, kind, name),
    constraint fk_categories_household foreign key (household_id) references households(id)
);

create table financial_transactions (
    id bigint auto_increment primary key,
    household_id bigint not null,
    member_id bigint not null,
    category_id bigint not null,
    kind varchar(255) not null,
    amount_cents bigint not null,
    occurred_on date not null,
    merchant varchar(255),
    location varchar(255),
    note varchar(500),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint fk_transactions_household foreign key (household_id) references households(id),
    constraint fk_transactions_member foreign key (member_id) references family_members(id),
    constraint fk_transactions_category foreign key (category_id) references categories(id)
);

create index ix_financial_transactions_household_occurred_on
    on financial_transactions (household_id, occurred_on);
