# Plan 1 broad-review Java/API fix report

## Status

DONE

## Implementation commit

- `34951d92fc62e90df9097013563de08a22599edf fix: harden Stage 2 identity APIs`

## Scope delivered

- Added one shared family-mutation authorization path. Every authenticated family mutation now acquires the household lock, clears the persistence context, reloads the caller's active membership under a pessimistic lock, and authorizes from that fresh role. Rename, invite creation/revocation, role mutation, ownership transfer, and archive all use the path.
- Added deterministic lock-barrier regressions for ownership transfer racing family rename, `ADMIN` invite creation, and `ADMIN` invite revocation, plus admin demotion racing member-invite creation.
- Added an atomic fixed-window login limiter at the Spring Security authentication boundary. It is keyed by normalized identifier plus remote IP, maps exact `demo` to `demo@local.family`, stores only SHA-256 digest keys, caps storage at 10,000 buckets with deterministic oldest-entry eviction, accepts an injected `Clock`, resets on successful login, and returns a structured non-enumerating `429 LOGIN_RATE_LIMITED` response.
- Changed `GET /api/family/memberships` to stable `id DESC` page-number pagination. Defaults are `page=0,size=20`, size is clamped to `1..50`, and the response contains `items`, `page`, `size`, `totalElements`, `totalPages`, and `hasNext`.
- Mapped `app_users.status` as `AppUserStatus` with entity and schema default `ACTIVE`. Non-active users receive the generic login failure, while an existing session is invalidated with `401 AUTH_REQUIRED` on its next API request.
- Updated only Java/API implementation and affected Java tests. README and macOS/Linux/Windows launchers were not changed.

## TDD evidence

### RED

1. Family mutation races:

   `./mvnw -q -Dtest=FamilyMutationAuthorizationConcurrencyTest test`

   Failed 4/4 assertions because the paused former owner/admin resumed with the captured pre-lock role and completed the mutation.

2. Membership pagination:

   `./mvnw -q -Dtest=MembershipPaginationApiTest test`

   Failed because `$.data` was an unbounded array and had no pagination metadata.

3. User status:

   `./mvnw -q -Dtest=EmailAuthenticationTest#nonActiveUserCannotAuthenticateAndGetsTheGenericLoginFailure+sessionEstablishedBeforeUserSuspensionIsInvalidatedOnTheNextApiRequest test`

   Failed 2/2 assertions because both the non-active login and the pre-existing session still returned 200.

4. Login throttling:

   `./mvnw -q -Dtest=LoginRateLimiterTest,EmailAuthenticationTest#loginRateLimitIsAccountNonEnumeratingAndScopedByNormalizedIdentifierAndRemoteIp+successfulExactDemoLoginResetsFailuresAndKeepsItsSessionUsable test`

   Failed at test compilation because `LoginRateLimiter` did not exist; the HTTP behavior was also absent from the security chain.

### GREEN

- Mutation and pagination focused suite:

  `./mvnw -q -Dtest=FamilyMutationAuthorizationConcurrencyTest,ArchiveConcurrencyTest,OwnershipInvariantConcurrencyTest,MembershipPaginationApiTest,RolePermissionApiTest,StageTwoFoundationSmokeTest test`

  Exit 0.

- Login limiter and status focused suite:

  `./mvnw -q -Dtest=LoginRateLimiterTest,EmailAuthenticationTest test`

  Exit 0.

- Security/concurrency/Windows preservation suite:

  `./mvnw -q -Dtest=FamilyMutationAuthorizationConcurrencyTest,ArchiveConcurrencyTest,OwnershipInvariantConcurrencyTest,MembershipPaginationApiTest,RolePermissionApiTest,InviteApiTest,LoginRateLimiterTest,EmailAuthenticationTest,AuthenticationApiTest,RegistrationApiTest,RegistrationRequestBodyLimitIntegrationTest,RegistrationRequestBodyLimitContextPathIntegrationTest,RepositoryLockFailureTranslationApiTest,UnexpectedExceptionCorrelationApiTest,WindowsStartupGateIsolationTest test`

  Exit 0.

- Full suite:

  `./mvnw test`

  `BUILD SUCCESS`; 124 tests, 0 failures, 0 errors, 0 skipped.

## Diff and boundary checks

- `git diff --check` and `git diff --cached --check` were clean before the implementation commit.
- The implementation commit changes 20 Java source/test files only.
- No README, launcher, migration SQL, credential file, course document, frontend file, or production database was changed.
- The existing migration, request-correlation, CSRF, request-body limit, repository-lock translation, archive/ownership concurrency, Stage 2 foundation smoke, and Windows isolation tests remain in the passing full suite.
