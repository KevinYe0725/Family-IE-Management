package com.familyfinance.loan;

import java.time.LocalDate;
import java.util.List;

public record LoanRepaymentPreview(List<DueInstallment> dueInstallments, String duePrincipalAmount,
        String dueInterestAmount, String additionalPrincipal, String totalPrincipalAmount, String totalInterestAmount,
        String totalCashAmount, long paymentAccountId, String availableBalance, String balanceAfter, LocalDate paidOn,
        PrepaymentStrategy strategy, Integer targetPeriods, LoanPrepaymentPreview.ScheduleSummary before,
        LoanPrepaymentPreview.ScheduleSummary after, List<LoanTermOptions.Option> termOptions,
        LoanRepaymentPolicy policy, String planToken, Boolean cashImpact, Long settlementAssetId) {
    public LoanRepaymentPreview {cashImpact=cashImpact==null?settlementAssetId==null:cashImpact;}
    public LoanRepaymentPreview(List<DueInstallment> dueInstallments,String duePrincipalAmount,
            String dueInterestAmount,String additionalPrincipal,String totalPrincipalAmount,String totalInterestAmount,
            String totalCashAmount,long paymentAccountId,String availableBalance,String balanceAfter,LocalDate paidOn,
            PrepaymentStrategy strategy,Integer targetPeriods,LoanPrepaymentPreview.ScheduleSummary before,
            LoanPrepaymentPreview.ScheduleSummary after,List<LoanTermOptions.Option> termOptions,
            LoanRepaymentPolicy policy,String planToken){
        this(dueInstallments,duePrincipalAmount,dueInterestAmount,additionalPrincipal,totalPrincipalAmount,totalInterestAmount,
            totalCashAmount,paymentAccountId,availableBalance,balanceAfter,paidOn,strategy,targetPeriods,before,after,termOptions,policy,planToken,true,null);
    }
    public record DueInstallment(long installmentId, int installmentNo, LocalDate dueOn,
            String principalAmount, String interestAmount, String cashAmount) {}
}
