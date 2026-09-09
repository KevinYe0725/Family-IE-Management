package com.familyfinance.loan;

import com.familyfinance.accounting.AccountingRequests;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Bounded historical DTO shapes; operation, actor and every then-supported field stay bound. */
final class LoanRequestHistory {
 private LoanRequestHistory(){}
 static String[] prepayment(AccountingRequests requests,String operation,long actor,LoanPrepaymentRequest r){
  if(r==null||r.strategy()!=null||r.planToken()!=null)return new String[0];
  String four=requests.digest(operation,actor,new PrepaymentV2(r.amount(),r.paidOn(),r.idempotencyKey(),r.paymentAccountId()));
  return r.paymentAccountId()!=null?new String[]{four}:new String[]{four,requests.digest(operation,actor,new PrepaymentV1(r.amount(),r.paidOn(),r.idempotencyKey()))};
 }
 static String[] create(AccountingRequests requests,long actor,LoanCreateRequest r){
  if(r==null||r.purchaseValue()!=null||r.ownContributionAccountId()!=null||r.assetRelation()!=null)return new String[0];
  String roundOne=requests.digest("LOAN_CREATE",actor,new CreateV3(r.name(),r.type(),r.linkedAssetId(),r.memberId(),r.assignedUserId(),r.paymentAccountId(),r.paymentCategoryId(),r.principal(),r.annualRate(),r.termMonths(),r.repaymentMethod(),r.startOn(),r.customSchedule(),r.fundingMode(),r.accountingOn(),r.disbursementAccountId(),r.createPurchasedAsset(),r.disbursementAmount()));
  if(r.disbursementAmount()!=null)return new String[]{roundOne};
  String previous=requests.digest("LOAN_CREATE",actor,new CreateV2(r.name(),r.type(),r.linkedAssetId(),r.memberId(),r.assignedUserId(),r.paymentAccountId(),r.paymentCategoryId(),r.principal(),r.annualRate(),r.termMonths(),r.repaymentMethod(),r.startOn(),r.customSchedule(),r.fundingMode(),r.accountingOn(),r.disbursementAccountId(),r.createPurchasedAsset()));
  return r.createPurchasedAsset()!=null?new String[]{roundOne,previous}:new String[]{roundOne,previous,requests.digest("LOAN_CREATE",actor,new CreateV1(r.name(),r.type(),r.linkedAssetId(),r.memberId(),r.assignedUserId(),r.paymentAccountId(),r.paymentCategoryId(),r.principal(),r.annualRate(),r.termMonths(),r.repaymentMethod(),r.startOn(),r.customSchedule(),r.fundingMode(),r.accountingOn(),r.disbursementAccountId()))};
 }
 static String[] payment(AccountingRequests requests,String operation,long actor,LoanPaymentRequest r){
  return r==null||r.paymentAccountId()!=null?new String[0]:new String[]{requests.digest(operation,actor,new PaymentV1(r.paidOn()))};
 }
 private record PrepaymentV1(String amount,LocalDate paidOn,String idempotencyKey){}
 private record PrepaymentV2(String amount,LocalDate paidOn,String idempotencyKey,Long paymentAccountId){}
 private record PaymentV1(LocalDate paidOn){}
 private record CreateV1(String name,LoanType type,Long linkedAssetId,Long memberId,Long assignedUserId,Long paymentAccountId,Long paymentCategoryId,String principal,BigDecimal annualRate,Integer termMonths,RepaymentMethod repaymentMethod,LocalDate startOn,List<CustomInstallmentRequest> customSchedule,LoanFundingMode fundingMode,LocalDate accountingOn,Long disbursementAccountId){}
 private record CreateV2(String name,LoanType type,Long linkedAssetId,Long memberId,Long assignedUserId,Long paymentAccountId,Long paymentCategoryId,String principal,BigDecimal annualRate,Integer termMonths,RepaymentMethod repaymentMethod,LocalDate startOn,List<CustomInstallmentRequest> customSchedule,LoanFundingMode fundingMode,LocalDate accountingOn,Long disbursementAccountId,Boolean createPurchasedAsset){}
 private record CreateV3(String name,LoanType type,Long linkedAssetId,Long memberId,Long assignedUserId,Long paymentAccountId,Long paymentCategoryId,String principal,BigDecimal annualRate,Integer termMonths,RepaymentMethod repaymentMethod,LocalDate startOn,List<CustomInstallmentRequest> customSchedule,LoanFundingMode fundingMode,LocalDate accountingOn,Long disbursementAccountId,Boolean createPurchasedAsset,String disbursementAmount){}
}
