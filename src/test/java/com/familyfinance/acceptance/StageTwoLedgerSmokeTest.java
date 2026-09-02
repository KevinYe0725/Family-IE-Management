package com.familyfinance.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.familyfinance.FamilyFinanceApplication;
import com.familyfinance.ledger.recurring.RecurringService;
import com.familyfinance.shared.ApiEnvelope;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class StageTwoLedgerSmokeTest {

    private static final String EMAIL = "stage-two-ledger-owner@example.com";
    private static final String PASSWORD = "stage-two-pass-2026";
    private static final String PERIOD = "2026-09";

    @TempDir
    Path tempDir;

    @Test
    void realHttpLedgerFlowSurvivesACompleteApplicationRestart() throws Exception {
        Path database = tempDir.resolve("stage-two-ledger").toAbsolutePath();
        State state;
        RunningApplication first = start(database);
        try {
            assertThat(first.context().getEnvironment().getProperty("app.scheduling.enabled"))
                    .isEqualTo("false");
            assertThat(first.context().containsBean(
                    TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)).isFalse();
            Api anonymous = first.newApi();
            assertThat(anonymous.get("/api/accounts").statusCode()).isEqualTo(401);
            assertThat(anonymous.write("POST", "/api/auth/register", """
                    {"email":"%s","displayName":"第二阶段所有者","password":"%s",
                     "mode":"CREATE","householdName":"第二阶段验收家庭"}
                    """.formatted(EMAIL, PASSWORD)).statusCode()).isEqualTo(201);

            Api owner = first.newApi();
            assertThat(owner.login(EMAIL, PASSWORD).statusCode()).isEqualTo(200);
            JsonNode session = owner.data(owner.get("/api/session"));
            long userId = session.path("userId").asLong();
            long householdId = session.path("householdId").asLong();
            assertThat(session.path("role").asString()).isEqualTo("OWNER");

            HttpResponse<String> missingCsrf = owner.send(HttpRequest.newBuilder(owner.uri("/api/accounts"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(accountBody()))
                    .build());
            assertThat(missingCsrf.statusCode()).isEqualTo(403);

            JsonNode initialAccounts = owner.data(owner.get("/api/accounts")).path("items");
            assertThat(initialAccounts).hasSize(1);
            long defaultAccountId = initialAccounts.get(0).path("id").asLong();
            assertAccount(initialAccounts.get(0), defaultAccountId, "默认账户", "CASH", "0.00");
            assertThat(owner.data(owner.get("/api/budgets?periodMonth=" + PERIOD))).isEmpty();
            long accountId = owner.data(owner.expectStatus(
                    owner.write("POST", "/api/accounts", accountBody()), 201)).path("id").asLong();

            JsonNode members = owner.data(owner.get("/api/members"));
            assertThat(members).hasSize(1);
            long memberId = members.get(0).path("id").asLong();

            long parentCategoryId = owner.data(owner.expectStatus(owner.write("POST", "/api/categories", """
                    {"kind":"EXPENSE","name":"居家服务","color":"#345678","parentId":null}
                    """), 201)).path("id").asLong();
            long childCategoryId = owner.data(owner.expectStatus(owner.write("POST", "/api/categories", """
                    {"kind":"EXPENSE","name":"物业费","color":"#456789","parentId":%d}
                    """.formatted(parentCategoryId)), 201)).path("id").asLong();
            assertHierarchy(owner, parentCategoryId, childCategoryId);

            JsonNode createdBudget = owner.data(owner.expectStatus(owner.write("POST", "/api/budgets", """
                    {"periodMonth":"%s","scopeType":"CATEGORY","categoryId":%d,"amount":"500.00"}
                    """.formatted(PERIOD, childCategoryId)), 201));
            long budgetId = createdBudget.path("id").asLong();
            int version = createdBudget.path("version").asInt();
            JsonNode updatedBudget = owner.data(owner.write("PATCH", "/api/budgets/" + budgetId, """
                    {"version":%d,"amount":"1000.00"}
                    """.formatted(version)));
            assertThat(updatedBudget.path("amount").asString()).isEqualTo("1000.00");
            int budgetVersion = updatedBudget.path("version").asInt();
            long revisionId = assertBudgetRevision(owner, budgetId, childCategoryId, userId, null);

            JsonNode createdRule = owner.data(owner.expectStatus(owner.write("POST", "/api/recurring-rules", """
                    {"kind":"EXPENSE","amount":"123.45","scheduleType":"MONTHLY","intervalValue":1,
                     "dayOfMonth":3,"startOn":"2026-09-03","accountId":%d,"memberId":%d,
                     "categoryId":%d,"assignedUserId":%d,"paused":false}
                    """.formatted(accountId, memberId, childCategoryId, userId)), 201));
            long ruleId = createdRule.path("id").asLong();
            assertRule(createdRule, ruleId, accountId, memberId, childCategoryId, userId, "2026-09-03");
            assertBudgetUsage(owner, budgetId, "0.00", "1000.00");

            HttpResponse<String> generation = owner.write("POST", "/api/__acceptance/recurring-generation", "{}");
            assertThat(generation.statusCode()).isEqualTo(200);
            assertThat(owner.data(generation).path("created").asInt()).isEqualTo(1);

            JsonNode pending = owner.data(owner.get(
                    "/api/recurring-occurrences?status=PENDING&from=2026-09-03&to=2026-09-03"));
            assertThat(pending).hasSize(1);
            long occurrenceId = pending.get(0).path("id").asLong();
            assertOccurrence(pending.get(0), occurrenceId, ruleId, userId, "PENDING", null);

            JsonNode firstConfirmation = owner.data(owner.write(
                    "POST", "/api/recurring-occurrences/" + occurrenceId + "/confirm", null));
            JsonNode repeatedConfirmation = owner.data(owner.write(
                    "POST", "/api/recurring-occurrences/" + occurrenceId + "/confirm", null));
            long transactionId = firstConfirmation.path("confirmedTransactionId").asLong();
            assertThat(firstConfirmation.path("status").asString()).isEqualTo("CONFIRMED");
            assertThat(transactionId).isPositive()
                    .isEqualTo(repeatedConfirmation.path("confirmedTransactionId").asLong());
            assertExactlyOneRecurringTransaction(
                    owner, transactionId, accountId, memberId, parentCategoryId, childCategoryId);
            assertBudgetUsage(owner, budgetId, "123.45", "876.55");
            state = new State(
                    householdId, userId, memberId, defaultAccountId, accountId, parentCategoryId, childCategoryId,
                    budgetId, budgetVersion, revisionId, ruleId, occurrenceId, transactionId);
        } finally {
            first.close();
        }
        assertThat(first.context().isActive()).isFalse();
        assertMigrationVersions(database);
        assertReciprocalRecurringLink(database, state);

        try (RunningApplication restarted = start(database)) {
            Api owner = restarted.newApi();
            assertThat(owner.login(EMAIL, PASSWORD).statusCode()).isEqualTo(200);
            assertPersistedState(owner, state);
        }
        assertReciprocalRecurringLink(database, state);
    }

    private RunningApplication start(Path database) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(
                FamilyFinanceApplication.class, FixedClockConfiguration.class, RecurringGenerationController.class)
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + h2Url(database),
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=",
                        "--spring.jpa.hibernate.ddl-auto=validate",
                        "--app.seed.enabled=false",
                        "--app.scheduling.enabled=false",
                        "--spring.main.banner-mode=off");
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        return new RunningApplication(context, port, context.getBean(ObjectMapper.class));
    }

    private static void assertHierarchy(Api api, long parentId, long childId) throws Exception {
        JsonNode roots = api.data(api.get("/api/categories?projection=tree&page=0&size=50"));
        JsonNode parent = findById(roots, parentId);
        assertThat(parent.path("id").asLong()).isEqualTo(parentId);
        assertThat(parent.path("kind").asString()).isEqualTo("expense");
        assertThat(parent.path("name").asString()).isEqualTo("居家服务");
        assertThat(parent.path("color").asString()).isEqualTo("#345678");
        assertThat(parent.path("defaultCategory").asBoolean()).isFalse();
        assertThat(parent.path("createdAt").asString()).isEqualTo("2026-09-03T02:00:00Z");
        assertThat(parent.path("level").asInt()).isEqualTo(1);
        assertThat(parent.path("parentId").isNull()).isTrue();
        assertThat(parent.path("children")).hasSize(1);
        JsonNode child = findById(parent.path("children"), childId);
        assertThat(child.path("id").asLong()).isEqualTo(childId);
        assertThat(child.path("kind").asString()).isEqualTo("expense");
        assertThat(child.path("name").asString()).isEqualTo("物业费");
        assertThat(child.path("color").asString()).isEqualTo("#456789");
        assertThat(child.path("defaultCategory").asBoolean()).isFalse();
        assertThat(child.path("createdAt").asString()).isEqualTo("2026-09-03T02:00:00Z");
        assertThat(child.path("level").asInt()).isEqualTo(2);
        assertThat(child.path("parentId").asLong()).isEqualTo(parentId);
        assertThat(child.path("children")).isEmpty();
    }

    private static long assertBudgetRevision(
            Api api, long budgetId, long categoryId, long userId, Long expectedRevisionId)
            throws Exception {
        JsonNode revisions = api.data(api.get("/api/budgets/" + budgetId + "/revisions"));
        assertThat(revisions).hasSize(1);
        JsonNode revision = revisions.get(0);
        long revisionId = revision.path("id").asLong();
        if (expectedRevisionId != null) {
            assertThat(revisionId).isEqualTo(expectedRevisionId);
        }
        assertThat(revision.path("budgetId").asLong()).isEqualTo(budgetId);
        assertThat(revision.path("oldPeriodMonth").asString()).isEqualTo(PERIOD);
        assertThat(revision.path("newPeriodMonth").asString()).isEqualTo(PERIOD);
        assertThat(revision.path("oldScopeType").asString()).isEqualTo("CATEGORY");
        assertThat(revision.path("newScopeType").asString()).isEqualTo("CATEGORY");
        assertThat(revision.path("oldCategoryId").asLong()).isEqualTo(categoryId);
        assertThat(revision.path("newCategoryId").asLong()).isEqualTo(categoryId);
        assertThat(revision.path("oldMemberId").isNull()).isTrue();
        assertThat(revision.path("newMemberId").isNull()).isTrue();
        assertThat(revision.path("oldAmount").asString()).isEqualTo("500.00");
        assertThat(revision.path("newAmount").asString()).isEqualTo("1000.00");
        assertThat(revision.path("oldActive").asBoolean()).isTrue();
        assertThat(revision.path("newActive").asBoolean()).isTrue();
        assertThat(revision.path("changedByUserId").asLong()).isEqualTo(userId);
        assertThat(revision.path("changedAt").asString()).isEqualTo("2026-09-03T02:00:00Z");
        return revisionId;
    }

    private static void assertBudgetUsage(Api api, long budgetId, String spent, String remaining) throws Exception {
        JsonNode usage = api.data(api.get("/api/budgets/usage?periodMonth=" + PERIOD));
        assertThat(usage).hasSize(1);
        assertThat(usage.get(0).at("/budget/id").asLong()).isEqualTo(budgetId);
        assertThat(usage.get(0).path("spent").asString()).isEqualTo(spent);
        assertThat(usage.get(0).path("remaining").asString()).isEqualTo(remaining);
        assertThat(new BigDecimal(usage.get(0).path("percent").asString()))
                .isEqualByComparingTo("0.00".equals(spent) ? "0.00" : "12.35");
        assertThat(usage.get(0).path("status").asString()).isEqualTo("ON_TRACK");
        assertThat(usage.get(0).path("rollupCategories").asBoolean()).isFalse();
    }

    private static void assertExactlyOneRecurringTransaction(
            Api api,
            long transactionId,
            long accountId,
            long memberId,
            long parentCategoryId,
            long childCategoryId) throws Exception {
        JsonNode transactions = api.data(api.get("/api/transactions?month=" + PERIOD
                + "&accountId=" + accountId + "&categoryId=" + childCategoryId));
        assertThat(transactions).hasSize(1);
        JsonNode transaction = transactions.get(0);
        assertThat(transaction.path("id").asLong()).isEqualTo(transactionId);
        assertThat(transaction.path("kind").asString()).isEqualTo("expense");
        assertThat(transaction.path("amount").asString()).isEqualTo("123.45");
        assertThat(transaction.path("occurredOn").asString()).isEqualTo("2026-09-03");
        assertThat(transaction.path("accountId").asLong()).isEqualTo(accountId);
        assertThat(transaction.path("accountName").asString()).isEqualTo("验收银行卡");
        assertThat(transaction.path("memberId").asLong()).isEqualTo(memberId);
        assertThat(transaction.path("memberName").asString()).isEqualTo("第二阶段所有者");
        assertThat(transaction.path("categoryId").asLong()).isEqualTo(childCategoryId);
        assertThat(transaction.path("categoryName").asString()).isEqualTo("物业费");
        assertThat(transaction.path("categoryParentId").asLong()).isEqualTo(parentCategoryId);
        assertThat(transaction.path("categoryLevel").asInt()).isEqualTo(2);
        assertThat(transaction.path("merchant").isNull()).isTrue();
        assertThat(transaction.path("location").isNull()).isTrue();
        assertThat(transaction.path("note").asString()).isEqualTo("周期账单");
        assertThat(transaction.path("createdAt").asString()).isEqualTo("2026-09-03T02:00:00Z");
        assertThat(transaction.path("updatedAt").asString()).isEqualTo("2026-09-03T02:00:00Z");
    }

    private static void assertPersistedState(Api api, State state) throws Exception {
        JsonNode session = api.data(api.get("/api/session"));
        assertThat(session.path("householdId").asLong()).isEqualTo(state.householdId());
        assertThat(session.path("userId").asLong()).isEqualTo(state.userId());
        assertThat(session.path("email").asString()).isEqualTo(EMAIL);
        assertThat(session.path("displayName").asString()).isEqualTo("第二阶段所有者");
        assertThat(session.path("role").asString()).isEqualTo("OWNER");
        assertThat(session.path("username").asString()).isEqualTo(EMAIL);

        JsonNode accounts = api.data(api.get("/api/accounts?page=0&size=50")).path("items");
        assertThat(ids(accounts)).containsExactlyInAnyOrder(state.defaultAccountId(), state.accountId());
        assertAccount(api.data(api.get("/api/accounts/" + state.defaultAccountId())),
                state.defaultAccountId(), "默认账户", "CASH", "0.00");
        assertAccount(api.data(api.get("/api/accounts/" + state.accountId())),
                state.accountId(), "验收银行卡", "BANK", "1000.00");

        JsonNode member = findById(api.data(api.get("/api/members")), state.memberId());
        assertThat(member.path("id").asLong()).isEqualTo(state.memberId());
        assertThat(member.path("name").asString()).isEqualTo("第二阶段所有者");
        assertThat(member.path("roleLabel").asString()).isEqualTo("所有者");
        assertHierarchy(api, state.parentCategoryId(), state.childCategoryId());

        JsonNode budget = api.data(api.get("/api/budgets/" + state.budgetId()));
        assertBudget(budget, state);
        assertBudgetRevision(
                api, state.budgetId(), state.childCategoryId(), state.userId(), state.revisionId());

        JsonNode rules = api.data(api.get("/api/recurring-rules?includeInactive=true"));
        JsonNode rule = findById(rules, state.ruleId());
        assertRule(rule, state.ruleId(), state.accountId(), state.memberId(),
                state.childCategoryId(), state.userId(), "2026-10-03");

        JsonNode occurrences = api.data(api.get("/api/recurring-occurrences?status=CONFIRMED"));
        JsonNode occurrence = findById(occurrences, state.occurrenceId());
        assertOccurrence(occurrence, state.occurrenceId(), state.ruleId(), state.userId(),
                "CONFIRMED", state.transactionId());

        JsonNode repeatedConfirmation = api.data(api.write(
                "POST", "/api/recurring-occurrences/" + state.occurrenceId() + "/confirm", null));
        assertThat(repeatedConfirmation.path("confirmedTransactionId").asLong()).isEqualTo(state.transactionId());
        assertExactlyOneRecurringTransaction(
                api, state.transactionId(), state.accountId(), state.memberId(),
                state.parentCategoryId(), state.childCategoryId());
        assertBudgetUsage(api, state.budgetId(), "123.45", "876.55");
    }

    private static void assertAccount(
            JsonNode account, long id, String name, String type, String openingBalance) {
        assertThat(account.path("id").asLong()).isEqualTo(id);
        assertThat(account.path("name").asString()).isEqualTo(name);
        assertThat(account.path("type").asString()).isEqualTo(type);
        assertThat(account.path("currency").asString()).isEqualTo("CNY");
        assertThat(account.path("openingBalance").asString()).isEqualTo(openingBalance);
        assertThat(account.path("archivedAt").isNull()).isTrue();
    }

    private static void assertBudget(JsonNode budget, State state) {
        assertThat(budget.path("id").asLong()).isEqualTo(state.budgetId());
        assertThat(budget.path("periodMonth").asString()).isEqualTo(PERIOD);
        assertThat(budget.path("scopeType").asString()).isEqualTo("CATEGORY");
        assertThat(budget.path("categoryId").asLong()).isEqualTo(state.childCategoryId());
        assertThat(budget.path("memberId").isNull()).isTrue();
        assertThat(budget.path("amount").asString()).isEqualTo("1000.00");
        assertThat(budget.path("version").asInt()).isEqualTo(state.budgetVersion());
        assertThat(budget.path("active").asBoolean()).isTrue();
    }

    private static void assertRule(
            JsonNode rule,
            long ruleId,
            long accountId,
            long memberId,
            long categoryId,
            long userId,
            String nextDueOn) {
        assertThat(rule.path("id").asLong()).isEqualTo(ruleId);
        assertThat(rule.path("kind").asString()).isEqualTo("expense");
        assertThat(rule.path("amount").asString()).isEqualTo("123.45");
        assertThat(rule.path("scheduleType").asString()).isEqualTo("MONTHLY");
        assertThat(rule.path("intervalValue").asInt()).isEqualTo(1);
        assertThat(rule.path("dayOfMonth").asInt()).isEqualTo(3);
        assertThat(rule.path("dayOfWeek").isNull()).isTrue();
        assertThat(rule.path("startOn").asString()).isEqualTo("2026-09-03");
        assertThat(rule.path("endOn").isNull()).isTrue();
        assertThat(rule.path("nextDueOn").asString()).isEqualTo(nextDueOn);
        assertThat(rule.path("accountId").asLong()).isEqualTo(accountId);
        assertThat(rule.path("accountName").asString()).isEqualTo("验收银行卡");
        assertThat(rule.path("memberId").asLong()).isEqualTo(memberId);
        assertThat(rule.path("memberName").asString()).isEqualTo("第二阶段所有者");
        assertThat(rule.path("categoryId").asLong()).isEqualTo(categoryId);
        assertThat(rule.path("categoryName").asString()).isEqualTo("物业费");
        assertThat(rule.path("assignedUserId").asLong()).isEqualTo(userId);
        assertThat(rule.path("assignedUserName").asString()).isEqualTo("第二阶段所有者");
        assertThat(rule.path("active").asBoolean()).isTrue();
        assertThat(rule.path("paused").asBoolean()).isFalse();
        assertThat(rule.path("createdByUserId").asLong()).isEqualTo(userId);
    }

    private static void assertOccurrence(
            JsonNode occurrence,
            long occurrenceId,
            long ruleId,
            long assignedUserId,
            String status,
            Long transactionId) {
        assertThat(occurrence.path("id").asLong()).isEqualTo(occurrenceId);
        assertThat(occurrence.path("ruleId").asLong()).isEqualTo(ruleId);
        assertThat(occurrence.path("dueOn").asString()).isEqualTo("2026-09-03");
        assertThat(occurrence.path("status").asString()).isEqualTo(status);
        assertThat(occurrence.path("assignedUserId").asLong()).isEqualTo(assignedUserId);
        if (transactionId == null) {
            assertThat(occurrence.path("confirmedTransactionId").isNull()).isTrue();
        } else {
            assertThat(occurrence.path("confirmedTransactionId").asLong()).isEqualTo(transactionId);
        }
    }

    private static List<Long> ids(JsonNode items) {
        List<Long> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    private static JsonNode findById(JsonNode items, long id) {
        for (JsonNode item : items) {
            if (item.path("id").asLong() == id) {
                return item;
            }
        }
        throw new AssertionError("Expected id " + id + " in " + items);
    }

    private static void assertMigrationVersions(Path database) throws Exception {
        List<String> versions = new ArrayList<>();
        try (var connection = DriverManager.getConnection(h2Url(database), "sa", "");
                var statement = connection.createStatement();
                var rows = statement.executeQuery("""
                        select "version" from "flyway_schema_history"
                        where "success" = true and "version" is not null order by "installed_rank"
                        """)) {
            while (rows.next()) {
                versions.add(rows.getString(1));
            }
        }
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
    }

    private static void assertReciprocalRecurringLink(Path database, State state) throws Exception {
        try (var connection = DriverManager.getConnection(h2Url(database), "sa", "");
                var statement = connection.prepareStatement("""
                        select count(*)
                        from financial_transactions transaction_row
                        join recurring_occurrences occurrence_row
                          on occurrence_row.confirmed_transaction_id = transaction_row.id
                         and transaction_row.source_id = occurrence_row.id
                        where transaction_row.id = ?
                          and transaction_row.source_type = 'RECURRING'
                          and occurrence_row.id = ?
                          and occurrence_row.rule_id = ?
                          and occurrence_row.status = 'CONFIRMED'
                        """)) {
            statement.setLong(1, state.transactionId());
            statement.setLong(2, state.occurrenceId());
            statement.setLong(3, state.ruleId());
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(1);
            }
        }
    }

    private static String accountBody() {
        return """
                {"name":"验收银行卡","type":"BANK","currency":"CNY","openingBalance":"1000.00"}
                """;
    }

    private static String h2Url(Path database) {
        return "jdbc:h2:file:" + database + ";DB_CLOSE_ON_EXIT=FALSE";
    }

    private record State(
            long householdId,
            long userId,
            long memberId,
            long defaultAccountId,
            long accountId,
            long parentCategoryId,
            long childCategoryId,
            long budgetId,
            int budgetVersion,
            long revisionId,
            long ruleId,
            long occurrenceId,
            long transactionId) {
    }

    private record RunningApplication(ConfigurableApplicationContext context, int port, ObjectMapper objectMapper)
            implements AutoCloseable {
        Api newApi() {
            return new Api(port, objectMapper);
        }

        @Override
        public void close() {
            context.close();
        }
    }

    private static final class Api {
        private final int port;
        private final ObjectMapper objectMapper;
        private final HttpClient client;

        private Api(int port, ObjectMapper objectMapper) {
            this.port = port;
            this.objectMapper = objectMapper;
            this.client = HttpClient.newBuilder()
                    .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                    .build();
        }

        HttpResponse<String> get(String path) throws Exception {
            return send(HttpRequest.newBuilder(uri(path)).GET().build());
        }

        HttpResponse<String> login(String username, String password) throws Exception {
            String form = Map.of("username", username, "password", password).entrySet().stream()
                    .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                            + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                    .reduce((left, right) -> left + "&" + right)
                    .orElseThrow();
            Csrf csrf = csrf();
            return send(HttpRequest.newBuilder(uri("/api/auth/login"))
                    .header(csrf.header(), csrf.token())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build());
        }

        HttpResponse<String> write(String method, String path, String body) throws Exception {
            Csrf csrf = csrf();
            HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header(csrf.header(), csrf.token());
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            return send(request.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build());
        }

        HttpResponse<String> expectStatus(HttpResponse<String> response, int expected) {
            assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
            return response;
        }

        JsonNode data(HttpResponse<String> response) throws Exception {
            assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
            return objectMapper.readTree(response.body()).path("data");
        }

        HttpResponse<String> send(HttpRequest request) throws Exception {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        URI uri(String path) {
            return URI.create("http://127.0.0.1:" + port + path);
        }

        private Csrf csrf() throws Exception {
            JsonNode csrf = data(get("/api/csrf"));
            return new Csrf(csrf.path("headerName").asString(), csrf.path("token").asString());
        }
    }

    private record Csrf(String header, String token) {
    }

    @Configuration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock stageTwoLedgerClock() {
            return Clock.fixed(Instant.parse("2026-09-03T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        }
    }

    @RestController
    static class RecurringGenerationController {
        private final RecurringService recurring;

        RecurringGenerationController(RecurringService recurring) {
            this.recurring = recurring;
        }

        @PostMapping("/api/__acceptance/recurring-generation")
        ApiEnvelope<Map<String, Integer>> generate() {
            return ApiEnvelope.data(Map.of("created", recurring.generateDueOccurrences()));
        }
    }
}
