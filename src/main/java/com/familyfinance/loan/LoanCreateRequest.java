package com.familyfinance.loan;
import java.math.BigDecimal; import java.time.LocalDate; import java.util.List;
public record LoanCreateRequest(String name, LoanType type, Long linkedAssetId, Long memberId, Long assignedUserId, Long paymentAccountId, Long paymentCategoryId, String principal, BigDecimal annualRate, Integer termMonths, RepaymentMethod repaymentMethod, LocalDate startOn, List<CustomInstallmentRequest> customSchedule) {}
