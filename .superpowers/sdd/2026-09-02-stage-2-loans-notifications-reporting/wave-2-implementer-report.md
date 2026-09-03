# Wave 2 implementation report

## Delivered

- Forward-only `V12` permits dedicated `LOAN_PAYMENT` and `LOAN_PREPAYMENT` sources, adds durable prepayment request keys, and persists market-service issues for notification projection.
- Installment confirmation takes the household mutation lock plus a pessimistic installment lock, rechecks assignee and active references, rejects future/cancelled work, creates one immutable expense transaction, applies principal once, closes at zero, and resolves its linked reminder.
- Prepayment is owner/admin-only and idempotent by household/loan/request key. It preserves paid installments, cancels remaining drafts, creates a cents-exact regenerated schedule, and closes the loan on full settlement.
- Notification service is the only notification-table writer. Its candidate projection reads budget thresholds, recurring due items, loan dues, stale asset valuations, and persisted market errors. List/unread/read/resolve APIs enforce household plus target-user visibility; the daily trigger is 00:20 Asia/Shanghai.

## Verification

- RED: repayment/prepayment tests initially failed on absent `LOAN_PAYMENT`; notification API initially returned 404 before the notification boundary existed.
- GREEN: `./mvnw -q -Dtest=LoanRepaymentApiTest,LoanPrepaymentTest,NotificationApiTest test` passed, covering repeated and concurrent installment confirmation, partial/full prepay, notification rerun/read/resolve.
- `./mvnw -q -DskipTests package` and `git diff --check` passed.

## Scope boundary

Wave 3 reporting, snapshots, and the full runtime acceptance path remain untouched.
