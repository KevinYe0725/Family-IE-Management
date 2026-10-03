alter table recurring_rules drop constraint ck_recurring_rules_schedule;
alter table recurring_rules add constraint ck_recurring_rules_schedule check (
    (schedule_type in ('MONTHLY', 'QUARTERLY', 'YEARLY')
        and day_of_month between 1 and 31 and day_of_week is null)
    or (schedule_type = 'WEEKLY' and day_of_month is null
        and day_of_week in ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY',
                            'FRIDAY', 'SATURDAY', 'SUNDAY'))
);
