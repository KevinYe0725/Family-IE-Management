-- Preserve legacy money targets without guessing a price or converting them into shares.
alter table investment_plans modify column amount decimal(13,2) null;
alter table investment_plans add column quantity decimal(19,4);
alter table investment_plans add constraint ck_ip_target check (
 (quantity is not null and quantity>0 and quantity<=999999999999999.9999 and amount is null)
 or (quantity is null and amount is not null and amount>0 and amount<=999999999.99)
);
alter table investment_plan_occurrences modify column amount decimal(13,2) null;
alter table investment_plan_occurrences add column quantity decimal(19,4);
-- Historical confirmations may reference corrected or deleted trades, so do not infer their original quantity.
alter table investment_plan_occurrences add column actual_quantity decimal(19,4);
alter table investment_plan_occurrences add constraint ck_ipo_actual_quantity check (
 (actual_quantity is null and (state<>'CONFIRMED' or quantity is null))
 or (actual_quantity is not null and actual_quantity>0 and actual_quantity<=999999999999999.9999 and state='CONFIRMED')
);
alter table investment_plan_occurrences add constraint ck_ipo_target check (
 (quantity is not null and quantity>0 and quantity<=999999999999999.9999 and amount is null)
 or (quantity is null and amount is not null and amount>0 and amount<=999999999.99)
);
-- Existing occurrence snapshots remain actionable and unchanged. Plan edits apply to future occurrences.
update investment_plans set state='PAUSED' where state='ACTIVE' and quantity is null;
