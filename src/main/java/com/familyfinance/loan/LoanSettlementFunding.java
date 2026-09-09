package com.familyfinance.loan;

import com.familyfinance.transaction.FinancialTransaction;

/** Trusted orchestration context, never bound from a public loan request. */
public record LoanSettlementFunding(Long settlementAssetId) {
    public LoanSettlementFunding {
        if(settlementAssetId!=null&&settlementAssetId<=0)throw new IllegalArgumentException("invalid settlement asset");
    }
    public static LoanSettlementFunding cash(){return new LoanSettlementFunding(null);}
    public static LoanSettlementFunding assetSale(long assetId){return new LoanSettlementFunding(assetId);}
    public boolean cashImpact(){return settlementAssetId==null;}
    public void mark(FinancialTransaction transaction){if(!cashImpact())transaction.markAssetSettlement(settlementAssetId);}
}
