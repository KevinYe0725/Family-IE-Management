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
import java.time.LocalDate;
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bank_account_id")
    private BankAccount bankAccount;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "wallet_provider", length = 16)
    private WalletProvider walletProvider;

    @Column(name = "bank_name", length = 80)
    private String bankName;

    @Column(name = "card_last_four", length = 4)
    private String cardLastFour;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "opening_balance_cents", nullable = false)
    private Long openingBalanceCents;

    @Column(name = "opening_confirmed", nullable = false)
    private boolean openingConfirmed;
    @Column(name = "opening_on")
    private LocalDate openingOn;
    @Column(name = "opening_source_id")
    private Long openingSourceId;

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

    public WalletProvider getWalletProvider() { return walletProvider; }
    public String getBankName() { return bankName; }
    public String getCardLastFour() { return cardLastFour; }

    void updateDetails(WalletProvider walletProvider, String bankName, String cardLastFour) {
        this.walletProvider = walletProvider;
        this.bankName = bankName;
        this.cardLastFour = cardLastFour;
    }

    void updateBankMetadata(String bankName, String cardLastFour) {
        this.walletProvider = null;
        this.bankName = bankName;
        this.cardLastFour = cardLastFour;
    }

    void rename(String name) {
        this.name = Objects.requireNonNull(name, "name must not be null");
    }

    public String getCurrency() {
        return currency;
    }

    public BankAccount getBankAccount() {
        return bankAccount;
    }

    public Long getBankAccountId() {
        return bankAccount == null ? null : bankAccount.getId();
    }

    public String getBankAccountName() {
        return bankAccount == null ? null : bankAccount.getName();
    }

    void attachBankAccount(BankAccount bankAccount) {
        this.bankAccount = bankAccount;
    }

    void detachBankAccount() {
        this.bankAccount = null;
    }

    public Long getOpeningBalanceCents() {
        return openingBalanceCents;
    }

    public boolean isOpeningConfirmed() { return openingConfirmed; }
    public LocalDate getOpeningOn() { return openingOn; }
    public Long getOpeningSourceId() { return openingSourceId; }
    public void confirmOpening(long amount, LocalDate day, Long sourceId) {
        this.openingBalanceCents=amount;
        this.openingConfirmed=true;
        this.openingOn=Objects.requireNonNull(day);
        this.openingSourceId=sourceId;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    void update(String name, AccountType type, String currency, Long openingBalanceCents) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.currency = Objects.requireNonNull(currency, "currency must not be null");
        this.openingBalanceCents = Objects.requireNonNull(openingBalanceCents, "opening balance must not be null");
    }

    void archive(Instant archivedAt) {
        if (this.archivedAt == null) {
            this.archivedAt = Objects.requireNonNull(archivedAt, "archivedAt must not be null");
        }
    }
}
