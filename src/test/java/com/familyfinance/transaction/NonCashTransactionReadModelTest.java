package com.familyfinance.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;

import com.familyfinance.category.Category;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.accounting.AccountingRequests;
import com.familyfinance.accounting.CashAccountingService;
import com.familyfinance.accounting.LedgerPostingService;
import com.familyfinance.category.CategoryRepository;
import com.familyfinance.family.FamilyMutationAuthorization;
import com.familyfinance.family.FamilyPermissionService;
import com.familyfinance.family.HouseholdMembership;
import com.familyfinance.family.HouseholdRole;
import com.familyfinance.family.MembershipContext;
import com.familyfinance.family.MembershipStatus;
import com.familyfinance.fx.FxJournalRates;
import com.familyfinance.household.AppUser;
import com.familyfinance.household.Household;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.ledger.AccountType;
import com.familyfinance.ledger.FinancialAccount;
import com.familyfinance.ledger.FinancialAccountRepository;
import com.familyfinance.reporting.CsvExportService;
import com.familyfinance.shared.ResourceConflictException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class NonCashTransactionReadModelTest {
    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
    private static final LocalDate DAY = LocalDate.parse("2026-09-09");
    private static final TransactionFilter FILTER = new TransactionFilter("2026-09", null, null, null, null, null, null, null, null);

    private final Household household = new Household("测试家庭", NOW);
    private final AppUser creator = new AppUser(household, "reader", "reader@example.test", "记录人", "unused", NOW);
    private final FinancialAccount bank = new FinancialAccount(household, "日常银行卡", AccountType.BANK, "CNY", 0L);
    private final Category food = new Category(household, TransactionKind.EXPENSE, "餐饮", "#3370FF", false, NOW);
    private final Category loan = new Category(household, TransactionKind.EXPENSE, "贷款还款", "#778899", false, NOW);
    private final Category salary = new Category(household, TransactionKind.INCOME, "工资", "#337755", false, NOW);
    private final ObjectMapper mapper = new ObjectMapper();
    private final TransactionService transactions = mock(TransactionService.class);
    private final FxJournalRates fx = new FxJournalRates(mock(JdbcTemplate.class));
    private final Authentication authentication = new UsernamePasswordAuthenticationToken("reader", "unused");

    NonCashTransactionReadModelTest() {
        ReflectionTestUtils.setField(household, "id", 1L);
        ReflectionTestUtils.setField(creator, "id", 7L);
        ReflectionTestUtils.setField(bank, "id", 5L);
        ReflectionTestUtils.setField(food, "id", 2L);
        ReflectionTestUtils.setField(loan, "id", 9L);
        ReflectionTestUtils.setField(salary, "id", 3L);
    }

    @Test
    void directRepaymentResponseLabelsBuyerSettlementAndRetainsLoanIdentity() {
        FinancialTransaction direct = directRepayment();

        JsonNode response = mapper.valueToTree(TransactionResponse.from(direct));

        assertThat(response.path("cashImpact").isBoolean()).isTrue();
        assertThat(response.path("cashImpact").asBoolean()).isFalse();
        assertThat(response.path("settlementAssetId").asLong()).isEqualTo(42L);
        assertThat(response.path("accountId").asLong()).isEqualTo(5L);
        assertThat(response.path("accountName").asText()).isEqualTo("买方代偿（非现金）");
        assertThat(response.path("sourceType").asText()).isEqualTo("LOAN_PREPAYMENT");
        assertThat(response.path("sourceId").asLong()).isEqualTo(88L);
        assertThat(response.path("amount").asText()).isEqualTo("1050.00");
        assertThat(response.path("principalAmount").asText()).isEqualTo("1000.00");
        assertThat(response.path("interestAmount").asText()).isEqualTo("50.00");
    }

    @Test
    void ordinaryAndLegacyResponseConstructorsKeepCashDefaultsAndBankName() {
        TransactionResponse ordinary = TransactionResponse.from(manual(1L, food, 1250L));
        TransactionResponse legacy = new TransactionResponse(
                ordinary.id(), ordinary.kind(), ordinary.amount(), ordinary.occurredOn(), ordinary.accountId(),
                ordinary.accountName(), ordinary.memberId(), ordinary.memberName(), ordinary.createdByUserId(),
                ordinary.createdByName(), ordinary.sourceType(), ordinary.categoryId(), ordinary.categoryName(),
                ordinary.categoryParentId(), ordinary.categoryLevel(), ordinary.merchant(), ordinary.location(),
                ordinary.note(), ordinary.createdAt(), ordinary.updatedAt(), ordinary.sourceId(),
                ordinary.principalAmount(), ordinary.interestAmount(), ordinary.currency());
        ObjectNode legacyJson = mapper.valueToTree(ordinary);
        legacyJson.remove("cashImpact");
        legacyJson.remove("settlementAssetId");
        TransactionResponse missingFlag = mapper.treeToValue(legacyJson, TransactionResponse.class);
        legacyJson.putNull("cashImpact");
        TransactionResponse nullFlag = mapper.treeToValue(legacyJson, TransactionResponse.class);

        for (TransactionResponse item : List.of(ordinary, legacy, missingFlag, nullFlag, TransactionResponse.from(cashRepayment()))) {
            JsonNode response = mapper.valueToTree(item);
            assertThat(response.path("cashImpact").asBoolean()).isTrue();
            assertThat(response.path("settlementAssetId").isNull()).isTrue();
            assertThat(response.path("accountName").asText()).isEqualTo("日常银行卡");
        }
    }

    @Test
    void summaryExcludesDirectRepaymentFromCashTotalsCategoriesAndDailyRows() {
        when(transactions.findAllForCsvExport(1L, FILTER)).thenReturn(List.of(
                manual(1L, food, 1250L), manual(2L, salary, 10000L), cashRepayment(), directRepayment()));

        TransactionSummaryResponse summary = new TransactionSummaryService(transactions, fx).summarize(1L, FILTER);

        assertThat(summary.income()).isEqualTo("100.00");
        assertThat(summary.expense()).isEqualTo("22.50");
        assertThat(summary.balance()).isEqualTo("77.50");
        assertThat(summary.transactionCount()).isEqualTo(4);
        assertThat(summary.unconvertedCount()).isZero();
        assertThat(mapper.valueToTree(summary).path("nonCashTransactionCount").asInt()).isEqualTo(1);
        assertThat(summary.categories()).containsExactlyInAnyOrder(
                new TransactionSummaryResponse.CategorySummary(2L, "餐饮", "#3370FF", TransactionKind.EXPENSE, "12.50", 1),
                new TransactionSummaryResponse.CategorySummary(3L, "工资", "#337755", TransactionKind.INCOME, "100.00", 1),
                new TransactionSummaryResponse.CategorySummary(9L, "贷款还款", "#778899", TransactionKind.EXPENSE, "10.00", 1));
        assertThat(summary.daily()).containsExactlyInAnyOrder(
                new TransactionSummaryResponse.DailySummary(DAY, TransactionKind.EXPENSE, 2L, "12.50", 1),
                new TransactionSummaryResponse.DailySummary(DAY, TransactionKind.INCOME, 3L, "100.00", 1),
                new TransactionSummaryResponse.DailySummary(DAY, TransactionKind.EXPENSE, 9L, "10.00", 1));
    }

    @Test
    void directOnlySummaryIsZeroCashWithNoCategoryOrDayEntries() {
        when(transactions.findAllForCsvExport(1L, FILTER)).thenReturn(List.of(directRepayment()));

        TransactionSummaryResponse summary = new TransactionSummaryService(transactions, fx).summarize(1L, FILTER);

        assertThat(summary.income()).isEqualTo("0.00");
        assertThat(summary.expense()).isEqualTo("0.00");
        assertThat(summary.balance()).isEqualTo("0.00");
        assertThat(summary.transactionCount()).isEqualTo(1);
        assertThat(summary.unconvertedCount()).isZero();
        assertThat(summary.categories()).isEmpty();
        assertThat(summary.daily()).isEmpty();
        assertThat(mapper.valueToTree(summary).path("nonCashTransactionCount").asInt()).isEqualTo(1);
    }

    @Test
    void legacySummaryConstructorDefaultsNoncashCountToZero() {
        TransactionSummaryResponse legacy = new TransactionSummaryResponse(
                "CNY", "100.00", "12.50", "87.50", 2, 0, List.of(), List.of());

        JsonNode response = mapper.valueToTree(legacy);

        assertThat(response.path("nonCashTransactionCount").isIntegralNumber()).isTrue();
        assertThat(response.path("nonCashTransactionCount").asInt()).isZero();
        assertThat(response.path("expense").asText()).isEqualTo("12.50");
    }

    @Test
    void csvRetainsEveryDebtRecordAndAppendsItsCashImpactWithoutChangingExistingColumns() {
        when(transactions.findAllForCsvExport(1L, FILTER)).thenReturn(List.of(manual(1L, food, 1250L), directRepayment()));
        CsvExportService export = new CsvExportService(transactions);
        ReflectionTestUtils.setField(export, "fx", fx);

        String csv = new String(export.export(1L, FILTER), StandardCharsets.UTF_8);
        String[] rows = csv.substring(1).split("\n");

        assertThat(rows).hasSize(3);
        assertThat(rows[0]).isEqualTo("日期,类型,金额,成员,分类,商家,地点,备注,币种,人民币参考金额,参考汇率,汇率日期,现金影响");
        assertThat(rows[1]).isEqualTo("2026-09-09,支出,12.50,全体（家庭共同）,餐饮,,,,CNY,12.50,1,,是");
        assertThat(rows[2]).isEqualTo("2026-09-10,支出,1050.00,全体（家庭共同）,贷款还款,,,贷款提前还款,CNY,1050.00,1,,否（买方代偿）");
    }

    @Test
    void directSettlementMetadataCannotBeEditedEvenByOwner() {
        FinancialTransaction direct = directRepayment();
        TransactionService service = mutationService(direct);
        TransactionPatchRequest patch = new TransactionPatchRequest(null, null, null, null, null, null, null, null, "改写历史");

        assertThatThrownBy(() -> service.update(authentication, direct.getId(), patch, "noncash-patch"))
                .isInstanceOf(ResourceConflictException.class)
                .extracting(error -> ((ResourceConflictException) error).code())
                .isEqualTo("ASSET_SETTLEMENT_IMMUTABLE");
        assertThat(direct.getNote()).isEqualTo("贷款提前还款");
    }

    @Test
    void directSettlementCannotBeDeletedEvenByOwner() {
        FinancialTransaction direct = directRepayment();

        assertThatThrownBy(() -> mutationService(direct).delete(authentication, direct.getId(), "noncash-delete"))
                .isInstanceOf(ResourceConflictException.class)
                .extracting(error -> ((ResourceConflictException) error).code())
                .isEqualTo("ASSET_SETTLEMENT_IMMUTABLE");
    }

    @Test
    void ordinaryLoanRepaymentStillAllowsMetadataEdits() {
        FinancialTransaction repayment = cashRepayment();
        TransactionPatchRequest patch = new TransactionPatchRequest(null, null, null, null, null, null, null, null, "补充备注");

        TransactionResponse response = mutationService(repayment).update(authentication, repayment.getId(), patch, "cash-patch");

        assertThat(response.note()).isEqualTo("补充备注");
        assertThat(response.amount()).isEqualTo("10.00");
        assertThat(response.accountName()).isEqualTo("日常银行卡");
    }

    private TransactionService mutationService(FinancialTransaction transaction) {
        FinancialTransactionRepository repository = mock(FinancialTransactionRepository.class);
        when(repository.findLockedByIdAndHouseholdId(transaction.getId(), 1L)).thenReturn(Optional.of(transaction));
        FamilyMutationAuthorization authorization = mock(FamilyMutationAuthorization.class);
        HouseholdMembership membership = new HouseholdMembership(household, creator, HouseholdRole.OWNER, MembershipStatus.ACTIVE, NOW);
        when(authorization.requireCurrent(authentication)).thenReturn(new FamilyMutationAuthorization.LockedFamilyAccess(
                household, membership, new MembershipContext(1L, 7L, HouseholdRole.OWNER)));
        AccountingRequests requests = mock(AccountingRequests.class);
        when(requests.replay(anyLong(), anyString(), nullable(String.class))).thenReturn(null);
        return new TransactionService(mock(FamilyMemberRepository.class), mock(CategoryRepository.class), repository,
                mock(FinancialAccountRepository.class), authorization, new FamilyPermissionService(),
                mock(TransactionFilterParser.class), mock(CashAccountingService.class), mock(LedgerPostingService.class),
                requests);
    }

    private FinancialTransaction manual(long id, Category category, long amount) {
        FinancialTransaction transaction = new FinancialTransaction(household, bank, creator, null, category,
                category.getKind(), amount, DAY, null, null, null, NOW, NOW);
        ReflectionTestUtils.setField(transaction, "id", id);
        return transaction;
    }

    private FinancialTransaction cashRepayment() {
        FinancialTransaction transaction = FinancialTransaction.loanPayment(household, bank, creator, null, loan,
                1000L, DAY, 77L, NOW);
        transaction.loanSplit(900L, 100L);
        ReflectionTestUtils.setField(transaction, "id", 3L);
        return transaction;
    }

    private FinancialTransaction directRepayment() {
        FinancialTransaction transaction = FinancialTransaction.loanPrepayment(household, bank, creator, null, loan,
                105000L, DAY.plusDays(1), 88L, NOW);
        transaction.loanSplit(100000L, 5000L);
        transaction.markAssetSettlement(42L);
        ReflectionTestUtils.setField(transaction, "id", 4L);
        return transaction;
    }
}
