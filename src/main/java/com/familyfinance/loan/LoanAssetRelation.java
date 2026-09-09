package com.familyfinance.loan;

import com.familyfinance.asset.AssetType;

public enum LoanAssetRelation {
    FINANCING,
    COLLATERAL;

    public boolean supports(LoanType loanType,AssetType assetType) {
        return this==COLLATERAL || switch(assetType) {
            case PROPERTY -> loanType==LoanType.MORTGAGE;
            case VEHICLE -> loanType==LoanType.CAR;
            case OTHER -> loanType==LoanType.OTHER;
        };
    }
}
