package com.familyfinance.loan;

import com.familyfinance.shared.Money;
import java.time.LocalDate;

public record LoanPrepaymentResponse(long id,long transactionId,String amount,String remainingPrincipal,LoanStatus status,
        LocalDate paidOn,String principalAmount,String interestAmount,String cashAmount,LoanPrepaymentKind operationKind,
        long paymentAccountId,String scheduledRepaymentTotal,String remainingRepaymentTotal,String paidRepaymentTotal,
        PrepaymentStrategy strategy,Long repaymentBatchId,Boolean cashImpact,Long settlementAssetId) {
    public LoanPrepaymentResponse {cashImpact=cashImpact==null?settlementAssetId==null:cashImpact;}
    public LoanPrepaymentResponse(long id,long transactionId,String amount,String remainingPrincipal,LoanStatus status,
            LocalDate paidOn,String principalAmount,String interestAmount,String cashAmount,LoanPrepaymentKind operationKind,
            long paymentAccountId,String scheduledRepaymentTotal,String remainingRepaymentTotal,String paidRepaymentTotal,
            PrepaymentStrategy strategy,Long repaymentBatchId){
        this(id,transactionId,amount,remainingPrincipal,status,paidOn,principalAmount,interestAmount,cashAmount,operationKind,
            paymentAccountId,scheduledRepaymentTotal,remainingRepaymentTotal,paidRepaymentTotal,strategy,repaymentBatchId,true,null);
    }
    static LoanPrepaymentResponse from(LoanPrepayment p,Loan loan,LoanTotalsService.Totals totals){
        return new LoanPrepaymentResponse(p.getId(),p.getTransaction().getId(),Money.formatCents(p.getAmountCents()),
            Money.formatCents(loan.getCurrentPrincipalCents()),loan.getStatus(),p.getPaidOn(),Money.formatCents(p.getAmountCents()),
            Money.formatCents(p.getInterestCents()),Money.formatCents(p.getTransaction().getAmountCents()),p.getOperationKind(),
            p.getTransaction().getAccount().getId(),totals.scheduledRepaymentTotal(),totals.remainingRepaymentTotal(),totals.paidRepaymentTotal(),
            p.getStrategy(),p.getRepaymentBatchId(),p.getTransaction().hasCashImpact(),p.getTransaction().getAssetSettlementId());
    }
}
