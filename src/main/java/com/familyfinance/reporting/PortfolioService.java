package com.familyfinance.reporting;

import com.familyfinance.investment.InvestmentPosition;
import com.familyfinance.investment.InvestmentTrade;
import com.familyfinance.investment.InvestmentTradeRepository;
import com.familyfinance.investment.PositionCalculator;
import com.familyfinance.investment.PositionTrade;
import com.familyfinance.market.MarketPriceResponse;
import com.familyfinance.market.QuoteRefreshService;
import com.familyfinance.shared.Money;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PortfolioService {
    private final InvestmentTradeRepository trades;
    private final QuoteRefreshService prices;
    private final PositionCalculator calculator = new PositionCalculator();

    public PortfolioService(InvestmentTradeRepository trades, QuoteRefreshService prices) {
        this.trades = trades;
        this.prices = prices;
    }

    public PortfolioResponse portfolio(long householdId) {
        Map<Long, MarketPriceResponse> effective = new LinkedHashMap<>();
        prices.effectivePrices(householdId).forEach(price -> effective.put(price.securityId(), price));
        Map<PositionKey, List<InvestmentTrade>> grouped = new LinkedHashMap<>();
        for (InvestmentTrade trade : trades.findActiveAccountTradesByHouseholdId(householdId)) {
            grouped.computeIfAbsent(new PositionKey(trade.getAccount().getId(), trade.getSecurity().getId()), ignored -> new ArrayList<>())
                    .add(trade);
        }

        List<CalculatedPosition> calculated = new ArrayList<>();
        for (List<InvestmentTrade> history : grouped.values()) {
            InvestmentTrade first = history.get(0);
            MarketPriceResponse price = effective.get(first.getSecurity().getId());
            Long priceCents = price == null || price.price() == null ? null : Money.parseCents(price.price());
            InvestmentPosition position = calculator.calculate(history.stream()
                    .map(trade -> new PositionTrade(trade.getId(), trade.getTradedOn(), trade.getType(), trade.getQuantity(),
                            trade.getPriceCents(), trade.getFeeCents()))
                    .toList(), priceCents);
            if (position.quantity().signum() > 0) calculated.add(new CalculatedPosition(first, position, price));
        }
        calculated.sort(Comparator.comparing((CalculatedPosition value) -> value.trade().getAccount().getName())
                .thenComparing(value -> value.trade().getSecurity().getTsCode())
                .thenComparing(value -> value.trade().getAccount().getId()));

        Totals totals = Totals.from(calculated);
        return new PortfolioResponse(calculated.stream().map(value -> response(value, totals.marketValue())).toList(), totals.response());
    }

    private static PortfolioPositionResponse response(CalculatedPosition value, Long totalMarketValue) {
        InvestmentTrade trade = value.trade();
        InvestmentPosition position = value.position();
        MarketPriceResponse price = value.price();
        Long totalProfit = position.unrealizedProfitCents() == null ? null
                : Math.addExact(position.realizedProfitCents(), position.unrealizedProfitCents());
        String allocation = position.marketValueCents() == null || totalMarketValue == null || totalMarketValue == 0L ? "0.0"
                : BigDecimal.valueOf(position.marketValueCents()).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(totalMarketValue), 1, RoundingMode.HALF_UP).toPlainString();
        return new PortfolioPositionResponse(trade.getAccount().getId(), trade.getAccount().getName(), trade.getAccount().getBrokerName(),
                trade.getSecurity().getId(), trade.getSecurity().getTsCode(), trade.getSecurity().getName(), position.quantity(),
                position.averageCostCents().movePointLeft(2).setScale(4, RoundingMode.HALF_UP).toPlainString(),
                Money.formatCents(position.costCents()), price == null ? null : price.price(), cents(position.marketValueCents()),
                Money.formatCents(position.realizedProfitCents()), cents(position.unrealizedProfitCents()), cents(totalProfit), allocation,
                price == null ? null : price.source(), price == null ? null : price.tradeDate(), price == null ? null : price.fetchedAt(),
                price == null || price.stale(), price == null ? "NO_QUOTE" : price.error());
    }

    private static String cents(Long value) {
        return value == null ? null : Money.formatCents(value);
    }

    private record PositionKey(long accountId, long securityId) { }
    private record CalculatedPosition(InvestmentTrade trade, InvestmentPosition position, MarketPriceResponse price) { }

    private record Totals(long cost, long realized, Long marketValue, Long unrealized, int unpriced) {
        static Totals from(List<CalculatedPosition> positions) {
            BigInteger cost = BigInteger.ZERO;
            BigInteger realized = BigInteger.ZERO;
            BigInteger value = BigInteger.ZERO;
            BigInteger unrealized = BigInteger.ZERO;
            int unpriced = 0;
            for (CalculatedPosition position : positions) {
                cost = cost.add(BigInteger.valueOf(position.position().costCents()));
                realized = realized.add(BigInteger.valueOf(position.position().realizedProfitCents()));
                if (position.position().marketValueCents() == null) unpriced++;
                else {
                    value = value.add(BigInteger.valueOf(position.position().marketValueCents()));
                    unrealized = unrealized.add(BigInteger.valueOf(position.position().unrealizedProfitCents()));
                }
            }
            return new Totals(cost.longValueExact(), realized.longValueExact(), unpriced == 0 ? value.longValueExact() : null,
                    unpriced == 0 ? unrealized.longValueExact() : null, unpriced);
        }

        PortfolioTotalsResponse response() {
            Long total = unrealized == null ? null : Math.addExact(realized, unrealized);
            return new PortfolioTotalsResponse(Money.formatCents(cost), cents(marketValue), Money.formatCents(realized),
                    cents(unrealized), cents(total), unpriced);
        }
    }
}
