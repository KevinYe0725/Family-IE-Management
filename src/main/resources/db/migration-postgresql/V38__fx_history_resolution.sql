-- Preserve old journal references while explicitly recording their unresolved provenance.
alter table fx_journal_rates add reference_status varchar(24) not null default 'LEGACY_UNVERIFIED';
create table fx_date_resolutions (
 requested_on date primary key,
 batch_id bigint not null,
 resolved_at timestamp(6) not null,
 constraint fk_fx_resolution_batch foreign key(batch_id) references fx_rate_batches(id)
);
create table fx_history_coverage (
 from_on date not null,
 to_on date not null,
 fetched_at timestamp(6) not null,
 primary key(from_on,to_on),
 constraint ck_fx_coverage_dates check(from_on<=to_on)
);
