package com.familyfinance.loan;

import com.familyfinance.category.*;
import com.familyfinance.family.*;
import com.familyfinance.household.*;
import com.familyfinance.ledger.*;
import com.familyfinance.shared.*;
import com.familyfinance.transaction.*;
import java.time.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanPrepaymentService {
 private final LoanRepository loans; private final LoanPrepaymentRepository prepayments; private final FinancialTransactionRepository transactions; private final FinancialAccountRepository accounts; private final CategoryRepository categories; private final FamilyMemberRepository members; private final FamilyMutationAuthorization authorization; private final Clock clock; private final AmortizationCalculator calculator=new AmortizationCalculator();
 LoanPrepaymentService(LoanRepository loans,LoanPrepaymentRepository prepayments,FinancialTransactionRepository transactions,FinancialAccountRepository accounts,CategoryRepository categories,FamilyMemberRepository members,FamilyMutationAuthorization authorization,Clock clock){this.loans=loans;this.prepayments=prepayments;this.transactions=transactions;this.accounts=accounts;this.categories=categories;this.members=members;this.authorization=authorization;this.clock=clock;}
 @Transactional public LoanPrepaymentResponse prepay(Authentication authentication,long loanId,LoanPrepaymentRequest request){
  var access=authorization.requireAdmin(authentication); if(request==null||request.idempotencyKey()==null||request.idempotencyKey().trim().isEmpty()||request.idempotencyKey().length()>100)throw new RequestValidationException(Map.of("idempotencyKey","幂等键不能为空且不超过100个字符"));
  Loan loan=loans.findLockedByIdAndHouseholdId(loanId,access.context().householdId()).orElseThrow(()->new ResourceNotFoundException("贷款不存在")); String key=request.idempotencyKey().trim();
  LoanPrepayment existing=prepayments.findByHouseholdIdAndLoanIdAndRequestKey(access.context().householdId(),loanId,key).orElse(null); if(existing!=null)return LoanPrepaymentResponse.from(existing,loan);
  if(loan.getStatus()!=LoanStatus.ACTIVE)throw new ResourceConflictException("LOAN_CLOSED","贷款已归档或结清"); long amount=parse(request.amount()); if(amount>loan.getCurrentPrincipalCents())throw new RequestValidationException(Map.of("amount","提前还款金额不能超过剩余本金")); LocalDate paidOn=request.paidOn();if(paidOn==null||paidOn.isAfter(LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")))))throw new RequestValidationException(Map.of("paidOn","还款日期不能为空且不能晚于今天"));
  LoanPrepayment prepayment=prepayments.save(new LoanPrepayment(loan,key,amount,paidOn,clock.instant()));
  FinancialTransaction transaction=transactions.saveAndFlush(FinancialTransaction.loanPrepayment(access.household(),account(loan,access.context().householdId()),access.membership().getUser(),member(loan,access.context().householdId()),category(loan,access.context().householdId()),amount,paidOn,prepayment.getId(),clock.instant()));
  prepayment.attach(transaction);
  loan.applyPrincipalPayment(amount,clock.instant()); loan.cancelPendingInstallments(); if(loan.getCurrentPrincipalCents()>0)regenerate(loan,paidOn); loans.flush(); return LoanPrepaymentResponse.from(prepayment,loan);
 }
 private void regenerate(Loan loan,LocalDate paidOn){int terms=(int)loan.getInstallments().stream().filter(i->i.getStatus()==LoanInstallmentStatus.CANCELLED).count(); List<InstallmentDraft> base=calculator.calculate(loan.getCurrentPrincipalCents(),loan.getAnnualRate(),Math.max(1,terms),paidOn,loan.getRepaymentMethod()==RepaymentMethod.CUSTOM?RepaymentMethod.EQUAL_PRINCIPAL:loan.getRepaymentMethod());int no=loan.nextInstallmentNo();loan.appendSchedule(base.stream().map(d->new InstallmentDraft(no+d.installmentNo()-1,d.dueOn(),d.principalCents(),d.interestCents(),d.remainingPrincipalCents())).toList());}
 private static long parse(String amount){try{long value=Money.parseCents(amount);if(value<=0)throw new IllegalArgumentException();return value;}catch(IllegalArgumentException e){throw new RequestValidationException(Map.of("amount","提前还款金额必须为正且最多两位小数"));}}
 private FinancialAccount account(Loan l,long h){return accounts.findByIdAndHouseholdIdAndArchivedAtIsNull(l.getPaymentAccount().getId(),h).orElseThrow(LoanPrepaymentService::stale);} private Category category(Loan l,long h){return categories.findByIdAndHouseholdId(l.getPaymentCategory().getId(),h).filter(x->x.getKind()==TransactionKind.EXPENSE).orElseThrow(LoanPrepaymentService::stale);} private FamilyMember member(Loan l,long h){if(l.getMember()!=null)return members.findByIdAndHouseholdId(l.getMember().getId(),h).orElseThrow(LoanPrepaymentService::stale);if(l.getAssignedUser()!=null)return members.findFirstByHouseholdIdAndLinkedUserId(h,l.getAssignedUser().getId()).orElseThrow(LoanPrepaymentService::stale);throw stale();} private static ResourceConflictException stale(){return new ResourceConflictException("STALE_REFERENCE","贷款关联的账户、分类或成员已失效");}
}
