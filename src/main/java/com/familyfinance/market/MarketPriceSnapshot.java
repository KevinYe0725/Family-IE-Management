package com.familyfinance.market;

import com.familyfinance.investment.Security;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "market_price_snapshots")
public class MarketPriceSnapshot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "security_id") private Security security;
    @Column(name = "trade_date", nullable = false) private LocalDate tradeDate;
    @Column(name = "open_cents", nullable = false) private long openCents;
    @Column(name = "high_cents", nullable = false) private long highCents;
    @Column(name = "low_cents", nullable = false) private long lowCents;
    @Column(name = "close_cents", nullable = false) private long closeCents;
    @Column(name = "pre_close_cents", nullable = false) private long preCloseCents;
    @Column(name = "pct_change", nullable = false, precision = 9, scale = 4) private BigDecimal pctChange;
    @Column(nullable = false, length = 16) private String source;
    @Column(name = "fetched_at", nullable = false) private Instant fetchedAt;
    protected MarketPriceSnapshot() { }
    MarketPriceSnapshot(Security security, DailyQuote quote, Instant fetchedAt) {
        this.security = security; this.tradeDate = quote.tradeDate(); this.openCents = quote.openCents();
        this.highCents = quote.highCents(); this.lowCents = quote.lowCents(); this.closeCents = quote.closeCents();
        this.preCloseCents = quote.preCloseCents(); this.pctChange = quote.pctChange();
        this.source = QuoteSource.TUSHARE.name(); this.fetchedAt = fetchedAt;
    }
    public Long getId() { return id; }
    public Security getSecurity() { return security; }
    public LocalDate getTradeDate() { return tradeDate; }
    public long getCloseCents() { return closeCents; }
    public Instant getFetchedAt() { return fetchedAt; }
}
