# Family Finance MySQL Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the production H2 runtime with MySQL 8 and run the application on the Ubuntu server while keeping H2 only for isolated tests.

**Architecture:** Production defaults to MySQL-specific Flyway V1–V12 migrations. H2 remains test-scoped and is activated through the test profile during Maven tests. The server runs a packaged Spring Boot application under systemd with credentials supplied only by a root-readable environment file.

**Tech Stack:** Java 17, Spring Boot 4.1.1, Spring Data JPA, Flyway, MySQL 8.0, MySQL Connector/J, H2 test runtime, Maven Wrapper, Ubuntu systemd.

**Spec:** docs/superpowers/specs/2026-09-03-family-finance-mysql-server-design.md

## Global Constraints

- Production runtime database is MySQL 8; H2 is test-only.
- Flyway remains authoritative; Hibernate uses ddl-auto=validate.
- MySQL credentials must never be committed, logged, or sent through chat.
- The server currently has no application database; initialize a fresh schema and do not import local H2 files.
- Keep the application bound to 127.0.0.1 until a reverse proxy is explicitly requested.
- Work in the existing isolated worktree on branch codex/family-finance-stage-2.

---

### Task 1: Switch production dependencies and configuration

**Files:**
- Modify: pom.xml
- Modify: src/main/resources/application.yml
- Modify: src/test/resources/application-test.yml
- Test: Maven Spring test context under the test profile

**Interfaces:**
- Production consumes SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, and SPRING_DATASOURCE_PASSWORD.
- Production Flyway loads classpath:db/migration-mysql.
- Tests use the existing in-memory H2 URL and classpath:db/migration.

- [ ] **Step 1: Add MySQL modules and retain H2 for tests**

Add these dependencies to pom.xml:

~~~xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
~~~

Change the existing H2 dependency scope from runtime to test.

- [ ] **Step 2: Force Spring tests onto the isolated profile**

Configure maven-surefire-plugin with:

~~~xml
<configuration>
    <systemPropertyVariables>
        <spring.profiles.active>test</spring.profiles.active>
    </systemPropertyVariables>
</configuration>
~~~

This covers test classes that use @SpringBootTest without @ActiveProfiles("test").

- [ ] **Step 3: Make MySQL the production default datasource**

Replace the H2 datasource and Flyway location in src/main/resources/application.yml with:

~~~yaml
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
    driver-class-name: com.mysql.cj.jdbc.Driver
  flyway:
    baseline-on-migrate: true
    baseline-version: 1
    locations: classpath:db/migration/mysql
~~~

Keep ddl-auto=validate, open-in-view=false, and the current server binding.

- [ ] **Step 4: Keep test resources explicitly H2-backed**

In src/test/resources/application-test.yml, retain the current H2 URL and set:

~~~yaml
spring:
  flyway:
    locations: classpath:db/migration
~~~

- [ ] **Step 5: Verify this boundary**

~~~bash
./mvnw -q -DskipTests compile
./mvnw -q -Dtest=FamilyFinanceApplicationTest test
~~~

Expected: production classes compile with MySQL Connector/J, and the Spring test uses H2 without a MySQL server.

- [ ] **Step 6: Commit**

~~~bash
git add pom.xml src/main/resources/application.yml src/test/resources/application-test.yml
git commit -m "feat: configure MySQL production runtime"
~~~

---

### Task 2: Add MySQL-compatible Flyway migrations

**Files:**
- Create: src/main/resources/db/migration-mysql/V1__stage1_schema.sql
- Create: src/main/resources/db/migration-mysql/V2__identity_and_family_roles.sql
- Create: src/main/resources/db/migration-mysql/V3__repair_demo_member_links.sql
- Create: src/main/resources/db/migration-mysql/V4__accounts_categories_recurring_budgets.sql
- Create: src/main/resources/db/migration-mysql/V5__budget_revision_snapshots.sql
- Create: src/main/resources/db/migration-mysql/V6__recurring_rule_schedule_state.sql
- Create: src/main/resources/db/migration-mysql/V7__ledger_relationship_integrity.sql
- Create: src/main/resources/db/migration-mysql/V8__assets_investments_quotes.sql
- Create: src/main/resources/db/migration-mysql/V9__asset_valuation_fetch_time.sql
- Create: src/main/resources/db/migration-mysql/V10__investment_account_creator.sql
- Create: src/main/resources/db/migration-mysql/V11__loans_notifications_snapshots.sql
- Create: src/main/resources/db/migration-mysql/V12__loan_payments_and_prepayment_requests.sql

**Interfaces:** Flyway sees exactly V1–V12 under the MySQL location, and table, column, constraint, and index semantics match the existing H2 history.

- [ ] **Step 1: Create the MySQL migration directory and one file per existing version**

~~~bash
mkdir -p src/main/resources/db/migration/mysql
~~~

Keep the version numbers and filenames unchanged.

- [ ] **Step 2: Convert identity and time columns**

Convert every identity declaration:

~~~sql
id bigint generated by default as identity primary key
~~~

to:

~~~sql
id bigint auto_increment primary key
~~~

Convert every timestamp with time zone column or alteration to datetime(6), preserving nullability and defaults.

- [ ] **Step 3: Convert generated columns**

For generated columns in V5, V7, and V11, retain each existing expression and use MySQL's typed stored form:

~~~sql
scope_target_key bigint generated always as (
    case when scope = 'HOUSEHOLD' then 0 else target_member_id end
) stored
~~~

Preserve all unique constraints and indexes that depend on these columns.

- [ ] **Step 4: Preserve MySQL 8 constraint semantics**

Keep all check constraints, foreign keys, unique keys, and indexes. Replace only H2-only expression syntax; retain current semantics for regexp_like, concat, substring, and trimmed-length checks.

- [ ] **Step 5: Validate the migration source before server execution**

~~~bash
rg -n -i 'generated by default as identity|timestamp with time zone|varchar_ignorecase|create alias|merge into' src/main/resources/db/migration-mysql
~~~

Expected: no H2 identity/time-zone syntax or H2-only commands remain.

- [ ] **Step 6: Commit**

~~~bash
git add src/main/resources/db/migration-mysql
git commit -m "feat: add MySQL Flyway migrations"
~~~

---

### Task 3: Update documentation and package verification

**Files:**
- Modify: README.md
- Create: docs/operations/mysql-server-runbook.md

**Interfaces:** Documentation states that server runtime uses MySQL 8, tests use isolated H2, and credentials never appear in source.

- [ ] **Step 1: Update README runtime instructions**

Replace H2 production instructions with MySQL 8 environment variables and systemd commands. Keep H2 references only in the test section.

- [ ] **Step 2: Add a credential-safe server runbook**

Document these package prerequisites without embedding any password or token:

~~~bash
sudo apt update
sudo apt install -y openjdk-17-jdk mysql-server openssl
sudo systemctl enable --now mysql
~~~

Document server-local password generation and a root-readable environment file.

- [ ] **Step 3: Run the full package check**

~~~bash
./mvnw -q package
~~~

Expected: frontend build, Java compilation, and the H2-backed test suite pass without a local MySQL server.

- [ ] **Step 4: Commit**

~~~bash
git add README.md docs/operations/mysql-server-runbook.md
git commit -m "docs: add MySQL server runbook"
~~~

---

### Task 4: Provision MySQL and systemd on the server

**Server paths:** /root/Family-IE-Management, /etc/family-finance/family-finance.env, /etc/systemd/system/family-finance.service

- [ ] **Step 1: Pull the approved branch**

~~~bash
cd /root/Family-IE-Management
git pull --ff-only origin codex/family-finance-stage-2
~~~

- [ ] **Step 2: Install Java 17 and MySQL 8**

~~~bash
apt update
apt install -y openjdk-17-jdk mysql-server openssl
systemctl enable --now mysql
~~~

- [ ] **Step 3: Create a local database and account without printing the password**

Run as root; generate the password inside the server shell, create family_finance, grant only family_finance.* privileges, and write SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, and SPRING_DATASOURCE_PASSWORD to /etc/family-finance/family-finance.env with mode 600. Do not echo the generated value.

- [ ] **Step 4: Build the server artifact**

~~~bash
cd /root/Family-IE-Management
./mvnw -q package
~~~

- [ ] **Step 5: Create and start the systemd unit**

Use WorkingDirectory=/root/Family-IE-Management, EnvironmentFile=/etc/family-finance/family-finance.env, ExecStart=/usr/bin/java -jar /root/Family-IE-Management/target/family-finance-0.0.1-SNAPSHOT.jar, After=network.target mysql.service, Requires=mysql.service, and Restart=on-failure. Keep the current 127.0.0.1:8080 binding.

~~~bash
systemctl daemon-reload
systemctl enable --now family-finance
systemctl status family-finance --no-pager
journalctl -u family-finance -n 120 --no-pager
~~~

Expected: MySQL connection succeeds, Flyway applies V1–V12, Hibernate validates the schema, and Tomcat starts on port 8080.

---

### Task 5: Real MySQL acceptance and handoff

**Interfaces:** GET /api/csrf returns HTTP 200; registration creates a durable user and household; service restart does not remove the account.

- [ ] **Step 1: Check schema and HTTP readiness**

~~~bash
curl --fail --silent --show-error http://127.0.0.1:8080/api/csrf
mysql --protocol=socket -uroot -e "SELECT VERSION(); SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='family_finance';"
~~~

- [ ] **Step 2: Register one uniquely named acceptance account**

Use the repository's CSRF-cookie flow to register a unique email and household. Do not print or commit the acceptance password.

- [ ] **Step 3: Restart and verify the same account**

~~~bash
systemctl restart family-finance
sleep 3
curl --fail --silent --show-error http://127.0.0.1:8080/api/csrf
~~~

Log in with the acceptance account and query the household endpoint; it must return the same household after restart.

- [ ] **Step 4: Verify repository cleanliness**

~~~bash
cd /root/Family-IE-Management
git status --short --branch
git diff --check
~~~

Expected: clean Git tree; the environment file and MySQL data are outside the repository.

- [ ] **Step 5: Handoff**

Report the active branch/commit, Flyway version, service commands, and SSH-tunnel access method. Never report the database password.
