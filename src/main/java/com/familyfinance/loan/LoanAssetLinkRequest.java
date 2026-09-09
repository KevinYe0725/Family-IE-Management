package com.familyfinance.loan;

public record LoanAssetLinkRequest(Long assetId,LoanAssetRelation relation,Long expectedAssetId,LoanAssetRelation expectedRelation) {}
