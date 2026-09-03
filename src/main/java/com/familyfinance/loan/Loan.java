package com.familyfinance.loan;

import com.familyfinance.asset.Asset;
import com.familyfinance.category.Category;
import com.familyfinance.household.AppUser;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.Household;
import com.familyfinance.ledger.FinancialAccount;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity @Table(name = "loans")
public class Loan {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "household_id") private Household household;
    @Column(nullable = false) private String name;
    @Enumerated(EnumType.STRING) @Column(name = "loan_type", nullable = false) private LoanType type;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "linked_asset_id") private Asset linkedAsset;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "member_id") private FamilyMember member;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "assigned_user_id") private AppUser assignedUser;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "payment_account_id") private FinancialAccount paymentAccount;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "payment_category_id") private Category paymentCategory;
    @Column(name = "principal_cents", nullable = false) private long principalCents;
    @Column(name = "annual_rate", nullable = false, precision = 9, scale = 6) private BigDecimal annualRate;
    @Column(name = "term_months", nullable = false) private int termMonths;
    @Enumerated(EnumType.STRING) @Column(name = "repayment_method", nullable = false) private RepaymentMethod repaymentMethod;
    @Column(name = "start_on", nullable = false) private LocalDate startOn;
    @Column(name = "current_principal_cents", nullable = false) private long currentPrincipalCents;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private LoanStatus status = LoanStatus.ACTIVE;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by") private AppUser createdBy;
    @Column(name = "archived_at") private Instant archivedAt;
    @OneToMany(mappedBy = "loan", cascade = CascadeType.ALL, orphanRemoval = true) @OrderBy("installmentNo asc") private List<LoanInstallment> installments = new ArrayList<>();
    protected Loan() {}
    Loan(Household household, String name, LoanType type, Asset linkedAsset, FamilyMember member, AppUser assignedUser,
         FinancialAccount paymentAccount, Category paymentCategory, long principalCents, BigDecimal annualRate, int termMonths,
         RepaymentMethod repaymentMethod, LocalDate startOn, AppUser createdBy) {
        this.household=household; this.name=name; this.type=type; this.linkedAsset=linkedAsset; this.member=member; this.assignedUser=assignedUser;
        this.paymentAccount=paymentAccount; this.paymentCategory=paymentCategory; this.principalCents=principalCents; this.annualRate=annualRate;
        this.termMonths=termMonths; this.repaymentMethod=repaymentMethod; this.startOn=startOn; this.currentPrincipalCents=principalCents; this.createdBy=createdBy;
    }
    public Long getId(){return id;} public Household getHousehold(){return household;} public String getName(){return name;} public LoanType getType(){return type;}
    public Asset getLinkedAsset(){return linkedAsset;} public FamilyMember getMember(){return member;} public AppUser getAssignedUser(){return assignedUser;}
    public FinancialAccount getPaymentAccount(){return paymentAccount;} public Category getPaymentCategory(){return paymentCategory;} public long getPrincipalCents(){return principalCents;}
    public BigDecimal getAnnualRate(){return annualRate;} public int getTermMonths(){return termMonths;} public RepaymentMethod getRepaymentMethod(){return repaymentMethod;}
    public LocalDate getStartOn(){return startOn;} public long getCurrentPrincipalCents(){return currentPrincipalCents;} public LoanStatus getStatus(){return status;} public AppUser getCreatedBy(){return createdBy;}
    public Instant getArchivedAt(){return archivedAt;} public List<LoanInstallment> getInstallments(){return installments;} public boolean isArchived(){return status != LoanStatus.ACTIVE;}
    void replaceSchedule(List<InstallmentDraft> drafts) { installments.clear(); drafts.forEach(d -> installments.add(new LoanInstallment(this,d))); }
    void update(String name, FamilyMember member, AppUser assignedUser, Asset linkedAsset, FinancialAccount account, Category category,
                long principal, BigDecimal rate, int term, RepaymentMethod method, LocalDate start, List<InstallmentDraft> schedule) {
        this.name=name; this.member=member; this.assignedUser=assignedUser; this.linkedAsset=linkedAsset; this.paymentAccount=account; this.paymentCategory=category;
        this.principalCents=principal; this.currentPrincipalCents=principal; this.annualRate=rate; this.termMonths=term; this.repaymentMethod=method; this.startOn=start; replaceSchedule(schedule);
    }
    void archive(Instant at) { if (status == LoanStatus.ACTIVE) { status=LoanStatus.ARCHIVED; archivedAt=at; } }
}
