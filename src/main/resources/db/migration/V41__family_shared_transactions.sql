-- V41: transactions may be attributed to the whole family (member NULL = 全体/家庭共同).
alter table financial_transactions alter column member_id set null;
