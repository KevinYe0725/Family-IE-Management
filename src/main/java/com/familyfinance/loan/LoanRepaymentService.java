package com.familyfinance.loan;

import com.familyfinance.accounting.*;
import com.familyfinance.family.*;
import com.familyfinance.household.AppUserStatus;
import com.familyfinance.ledger.*;
import com.familyfinance.shared.*;
import com.familyfinance.transaction.FinancialTransaction;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class LoanRepaymentService {
    private final LoanRepository loans;private final LoanInstallmentRepository installments;
    private final FinancialAccountRepository accounts;private final CurrentMembership current;
    private final FamilyMutationAuthorization authorization;private final FamilyPermissionService permissions;
    private final LoanAccountingService accounting;private final CashAccountingService cash;
    private final LedgerPostingService posting;private final AccountingRequests requests;
    private final LoanPlanToken plans;private final LoanRepaymentPolicyService policies;
    private final LoanInstallmentSettlement settlement;private final LoanPrepaymentService prepayments;
    private final LoanRepaymentBatchRepository batches;private final JdbcTemplate jdbc;private final Clock clock;
    private final LoanPlanningBudgetFactory planningBudgets;
    private final LoanPrepaymentPlanner planner=new LoanPrepaymentPlanner();
    public LoanRepaymentService(LoanRepository loans,LoanInstallmentRepository installments,FinancialAccountRepository accounts,
            CurrentMembership current,FamilyMutationAuthorization authorization,FamilyPermissionService permissions,
            LoanAccountingService accounting,CashAccountingService cash,LedgerPostingService posting,AccountingRequests requests,
            LoanPlanToken plans,LoanRepaymentPolicyService policies,LoanInstallmentSettlement settlement,
            LoanPrepaymentService prepayments,LoanRepaymentBatchRepository batches,JdbcTemplate jdbc,Clock clock,LoanPlanningBudgetFactory planningBudgets){
        this.loans=loans;this.installments=installments;this.accounts=accounts;this.current=current;this.authorization=authorization;
        this.permissions=permissions;this.accounting=accounting;this.cash=cash;this.posting=posting;this.requests=requests;
        this.plans=plans;this.policies=policies;this.settlement=settlement;this.prepayments=prepayments;this.batches=batches;this.jdbc=jdbc;this.clock=clock;this.planningBudgets=planningBudgets;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,propagation=Propagation.REQUIRES_NEW)
    public LoanRepaymentPreview preview(Authentication a,long id,String extra,LocalDate day,Long accountId,
            PrepaymentStrategy strategy,Integer targetPeriods){
        var context=current.require(a);permissions.requireAdmin(context);
        var loan=loans.findByIdAndHouseholdId(id,context.householdId()).orElseThrow(()->new ResourceNotFoundException("贷款不存在"));
        return prepare(context,loan,extra,day,accountId,strategy,targetPeriods,false).preview();
    }
    @Transactional
    public LoanRepaymentResponse repay(Authentication a,long id,LoanRepaymentRequest request){
        var access=authorization.requireAdmin(a);long h=access.context().householdId();
        if(request==null||request.idempotencyKey()==null)throw new RequestValidationException(Map.of("idempotencyKey","请提供本次完整请求的幂等键"));
        String key=AccountingRequests.key(request.idempotencyKey());
        String digest=requests.digest("LOAN_REPAYMENT:"+id,access.context().userId(),request);
        Long replay=requests.replay(h,key,digest);
        // The immutable full-request receipt precedes current closed/plan/account/assignee checks.
        if(replay!=null)return LoanRepaymentResponse.from(batches.get(h,id,replay,true));
        var loan=loans.findLockedByIdAndHouseholdId(id,h).orElseThrow(()->new ResourceNotFoundException("贷款不存在"));
        var prepared=prepare(access.context(),loan,request.additionalPrincipal(),request.paidOn(),request.paymentAccountId(),request.strategy(),request.targetPeriods(),true);
        var q=prepared.preview();plans.requireMatch(q.planToken(),request.planToken());
        var response=repayPrepared(access,loan,prepared,key,LoanSettlementFunding.cash());
        requests.record(h,key,digest,response.batchId());return response;
    }
    /** Joined snapshot preparation for an outer sale; projected cash is supplied only by trusted orchestration. */
    @Transactional(propagation=Propagation.MANDATORY)
    public Prepared prepareForSale(MembershipContext context,Loan loan,String extra,LocalDate day,long accountId,
            PrepaymentStrategy strategy,Integer target,boolean locked,BigDecimal projectedBalance,LoanSettlementFunding funding){
        permissions.requireAdmin(context);
        if(loan.getHousehold().getId()!=context.householdId())throw new ResourceNotFoundException("贷款不存在");
        return prepare(context,loan,extra,day,accountId,strategy,target,locked,projectedBalance,funding);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public LoanRepaymentResponse repayPrepared(FamilyMutationAuthorization.LockedFamilyAccess access,Loan loan,
            Prepared prepared,String key,LoanSettlementFunding funding){
        long h=access.context().householdId();var q=prepared.preview();
        if(loan.getHousehold().getId()!=h||!Objects.equals(q.settlementAssetId(),funding.settlementAssetId()))
            throw new IllegalArgumentException("invalid prepared repayment");
        // The same current daily-balance engine used by posting verifies the entire debit before any child exists.
        if(funding.cashImpact())posting.requireAvailableCash(h,prepared.account().getId(),q.paidOn(),new BigDecimal(q.totalCashAmount()));
        var children=new ArrayList<LoanRepaymentBatch.Child>();
        for(var due:prepared.projection().due()){
            var row=installments.findLockedByIdAndHouseholdId(due.id(),h).orElseThrow(()->new ResourceConflictException("LOAN_PLAN_CHANGED","期次已变化"));
            var tx=settlement.settleAuthorized(access,loan,row,prepared.account(),q.paidOn(),childKey(key,"due:"+due.id()),funding);
            children.add(child(tx));
        }
        var event=prepayments.prepayAuthorized(access,loan,prepared.account(),DecimalMoney.toCents(new BigDecimal(q.additionalPrincipal())),
                q.paidOn(),q.strategy(),prepared.replacement(),childKey(key,"extra"),funding);
        children.add(child(event.getTransaction()));
        var batch=batches.append(h,access.context().userId(),key,loan,q,children,clock.instant());
        event.attachRepaymentBatch(batch.id());loans.flush();
        return LoanRepaymentResponse.from(batch);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<LoanRepaymentResponse> history(Authentication a,long id){
        long h=current.require(a).householdId();
        loans.findByIdAndHouseholdId(id,h).orElseThrow(()->new ResourceNotFoundException("贷款不存在"));
        return batches.history(h,id).stream().map(LoanRepaymentResponse::from).toList();
    }
    public record Prepared(LoanRepaymentPreview preview,LoanRepaymentPolicyService.Projection projection,
            FinancialAccount account,List<InstallmentDraft> replacement){}
    private Prepared prepare(MembershipContext context,Loan loan,String raw,LocalDate day,Long accountId,
            PrepaymentStrategy requestedStrategy,Integer target,boolean locked){
        return prepare(context,loan,raw,day,accountId,requestedStrategy,target,locked,null,LoanSettlementFunding.cash());
    }
    private Prepared prepare(MembershipContext context,Loan loan,String raw,LocalDate day,Long accountId,
            PrepaymentStrategy requestedStrategy,Integer target,boolean locked,BigDecimal projectedBalance,LoanSettlementFunding funding){
        long h=context.householdId();validateLoan(loan,day,locked);
        BigDecimal additional=parse(raw);var periods=plans.pending(h,loan.getId(),locked);
        BigDecimal duePrincipal=periods.stream().filter(p->!p.dueOn().isAfter(day)).map(LoanPlanToken.Period::principalAmount).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal maximum=loan.getCurrentPrincipalAmount().subtract(duePrincipal);
        if(additional.compareTo(maximum)>0)throw new RequestValidationException(Map.of("additionalPrincipal","额外本金不能超过到期款结算后的剩余本金 ¥"+DecimalMoney.format(maximum)));
        var projection=policies.project(loan,periods,day,additional,plans.roundingContext(h,loan.getId(),locked));
        if(!projection.due().isEmpty()){
            Long assignee=loan.getAssignedUser()==null?null:loan.getAssignedUser().getId();
            if(assignee==null)throw new ResourceConflictException("INSTALLMENT_UNASSIGNED","贷款尚未分配确认人");
            permissions.requireCanConfirmAssignedOccurrence(context,assignee);
            var states=jdbc.queryForList("select status from app_users where household_id=? and id=?"+(locked?" for update":""),String.class,h,assignee);
            if(states.isEmpty()||!AppUserStatus.ACTIVE.name().equals(states.get(0)))throw new ResourceConflictException("STALE_REFERENCE","指定确认人已失效");
        }
        long selected=accountId==null?loan.getPaymentAccount().getId():accountId;
        var account=(locked?accounts.findLockedByIdAndHouseholdId(selected,h):accounts.findByIdAndHouseholdId(selected,h))
                .orElseThrow(()->new RequestValidationException(Map.of("paymentAccountId","付款账户必须属于当前家庭")));
        if(locked)cash.requireConfirmed(account,day);else{
            if(account.isArchived())throw new ResourceConflictException("ACCOUNT_ARCHIVED","付款账户已归档");
            if(!account.isOpeningConfirmed()||account.getOpeningOn()==null)throw new ResourceConflictException("ACCOUNTING_NOT_INITIALIZED","付款账户尚未确认期初");
            if(day.isBefore(account.getOpeningOn()))throw new ResourceConflictException("ACCOUNT_ACTIVITY_BEFORE_OPENING","日期不能早于付款账户开账日期");
        }
        BigDecimal balance=jdbc.queryForList("select balance_amount from ledger_accounts where household_id=? and account_code=?"+(locked?" for update":""),BigDecimal.class,h,"CASH:"+selected).stream().findFirst().orElse(BigDecimal.ZERO);
        if(projectedBalance!=null)balance=projectedBalance;
        var strategy=requestedStrategy==null?PrepaymentStrategy.REDUCE_PAYMENT:requestedStrategy;
        if(strategy!=PrepaymentStrategy.ADJUST_TERM&&target!=null)throw new RequestValidationException(Map.of("targetPeriods","只有指定期数策略可设置目标期数"));
        if(strategy==PrepaymentStrategy.ADJUST_TERM&&(target==null||target<1||target>=projection.future().size()))
            throw new RequestValidationException(Map.of("targetPeriods","目标期数必须为正且少于原未来期数；保留期数请使用降低月供"));
        var before=futureDrafts(projection);var budget=planningBudgets.create();
        var after=projection.remainingPrincipal().signum()==0?List.<InstallmentDraft>of():planner.planRemaining(before,projection.remainingPrincipal(),
                loan.getAnnualRate(),loan.getRepaymentMethod(),strategy,target,projection.roundingContext(),loan.getMinimumInstallmentAmount(),budget);
        var options=projection.remainingPrincipal().signum()==0?List.<LoanTermOptions.Option>of():loan.getRepaymentMethod()==RepaymentMethod.CUSTOM
                ?new LoanTermOptions().evaluateCustom(before,projection.remainingPrincipal(),projection.roundingContext(),loan.getMinimumInstallmentAmount(),budget)
                :new LoanTermOptions().evaluate(projection.remainingPrincipal(),loan.getAnnualRate(),projection.future().stream().map(LoanPlanToken.Period::dueOn).toList(),loan.getRepaymentMethod(),projection.roundingContext(),loan.getMinimumInstallmentAmount());
        BigDecimal principal=projection.duePrincipal().add(additional),interest=projection.dueInterest(),total=DecimalMoney.settled(principal.add(interest));
        long effectiveMember=settlement.memberId(loan,locked);settlement.category(loan);
        String token=plans.token(loan,periods,"COMBINED_REPAYMENT_V1",day,selected,DecimalMoney.toCents(interest),
                "additional="+DecimalMoney.format(additional)+";strategy="+strategy+";target="+target+";context="+projection.roundingContext()
                +";member="+effectiveMember+";assignee="+(loan.getAssignedUser()==null?null:loan.getAssignedUser().getId())
                +";cashBalance="+DecimalMoney.format(balance)+";accountOpening="+account.getOpeningOn()
                +(funding.cashImpact()?"":";assetSettlement="+funding.settlementAssetId()));
        var dues=projection.due().stream().map(p->new LoanRepaymentPreview.DueInstallment(p.id(),p.installmentNo(),p.dueOn(),
                DecimalMoney.format(p.principalAmount()),DecimalMoney.format(p.interestAmount()),DecimalMoney.format(p.principalAmount().add(p.interestAmount())))).toList();
        var preview=new LoanRepaymentPreview(dues,DecimalMoney.format(projection.duePrincipal()),DecimalMoney.format(interest),DecimalMoney.format(additional),
                DecimalMoney.format(principal),DecimalMoney.format(interest),DecimalMoney.format(total),selected,DecimalMoney.format(balance),DecimalMoney.format(funding.cashImpact()?balance.subtract(total):balance),
                day,strategy,target,summary(before),summary(after),options,LoanRepaymentPolicy.from(loan),token,funding.cashImpact(),funding.settlementAssetId());
        return new Prepared(preview,projection,account,after);
    }
    private void validateLoan(Loan loan,LocalDate day,boolean locked){
        if(loan.getStatus()!=LoanStatus.ACTIVE)throw new ResourceConflictException("LOAN_CLOSED","贷款已归档或结清");
        if(day==null||day.getYear()<1000||day.isAfter(LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")))))throw new RequestValidationException(Map.of("paidOn","日期必须有效且不晚于今天"));
        if(locked){accounting.requirePaymentDate(loan,day);return;}
        long h=loan.getHousehold().getId();
        if(loan.getAccountingOn()==null||loan.getFundingMode()==null||jdbc.queryForObject("select count(*) from ledger_sources where household_id=? and source_type=? and source_id=? and current_journal_id is not null",Long.class,h,LoanAccountingService.source(loan),loan.getId())==0)
            throw new ResourceConflictException("ACCOUNTING_NOT_INITIALIZED","贷款尚未确认账务期初");
        if(day.isBefore(loan.getAccountingOn()))throw new ResourceConflictException("LOAN_PAYMENT_BEFORE_OPENING","日期不能早于贷款账务起始日期");
        if(loan.getLastPaymentOn()!=null&&day.isBefore(loan.getLastPaymentOn()))throw new ResourceConflictException("LOAN_PAYMENT_CHRONOLOGY","日期不能早于最近已入账还款");
        var balances=jdbc.queryForList("select balance_amount from ledger_accounts where household_id=? and account_code=?",BigDecimal.class,h,"LOAN:"+loan.getId());
        if(balances.size()!=1||balances.get(0).compareTo(loan.getCurrentPrincipalAmount())!=0)throw new ResourceConflictException("ACCOUNTING_BALANCE_MISMATCH","贷款余额与账务不一致");
    }
    private static List<InstallmentDraft> futureDrafts(LoanRepaymentPolicyService.Projection p){
        long remaining=p.future().stream().mapToLong(LoanPlanToken.Period::principalCents).reduce(0,Math::addExact);
        var rows=new ArrayList<InstallmentDraft>();for(var row:p.future()){remaining=Math.subtractExact(remaining,row.principalCents());rows.add(row.draft(remaining));}return List.copyOf(rows);
    }
    private static LoanPrepaymentPreview.ScheduleSummary summary(List<InstallmentDraft> rows){
        return rows.isEmpty()?new LoanPrepaymentPreview.ScheduleSummary("0.00",0,null,null,null,"0.00","0.00",List.of()):LoanPrepaymentPreview.summarize(rows);
    }
    private static BigDecimal parse(String raw){try{var value=DecimalMoney.settled(new BigDecimal(raw));if(value.signum()<=0)throw new IllegalArgumentException();return value;}
        catch(RuntimeException e){throw new RequestValidationException(Map.of("additionalPrincipal","额外本金必须为正且精确到分"));}}
    private String childKey(String parent,String suffix){return "repayment:"+requests.digest("CHILD",0,List.of(parent,suffix));}
    private static LoanRepaymentBatch.Child child(FinancialTransaction tx){return new LoanRepaymentBatch.Child(tx.getSourceType().name(),tx.getSourceId(),tx.getId(),
            Money.formatCents(tx.getLoanPrincipalCents()),Money.formatCents(tx.getLoanInterestCents()),Money.formatCents(tx.getAmountCents()));}
}
