# Plan 1 Task 5 Implementer Report

## Delivered commit

- `f19124171b7957a67e67c08e1938733000e1f590` — `test: certify Stage 2 identity foundation`

## Scope delivered

- Added a real HTTP foundation smoke test that creates a Stage 1 H2 fixture, starts the migrated application, verifies `demo@local.family` with `OWNER`, creates a household, creates an invite, registers a `JOIN` member, and proves that member receives `403` when attempting a role change.
- Corrected the legacy fixture identity sequences so a copied historical database can create new rows after migration.
- Added the Windows pre-migration safety gate: only a nonempty `data/family-finance.*.db` set without Flyway history is copied; every matching companion is copied into a collision-safe timestamped `data-backups/` directory, SHA-256 verified, recorded in `RESTORE.txt`, and promoted only after the complete copy succeeds. A failed copy remains explicitly marked `.partial` and startup stops.
- Made `-Smoke` use a random database beneath `target/`; it bypasses production-database inspection and never creates a production backup.
- Updated the README and both acceptance checklists, and ignored `data-backups/`.

## TDD and verification evidence

- RED: `./mvnw -q -Dtest=StageTwoFoundationSmokeTest test` initially failed at create registration with `500`; the fixture had inserted explicit legacy IDs without advancing H2 identity sequences.
- GREEN: after repairing the fixture sequence state, `./mvnw -q -Dtest=StageTwoFoundationSmokeTest test` passed.
- Focused regression: `./mvnw -q -Dtest=StageTwoFoundationSmokeTest,FlywayFreshDatabaseTest,FlywayStageOneUpgradeTest test` passed.
- Full Java suite: `./mvnw test` passed with `104` tests, `0` failures, `0` errors, `0` skipped.
- Static integrity: `git diff --check` passed before the implementation commit.

## Windows-only verification still required

This macOS worker does not have `pwsh` or Windows PowerShell, so it could not execute the Windows startup script. Before release, run the existing Windows workflow or its equivalent and confirm:

1. An unrelated HTTP listener on 8080 is rejected by `start-local.cmd -NoBrowser -Smoke`.
2. `-Smoke` starts, probes, and stops with only a unique `target/windows-startup-smoke-*` database and no `data/` or `data-backups/` changes.
3. A nonempty, no-history legacy `data/family-finance.*.db` set produces exactly one verified timestamped backup before migration; a second same-second backup remains collision-safe.
4. The completed backup can be restored using all listed database companions, while a deliberately interrupted copy remains `.partial` and prevents startup.
