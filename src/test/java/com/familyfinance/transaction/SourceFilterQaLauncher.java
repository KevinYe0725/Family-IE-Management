package com.familyfinance.transaction;

import com.familyfinance.FamilyFinanceApplication;
import com.familyfinance.category.Category;
import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.household.AppUser;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.ledger.AccountType;
import com.familyfinance.ledger.FinancialAccount;
import com.familyfinance.ledger.FinancialAccountRepository;
import java.time.Instant;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Manual QA only. Always launches a fresh in-memory test database and loopback server. */
public final class SourceFilterQaLauncher {
    private SourceFilterQaLauncher() {}

    public static void main(String[] args) throws Exception {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(FamilyFinanceApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=18801",
                "--spring.profiles.active=test", "--spring.datasource.url=jdbc:h2:mem:qa_source_filter;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.flyway.locations=classpath:db/migration", "--app.seed.enabled=true",
                "--app.scheduling.enabled=false", "--app.fx.scheduled=false");
        try (var connection = context.getBean(DataSource.class).getConnection()) {
            if (!connection.getMetaData().getURL().equals("jdbc:h2:mem:qa_source_filter")) {
                context.close();
                throw new IllegalStateException("Refusing to seed a database other than the QA in-memory H2 database");
            }
        }
        context.getBean(TransactionTemplate.class).executeWithoutResult(status -> seed(context));
        System.out.println("QA_SOURCE_READY http://127.0.0.1:18801/workspace/transactions?month=2026-10");
    }

    private static void seed(ConfigurableApplicationContext context) {
        var users = context.getBean(AppUserRepository.class);
        var households = context.getBean(HouseholdRepository.class);
        var members = context.getBean(FamilyMemberRepository.class);
        var categories = context.getBean(CategoryRepository.class);
        var accounts = context.getBean(FinancialAccountRepository.class);
        var transactions = context.getBean(FinancialTransactionRepository.class);
        var jdbc = context.getBean(JdbcTemplate.class);
        Instant time = Instant.parse("2026-10-01T00:00:00Z");
        LocalDate date = LocalDate.parse("2026-10-12");
        AppUser owner = users.findByEmail("demo@local.family").orElseThrow();
        Household home = owner.getHousehold();
        FamilyMember member = members.findByHouseholdOrderById(home).get(0);
        Category category = categories.save(new Category(home, TransactionKind.EXPENSE, "QA 合成分类", "#228866", false, time));
        FinancialAccount account = accounts.save(new FinancialAccount(home, "QA 合成账户", AccountType.CASH, "CNY", 0L));
        FinancialTransaction manual = new FinancialTransaction(home, account, owner, member, category,
                TransactionKind.EXPENSE, 100L, date, null, null, "QA-MANUAL", time, time);
        transactions.save(manual);
        for (int i = 1; i <= 51; i++) {
            FinancialTransaction recurring = FinancialTransaction.recurring(home, account, owner, member, category,
                    TransactionKind.EXPENSE, 200L, date.plusDays(i % 3), 900000L + i, time);
            recurring.updateDetails(account, member, category, TransactionKind.EXPENSE, 200L,
                    date.plusDays(i % 3), null, null, "QA-RECURRING-" + i, time);
            transactions.save(recurring);
        }
        FinancialTransaction legacy = FinancialTransaction.recurring(home, account, owner, member, category,
                TransactionKind.EXPENSE, 300L, date, 910001L, time);
        legacy.updateDetails(account, member, category, TransactionKind.EXPENSE, 300L, date, null, null, "QA-LOAN", time);
        transactions.saveAndFlush(legacy);
        jdbc.update("update financial_transactions set source_type='LOAN' where id=?", legacy.getId());
        FinancialTransaction payment = FinancialTransaction.loanPayment(home, account, owner, member, category, 400L, date, 910002L, time);
        payment.updateDetails(account, member, category, TransactionKind.EXPENSE, 400L, date, null, null, "QA-LOAN-PAYMENT", time);
        transactions.save(payment);
        FinancialTransaction prepayment = FinancialTransaction.loanPrepayment(home, account, owner, member, category, 500L, date, 910003L, time);
        prepayment.updateDetails(account, member, category, TransactionKind.EXPENSE, 500L, date, null, null, "QA-LOAN-PREPAYMENT", time);
        transactions.save(prepayment);

        Household foreign = households.save(new Household("QA 合成外部家庭", time));
        AppUser foreignUser = users.save(new AppUser(foreign, "qa-source-foreign", "qa-source-foreign@local.family", "QA 外部", "not-a-login-password", time));
        FamilyMember foreignMember = members.save(new FamilyMember(foreign, "QA 外部成员", "成员", time));
        Category foreignCategory = categories.save(new Category(foreign, TransactionKind.EXPENSE, "QA 外部分类", "#228866", false, time));
        FinancialAccount foreignAccount = accounts.save(new FinancialAccount(foreign, "QA 外部账户", AccountType.CASH, "CNY", 0L));
        FinancialTransaction foreignRecurring = FinancialTransaction.recurring(foreign, foreignAccount, foreignUser,
                foreignMember, foreignCategory, TransactionKind.EXPENSE, 9900L, date, 920001L, time);
        foreignRecurring.updateDetails(foreignAccount, foreignMember, foreignCategory, TransactionKind.EXPENSE,
                9900L, date, null, null, "QA-FOREIGN-DO-NOT-SHOW", time);
        transactions.save(foreignRecurring);
    }
}
