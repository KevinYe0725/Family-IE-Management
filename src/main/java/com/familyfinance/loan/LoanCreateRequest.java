package com.familyfinance.loan;
import java.math.BigDecimal; import java.time.LocalDate; import java.util.List;
public record LoanCreateRequest(String name, LoanType type, Long linkedAssetId, Long memberId, Long assignedUserId, Long paymentAccountId, Long paymentCategoryId, String principal, BigDecimal annualRate, Integer termMonths, RepaymentMethod repaymentMethod, LocalDate startOn, List<CustomInstallmentRequest> customSchedule, LoanFundingMode fundingMode, LocalDate accountingOn, Long disbursementAccountId, Boolean createPurchasedAsset, String disbursementAmount, String purchaseValue, Long ownContributionAccountId, LoanAssetRelation assetRelation) {
 public LoanCreateRequest(String name, LoanType type, Long linkedAssetId, Long memberId, Long assignedUserId, Long paymentAccountId, Long paymentCategoryId, String principal, BigDecimal annualRate, Integer termMonths, RepaymentMethod repaymentMethod, LocalDate startOn, List<CustomInstallmentRequest> customSchedule, LoanFundingMode fundingMode, LocalDate accountingOn, Long disbursementAccountId, Boolean createPurchasedAsset, String disbursementAmount) {
  this(name,type,linkedAssetId,memberId,assignedUserId,paymentAccountId,paymentCategoryId,principal,annualRate,termMonths,repaymentMethod,startOn,customSchedule,fundingMode,accountingOn,disbursementAccountId,createPurchasedAsset,disbursementAmount,null,null,null);
 }
 public LoanCreateRequest(String name, LoanType type, Long linkedAssetId, Long memberId, Long assignedUserId, Long paymentAccountId, Long paymentCategoryId, String principal, BigDecimal annualRate, Integer termMonths, RepaymentMethod repaymentMethod, LocalDate startOn, List<CustomInstallmentRequest> customSchedule, LoanFundingMode fundingMode, LocalDate accountingOn, Long disbursementAccountId, Boolean createPurchasedAsset) {
  this(name,type,linkedAssetId,memberId,assignedUserId,paymentAccountId,paymentCategoryId,principal,annualRate,termMonths,repaymentMethod,startOn,customSchedule,fundingMode,accountingOn,disbursementAccountId,createPurchasedAsset,null);
 }
 public LoanCreateRequest(String name, LoanType type, Long linkedAssetId, Long memberId, Long assignedUserId, Long paymentAccountId, Long paymentCategoryId, String principal, BigDecimal annualRate, Integer termMonths, RepaymentMethod repaymentMethod, LocalDate startOn, List<CustomInstallmentRequest> customSchedule, LoanFundingMode fundingMode, LocalDate accountingOn, Long disbursementAccountId) {
  this(name,type,linkedAssetId,memberId,assignedUserId,paymentAccountId,paymentCategoryId,principal,annualRate,termMonths,repaymentMethod,startOn,customSchedule,fundingMode,accountingOn,disbursementAccountId,false);
 }
 public LoanCreateRequest(String name, LoanType type, Long linkedAssetId, Long memberId, Long assignedUserId, Long paymentAccountId, Long paymentCategoryId, String principal, BigDecimal annualRate, Integer termMonths, RepaymentMethod repaymentMethod, LocalDate startOn, List<CustomInstallmentRequest> customSchedule) {
  this(name,type,linkedAssetId,memberId,assignedUserId,paymentAccountId,paymentCategoryId,principal,annualRate,termMonths,repaymentMethod,startOn,customSchedule,null,null,null);
 }
}
