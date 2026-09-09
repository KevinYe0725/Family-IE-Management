package com.familyfinance.asset;

import java.time.LocalDate;
import java.util.List;

public record AssetSalePreview(long assetId,String assetName,LocalDate disposedOn,AssetSaleDraft.Route route,
        String proceeds,String fee,String bookValue,String bookGain,String totalPrincipal,String totalInterest,
        String totalRepayment,String netSettlement,List<Balance> balances,List<Loan> loans,
        List<RetainedLoan> retainedLoans,boolean canConfirm,List<String> blockers,String planToken) {
    public record Balance(long accountId,String accountName,String currency,String before,String change,String after){}
    public record Loan(long loanId,String name,AssetSaleDraft.Mode mode,String principal,String interest,String total,
            String remainingPrincipal,Integer remainingTerm,String nextPaymentAmount,
            String duePrincipal,String dueInterest,String additionalPrincipal){}
    public record RetainedLoan(long loanId,String name,String remainingPrincipal){}
    AssetSalePreview token(String token){return new AssetSalePreview(assetId,assetName,disposedOn,route,proceeds,fee,bookValue,
        bookGain,totalPrincipal,totalInterest,totalRepayment,netSettlement,balances,loans,retainedLoans,canConfirm,blockers,token);}
}
