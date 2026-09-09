package com.familyfinance.asset;

import com.familyfinance.loan.PrepaymentStrategy;
import java.time.LocalDate;
import java.util.List;

public record AssetSaleDraft(LocalDate disposedOn,String proceeds,String fee,Long cashAccountId,
        Long repaymentAccountId,Route route,List<Repayment> repayments,boolean retainUnselectedLoans) {
    public enum Route { VIA_ACCOUNT,DIRECT }
    public enum Mode { PAYOFF,PARTIAL }
    public record Repayment(long loanId,Mode mode,String additionalPrincipal,PrepaymentStrategy strategy,
            Integer targetPeriods,String interestAmount){}
}
