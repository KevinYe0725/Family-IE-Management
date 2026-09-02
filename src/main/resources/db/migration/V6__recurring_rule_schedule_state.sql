alter table recurring_rules add column start_on date;
alter table recurring_rules add column end_on date;
alter table recurring_rules add column day_of_week varchar(9);
alter table recurring_rules add column assigned_user_id bigint;
alter table recurring_rules add column paused boolean default false not null;

update recurring_rules
set start_on = coalesce(next_due_on, current_date),
    assigned_user_id = created_by;

update recurring_rules
set day_of_week = case dayofweek(start_on)
    when 1 then 'SUNDAY'
    when 2 then 'MONDAY'
    when 3 then 'TUESDAY'
    when 4 then 'WEDNESDAY'
    when 5 then 'THURSDAY'
    when 6 then 'FRIDAY'
    when 7 then 'SATURDAY'
end
where schedule_type = 'WEEKLY';

alter table recurring_rules alter column start_on set not null;
alter table recurring_rules alter column start_on set default current_date;
alter table recurring_rules drop constraint ck_recurring_rules_schedule;
alter table recurring_rules add constraint ck_recurring_rules_schedule check (
    (schedule_type = 'MONTHLY' and day_of_month between 1 and 31 and day_of_week is null)
    or (schedule_type = 'WEEKLY' and day_of_month is null
        and day_of_week in ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY',
                            'FRIDAY', 'SATURDAY', 'SUNDAY'))
);
alter table recurring_rules add constraint ck_recurring_rules_dates
    check (end_on is null or end_on >= start_on);
alter table recurring_rules add constraint ck_recurring_rules_amount_upper
    check (amount_cents <= 99999999999);
alter table recurring_rules add constraint fk_recurring_rules_assignee_household
    foreign key (assigned_user_id, household_id) references app_users(id, household_id);

create index ix_recurring_rules_generation_due
    on recurring_rules (active, paused, next_due_on, id);
