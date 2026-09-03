package com.familyfinance.loan;

import com.familyfinance.household.Household;
import com.familyfinance.transaction.FinancialTransaction;
import jakarta.persistence.*;
import java.time.*;

@Entity @Table(name="loan_prepayments")
public class LoanPrepayment {
 @Id @GeneratedValue(strategy=GenerationType.SEQUENCE,generator="loan_prepayment_seq") @SequenceGenerator(name="loan_prepayment_seq",sequenceName="loan_prepayment_id_seq",allocationSize=1) private Long id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="household_id") private Household household;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="loan_id") private Loan loan;
 @Column(name="request_key",nullable=false) private String requestKey;
 @Column(name="amount_cents",nullable=false) private long amountCents;
 @Column(name="paid_on",nullable=false) private LocalDate paidOn;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="transaction_id") private FinancialTransaction transaction;
 @Column(name="created_at",nullable=false) private Instant createdAt;
 protected LoanPrepayment(){}
 LoanPrepayment(Loan loan,String requestKey,long amountCents,LocalDate paidOn,Instant createdAt){this.household=loan.getHousehold();this.loan=loan;this.requestKey=requestKey;this.amountCents=amountCents;this.paidOn=paidOn;this.createdAt=createdAt;}
 void attach(FinancialTransaction value){this.transaction=value;}
 public Long getId(){return id;} public long getAmountCents(){return amountCents;} public LocalDate getPaidOn(){return paidOn;} public FinancialTransaction getTransaction(){return transaction;}
}
