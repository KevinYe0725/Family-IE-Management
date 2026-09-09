package com.familyfinance.loan;

import com.familyfinance.accounting.AccountingRequests;
import com.familyfinance.asset.Asset;
import com.familyfinance.asset.AssetRepository;
import com.familyfinance.family.FamilyMutationAuthorization;
import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.shared.ResourceNotFoundException;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Existing financial activity is referenced without posting another economic event. */
@Service @Transactional
public class LoanAssetLinkService {
    private final LoanRepository loans;
    private final AssetRepository assets;
    private final FamilyMutationAuthorization mutations;
    private final AccountingRequests requests;
    private final LoanTotalsService totals;

    public LoanAssetLinkService(LoanRepository loans,AssetRepository assets,FamilyMutationAuthorization mutations,AccountingRequests requests,LoanTotalsService totals) {
        this.loans=loans;this.assets=assets;this.mutations=mutations;this.requests=requests;this.totals=totals;
    }

    public LoanResponse update(Authentication authentication,long id,LoanAssetLinkRequest request,String key) {
        var access=mutations.requireAdmin(authentication);
        long household=access.context().householdId();
        String digest=requests.digest("LOAN_ASSET_LINK:"+id,access.context().userId(),request);
        Long replay=requests.replay(household,key,digest);
        if(replay!=null)return response(locked(household,replay));
        Loan loan=locked(household,id);
        if(request==null)throw new RequestValidationException(Map.of("request","请求不能为空"));
        if((request.assetId()==null)!=(request.relation()==null))
            throw new RequestValidationException(Map.of("assetId","关联资产和关系类型必须同时填写或同时清除"));
        if((request.expectedAssetId()==null)!=(request.expectedRelation()==null))
            throw new RequestValidationException(Map.of("expectedAssetId","请提供原关联资产及其关系类型"));
        Long currentAsset=loan.getLinkedAsset()==null?null:loan.getLinkedAsset().getId();
        if(!Objects.equals(currentAsset,request.expectedAssetId())||loan.getAssetRelation()!=request.expectedRelation())
            throw new ResourceConflictException("LOAN_ASSET_LINK_CHANGED","资产关联已被更改，请刷新后重新确认");
        if(loan.getPurchasedAssetId()!=null&&(!Objects.equals(loan.getPurchasedAssetId(),request.assetId())||request.relation()!=LoanAssetRelation.FINANCING))
            throw new ResourceConflictException("LOAN_PURCHASE_IMMUTABLE","贷款购买物的原始来源关联不能移动、清除或改为抵押");
        Asset asset=null;
        if(request.assetId()!=null){
            asset=assets.findCurrent(request.assetId(),household).filter(a->!a.isArchived())
                .orElseThrow(()->new RequestValidationException(Map.of("assetId","关联资产必须属于当前家庭且未归档")));
            if(!request.relation().supports(loan.getType(),asset.getType()))
                throw new RequestValidationException(Map.of("assetId","贷款类型与融资资产类型不兼容"));
        }
        loan.assetLink(asset,request.relation());
        loans.flush();
        requests.record(household,key,digest,id);
        return response(loan);
    }

    private Loan locked(long household,long id) {
        return loans.findLockedByIdAndHouseholdId(id,household).orElseThrow(()->new ResourceNotFoundException("贷款不存在"));
    }
    private LoanResponse response(Loan loan) {return LoanResponse.from(loan,totals.read(loan,true));}
}
