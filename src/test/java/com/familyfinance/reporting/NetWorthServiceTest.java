package com.familyfinance.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.familyfinance.asset.Asset;
import com.familyfinance.asset.AssetRepository;
import com.familyfinance.asset.AssetStatus;
import com.familyfinance.budget.BudgetRepository;
import com.familyfinance.investment.InvestmentTradeRepository;
import com.familyfinance.ledger.AccountBalance;
import com.familyfinance.ledger.FinancialAccountRepository;
import com.familyfinance.loan.Loan;
import com.familyfinance.loan.LoanRepository;
import com.familyfinance.loan.LoanStatus;
import com.familyfinance.transaction.FinancialTransactionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class NetWorthServiceTest {

    @Test
    void handCalculatedAssetsAndLiabilitiesAreCountedExactlyOnce() {
        FinancialAccountRepository accounts = mock(FinancialAccountRepository.class);
        AssetRepository assets = mock(AssetRepository.class);
        LoanRepository loans = mock(LoanRepository.class);
        PortfolioService portfolio = mock(PortfolioService.class);
        when(accounts.findActiveBalancesByHouseholdIdAndOccurredOnBefore(1L, LocalDate.of(2026, 9, 3)))
                .thenReturn(List.of(new AccountBalance(10L, 100_000L)));
        Asset property = mock(Asset.class);
        when(property.getCurrentValueCents()).thenReturn(900_000L);
        when(assets.findAllByHouseholdIdAndStatus(1L, AssetStatus.ACTIVE)).thenReturn(List.of(property));
        Loan loan = mock(Loan.class);
        when(loan.getPrincipalCents()).thenReturn(400_000L);
        when(loan.getCurrentPrincipalCents()).thenReturn(400_000L);
        when(loans.findAllByHouseholdIdAndStatus(1L, LoanStatus.ACTIVE)).thenReturn(List.of(loan));
        when(portfolio.portfolio(1L)).thenReturn(new PortfolioResponse(List.of(),
                new PortfolioTotalsResponse("0.00", "2000.00", "0.00", "0.00", "0.00", 0)));

        NetWorthService service = new NetWorthService(accounts, assets, loans, portfolio,
                mock(BudgetRepository.class), mock(FinancialTransactionRepository.class),
                Clock.fixed(Instant.parse("2026-09-03T12:00:00Z"), ZoneOffset.UTC));

        NetWorthResult result = service.calculate(1L, LocalDate.of(2026, 9, 3));

        assertThat(result.assetCents()).isEqualTo(1_200_000L);
        assertThat(result.liabilityCents()).isEqualTo(400_000L);
        assertThat(result.netWorthCents()).isEqualTo(800_000L);
        assertThat(result.allocation().stream().mapToInt(AllocationSlice::shareTenths).sum()).isEqualTo(1000);
    }
}
