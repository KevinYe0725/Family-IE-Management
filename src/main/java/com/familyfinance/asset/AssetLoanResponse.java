package com.familyfinance.asset;

import com.familyfinance.loan.LoanAssetRelation;
import com.familyfinance.loan.LoanStatus;
import java.util.List;

public record AssetLoanResponse(long assetId,String financedPrincipal,String referenceEquity,List<LinkedLoan> loans) {
    public record LinkedLoan(long loanId,String name,LoanStatus status,LoanAssetRelation relation,String remainingPrincipal,boolean originPurchase) {}
}
