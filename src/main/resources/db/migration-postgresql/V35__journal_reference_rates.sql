create table fx_journal_rates (
 journal_id bigint not null,
 currency varchar(3) not null,
 batch_id bigint not null,
 cny_per_unit decimal(30,12) not null,
 primary key(journal_id,currency),
 constraint fk_fx_journal foreign key(journal_id) references ledger_journals(id),
 constraint fk_fx_journal_rate foreign key(batch_id,currency) references fx_rates(batch_id,currency),
 constraint ck_fx_journal_positive check(cny_per_unit>0)
);
