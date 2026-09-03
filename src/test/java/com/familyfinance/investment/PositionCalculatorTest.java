package com.familyfinance.investment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PositionCalculatorTest {

    private final PositionCalculator calculator = new PositionCalculator();

    @Test
    void weightedAverageCostAndPartialSaleAreHandCalculated() {
        InvestmentPosition position = calculator.calculate(List.of(
                buy(1, "100.0000", 1_000L, 100L),
                buy(2, "50.0000", 1_200L, 50L),
                sell(3, "60.0000", 1_500L, 80L)), 1_600L);

        assertThat(position.quantity()).isEqualByComparingTo("90.0000");
        assertThat(position.costCents()).isEqualTo(96_090L);
        assertThat(position.averageCostCents()).isEqualByComparingTo("1067.6667");
        assertThat(position.realizedProfitCents()).isEqualTo(25_860L);
        assertThat(position.cashImpactCents()).isEqualTo(-70_230L);
        assertThat(position.marketValueCents()).isEqualTo(144_000L);
        assertThat(position.unrealizedProfitCents()).isEqualTo(47_910L);
    }

    @Test
    void fullSaleConsumesExactRemainingCostAndLeavesNoRoundingDust() {
        InvestmentPosition position = calculator.calculate(List.of(
                buy(1, "3.0000", 333L, 1L),
                sell(2, "1.0000", 500L, 1L),
                sell(3, "2.0000", 600L, 1L)), null);

        assertThat(position.quantity()).isEqualByComparingTo("0.0000");
        assertThat(position.costCents()).isZero();
        assertThat(position.averageCostCents()).isEqualByComparingTo("0.0000");
        assertThat(position.realizedProfitCents()).isEqualTo(698L);
        assertThat(position.marketValueCents()).isNull();
    }

    @Test
    void saleOverAvailableHoldingIsRejected() {
        assertThatThrownBy(() -> calculator.calculate(List.of(
                buy(1, "1.0000", 1_000L, 0L),
                sell(2, "1.0001", 1_100L, 0L)), null))
                .isInstanceOf(InsufficientHoldingException.class);
    }

    @Test
    void dividendsAndStandaloneFeesAffectCashAndRealizedProfitButNotCost() {
        InvestmentPosition position = calculator.calculate(List.of(
                buy(1, "10.0000", 1_000L, 20L),
                dividend(2, 300L),
                fee(3, 25L)), null);

        assertThat(position.quantity()).isEqualByComparingTo("10.0000");
        assertThat(position.costCents()).isEqualTo(10_020L);
        assertThat(position.realizedProfitCents()).isEqualTo(275L);
        assertThat(position.cashImpactCents()).isEqualTo(-9_745L);
    }

    @Test
    void fractionalQuantitiesRoundEachGrossAndAverageCostAllocationHalfUpToCents() {
        InvestmentPosition position = calculator.calculate(List.of(
                buy(1, "0.3333", 101L, 1L),
                buy(2, "0.6667", 101L, 0L),
                sell(3, "0.2500", 200L, 0L)), null);

        assertThat(position.quantity()).isEqualByComparingTo("0.7500");
        assertThat(position.costCents()).isEqualTo(76L);
        assertThat(position.realizedProfitCents()).isEqualTo(24L);
        assertThat(position.cashImpactCents()).isEqualTo(-52L);
    }

    @Test
    void sameDayTradesUseIdAsStableTieBreaker() {
        LocalDate day = LocalDate.of(2026, 9, 3);
        PositionTrade sellInsertedFirst = new PositionTrade(
                2, day, InvestmentTradeType.SELL, new BigDecimal("1.0000"), 1_200L, 0L);
        PositionTrade buyInsertedSecond = new PositionTrade(
                1, day, InvestmentTradeType.BUY, new BigDecimal("1.0000"), 1_000L, 0L);

        InvestmentPosition position = calculator.calculate(List.of(sellInsertedFirst, buyInsertedSecond), null);

        assertThat(position.quantity()).isEqualByComparingTo("0.0000");
        assertThat(position.realizedProfitCents()).isEqualTo(200L);
    }

    @Test
    void currentMarketPriceIsOptional() {
        InvestmentPosition withoutPrice = calculator.calculate(
                List.of(buy(1, "2.0000", 1_000L, 0L)), null);

        assertThat(withoutPrice.marketPriceCents()).isNull();
        assertThat(withoutPrice.marketValueCents()).isNull();
        assertThat(withoutPrice.unrealizedProfitCents()).isNull();
    }

    @Test
    void exactArithmeticRejectsLongOverflow() {
        assertThatThrownBy(() -> calculator.calculate(List.of(
                buy(1, "999999999999999.9999", 99_999_999_999L, 0L)), null))
                .isInstanceOf(ArithmeticException.class);
    }

    private static PositionTrade buy(long id, String quantity, long priceCents, long feeCents) {
        return trade(id, InvestmentTradeType.BUY, quantity, priceCents, feeCents);
    }

    private static PositionTrade sell(long id, String quantity, long priceCents, long feeCents) {
        return trade(id, InvestmentTradeType.SELL, quantity, priceCents, feeCents);
    }

    private static PositionTrade dividend(long id, long amountCents) {
        return trade(id, InvestmentTradeType.DIVIDEND, null, amountCents, 0L);
    }

    private static PositionTrade fee(long id, long amountCents) {
        return trade(id, InvestmentTradeType.FEE, null, amountCents, 0L);
    }

    private static PositionTrade trade(
            long id, InvestmentTradeType type, String quantity, long priceCents, long feeCents) {
        return new PositionTrade(
                id,
                LocalDate.of(2026, 1, 1),
                type,
                quantity == null ? null : new BigDecimal(quantity),
                priceCents,
                feeCents);
    }
}
