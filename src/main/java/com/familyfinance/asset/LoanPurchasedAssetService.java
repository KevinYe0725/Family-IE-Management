package com.familyfinance.asset;

import com.familyfinance.household.FamilyMember;
import com.familyfinance.loan.Loan;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Domain metadata for a purchase whose single economic journal belongs to its loan. */
@Service
public class LoanPurchasedAssetService {
    private final AssetRepository assets;
    private final AssetValuationRepository valuations;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public LoanPurchasedAssetService(AssetRepository assets,AssetValuationRepository valuations,JdbcTemplate jdbc,Clock clock){
        this.assets=assets;this.valuations=valuations;this.jdbc=jdbc;this.clock=clock;
    }
    /** 自定义资产（名称/归属/购入价），用于“资产侧发起贷款购买”；贷款购买为全额贷款，购入价固定等于贷款本金（不允许首付差额）。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public Asset create(Loan loan,LocalDate day,String name,FamilyMember owner,long purchasePriceCents){
        AssetType type=switch(loan.getType()){case MORTGAGE->AssetType.PROPERTY;case CAR->AssetType.VEHICLE;case OTHER->AssetType.OTHER;};
        long purchase=purchasePriceCents>0?purchasePriceCents:loan.getPrincipalCents();
        if(purchase<loan.getPrincipalCents())throw new IllegalArgumentException("资产购入价值不能低于贷款本金");
        String assetName=name;
        if(assetName==null||assetName.trim().isEmpty()){
            // Household mutation lock is held. Current names include archived and manually named assets.
            var names=new HashSet<>(jdbc.queryForList("select name from assets where household_id=? for update",String.class,loan.getHousehold().getId()));
            String prefix=switch(type){case PROPERTY->"房产";case VEHICLE->"车辆";case OTHER->"其他资产";};
            int number=1;assetName=prefix+number;while(names.contains(assetName))assetName=prefix+(++number);
        }else if(assetName.trim().length()>100)throw new IllegalArgumentException("资产名称过长");
        var asset=new Asset(loan.getHousehold(),assetName.trim(),type,owner,day,purchase,purchase,loan.getCreatedBy());
        asset.purchasedWithLoan(loan.getId());
        asset.initialize(AssetAccountingMode.FINANCED_PURCHASE,day,purchase,null);
        assets.saveAndFlush(asset);
        valuations.saveAndFlush(new AssetValuation(loan.getHousehold(),asset,day,purchase,AssetValuationSource.PURCHASE,null,loan.getCreatedBy(),clock.instant()));
        return asset;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Asset create(Loan loan,LocalDate day){
        return create(loan,day,null,null,loan.getPrincipalCents());
    }
}
