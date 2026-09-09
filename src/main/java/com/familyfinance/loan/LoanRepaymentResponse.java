package com.familyfinance.loan;

import java.time.*;
import java.util.List;

/** balanceAfter and remainingPrincipal are the original operation's result, including on replay/history. */
public record LoanRepaymentResponse(long batchId, long loanId, LoanStatus status, String remainingPrincipal,
        Instant recordedAt, List<LoanRepaymentPreview.DueInstallment> dueInstallments, String duePrincipalAmount,
        String dueInterestAmount, String additionalPrincipal, String totalPrincipalAmount, String totalInterestAmount,
        String totalCashAmount, long paymentAccountId, String availableBalance, String balanceAfter, LocalDate paidOn,
        PrepaymentStrategy strategy, Integer targetPeriods, LoanPrepaymentPreview.ScheduleSummary before,
        LoanPrepaymentPreview.ScheduleSummary after, List<LoanTermOptions.Option> termOptions,
        LoanRepaymentPolicy policy, String planToken, List<LoanRepaymentBatch.Child> children,
        Boolean cashImpact,Long settlementAssetId) {
    public LoanRepaymentResponse {cashImpact=cashImpact==null?settlementAssetId==null:cashImpact;}
    public LoanRepaymentResponse(long batchId,long loanId,LoanStatus status,String remainingPrincipal,
            Instant recordedAt,List<LoanRepaymentPreview.DueInstallment> dueInstallments,String duePrincipalAmount,
            String dueInterestAmount,String additionalPrincipal,String totalPrincipalAmount,String totalInterestAmount,
            String totalCashAmount,long paymentAccountId,String availableBalance,String balanceAfter,LocalDate paidOn,
            PrepaymentStrategy strategy,Integer targetPeriods,LoanPrepaymentPreview.ScheduleSummary before,
            LoanPrepaymentPreview.ScheduleSummary after,List<LoanTermOptions.Option> termOptions,
            LoanRepaymentPolicy policy,String planToken,List<LoanRepaymentBatch.Child> children){
        this(batchId,loanId,status,remainingPrincipal,recordedAt,dueInstallments,duePrincipalAmount,dueInterestAmount,
            additionalPrincipal,totalPrincipalAmount,totalInterestAmount,totalCashAmount,paymentAccountId,availableBalance,
            balanceAfter,paidOn,strategy,targetPeriods,before,after,termOptions,policy,planToken,children,true,null);
    }
    static LoanRepaymentResponse from(LoanRepaymentBatch b) {
        var q=b.preview();
        return new LoanRepaymentResponse(b.id(),b.loanId(),b.status(),b.remainingPrincipal(),b.recordedAt(),
                q.dueInstallments(),q.duePrincipalAmount(),q.dueInterestAmount(),q.additionalPrincipal(),
                q.totalPrincipalAmount(),q.totalInterestAmount(),q.totalCashAmount(),q.paymentAccountId(),
                q.availableBalance(),q.balanceAfter(),q.paidOn(),q.strategy(),q.targetPeriods(),q.before(),q.after(),
                q.termOptions(),q.policy(),q.planToken(),b.children(),q.cashImpact(),q.settlementAssetId());
    }
}
