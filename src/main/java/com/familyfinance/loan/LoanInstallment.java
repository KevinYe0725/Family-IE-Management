package com.familyfinance.loan;
import com.familyfinance.household.Household;
import com.familyfinance.transaction.FinancialTransaction;
import jakarta.persistence.*;
import java.time.LocalDate;
@Entity @Table(name="loan_installments")
public class LoanInstallment {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="loan_id") private Loan loan;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="household_id") private Household household;
 @Column(name="installment_no",nullable=false) private int installmentNo;
 @Column(name="due_on",nullable=false) private LocalDate dueOn;
 @Column(name="principal_cents",nullable=false) private long principalCents;
 @Column(name="interest_cents",nullable=false) private long interestCents;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private LoanInstallmentStatus status=LoanInstallmentStatus.PENDING;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="confirmed_transaction_id") private FinancialTransaction confirmedTransaction;
 protected LoanInstallment(){} LoanInstallment(Loan loan, InstallmentDraft d){this.loan=loan;this.household=loan.getHousehold();installmentNo=d.installmentNo();dueOn=d.dueOn();principalCents=d.principalCents();interestCents=d.interestCents();}
 public Long getId(){return id;} public Loan getLoan(){return loan;} public Household getHousehold(){return household;} public int getInstallmentNo(){return installmentNo;} public LocalDate getDueOn(){return dueOn;} public long getPrincipalCents(){return principalCents;} public long getInterestCents(){return interestCents;} public LoanInstallmentStatus getStatus(){return status;} public FinancialTransaction getConfirmedTransaction(){return confirmedTransaction;}
 void confirm(FinancialTransaction transaction){ if(status==LoanInstallmentStatus.PENDING){confirmedTransaction=transaction;status=LoanInstallmentStatus.PAID;} }
 void cancel(){if(status==LoanInstallmentStatus.PENDING)status=LoanInstallmentStatus.CANCELLED;}
}
