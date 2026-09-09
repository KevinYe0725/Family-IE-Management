package com.familyfinance.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.family.CurrentMembership;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LoanDebtOverviewServiceTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired CurrentMembership memberships;
    @Autowired LoanRepository loans;
    @Autowired EntityManager entityManager;

    MockHttpSession session;
    long household;
    long member;
    long user;
    long category;
    long account;

    @BeforeEach
    void setUp() throws Exception {
        String email = UUID.randomUUID() + "@loan-overview.test";
        mvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"displayName\":\"Overview\",\"password\":\"loan-test-password\",\"mode\":\"CREATE\",\"householdName\":\"Overview\"}"))
                .andExpect(status().isCreated());
        session = (MockHttpSession) mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .param("username", email)
                        .param("password", "loan-test-password"))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);
        user = jdbc.queryForObject("select id from app_users where email=?", Long.class, email);
        household = jdbc.queryForObject("select household_id from app_users where id=?", Long.class, user);
        member = jdbc.queryForObject("select id from family_members where household_id=?", Long.class, household);
        category = jdbc.queryForObject("select min(id) from categories where household_id=? and kind='EXPENSE'", Long.class, household);
        account = jdbc.queryForObject("select id from financial_accounts where household_id=?", Long.class, household);
    }

    @Test
    void countsLegacyPrepaymentEventWhenItsHistoricalTransactionIsNull() throws Exception {
        long loan = createLoan("legacy", "1000.00", "0.100000", "2026-09-10",
                new String[][]{{"2026-09-10", "1000.00", "0.00"}});
        jdbc.update("insert into loan_prepayments(household_id,loan_id,request_key,amount,interest_amount,operation_kind,paid_on,created_at) values(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                household, loan, "legacy-null-transaction", new BigDecimal("100.00"), new BigDecimal("2.00"),
                "PREPAYMENT", LocalDate.of(2026, 9, 1));

        LoanDebtOverviewResponse overview = service(Clock.fixed(Instant.parse("2026-09-09T16:30:00Z"), ZoneOffset.UTC))
                .overview(authentication());

        assertThat(overview.paidRepayment()).isEqualTo("102.00");
    }

    @Test
    void usesShanghaiBusinessDateForOverviewAndLoanTotals() throws Exception {
        long loan = createLoan("overnight", "400.00", "0.100000", "2026-09-10", new String[][]{
                {"2026-09-09", "100.00", "0.00"},
                {"2026-09-10", "100.00", "0.00"},
                {"2026-10-09", "100.00", "0.00"},
                {"2026-10-10", "100.00", "0.00"},
        });
        Clock justAfterShanghaiMidnight = Clock.fixed(Instant.parse("2026-09-09T16:30:00Z"), ZoneOffset.UTC);

        LoanDebtOverviewResponse overview = service(justAfterShanghaiMidnight).overview(authentication());
        assertThat(overview.overdueInstallments()).isEqualTo(1);
        assertThat(overview.overdueAmount()).isEqualTo("100.00");
        assertThat(overview.overdueDays()).isEqualTo(1);
        assertThat(overview.thirtyDayDue()).isEqualTo("200.00");

        Loan loanEntity = loans.findById(loan).orElseThrow();
        LoanTotalsService.Totals totals = new LoanTotalsService(jdbc, entityManager, justAfterShanghaiMidnight)
                .read(loanEntity, false);
        assertThat(totals.overdueInstallments()).isEqualTo(1);
        assertThat(totals.overdueAmount()).isEqualTo("100.00");
        assertThat(totals.overdueDays()).isEqualTo(1);
    }

    @Test
    void weightsAnnualRateByCurrentRemainingPrincipal() throws Exception {
        long highRate = createLoan("high-rate", "1000.00", "0.100000", "2026-09-10",
                new String[][]{{"2026-10-10", "1000.00", "0.00"}});
        createLoan("zero-rate", "100.00", "0.000000", "2026-09-10",
                new String[][]{{"2026-10-10", "100.00", "0.00"}});
        jdbc.update("update loans set current_principal_amount=100.00 where id=?", highRate);

        LoanDebtOverviewResponse overview = service(Clock.fixed(Instant.parse("2026-09-09T16:30:00Z"), ZoneOffset.UTC))
                .overview(authentication());

        assertThat(overview.remainingPrincipal()).isEqualTo("200.00");
        assertThat(overview.weightedAnnualRatePercent()).isEqualTo("5.00");
    }

    @Test
    void declaresRepeatableReadIsolationForMultiQuerySnapshot() {
        Transactional transactional = LoanDebtOverviewService.class.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    private LoanDebtOverviewService service(Clock clock) {
        return new LoanDebtOverviewService(memberships, jdbc, clock);
    }

    private Authentication authentication() {
        org.springframework.security.core.context.SecurityContext context =
                (org.springframework.security.core.context.SecurityContext) session.getAttribute("SPRING_SECURITY_CONTEXT");
        return context.getAuthentication();
    }

    private long createLoan(String name, String principal, String rate, String startOn, String[][] schedule) throws Exception {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < schedule.length; i++) {
            if (i > 0) rows.append(',');
            rows.append("{\"dueOn\":\"").append(schedule[i][0]).append("\",\"principal\":\"")
                    .append(schedule[i][1]).append("\",\"interest\":\"").append(schedule[i][2]).append("\"}");
        }
        String body = "{\"name\":\"" + name + "\",\"type\":\"OTHER\",\"memberId\":" + member
                + ",\"assignedUserId\":" + user + ",\"paymentAccountId\":" + account
                + ",\"paymentCategoryId\":" + category + ",\"principal\":\"" + principal + "\",\"annualRate\":" + rate
                + ",\"termMonths\":" + schedule.length + ",\"repaymentMethod\":\"CUSTOM\",\"startOn\":\"" + startOn
                + "\",\"fundingMode\":\"OPENING\",\"accountingOn\":\"2026-01-01\",\"customSchedule\":[" + rows + "]}";
        String response = mvc.perform(post("/api/loans")
                        .with(csrf())
                        .session(session)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode data = json.readTree(response).path("data");
        return data.path("id").asLong();
    }
}
