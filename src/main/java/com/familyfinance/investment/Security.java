package com.familyfinance.investment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "securities")
public class Security {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 2, updatable = false)
    private String market;

    @Column(name = "ts_code", nullable = false, length = 9, updatable = false)
    private String tsCode;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "security_type", nullable = false, length = 16, updatable = false)
    private String securityType;

    @Column(nullable = false)
    private boolean active;

    protected Security() {
    }

    public Long getId() { return id; }
    public String getMarket() { return market; }
    public String getTsCode() { return tsCode; }
    public String getName() { return name; }
    public String getSecurityType() { return securityType; }
    public boolean isActive() { return active; }
}
