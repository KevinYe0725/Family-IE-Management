package com.familyfinance.ledger;

import com.familyfinance.household.Household;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** A bank card identity that groups one or more native-currency cash accounts. */
@Entity
@Table(
        name = "bank_accounts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_bank_accounts_id_household",
                columnNames = {"id", "household_id"}))
public class BankAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    private Household household;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "bank_name", length = 80)
    private String bankName;

    @Column(name = "card_last_four", length = 4)
    private String cardLastFour;

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected BankAccount() {
    }

    public BankAccount(Household household, String name, String bankName, String cardLastFour) {
        this.household = Objects.requireNonNull(household, "household must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.bankName = bankName;
        this.cardLastFour = cardLastFour;
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

    public String getBankName() {
        return bankName;
    }

    public String getCardLastFour() {
        return cardLastFour;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    void update(String name, String bankName, String cardLastFour) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.bankName = bankName;
        this.cardLastFour = cardLastFour;
    }

    void archive(Instant at) {
        if (archivedAt == null) {
            archivedAt = Objects.requireNonNull(at, "archivedAt must not be null");
        }
    }
}
