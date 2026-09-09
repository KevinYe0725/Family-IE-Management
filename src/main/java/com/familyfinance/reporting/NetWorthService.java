package com.familyfinance.reporting;

import com.familyfinance.asset.AssetRepository;
import com.familyfinance.asset.AssetStatus;
import com.familyfinance.budget.Budget;
import com.familyfinance.budget.BudgetRepository;
import com.familyfinance.budget.BudgetUsageStatus;
import com.familyfinance.ledger.FinancialAccountRepository;
import com.familyfinance.loan.Loan;
import com.familyfinance.loan.LoanRepository;
import com.familyfinance.loan.LoanStatus;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.accounting.LedgerReportingService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class NetWorthService {
    private static final BigInteger MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE);
    private final FinancialAccountRepository accounts;
    private final AssetRepository assets;
    private final LoanRepository loans;
    private final PortfolioService portfolio;
    private final BudgetRepository budgets;
    private final LedgerReportingService transactions;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired private com.familyfinance.fx.FxJournalRates fx;

    public NetWorthService(FinancialAccountRepository accounts, AssetRepository assets, LoanRepository loans,
            PortfolioService portfolio, BudgetRepository budgets, LedgerReportingService transactions, Clock clock) {
        this.accounts = accounts;
        this.assets = assets;
        this.loans = loans;
        this.portfolio = portfolio;
        this.budgets = budgets;
        this.transactions = transactions;
        this.clock = clock;
    }

    public NetWorthResult calculate(long householdId, LocalDate asOf) {
        transactions.requireComplete(householdId);
        if(asOf==null||asOf.isAfter(LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Shanghai"))))||asOf.getYear()<1000)
            throw new com.familyfinance.shared.RequestValidationException(java.util.Map.of("asOf","截止日期必须在1000年至今天之间"));
        var balances=transactions.balancesAsOf(householdId,asOf);
        var currencies=transactions.currencies(householdId);
        List<NetWorthResult.Unconverted> missing=new ArrayList<>();long knownCash=0;
        for(var entry:balances.entrySet()){
            if(!entry.getKey().startsWith("CASH:"))continue;
            String currency=currencies.getOrDefault(entry.getKey(),"CNY");
            boolean currentDay=asOf.equals(LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Shanghai"))));
            BigDecimal converted=currency.equals("CNY")?BigDecimal.valueOf(entry.getValue(),2):currentDay?fx.valuationConvert(currency,BigDecimal.valueOf(entry.getValue(),2),asOf):fx.convert(currency,BigDecimal.valueOf(entry.getValue(),2),asOf);
            if(converted==null)missing.add(new NetWorthResult.Unconverted("CASH",currency,com.familyfinance.shared.Money.formatCents(entry.getValue())));
            else knownCash=Math.addExact(knownCash,converted.movePointRight(2).longValueExact());
        }
        Long cash=missing.isEmpty()?knownCash:null;
        long nonCashAssets = sum(balances.entrySet().stream().filter(e->e.getKey().startsWith("ASSET:")).map(java.util.Map.Entry::getValue).toList());
        PortfolioResponse portfolioResponse = portfolio.portfolio(householdId,asOf);
        InvestmentSummary investment = investment(portfolioResponse);
        long liabilities = sum(balances.entrySet().stream().filter(e->e.getKey().startsWith("LOAN:")).map(java.util.Map.Entry::getValue).toList());
        for(var p:portfolioResponse.positions())if(p.base()!=null&&p.base().estimatedValue()==null)missing.add(new NetWorthResult.Unconverted("INVESTMENT",p.currency(),p.estimatedValue()));
        Long assetCents = cash==null||investment.estimatedValueCents()==null?null:sum(List.of(cash,nonCashAssets,investment.estimatedValueCents()));
        Long netWorth = assetCents==null?null:subtract(assetCents,liabilities);
        long knownAsset=sum(List.of(knownCash,nonCashAssets,parseCents(portfolioResponse.totals().knownEstimatedValue())));
        List<Loan> activeLoans = loans.findAllByHouseholdIdAndAccountingOnLessThanEqual(householdId,asOf);
        return new NetWorthResult(assetCents, liabilities, netWorth,
                assetCents==null?List.of():allocation(cash, nonCashAssets, investment.estimatedValueCents()), assetCents==null?null:ratio(liabilities, assetCents),
                debtProgress(activeLoans,balances), budget(householdId, YearMonth.from(asOf),asOf.plusDays(1)), investment,
                subtract(balances.getOrDefault("INCOME:VALUATION_GAIN",0L),balances.getOrDefault("EXPENSE:VALUATION_LOSS",0L)),knownAsset,List.copyOf(missing));
    }

    private InvestmentSummary investment(PortfolioResponse portfolioResponse) {
        Long market = portfolioResponse.totals().marketValue()==null?null:parseCents(portfolioResponse.totals().marketValue());
        boolean manual = portfolioResponse.positions().stream().anyMatch(position -> position.source() != null
                && position.source().name().equals("MANUAL"));
        boolean stale = portfolioResponse.positions().stream().anyMatch(PortfolioPositionResponse::stale);
        boolean missing = portfolioResponse.positions().stream().anyMatch(position -> position.marketValue() == null);
        return new InvestmentSummary(market, portfolioResponse.positions().size(),
                portfolioResponse.totals().unpricedPositions(), manual, stale, missing,portfolioResponse.totals().estimatedValue()==null?null:parseCents(portfolioResponse.totals().estimatedValue()));
    }

    private BudgetSummary budget(long householdId, YearMonth month,LocalDate end) {
        List<Budget> active = budgets.findAllByHouseholdIdAndPeriodMonthAndActiveTrue(householdId, month.toString());
        // Only member-less CATEGORY rows are hard family allocations; member-tagged
        // rows are observation lines and must not inflate the household totals.
        List<Budget> pool = active.stream()
                .filter(budget -> budget.getScopeType() == com.familyfinance.budget.BudgetScopeType.CATEGORY)
                .toList();
        BigInteger planned = BigInteger.ZERO;
        BigInteger spent = BigInteger.ZERO;
        int near = 0;
        int over = 0;
        boolean complete=true;
        for (Budget budget : pool) {
            long used;
            try {used=parseAggregateCents(transactions.sumBudgetExpenseCents(householdId, month.atDay(1),end,budget.getScopeType().name(),
                budget.getCategory()==null?null:budget.getCategory().getId(),budget.getMember()==null?null:budget.getMember().getId(),true));}
            catch(ResourceConflictException error){if(!error.code().equals("FX_RATE_MISSING"))throw error;planned=planned.add(BigInteger.valueOf(budget.getAmountCents()));complete=false;continue;}
            planned = planned.add(BigInteger.valueOf(budget.getAmountCents()));
            spent = spent.add(BigInteger.valueOf(used));
            BudgetUsageStatus status = budgetStatus(used, budget.getAmountCents());
            if (status == BudgetUsageStatus.NEAR_LIMIT) near++;
            if (status == BudgetUsageStatus.AT_LIMIT || status == BudgetUsageStatus.OVER_BUDGET) over++;
        }
        return new BudgetSummary(pool.size(), bounded(planned), complete?bounded(spent):null, complete?near:null, complete?over:null);
    }

    private static BudgetUsageStatus budgetStatus(long spent, long amount) {
        if (spent > amount) return BudgetUsageStatus.OVER_BUDGET;
        if (spent == amount) return BudgetUsageStatus.AT_LIMIT;
        return BigInteger.valueOf(spent).multiply(BigInteger.valueOf(100)).compareTo(
                BigInteger.valueOf(amount).multiply(BigInteger.valueOf(80))) >= 0
                ? BudgetUsageStatus.NEAR_LIMIT : BudgetUsageStatus.ON_TRACK;
    }

    private static List<AllocationSlice> allocation(long cash, long assets, long investment) {
        List<AllocationSlice> source = new ArrayList<>();
        source.add(new AllocationSlice("CASH", Math.max(0L, cash), 0));
        source.add(new AllocationSlice("ASSETS", Math.max(0L, assets), 0));
        source.add(new AllocationSlice("INVESTMENTS", Math.max(0L, investment), 0));
        long total = sum(source.stream().map(AllocationSlice::amountCents).toList());
        if (total == 0) return List.of();
        List<AllocationSlice> rounded = new ArrayList<>();
        int used = 0;
        for (AllocationSlice value : source) {
            if (value.amountCents() == 0) continue;
            int share = BigDecimal.valueOf(value.amountCents()).multiply(BigDecimal.valueOf(1000))
                    .divide(BigDecimal.valueOf(total), 0, RoundingMode.HALF_UP).intValueExact();
            rounded.add(new AllocationSlice(value.type(), value.amountCents(), share));
            used += share;
        }
        int adjustment = 1000 - used;
        int target = rounded.stream().max(Comparator.comparingLong(AllocationSlice::amountCents)
                .thenComparing(AllocationSlice::type)).orElseThrow().shareTenths();
        for (int i = 0; i < rounded.size(); i++) {
            if (rounded.get(i).shareTenths() == target) {
                AllocationSlice value = rounded.get(i);
                rounded.set(i, new AllocationSlice(value.type(), value.amountCents(), value.shareTenths() + adjustment));
                break;
            }
        }
        return List.copyOf(rounded);
    }

    private static List<DebtProgress> debtProgress(List<Loan> loans,java.util.Map<String,Long> balances) {
        return loans.stream().sorted(Comparator.comparing(Loan::getId)).map(loan -> new DebtProgress(loan.getId(), loan.getName(),
                loan.getPrincipalCents(), balances.getOrDefault("LOAN:"+loan.getId(),0L), ratio(
                        subtract(loan.getPrincipalCents(), balances.getOrDefault("LOAN:"+loan.getId(),0L)), loan.getPrincipalCents())))
                .toList();
    }

    private static int ratio(long numerator, long denominator) {
        if (denominator <= 0) return 0;
        return BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(1000))
                .divide(BigDecimal.valueOf(denominator), 0, RoundingMode.HALF_UP).intValueExact();
    }

    private static long parseCents(String money) {
        if (money == null) return 0L;
        return new BigDecimal(money).movePointRight(2).longValueExact();
    }

    private static long parseAggregateCents(String cents) {
        if (cents == null) return 0L;
        return bounded(new BigInteger(cents));
    }

    private static long sum(List<Long> values) {
        BigInteger total = BigInteger.ZERO;
        for (Long value : values) total = total.add(BigInteger.valueOf(value));
        return bounded(total);
    }

    private static long subtract(long left, long right) { return bounded(BigInteger.valueOf(left).subtract(BigInteger.valueOf(right))); }

    private static long bounded(BigInteger value) {
        if (value.compareTo(MAX_LONG) > 0 || value.compareTo(MAX_LONG.negate()) < 0) {
            throw new ResourceConflictException("AMOUNT_OVERFLOW", "金额超出可计算范围");
        }
        return value.longValueExact();
    }
}
