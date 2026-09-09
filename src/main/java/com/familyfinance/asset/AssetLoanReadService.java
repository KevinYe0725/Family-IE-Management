package com.familyfinance.asset;

import com.familyfinance.family.CurrentMembership;
import com.familyfinance.loan.Loan;
import com.familyfinance.loan.LoanAssetRelation;
import com.familyfinance.loan.LoanRepository;
import com.familyfinance.loan.LoanStatus;
import com.familyfinance.shared.DecimalMoney;
import com.familyfinance.shared.ResourceNotFoundException;
import java.math.BigDecimal;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class AssetLoanReadService {
    private final AssetRepository assets;
    private final LoanRepository loans;
    private final CurrentMembership current;

    public AssetLoanReadService(AssetRepository assets,LoanRepository loans,CurrentMembership current) {this.assets=assets;this.loans=loans;this.current=current;}

    public AssetLoanResponse get(Authentication authentication,long id) {
        long household=current.require(authentication).householdId();
        Asset asset=assets.findByIdAndHouseholdId(id,household).orElseThrow(()->new ResourceNotFoundException("资产不存在"));
        var linked=loans.findAllByHouseholdIdAndLinkedAsset_IdOrderByIdAsc(household,id);
        BigDecimal financed=linked.stream().filter(l->l.getStatus()==LoanStatus.ACTIVE&&l.getAssetRelation()==LoanAssetRelation.FINANCING)
            .map(Loan::getCurrentPrincipalAmount).reduce(BigDecimal.ZERO,BigDecimal::add);
        var rows=linked.stream().map(l->new AssetLoanResponse.LinkedLoan(l.getId(),l.getName(),l.getStatus(),l.getAssetRelation(),
            DecimalMoney.format(l.getCurrentPrincipalAmount()),Objects.equals(l.getPurchasedAssetId(),asset.getId()))).toList();
        return new AssetLoanResponse(id,DecimalMoney.format(financed),DecimalMoney.format(DecimalMoney.fromCents(asset.getCurrentValueCents()).subtract(financed)),rows);
    }
}
