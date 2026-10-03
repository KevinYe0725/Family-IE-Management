ALTER TABLE loan_prepayments ADD COLUMN strategy VARCHAR(32);
ALTER TABLE loan_prepayments ADD CONSTRAINT ck_prepayment_strategy CHECK (strategy IS NULL OR (operation_kind = 'PREPAYMENT' AND strategy IN ('REDUCE_TERM', 'REDUCE_PAYMENT')));
