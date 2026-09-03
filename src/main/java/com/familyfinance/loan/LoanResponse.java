package com.familyfinance.loan;
import com.familyfinance.shared.Money; import java.time.LocalDate;
public record LoanResponse(long id,String name,LoanType type,Long linkedAssetId,Long memberId,Long assignedUserId,Long paymentAccountId,Long paymentCategoryId,String principal,String annualRate,int termMonths,RepaymentMethod repaymentMethod,LocalDate startOn,String currentPrincipal,LoanStatus status) {
 static LoanResponse from(Loan l){return new LoanResponse(l.getId(),l.getName(),l.getType(),l.getLinkedAsset()==null?null:l.getLinkedAsset().getId(),l.getMember()==null?null:l.getMember().getId(),l.getAssignedUser()==null?null:l.getAssignedUser().getId(),l.getPaymentAccount().getId(),l.getPaymentCategory().getId(),Money.formatCents(l.getPrincipalCents()),l.getAnnualRate().toPlainString(),l.getTermMonths(),l.getRepaymentMethod(),l.getStartOn(),Money.formatCents(l.getCurrentPrincipalCents()),l.getStatus());}
}
