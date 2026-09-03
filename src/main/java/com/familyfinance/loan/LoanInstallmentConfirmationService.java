package com.familyfinance.loan;

import com.familyfinance.category.*;
import com.familyfinance.family.*;
import com.familyfinance.household.*;
import com.familyfinance.ledger.*;
import com.familyfinance.shared.*;
import com.familyfinance.transaction.*;
import com.familyfinance.notification.NotificationService;
import java.time.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanInstallmentConfirmationService {
 private final LoanInstallmentRepository installments; private final FinancialTransactionRepository transactions; private final FinancialAccountRepository accounts; private final CategoryRepository categories; private final FamilyMemberRepository members; private final FamilyMutationAuthorization authorization; private final FamilyPermissionService permissions; private final NotificationService notifications; private final Clock clock;
 LoanInstallmentConfirmationService(LoanInstallmentRepository installments, FinancialTransactionRepository transactions, FinancialAccountRepository accounts, CategoryRepository categories, FamilyMemberRepository members, FamilyMutationAuthorization authorization, FamilyPermissionService permissions, NotificationService notifications, Clock clock) {this.installments=installments;this.transactions=transactions;this.accounts=accounts;this.categories=categories;this.members=members;this.authorization=authorization;this.permissions=permissions;this.notifications=notifications;this.clock=clock;}
 @Transactional public LoanInstallmentResponse confirm(Authentication authentication,long installmentId) {
  var access=authorization.requireCurrent(authentication); long householdId=access.context().householdId();
  LoanInstallment installment=installments.findLockedByIdAndHouseholdId(installmentId,householdId).orElseThrow(()->new ResourceNotFoundException("还款期次不存在"));
  Loan loan=installment.getLoan();
  Long assignee=loan.getAssignedUser()==null?null:loan.getAssignedUser().getId();
  if(assignee==null) throw new ResourceConflictException("INSTALLMENT_UNASSIGNED","贷款尚未分配确认人");
  permissions.requireCanConfirmAssignedOccurrence(access.context(),assignee);
  if(installment.getStatus()==LoanInstallmentStatus.PAID) return LoanInstallmentResponse.from(installment);
  if(installment.getStatus()==LoanInstallmentStatus.CANCELLED) throw new ResourceConflictException("INSTALLMENT_CANCELLED","还款期次已取消");
  if(installment.getDueOn().isAfter(LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))))) throw new ResourceConflictException("INSTALLMENT_NOT_DUE","还款期次尚未到期");
  if(loan.getStatus()!=LoanStatus.ACTIVE) throw new ResourceConflictException("LOAN_CLOSED","贷款已归档或结清");
  if(loan.getAssignedUser().getStatus()!=AppUserStatus.ACTIVE) throw stale();
  FinancialTransaction existing=transactions.findBySourceTypeAndSourceId(TransactionSourceType.LOAN_PAYMENT,installmentId).orElse(null);
  if(existing!=null){installment.confirm(existing);return LoanInstallmentResponse.from(installment);}
  try {
   FinancialTransaction transaction=transactions.saveAndFlush(FinancialTransaction.loanPayment(access.household(),account(loan,householdId),access.membership().getUser(),member(loan,householdId),category(loan,householdId),Math.addExact(installment.getPrincipalCents(),installment.getInterestCents()),installment.getDueOn(),installmentId,clock.instant()));
   installment.confirm(transaction); loan.applyPrincipalPayment(installment.getPrincipalCents(),clock.instant()); notifications.resolveReference(householdId,"LOAN_INSTALLMENT",installmentId); installments.flush(); return LoanInstallmentResponse.from(installment);
  } catch(DataIntegrityViolationException e) { throw new ResourceConflictException("LOAN_CONFIRMATION_RACE","贷款还款正在由另一请求确认，请重试"); }
 }
 private FinancialAccount account(Loan loan,long h){return accounts.findByIdAndHouseholdIdAndArchivedAtIsNull(loan.getPaymentAccount().getId(),h).orElseThrow(LoanInstallmentConfirmationService::stale);}
 private Category category(Loan loan,long h){return categories.findByIdAndHouseholdId(loan.getPaymentCategory().getId(),h).filter(c->c.getKind()==TransactionKind.EXPENSE).orElseThrow(LoanInstallmentConfirmationService::stale);}
 private FamilyMember member(Loan loan,long h){if(loan.getMember()!=null)return members.findByIdAndHouseholdId(loan.getMember().getId(),h).orElseThrow(LoanInstallmentConfirmationService::stale);return members.findFirstByHouseholdIdAndLinkedUserId(h,loan.getAssignedUser().getId()).orElseThrow(LoanInstallmentConfirmationService::stale);}
 private static ResourceConflictException stale(){return new ResourceConflictException("STALE_REFERENCE","贷款关联的账户、分类或成员已失效");}
}
