package com.familyfinance.loan;
import java.math.BigDecimal; import java.time.LocalDate; import java.util.List;
public record LoanCreateRequest(String name, LoanType type, Long linkedAssetId, Long memberId, Long assignedUserId, Long paymentAccountId, Long paymentCategoryId, String principal, BigDecimal annualRate, Integer termMonths, RepaymentMethod repaymentMethod, LocalDate startOn, List<CustomInstallmentRequest> customSchedule, LoanFundingMode fundingMode, LocalDate accountingOn, Long disbursementAccountId, Boolean createPurchasedAsset, PurchasedAssetSpec purchasedAsset, Long downPaymentAccountId) {
 /** 贷款购买自动创建的资产规格：可自定义名称/归属；purchaseValue 默认等于贷款本金（无首付）。 */
 public record PurchasedAssetSpec(String name, Long ownerMemberId, String purchaseValue) {}
 public LoanCreateRequest(String name, LoanType type, Long linkedAssetId, Long memberId, Long assignedUserId, Long paymentAccountId, Long paymentCategoryId, String principal, BigDecimal annualRate, Integer termMonths, RepaymentMethod repaymentMethod, LocalDate startOn, List<CustomInstallmentRequest> customSchedule, LoanFundingMode fundingMode, LocalDate accountingOn, Long disbursementAccountId) {
  this(name,type,linkedAssetId,memberId,assignedUserId,paymentAccountId,paymentCategoryId,principal,annualRate,termMonths,repaymentMethod,startOn,customSchedule,fundingMode,accountingOn,disbursementAccountId,false,null,null);
 }
 public LoanCreateRequest(String name, LoanType type, Long linkedAssetId, Long memberId, Long assignedUserId, Long paymentAccountId, Long paymentCategoryId, String principal, BigDecimal annualRate, Integer termMonths, RepaymentMethod repaymentMethod, LocalDate startOn, List<CustomInstallmentRequest> customSchedule) {
  this(name,type,linkedAssetId,memberId,assignedUserId,paymentAccountId,paymentCategoryId,principal,annualRate,termMonths,repaymentMethod,startOn,customSchedule,null,null,null);
 }
}
