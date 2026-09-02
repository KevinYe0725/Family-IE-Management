package com.familyfinance.ledger;

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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
        name = "financial_accounts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_financial_accounts_household_name",
                columnNames = {"household_id", "name"}))
public class FinancialAccount {

    public static final String DEFAULT_NAME = "默认账户";
    public static final String STAGE_TWO_CURRENCY = "CNY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    private Household household;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountType type;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "opening_balance_cents", nullable = false)
    private Long openingBalanceCents;

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected FinancialAccount() {
    }

    public FinancialAccount(
            Household household,
            String name,
            AccountType type,
            String currency,
            Long openingBalanceCents) {
        this.household = Objects.requireNonNull(household, "household must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.currency = Objects.requireNonNull(currency, "currency must not be null");
        this.openingBalanceCents = Objects.requireNonNull(openingBalanceCents, "opening balance must not be null");
    }

    public Long getId() {
        return id;
    }

    public Household getHousehold() {
        return household;
    }

    public String getName() {
        return name;
    }

    public AccountType getType() {
        return type;
    }

    public String getCurrency() {
        return currency;
    }

    public Long getOpeningBalanceCents() {
        return openingBalanceCents;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }
}
