package com.familyfinance.investment;

import com.familyfinance.household.AppUser;
import com.familyfinance.household.Household;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(name = "investment_trades")
public class InvestmentTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    private Household household;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private InvestmentAccount account;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "security_id", nullable = false)
    private Security security;

    @Enumerated(EnumType.STRING)
    @Column(name = "trade_type", nullable = false, length = 16)
    private InvestmentTradeType type;

    @Column(precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    @Column(name = "fee_cents", nullable = false)
    private long feeCents;

    @Column(name = "traded_on", nullable = false)
    private LocalDate tradedOn;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private AppUser createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 16, updatable = false)
    private InvestmentTradeSourceType sourceType;

    @Column(name = "source_id", length = 100, updatable = false)
    private String sourceId;

    protected InvestmentTrade() {
    }

    InvestmentTrade(
            Household household,
            InvestmentAccount account,
            Security security,
            InvestmentTradeType type,
            BigDecimal quantity,
            long priceCents,
            long feeCents,
            LocalDate tradedOn,
            AppUser createdBy) {
        this.household = Objects.requireNonNull(household);
        this.account = Objects.requireNonNull(account);
        this.security = Objects.requireNonNull(security);
        this.type = Objects.requireNonNull(type);
        this.quantity = quantity;
        this.priceCents = priceCents;
        this.feeCents = feeCents;
        this.tradedOn = Objects.requireNonNull(tradedOn);
        this.createdBy = Objects.requireNonNull(createdBy);
        this.sourceType = InvestmentTradeSourceType.MANUAL;
    }

    public Long getId() { return id; }
    public Household getHousehold() { return household; }
    public InvestmentAccount getAccount() { return account; }
    public Security getSecurity() { return security; }
    public InvestmentTradeType getType() { return type; }
    public BigDecimal getQuantity() { return quantity; }
    public long getPriceCents() { return priceCents; }
    public long getFeeCents() { return feeCents; }
    public LocalDate getTradedOn() { return tradedOn; }
    public AppUser getCreatedBy() { return createdBy; }
    public InvestmentTradeSourceType getSourceType() { return sourceType; }
    public String getSourceId() { return sourceId; }

    void update(
            InvestmentAccount account,
            Security security,
            InvestmentTradeType type,
            BigDecimal quantity,
            long priceCents,
            long feeCents,
            LocalDate tradedOn) {
        this.account = Objects.requireNonNull(account);
        this.security = Objects.requireNonNull(security);
        this.type = Objects.requireNonNull(type);
        this.quantity = quantity;
        this.priceCents = priceCents;
        this.feeCents = feeCents;
        this.tradedOn = Objects.requireNonNull(tradedOn);
    }

    PositionTrade toPositionTrade() {
        return new PositionTrade(id, tradedOn, type, quantity, priceCents, feeCents);
    }
}
