package com.familyfinance.loan;
import com.familyfinance.shared.Money;
public record LoanPrepaymentResponse(long id,long transactionId,String amount,String remainingPrincipal,LoanStatus status) { static LoanPrepaymentResponse from(LoanPrepayment p,Loan loan){return new LoanPrepaymentResponse(p.getId(),p.getTransaction().getId(),Money.formatCents(p.getAmountCents()),Money.formatCents(loan.getCurrentPrincipalCents()),loan.getStatus());} }
