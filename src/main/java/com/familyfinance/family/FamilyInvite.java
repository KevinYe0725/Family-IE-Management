package com.familyfinance.family;

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
import java.time.Instant;

@Entity
@Table(name = "family_invites")
public class FamilyInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    private Household household;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private HouseholdRole role;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "max_uses", nullable = false)
    private int maxUses;

    @Column(name = "used_count", nullable = false)
    private int usedCount;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FamilyInvite() {
    }

    public FamilyInvite(Household household, String tokenHash, HouseholdRole role, Instant expiresAt, int maxUses,
            AppUser createdBy, Instant createdAt) {
        this.household = household;
        this.tokenHash = tokenHash;
        this.role = role;
        this.expiresAt = expiresAt;
        this.maxUses = maxUses;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Household getHousehold() { return household; }
    public String getTokenHash() { return tokenHash; }
    public HouseholdRole getRole() { return role; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getMaxUses() { return maxUses; }
    public int getUsedCount() { return usedCount; }
    public Instant getRevokedAt() { return revokedAt; }
    public AppUser getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }

    public void consume(Instant now) {
        if (revokedAt != null) throw new InviteStateException("INVITE_REVOKED", "邀请已撤销");
        if (!expiresAt.isAfter(now)) throw new InviteStateException("INVITE_EXPIRED", "邀请已过期");
        if (usedCount >= maxUses) throw new InviteStateException("INVITE_EXHAUSTED", "邀请使用次数已用尽");
        usedCount++;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) revokedAt = now;
    }
}
