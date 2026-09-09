create table bank_accounts (
    id bigint auto_increment primary key,
    household_id bigint not null,
    name varchar(100) not null,
    bank_name varchar(80),
    card_last_four varchar(4),
    archived_at datetime(6),
    constraint uk_bank_accounts_id_household unique (id, household_id),
    constraint ck_bank_accounts_name check (char_length(trim(name)) > 0),
    constraint ck_bank_accounts_card_last_four check (
        card_last_four is null or regexp_like(card_last_four, '^[0-9]{4}$')
    ),
    constraint fk_bank_accounts_household foreign key (household_id) references households(id)
);

create index ix_bank_accounts_household_archived
    on bank_accounts (household_id, archived_at, id);

alter table financial_accounts add column bank_account_id bigint;

insert into bank_accounts (household_id, name, bank_name, card_last_four, archived_at)
select household_id, name, bank_name,
       case when card_last_four is null or regexp_like(card_last_four, '^[0-9]{4}$')
            then card_last_four else null end,
       archived_at
from financial_accounts
where type = 'BANK';

update financial_accounts account_row
join bank_accounts bank_row
  on bank_row.household_id = account_row.household_id
 -- Names were copied verbatim; byte comparison also handles different table collations.
 and binary bank_row.name = binary account_row.name
set account_row.bank_account_id = bank_row.id
where account_row.type = 'BANK';

alter table financial_accounts add constraint uk_financial_accounts_bank_currency
    unique (bank_account_id, currency);
alter table financial_accounts add constraint fk_financial_accounts_bank_household
    foreign key (bank_account_id, household_id) references bank_accounts (id, household_id);

create index ix_financial_accounts_bank_account
    on financial_accounts (household_id, bank_account_id, archived_at, currency, id);
