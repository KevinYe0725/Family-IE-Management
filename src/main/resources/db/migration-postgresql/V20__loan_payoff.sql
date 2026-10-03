-- Historical events retain principal-only amount_cents and zero interest.
alter table loan_prepayments add column interest_cents bigint not null default 0;
alter table loan_prepayments add column operation_kind varchar(20) not null default 'PREPAYMENT';
alter table loan_prepayments add constraint ck_prepayment_interest check (interest_cents >= 0);
alter table loan_prepayments add constraint ck_prepayment_kind check (operation_kind in ('PREPAYMENT','PAYOFF'));
alter table loan_installments add column cancelled_by_prepayment_id bigint;
alter table loan_prepayments add constraint uk_prepayment_id_loan_household unique (id,loan_id,household_id);
alter table loan_installments add constraint fk_installment_cancel_prepayment foreign key (cancelled_by_prepayment_id,loan_id,household_id) references loan_prepayments(id,loan_id,household_id);
