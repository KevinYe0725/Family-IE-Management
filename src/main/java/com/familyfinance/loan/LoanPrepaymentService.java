package com.familyfinance.loan;

import com.familyfinance.accounting.*;
import com.familyfinance.family.*;
import com.familyfinance.household.*;
import com.familyfinance.ledger.*;
import com.familyfinance.shared.*;
import com.familyfinance.transaction.*;
import java.time.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.familyfinance.notification.NotificationService;

@Service
public class LoanPrepaymentService {
 private final LoanRepository loans; private final LoanPrepaymentRepository prepayments; private final FinancialTransactionRepository transactions; private final FinancialAccountRepository accounts; private final FamilyMutationAuthorization authorization; private final Clock clock; private final LoanPrepaymentPlanner planner=new LoanPrepaymentPlanner();
 private final CurrentMembership current;private final FamilyPermissionService permissions;private final JdbcTemplate jdbc;private final NotificationService notifications;
 private final LoanTotalsService totals; private final LoanPlanToken plans; private final LoanAccountingService accounting; private final AccountingRequests requests; private final LoanInstallmentRepository installments; private final LoanInstallmentSettlement settlement;
 LoanPrepaymentService(LoanRepository loans,LoanPrepaymentRepository prepayments,FinancialTransactionRepository transactions,FinancialAccountRepository accounts,FamilyMutationAuthorization authorization,Clock clock,LoanAccountingService accounting,AccountingRequests requests,LoanInstallmentRepository installments,LoanTotalsService totals,LoanPlanToken plans,CurrentMembership current,FamilyPermissionService permissions,JdbcTemplate jdbc,NotificationService notifications,LoanInstallmentSettlement settlement){this.current=current;this.permissions=permissions;this.jdbc=jdbc;this.notifications=notifications;this.totals=totals;this.plans=plans;this.loans=loans;this.prepayments=prepayments;this.transactions=transactions;this.accounts=accounts;this.authorization=authorization;this.clock=clock;this.accounting=accounting;this.requests=requests;this.installments=installments;this.settlement=settlement;}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public LoanPrepaymentPreview preview(Authentication authentication,long loanId,String raw,LocalDate paidOn,PrepaymentStrategy strategy,Long accountId){
  var context=current.require(authentication);permissions.requireAdmin(context);long h=context.householdId();
  var loan=loans.findByIdAndHouseholdId(loanId,h).orElseThrow(()->new ResourceNotFoundException("贷款不存在"));validate(loan,paidOn);
  if(jdbc.queryForObject("select count(*) from ledger_sources where household_id=? and source_type=? and source_id=? and current_journal_id is not null",Long.class,h,LoanAccountingService.source(loan),loanId)==0)throw new ResourceConflictException("ACCOUNTING_NOT_INITIALIZED","贷款尚未确认账务期初");
  var balance=jdbc.queryForList("select balance_cents from ledger_accounts where household_id=? and account_code=?",Long.class,h,"LOAN:"+loanId);
  if(balance.size()!=1||balance.get(0)!=loan.getCurrentPrincipalCents())throw new ResourceConflictException("ACCOUNTING_BALANCE_MISMATCH","贷款剩余本金与账务余额不一致，请核对账务后再操作");
  long selected=accountId==null?loan.getPaymentAccount().getId():accountId;
  var account=accounts.findByIdAndHouseholdId(selected,h).orElseThrow(()->new RequestValidationException(Map.of("paymentAccountId","付款账户必须属于当前家庭")));
  if(account.isArchived())throw new ResourceConflictException("ACCOUNT_ARCHIVED","账户已归档");
  if(!account.isOpeningConfirmed()||account.getOpeningOn()==null)throw new ResourceConflictException("ACCOUNTING_NOT_INITIALIZED","账户尚未确认期初余额和日期");
  if(paidOn.isBefore(account.getOpeningOn()))throw new ResourceConflictException("ACCOUNT_ACTIVITY_BEFORE_OPENING","实际还款日期不能早于账户开账日期");
  long cash=jdbc.queryForList("select balance_cents from ledger_accounts where household_id=? and account_code=?",Long.class,h,"CASH:"+selected).stream().findFirst().orElse(0L);
  long amount=parse(raw);if(amount>loan.getCurrentPrincipalCents())throw new RequestValidationException(Map.of("amount","提前还款金额不能超过剩余本金"));if(amount==loan.getCurrentPrincipalCents())throw new ResourceConflictException("LOAN_PAYOFF_REQUIRED","全部偿还请使用一次结清核对实际本金与利息扣款");
  var effective=strategy==null?PrepaymentStrategy.REDUCE_PAYMENT:strategy;var periods=plans.pending(h,loanId,false);var before=drafts(loan,periods);var after=planner.plan(before,DecimalMoney.fromCents(amount),loan.getAnnualRate(),loan.getRepaymentMethod(),paidOn,effective,null,plans.roundingContext(h,loanId,false),loan.getMinimumInstallmentAmount());
  return new LoanPrepaymentPreview(effective,Money.formatCents(amount),Money.formatCents(amount),selected,Money.formatCents(cash),paidOn,token(loan,periods,amount,paidOn,selected,effective,false),LoanPrepaymentPreview.summarize(before),LoanPrepaymentPreview.summarize(after));
 }
 @Transactional public LoanPrepaymentResponse prepay(Authentication authentication,long loanId,LoanPrepaymentRequest request){
  var access=authorization.requireAdmin(authentication); if(request==null||request.idempotencyKey()==null||request.idempotencyKey().trim().isEmpty()||request.idempotencyKey().length()>100)throw new RequestValidationException(Map.of("idempotencyKey","幂等键不能为空且不超过100个字符"));
  String key=AccountingRequests.key(request.idempotencyKey());long h=access.context().householdId();String digest=requests.digest("LOAN_PREPAYMENT:"+loanId,access.context().userId(),request);
  Long replay=requests.replay(h,key,digest,LoanRequestHistory.prepayment(requests,"LOAN_PREPAYMENT:"+loanId,access.context().userId(),request));
  Loan loan=loans.findLockedByIdAndHouseholdId(loanId,h).orElseThrow(()->new ResourceNotFoundException("贷款不存在"));
  if(replay!=null){
   LoanPrepayment original=prepayments.findLockedByIdAndHouseholdId(replay,h).orElseThrow(()->new ResourceNotFoundException("提前还款记录不存在"));
   transactions.findLockedByIdAndHouseholdId(original.getTransaction().getId(),h).orElseThrow(()->new ResourceNotFoundException("原提前还款交易不存在"));
   return LoanPrepaymentResponse.from(original,loan,totals.read(loan,true));
  }
  validate(loan,request.paidOn());long amount=parse(request.amount()); if(amount>loan.getCurrentPrincipalCents())throw new RequestValidationException(Map.of("amount","提前还款金额不能超过剩余本金")); LocalDate paidOn=request.paidOn();
  accounting.requirePaymentDate(loan,paidOn);
  var periods=plans.pending(h,loanId,true);var selectedAccount=account(loan,h,request.paymentAccountId());var strategy=request.strategy()==null?PrepaymentStrategy.REDUCE_PAYMENT:request.strategy();boolean full=amount==loan.getCurrentPrincipalCents();
  if(full&&(request.strategy()!=null||request.planToken()!=null||plans.dueInterest(periods,paidOn)>0))throw new ResourceConflictException("LOAN_PAYOFF_REQUIRED","全部偿还请使用一次结清核对实际本金与利息扣款");
  if(request.strategy()!=null||request.planToken()!=null)plans.requireMatch(token(loan,periods,amount,paidOn,selectedAccount.getId(),strategy,true),request.planToken());
  List<InstallmentDraft> replacement=full?List.of():planner.plan(drafts(loan,periods),DecimalMoney.fromCents(amount),loan.getAnnualRate(),loan.getRepaymentMethod(),paidOn,strategy,null,plans.roundingContext(h,loanId,true),loan.getMinimumInstallmentAmount());
  LoanPrepayment prepayment=prepayAuthorized(access,loan,selectedAccount,amount,paidOn,full?null:strategy,replacement,key);
  requests.record(h,key,digest,prepayment.getId()); return LoanPrepaymentResponse.from(prepayment,loan,totals.read(loan,true));
 }
 /** Internal principal-only event. The caller owns authorization, the locked loan and the whole receipt. */
 @Transactional(propagation=Propagation.MANDATORY)
 public LoanPrepayment prepayAuthorized(FamilyMutationAuthorization.LockedFamilyAccess access,Loan loan,FinancialAccount selectedAccount,
         long amount,LocalDate paidOn,PrepaymentStrategy strategy,List<InstallmentDraft> replacement,String key){
  return prepayAuthorized(access,loan,selectedAccount,amount,paidOn,strategy,replacement,key,LoanSettlementFunding.cash());
 }
 @Transactional(propagation=Propagation.MANDATORY)
 public LoanPrepayment prepayAuthorized(FamilyMutationAuthorization.LockedFamilyAccess access,Loan loan,FinancialAccount selectedAccount,
         long amount,LocalDate paidOn,PrepaymentStrategy strategy,List<InstallmentDraft> replacement,String key,LoanSettlementFunding funding){
  long h=access.context().householdId();long loanId=loan.getId();
  if(loan.getHousehold().getId()!=h||selectedAccount.getHousehold().getId()!=h||amount<=0)throw new IllegalArgumentException("invalid authorized prepayment");
  LoanPrepayment prepayment=new LoanPrepayment(loan,key,amount,paidOn,clock.instant());prepayment.strategy(strategy);prepayments.saveAndFlush(prepayment);
  FinancialTransaction transaction=FinancialTransaction.loanPrepayment(access.household(),selectedAccount,access.membership().getUser(),settlement.member(loan,true),settlement.category(loan),amount,paidOn,prepayment.getId(),clock.instant());
  transaction.loanSplit(amount,0);funding.mark(transaction);transactions.saveAndFlush(transaction);
  accounting.pay(loan,transaction,DecimalMoney.fromCents(amount),DecimalMoney.fromCents(0),key);
  prepayment.attach(transaction);
  var currentSchedule=installments.findAllLockedByLoanIdAndHouseholdIdOrderByInstallmentNo(loanId,h);
  int nextNo=currentSchedule.stream().mapToInt(LoanInstallment::getInstallmentNo).max().orElse(0)+1;
  loan.applyPrincipalPayment(amount,clock.instant());accounting.requireBalance(loan);
  for(var row:currentSchedule)if(row.getStatus()==LoanInstallmentStatus.PENDING){row.cancel(prepayment.getId());notifications.resolveReference(h,"LOAN_INSTALLMENT",row.getId());}
  installments.saveAll(replacement.stream().map(d->new LoanInstallment(loan,d.renumber(nextNo+d.installmentNo()-1))).toList());
  loans.flush();return prepayment;
 }
 private String token(Loan loan,List<LoanPlanToken.Period> periods,long amount,LocalDate day,long account,PrepaymentStrategy strategy,boolean current){return plans.token(loan,periods,"PARTIAL_PREPAYMENT",day,account,0,"principalCents="+amount+";strategy="+strategy.name()+";rounding="+plans.roundingContext(loan.getHousehold().getId(),loan.getId(),current));}
 private List<InstallmentDraft> drafts(Loan loan,List<LoanPlanToken.Period> periods){long remaining=loan.getCurrentPrincipalCents();List<InstallmentDraft> rows=new ArrayList<>();for(var p:periods){remaining=Math.subtractExact(remaining,p.principalCents());rows.add(p.draft(remaining));}if(remaining!=0)throw new ResourceConflictException("LOAN_PLAN_INVALID","待还计划本金与剩余本金不一致，请核对计划");return rows;}
 private void validate(Loan loan,LocalDate day){
  if(loan.getStatus()!=LoanStatus.ACTIVE)throw new ResourceConflictException("LOAN_CLOSED","贷款已归档或结清");
  if(day==null||day.isAfter(LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")))))throw new RequestValidationException(Map.of("paidOn","还款日期不能为空且不能晚于今天"));
  if(loan.getFundingMode()==null||loan.getAccountingOn()==null)throw new ResourceConflictException("ACCOUNTING_NOT_INITIALIZED","贷款尚未确认账务期初");
  if(day.isBefore(loan.getAccountingOn()))throw new ResourceConflictException("LOAN_PAYMENT_BEFORE_OPENING","还款日期不能早于贷款账务起始日期");
  if(loan.getLastPaymentOn()!=null&&day.isBefore(loan.getLastPaymentOn()))throw new ResourceConflictException("LOAN_PAYMENT_CHRONOLOGY","还款日期不能早于已入账的最近还款日期");
 }
 private static long parse(String amount){try{long value=Money.parseCents(amount);if(value<=0)throw new IllegalArgumentException();return value;}catch(IllegalArgumentException e){throw new RequestValidationException(Map.of("amount","提前还款金额必须为正且最多两位小数"));}}
 private FinancialAccount account(Loan l,long h,Long actual){return accounts.findLockedByIdAndHouseholdId(actual==null?l.getPaymentAccount().getId():actual,h).orElseThrow(LoanPrepaymentService::stale);} private static ResourceConflictException stale(){return new ResourceConflictException("STALE_REFERENCE","贷款关联的账户、分类或成员已失效");}
}
