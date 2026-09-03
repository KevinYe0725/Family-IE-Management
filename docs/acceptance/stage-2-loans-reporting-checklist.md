# Stage 2 loans, reminders, and reporting acceptance checklist

## Boundary

- [x] `GET /api/net-worth` calculates active cash-account balances as of today, non-cash active assets, priced investment positions, and active loan principal once each; it returns allocation, budget and investment freshness status, plus at most 24 daily snapshots.
- [x] `GET /api/debt-analysis` returns household debt ratio and progress for active loans. Closed and archived loans do not contribute to current debt.
- [x] The daily snapshot job runs at `23:50` in `Asia/Shanghai`. Its `(household_id, snapshot_on)` natural key updates the same row on retries rather than adding history.
- [x] Missing quotes remain explicit; existing manual or last-good quotes are used by portfolio reporting, so a market refresh failure does not erase snapshot inputs.
- [x] `StageTwoLoanReportingSmokeTest` starts a random-port service against a JUnit temporary file H2, registers an owner, creates linked property/loan/schedule, generates reminders, confirms the installment twice, verifies ledger/debt/net-worth, triggers budget and stale-valuation reminders, snapshots twice, and fully restarts against the same file.

## Reproducible automated gate

```bash
./mvnw -q -Dtest=StageTwoLoanReportingSmokeTest test
```

The test uses real HTTP cookie sessions and CSRF tokens for product writes. It disables scheduled background execution only to keep timing deterministic, then invokes the production snapshot service directly for the explicit daily-snapshot assertion. It is not a browser UI acceptance claim.

## Current UI boundary

The server APIs now provide the reporting contract for the planned dashboard. The current native static UI has not yet added loan, reminder, net-worth, or debt-analysis pages, so these capabilities are accepted through API/runtime tests rather than being claimed as visible UI work.
