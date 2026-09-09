package com.familyfinance.loan;

import com.familyfinance.accounting.*;
import com.familyfinance.category.*;
import com.familyfinance.family.*;
import com.familyfinance.household.*;
import com.familyfinance.ledger.*;
import com.familyfinance.notification.NotificationService;
import com.familyfinance.shared.*;
import com.familyfinance.transaction.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class LoanPayoffService {
 private final LoanRepository loans;private final LoanInstallmentRepository installments;private final LoanPrepaymentRepository prepayments;private final FinancialTransactionRepository transactions;private final FinancialAccountRepository accounts;private final CategoryRepository categories;private final FamilyMemberRepository members;
 private final CurrentMembership current;private final FamilyPermissionService permissions;private final FamilyMutationAuthorization authorization;private final LoanAccountingService accounting;private final CashAccountingService cash;private final AccountingRequests requests;private final LoanPlanToken plans;private final LoanTotalsService totals;private final JdbcTemplate jdbc;private final Clock clock;private final NotificationService notifications;
 public LoanPayoffService(LoanRepository loans,LoanInstallmentRepository installments,LoanPrepaymentRepository prepayments,FinancialTransactionRepository transactions,FinancialAccountRepository accounts,CategoryRepository categories,FamilyMemberRepository members,CurrentMembership current,FamilyPermissionService permissions,FamilyMutationAuthorization authorization,LoanAccountingService accounting,CashAccountingService cash,AccountingRequests requests,LoanPlanToken plans,LoanTotalsService totals,JdbcTemplate jdbc,Clock clock,NotificationService notifications){this.loans=loans;this.installments=installments;this.prepayments=prepayments;this.transactions=transactions;this.accounts=accounts;this.categories=categories;this.members=members;this.current=current;this.permissions=permissions;this.authorization=authorization;this.accounting=accounting;this.cash=cash;this.requests=requests;this.plans=plans;this.totals=totals;this.jdbc=jdbc;this.clock=clock;this.notifications=notifications;}

 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public LoanPayoffQuote quote(Authentication auth,long id,LocalDate paidOn,Long accountId,String actualInterest){
  var context=current.require(auth);permissions.requireAdmin(context);long h=context.householdId();
  Loan loan=loans.findByIdAndHouseholdId(id,h).orElseThrow(()->new ResourceNotFoundException("贷款不存在"));
  LocalDate day=day(paidOn);validateLoan(loan,day);
  // All quote reads use the same non-locking snapshot, including account and source readiness.
  if(jdbc.queryForObject("select count(*) from ledger_sources where household_id=? and source_type=? and source_id=? and current_journal_id is not null",Long.class,h,LoanAccountingService.source(loan),id)==0)throw new ResourceConflictException("ACCOUNTING_NOT_INITIALIZED","贷款尚未确认账务期初");
  long selected=accountId==null?loan.getPaymentAccount().getId():accountId;
  FinancialAccount account=accounts.findByIdAndHouseholdId(selected,h).orElseThrow(()->new RequestValidationException(Map.of("paymentAccountId","付款账户必须属于当前家庭")));
  if(account.isArchived())throw new ResourceConflictException("ACCOUNT_ARCHIVED","账户已归档");
  if(!account.isOpeningConfirmed()||account.getOpeningOn()==null)throw new ResourceConflictException("ACCOUNTING_NOT_INITIALIZED","账户尚未确认期初余额和日期");
  if(day.isBefore(account.getOpeningOn()))throw new ResourceConflictException("ACCOUNT_ACTIVITY_BEFORE_OPENING","实际还款日期不能早于账户开账日期");
  long balance=jdbc.query("select balance_cents from ledger_accounts where household_id=? and account_code=?",(r,n)->r.getLong(1),h,"CASH:"+selected).stream().findFirst().orElse(0L);
  return calculate(loan,plans.pending(h,id,false),day,selected,actualInterest,balance);
 }

 @Transactional
 public LoanPrepaymentResponse payoff(Authentication auth,long id,LoanPayoffRequest request){
  var access=authorization.requireAdmin(auth);long h=access.context().householdId();
  if(request==null||request.idempotencyKey()==null)throw new RequestValidationException(Map.of("idempotencyKey","请提供本次请求键"));
  String key=AccountingRequests.key(request.idempotencyKey()),digest=requests.digest("LOAN_PAYOFF:"+id,access.context().userId(),request);
  Long replay=requests.replay(h,key,digest);
  Loan loan=loans.findLockedByIdAndHouseholdId(id,h).orElseThrow(()->new ResourceNotFoundException("贷款不存在"));
  if(replay!=null){var original=prepayments.findLockedByIdAndHouseholdId(replay,h).orElseThrow(()->new ResourceNotFoundException("结清记录不存在"));transactions.findLockedByIdAndHouseholdId(original.getTransaction().getId(),h).orElseThrow(()->new ResourceNotFoundException("结清交易不存在"));return LoanPrepaymentResponse.from(original,loan,totals.read(loan,true));}
  LocalDate day=day(request.paidOn());validateLoan(loan,day);accounting.requirePaymentDate(loan,day);
  var periods=plans.pending(h,id,true);
  long selected=request.paymentAccountId()==null?loan.getPaymentAccount().getId():request.paymentAccountId();
  var account=accounts.findLockedByIdAndHouseholdId(selected,h).orElseThrow(()->new RequestValidationException(Map.of("paymentAccountId","付款账户必须属于当前家庭")));
  var quote=calculate(loan,periods,day,selected,request.interestAmount(),0);
  plans.requireMatch(quote.planToken(),request.planToken());
  var result=payoffPrepared(access,loan,new SalePrepared(quote,account),key,LoanSettlementFunding.cash());
  requests.record(h,key,digest,result.id());return result;
 }
 public record SalePrepared(LoanPayoffQuote quote,FinancialAccount account){}
 /** Uses the caller's snapshot and never starts a separate preview transaction. */
 @Transactional(propagation=Propagation.MANDATORY)
 public SalePrepared prepareForSale(MembershipContext context,Loan loan,LocalDate paidOn,long selected,String raw,
         boolean locked,java.math.BigDecimal projectedBalance){
  permissions.requireAdmin(context);long h=context.householdId();
  if(loan.getHousehold().getId()!=h)throw new ResourceNotFoundException("贷款不存在");
  LocalDate day=day(paidOn);validateLoan(loan,day);
  if(locked)accounting.requirePaymentDate(loan,day);
  else{
   if(jdbc.queryForObject("select count(*) from ledger_sources where household_id=? and source_type=? and source_id=? and current_journal_id is not null",Long.class,h,LoanAccountingService.source(loan),loan.getId())==0)
    throw new ResourceConflictException("ACCOUNTING_NOT_INITIALIZED","贷款尚未确认账务期初");
   var amounts=jdbc.queryForList("select balance_amount from ledger_accounts where household_id=? and account_code=?",java.math.BigDecimal.class,h,"LOAN:"+loan.getId());
   if(amounts.size()!=1||amounts.get(0).compareTo(loan.getCurrentPrincipalAmount())!=0)
    throw new ResourceConflictException("ACCOUNTING_BALANCE_MISMATCH","贷款本金与账务不一致");
  }
  var periods=plans.pending(h,loan.getId(),locked);
  if(periods.stream().anyMatch(p->!p.dueOn().isAfter(day))){
   Long assignee=loan.getAssignedUser()==null?null:loan.getAssignedUser().getId();
   if(assignee==null)throw new ResourceConflictException("INSTALLMENT_UNASSIGNED","贷款尚未分配确认人");
   permissions.requireCanConfirmAssignedOccurrence(context,assignee);
   var states=jdbc.queryForList("select status from app_users where household_id=? and id=?"+(locked?" for update":""),String.class,h,assignee);
   if(states.isEmpty()||!AppUserStatus.ACTIVE.name().equals(states.get(0)))throw stale();
  }
  var account=(locked?accounts.findLockedByIdAndHouseholdId(selected,h):accounts.findByIdAndHouseholdId(selected,h))
      .orElseThrow(()->new RequestValidationException(Map.of("repaymentAccountId","付款账户必须属于当前家庭")));
  if(account.isArchived())throw new ResourceConflictException("ACCOUNT_ARCHIVED","付款账户已归档");
  if(!account.isOpeningConfirmed()||account.getOpeningOn()==null)throw new ResourceConflictException("ACCOUNTING_NOT_INITIALIZED","付款账户尚未确认期初");
  if(day.isBefore(account.getOpeningOn()))throw new ResourceConflictException("ACCOUNT_ACTIVITY_BEFORE_OPENING","日期不能早于付款账户开账日期");
  if(locked)cash.requireConfirmed(account,day);
  long memberId=requireSaleDimensions(loan,locked);
  var q=calculate(loan,periods,day,selected,raw,DecimalMoney.toCents(projectedBalance));
  String token=requests.digest("ASSET_SALE_PAYOFF_PLAN_V1",context.userId(),List.of(q,memberId));
  return new SalePrepared(new LoanPayoffQuote(q.principalAmount(),q.dueInterestAmount(),q.interestAmount(),q.futureScheduledInterest(),
      q.cashAmount(),q.paymentAccountId(),q.availableBalance(),q.paidOn(),token),account);
 }
 /** Match the exact accounting member/category used by execution, including the assigned-user fallback. */
 private long requireSaleDimensions(Loan loan,boolean locked){
  long h=loan.getHousehold().getId();String lock=locked?" for update":"";
  var memberIds=loan.getMember()!=null
      ?jdbc.queryForList("select id from family_members where household_id=? and id=?"+lock,Long.class,h,loan.getMember().getId())
      :loan.getAssignedUser()==null?List.<Long>of()
      :jdbc.queryForList("select id from family_members where household_id=? and linked_user_id=? order by id"+lock,Long.class,h,loan.getAssignedUser().getId());
  if(memberIds.isEmpty())throw stale();
  var kinds=jdbc.queryForList("select kind from categories where household_id=? and id=?"+lock,String.class,h,loan.getPaymentCategory().getId());
  if(kinds.size()!=1||!TransactionKind.EXPENSE.name().equals(kinds.get(0)))throw stale();
  return memberIds.get(0);
 }
 @Transactional(propagation=Propagation.MANDATORY)
 public LoanPrepaymentResponse payoffPrepared(FamilyMutationAuthorization.LockedFamilyAccess access,Loan loan,
         SalePrepared prepared,String key,LoanSettlementFunding funding){
  long h=access.context().householdId(),id=loan.getId();var quote=prepared.quote();var account=prepared.account();var day=quote.paidOn();
  if(loan.getHousehold().getId()!=h||account.getHousehold().getId()!=h)throw new IllegalArgumentException("invalid payoff context");
  long principal=loan.getCurrentPrincipalCents(),interest=interestCents(quote.interestAmount());
  var event=new LoanPrepayment(loan,key,principal,day,clock.instant());event.payoff(interest);prepayments.saveAndFlush(event);
  var member=loan.getMember()!=null?members.findByIdAndHouseholdId(loan.getMember().getId(),h):loan.getAssignedUser()!=null?members.findFirstByHouseholdIdAndLinkedUserId(h,loan.getAssignedUser().getId()):Optional.<FamilyMember>empty();
  var category=categories.findByIdAndHouseholdId(loan.getPaymentCategory().getId(),h).filter(c->c.getKind()==TransactionKind.EXPENSE).orElseThrow(LoanPayoffService::stale);
  var transaction=FinancialTransaction.loanPrepayment(access.household(),account,access.membership().getUser(),member.orElseThrow(LoanPayoffService::stale),category,Math.addExact(principal,interest),day,event.getId(),clock.instant());
  transaction.loanSplit(principal,interest);funding.mark(transaction);transactions.saveAndFlush(transaction);accounting.pay(loan,transaction,principal,interest,key);event.attach(transaction);
  loan.applyPrincipalPayment(principal,clock.instant());accounting.requireBalance(loan);
  for(var row:installments.findAllLockedByLoanIdAndHouseholdIdOrderByInstallmentNo(id,h)){if(row.getStatus()==LoanInstallmentStatus.PENDING){row.cancel(event.getId());notifications.resolveReference(h,"LOAN_INSTALLMENT",row.getId());}}
  loans.flush();return LoanPrepaymentResponse.from(event,loan,totals.read(loan,true));
 }
 private LoanPayoffQuote calculate(Loan loan,List<LoanPlanToken.Period> periods,LocalDate day,long account,String raw,long balance){
  long due=plans.dueInterest(periods,day),future=0;for(var p:periods)if(p.dueOn().isAfter(day))future=Math.addExact(future,p.interestCents());
  long interest=due;if(raw!=null){try{interest=interestCents(raw);if(interest<due)throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw new RequestValidationException(Map.of("interestAmount","本次实际利息不能小于到期未付利息，且最多两位小数"));}}
  return new LoanPayoffQuote(Money.formatCents(loan.getCurrentPrincipalCents()),Money.formatCents(due),Money.formatCents(interest),Money.formatCents(future),Money.formatCents(Math.addExact(loan.getCurrentPrincipalCents(),interest)),account,Money.formatCents(balance),day,plans.token(loan,periods,"PAYOFF",day,account,interest,""));
 }
 private static long interestCents(String raw){return raw!=null&&raw.trim().matches("0+(\\.0{1,2})?")?0:Money.parseCents(raw);}
 private LocalDate day(LocalDate day){return cash.date(day==null?null:day.toString(),"paidOn");}
 private void validateLoan(Loan loan,LocalDate day){
  if(loan.getStatus()!=LoanStatus.ACTIVE)throw new ResourceConflictException("LOAN_CLOSED","贷款已归档或结清");
  if(loan.getFundingMode()==null||loan.getAccountingOn()==null)throw new ResourceConflictException("ACCOUNTING_NOT_INITIALIZED","贷款尚未确认账务期初");
  if(day.isBefore(loan.getAccountingOn()))throw new ResourceConflictException("LOAN_PAYMENT_BEFORE_OPENING","还款日期不能早于贷款账务起始日期");
  if(loan.getLastPaymentOn()!=null&&day.isBefore(loan.getLastPaymentOn()))throw new ResourceConflictException("LOAN_PAYMENT_CHRONOLOGY","还款日期不能早于已入账的最近还款日期");
 }
 private static ResourceConflictException stale(){return new ResourceConflictException("STALE_REFERENCE","贷款关联的分类或成员已失效");}
}
