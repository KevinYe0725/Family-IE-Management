package com.familyfinance.household;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "households")
public class Household {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected Household() {
    }

    public Household(String name, Instant createdAt) {
        this.name = name;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getStatus() { return status; }

    public Instant getArchivedAt() { return archivedAt; }

    public void rename(String name) { this.name = name; }

    public void archive(Instant archivedAt) { this.status = "ARCHIVED"; this.archivedAt = archivedAt; }
}
