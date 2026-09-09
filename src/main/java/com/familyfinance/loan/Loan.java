package com.familyfinance.loan;

import com.familyfinance.asset.Asset;
import com.familyfinance.category.Category;
import com.familyfinance.household.AppUser;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.Household;
import com.familyfinance.ledger.FinancialAccount;
import jakarta.persistence.*;
import java.math.BigDecimal;
import com.familyfinance.shared.DecimalMoney;
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
    @Column(name = "purchased_asset_id") private Long purchasedAssetId;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "member_id") private FamilyMember member;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "assigned_user_id") private AppUser assignedUser;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "payment_account_id") private FinancialAccount paymentAccount;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "payment_category_id") private Category paymentCategory;
    @Column(name = "principal_amount", nullable = false, precision=21, scale=2) private BigDecimal principalAmount;
    @Column(name = "annual_rate", nullable = false, precision = 9, scale = 6) private BigDecimal annualRate;
    @Column(name = "term_months", nullable = false) private int termMonths;
    @Enumerated(EnumType.STRING) @Column(name = "repayment_method", nullable = false) private RepaymentMethod repaymentMethod;
    @Column(name = "start_on", nullable = false) private LocalDate startOn;
    @Column(name = "current_principal_amount", nullable = false, precision=21, scale=2) private BigDecimal currentPrincipalAmount;
    @Column(name = "repayment_policy_revision", nullable=false) private long repaymentPolicyRevision;
    @Column(name = "minimum_installment_amount",precision=21,scale=2) private BigDecimal minimumInstallmentAmount;
    @Column(name = "repayment_policy_source") private String repaymentPolicySource;
    @Enumerated(EnumType.STRING) @Column(name = "funding_mode") private LoanFundingMode fundingMode;
    @Column(name = "accounting_on") private LocalDate accountingOn;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "disbursement_account_id") private FinancialAccount disbursementAccount;
    @Column(name = "last_payment_on") private LocalDate lastPaymentOn;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private LoanStatus status = LoanStatus.ACTIVE;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by") private AppUser createdBy;
    @Column(name = "archived_at") private Instant archivedAt;
    @OneToMany(mappedBy = "loan", cascade = CascadeType.ALL, orphanRemoval = true) @OrderBy("installmentNo asc") private List<LoanInstallment> installments = new ArrayList<>();
    protected Loan() {}
    Loan(Household household, String name, LoanType type, Asset linkedAsset, FamilyMember member, AppUser assignedUser,
         FinancialAccount paymentAccount, Category paymentCategory, long principalCents, BigDecimal annualRate, int termMonths,
         RepaymentMethod repaymentMethod, LocalDate startOn, AppUser createdBy) {
        this.household=household; this.name=name; this.type=type; this.linkedAsset=linkedAsset; this.member=member; this.assignedUser=assignedUser;
        this.paymentAccount=paymentAccount; this.paymentCategory=paymentCategory; this.principalAmount=DecimalMoney.fromCents(principalCents); this.annualRate=annualRate;
        this.termMonths=termMonths; this.repaymentMethod=repaymentMethod; this.startOn=startOn; this.currentPrincipalAmount=this.principalAmount; this.createdBy=createdBy;
    }
    public Long getId(){return id;} public Household getHousehold(){return household;} public String getName(){return name;} public LoanType getType(){return type;}
    public Asset getLinkedAsset(){return linkedAsset;} public FamilyMember getMember(){return member;} public AppUser getAssignedUser(){return assignedUser;}
    public FinancialAccount getPaymentAccount(){return paymentAccount;} public Category getPaymentCategory(){return paymentCategory;} public long getPrincipalCents(){return DecimalMoney.toCents(principalAmount);}
    public BigDecimal getPrincipalAmount(){return principalAmount;} public BigDecimal getCurrentPrincipalAmount(){return currentPrincipalAmount;}
    public long getRepaymentPolicyRevision(){return repaymentPolicyRevision;}
    public BigDecimal getMinimumInstallmentAmount(){return minimumInstallmentAmount;}
    public String getRepaymentPolicySource(){return repaymentPolicySource;}
    void repaymentPolicy(BigDecimal minimum,String source){minimumInstallmentAmount=minimum;repaymentPolicySource=source;repaymentPolicyRevision++;}
    public BigDecimal getAnnualRate(){return annualRate;} public int getTermMonths(){return termMonths;} public RepaymentMethod getRepaymentMethod(){return repaymentMethod;}
    public LocalDate getStartOn(){return startOn;} public long getCurrentPrincipalCents(){return DecimalMoney.toCents(currentPrincipalAmount);} public LoanStatus getStatus(){return status;} public AppUser getCreatedBy(){return createdBy;}
    public Instant getArchivedAt(){return archivedAt;} public List<LoanInstallment> getInstallments(){return installments;} public boolean isArchived(){return status != LoanStatus.ACTIVE;}
    void replaceSchedule(List<InstallmentDraft> drafts) { installments.clear(); drafts.forEach(d -> installments.add(new LoanInstallment(this,d))); }
    public LoanFundingMode getFundingMode(){return fundingMode;}
    public Long getPurchasedAssetId(){return purchasedAssetId;}
    void attachPurchasedAsset(Asset asset){linkedAsset=asset;purchasedAssetId=asset.getId();}
    public LocalDate getAccountingOn(){return accountingOn;}
    public FinancialAccount getDisbursementAccount(){return disbursementAccount;}
    public LocalDate getLastPaymentOn(){return lastPaymentOn;}
    void accounting(LoanFundingMode mode, LocalDate day, FinancialAccount account){fundingMode=mode;accountingOn=day;disbursementAccount=account;}
    void paidOn(LocalDate day){lastPaymentOn=day;}
    void updateDefaults(String name, FamilyMember member, AppUser user, Asset asset, FinancialAccount account, Category category) {
        this.name=name;this.member=member;this.assignedUser=user;this.linkedAsset=asset;this.paymentAccount=account;this.paymentCategory=category;
    }
    void update(String name, FamilyMember member, AppUser assignedUser, Asset linkedAsset, FinancialAccount account, Category category,
                long principal, BigDecimal rate, int term, RepaymentMethod method, LocalDate start, List<InstallmentDraft> schedule) {
        updateContract(name,member,assignedUser,linkedAsset,account,category,principal,rate,term,method,start);replaceSchedule(schedule);
    }
    void updateContract(String name, FamilyMember member, AppUser assignedUser, Asset linkedAsset, FinancialAccount account, Category category,
                long principal, BigDecimal rate, int term, RepaymentMethod method, LocalDate start) {
        this.name=name; this.member=member; this.assignedUser=assignedUser; this.linkedAsset=linkedAsset; this.paymentAccount=account; this.paymentCategory=category;
        this.principalAmount=DecimalMoney.fromCents(principal); this.currentPrincipalAmount=principalAmount; this.annualRate=rate; this.termMonths=term; this.repaymentMethod=method; this.startOn=start;
    }
    void archive(Instant at) { if (status == LoanStatus.ACTIVE || status == LoanStatus.CLOSED) { status=LoanStatus.ARCHIVED; archivedAt=at; } }
    void applyPrincipalPayment(long amount, Instant at) {
        applyPrincipalPayment(DecimalMoney.fromCents(amount),at);
    }
    void applyPrincipalPayment(BigDecimal amount, Instant at) {
        amount=DecimalMoney.settled(amount);
        if (amount.signum()<0 || amount.compareTo(currentPrincipalAmount)>0) throw new IllegalArgumentException("invalid principal payment");
        currentPrincipalAmount=currentPrincipalAmount.subtract(amount);
        if (amount.signum()>0 && currentPrincipalAmount.signum()==0) { status = LoanStatus.CLOSED; archivedAt = at; }
    }
    void cancelPendingInstallments() { installments.forEach(LoanInstallment::cancel); }
    void appendSchedule(List<InstallmentDraft> drafts) { drafts.forEach(d -> installments.add(new LoanInstallment(this, d))); }
    int nextInstallmentNo() { return installments.stream().mapToInt(LoanInstallment::getInstallmentNo).max().orElse(0) + 1; }
}
