package com.familyfinance.asset;

import com.familyfinance.accounting.*;
import com.familyfinance.family.*;
import com.familyfinance.ledger.*;
import com.familyfinance.loan.*;
import com.familyfinance.shared.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** One asset disposition and all related debt settlements share a locked transaction and immutable receipt. */
@Service
public class AssetSaleService {
    private static final BigDecimal ZERO=new BigDecimal("0.00");
    private final AssetRepository assets;private final AssetService assetService;private final LoanRepository loans;
    private final FinancialAccountRepository accounts;private final CurrentMembership current;
    private final FamilyPermissionService permissions;private final FamilyMutationAuthorization authorization;
    private final AssetAccountingService accounting;private final CashAccountingService cash;
    private final LoanPayoffService payoffs;private final LoanRepaymentService repayments;
    private final AccountingRequests requests;private final AssetSaleReceiptRepository receipts;
    private final JdbcTemplate jdbc;private final Clock clock;
    public AssetSaleService(AssetRepository assets,AssetService assetService,LoanRepository loans,FinancialAccountRepository accounts,
            CurrentMembership current,FamilyPermissionService permissions,FamilyMutationAuthorization authorization,
            AssetAccountingService accounting,CashAccountingService cash,LoanPayoffService payoffs,LoanRepaymentService repayments,
            AccountingRequests requests,AssetSaleReceiptRepository receipts,JdbcTemplate jdbc,Clock clock){
        this.assets=assets;this.assetService=assetService;this.loans=loans;this.accounts=accounts;this.current=current;
        this.permissions=permissions;this.authorization=authorization;this.accounting=accounting;this.cash=cash;
        this.payoffs=payoffs;this.repayments=repayments;this.requests=requests;this.receipts=receipts;this.jdbc=jdbc;this.clock=clock;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public AssetSalePreview preview(Authentication auth,long assetId,AssetSaleDraft draft){
        var context=current.require(auth);permissions.requireAdmin(context);
        return prepare(context,assetId,draft,false).preview();
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public AssetSaleResult receipt(Authentication auth,long assetId){
        long household=current.require(auth).householdId();
        assets.findByIdAndHouseholdId(assetId,household).orElseThrow(()->new ResourceNotFoundException("资产不存在"));
        return receipts.read(household,assetId,null,false);
    }
    @Transactional
    public AssetSaleResult sell(Authentication auth,long assetId,AssetSaleRequest request,String key){
        var access=authorization.requireAdmin(auth);long h=access.context().householdId();
        if(request==null)throw invalid("draft","请提供出售方案");
        String digest=requests.digest("ASSET_SALE:"+assetId,access.context().userId(),request);
        Long replay=requests.replay(h,key,digest);
        // Replay precedes archived asset, current loan plan, current funding and assignee checks.
        if(replay!=null)return receipts.byId(h,assetId,replay,true);
        var prepared=prepare(access.context(),assetId,request.draft(),true);var q=prepared.preview();
        if(!q.planToken().equals(request.planToken()))throw new ResourceConflictException("ASSET_SALE_PLAN_CHANGED","资产、贷款或账户信息已变化，请重新预览并确认");
        if(!q.canConfirm())throw new ResourceConflictException(prepared.blockerCode(),String.join("；",q.blockers()));
        Asset asset=prepared.asset();var draft=request.draft();boolean direct=draft.route()==AssetSaleDraft.Route.DIRECT;
        var funding=direct?LoanSettlementFunding.assetSale(assetId):LoanSettlementFunding.cash();
        accounting.sell(asset,draft.disposedOn(),new BigDecimal(q.proceeds()).subtract(new BigDecimal(q.fee())),
            new BigDecimal(q.totalRepayment()),draft.cashAccountId(),direct,access.context().userId(),key);
        for(var child:prepared.children()){
            String childKey="asset-sale:"+requests.digest("CHILD",access.context().userId(),List.of(key,child.loan().getId()));
            if(child.payoff()!=null)payoffs.payoffPrepared(access,child.loan(),child.payoff(),childKey,funding);
            else repayments.repayPrepared(access,child.loan(),child.partial(),childKey,funding);
        }
        if(direct){
            var clearing=jdbc.queryForList("select balance_amount from ledger_accounts where household_id=? and account_code=? for update",BigDecimal.class,h,"ASSET_SALE_CLEARING:"+assetId);
            if(!clearing.isEmpty()&&clearing.get(0).signum()!=0)throw new ResourceConflictException("ASSET_SALE_CLEARING_UNBALANCED","资产结算款未完全结清");
        }
        asset.dispose(draft.disposedOn(),DecimalMoney.toCents(new BigDecimal(q.proceeds())),draft.cashAccountId(),access.context().userId(),clock.instant());
        assets.flush();
        var result=receipts.append(h,access.context().userId(),key,draft,q,clock.instant());
        requests.record(h,key,digest,result.saleId());return result;
    }

    private record Prepared(Asset asset,AssetSalePreview preview,List<Child> children,String blockerCode){}
    private record Child(Loan loan,LoanPayoffService.SalePrepared payoff,LoanRepaymentService.Prepared partial){
        BigDecimal principal(){return new BigDecimal(payoff!=null?payoff.quote().principalAmount():partial.preview().totalPrincipalAmount());}
        BigDecimal interest(){return new BigDecimal(payoff!=null?payoff.quote().interestAmount():partial.preview().totalInterestAmount());}
        BigDecimal total(){return principal().add(interest());}
        String token(){return payoff!=null?payoff.quote().planToken():partial.preview().planToken();}
        AssetSalePreview.Loan response(){return new AssetSalePreview.Loan(loan.getId(),loan.getName(),payoff!=null?AssetSaleDraft.Mode.PAYOFF:AssetSaleDraft.Mode.PARTIAL,
            format(principal()),format(interest()),format(total()),format(loan.getCurrentPrincipalAmount().subtract(principal())),
            payoff!=null?0:partial.preview().after().periodCount(),payoff!=null?null:partial.preview().after().nextPaymentAmount(),
            payoff!=null?null:partial.preview().duePrincipalAmount(),payoff!=null?payoff.quote().dueInterestAmount():partial.preview().dueInterestAmount(),
            payoff!=null?null:partial.preview().additionalPrincipal());}
    }
    private record Movement(long id,LocalDate day,BigDecimal change){}
    private record CashSnapshot(long accountId,String accountName,String currency,LocalDate openingOn,BigDecimal before,List<Movement> movements){
        boolean permits(LocalDate day,BigDecimal change){
            var days=new TreeMap<LocalDate,BigDecimal>();for(var m:movements)days.merge(m.day(),m.change(),BigDecimal::add);
            days.merge(day,change,BigDecimal::add);BigDecimal running=ZERO;
            for(var item:days.entrySet()){running=running.add(item.getValue());if(running.signum()<0)return false;}return true;
        }
    }
    private Prepared prepare(MembershipContext context,long assetId,AssetSaleDraft draft,boolean locked){
        long h=context.householdId();
        Asset asset=locked?assetService.findCurrent(h,assetId):assets.findByIdAndHouseholdId(assetId,h).orElseThrow(()->new ResourceNotFoundException("资产不存在"));
        if(asset.isArchived()||asset.getDisposedOn()!=null)throw new ResourceConflictException("ASSET_ARCHIVED","资产已出售或归档");
        if(draft==null||draft.route()==null||draft.repayments()==null)throw invalid("draft","请提供日期、结算方式和贷款选择");
        LocalDate day=cash.date(draft.disposedOn()==null?null:draft.disposedOn().toString(),"disposedOn");
        if(asset.getAccountingMode()==null)throw new ResourceConflictException("ACCOUNTING_NOT_INITIALIZED","资产尚未确认账务期初");
        accounting.requireChronology(asset,day);
        if(locked)accounting.requireBalance(asset);else{
            var balances=jdbc.queryForList("select balance_amount from ledger_accounts where household_id=? and account_code=?",BigDecimal.class,h,"ASSET:"+assetId);
            var actual=balances.isEmpty()?ZERO:balances.get(0);
            if(actual.compareTo(DecimalMoney.fromCents(asset.getCurrentValueCents()))!=0)throw new ResourceConflictException("ACCOUNTING_BALANCE_MISMATCH","资产当前价值与账务不一致");
        }
        Map<String,String> fields=new LinkedHashMap<>();
        Long gross=AssetService.parseRequiredMoney(draft.proceeds(),"proceeds",fields),fee=AssetService.parseRequiredMoney(draft.fee(),"fee",fields);
        if(!fields.isEmpty())throw new RequestValidationException(fields);
        BigDecimal proceeds=DecimalMoney.fromCents(gross),cost=DecimalMoney.fromCents(fee),saleNet=proceeds.subtract(cost);
        if(draft.repayments().size()>50)throw invalid("repayments","一次最多结算50笔贷款");
        if((saleNet.signum()!=0||!draft.repayments().isEmpty())&&draft.cashAccountId()==null)throw invalid("cashAccountId","请选择收款或补款的人民币账户");
        Long repaymentId=draft.repaymentAccountId()==null?draft.cashAccountId():draft.repaymentAccountId();
        boolean direct=draft.route()==AssetSaleDraft.Route.DIRECT;
        if(direct&&!Objects.equals(repaymentId,draft.cashAccountId()))throw invalid("repaymentAccountId","买方直接结算必须使用同一个收款或补款账户作为结算上下文");
        List<Loan> linked;
        if(locked){
            var ids=jdbc.queryForList("select id from loans where household_id=? and linked_asset_id=? order by id for update",Long.class,h,assetId);
            linked=ids.stream().map(id->loans.findLockedByIdAndHouseholdId(id,h).orElseThrow()).toList();
        }else linked=loans.findAllByHouseholdIdAndLinkedAsset_IdOrderByIdAsc(h,assetId);
        Map<Long,Loan> linkedById=new LinkedHashMap<>();for(var loan:linked)linkedById.put(loan.getId(),loan);
        Set<Long> selected=new HashSet<>();
        for(var selection:draft.repayments()){
            if(selection==null||selection.mode()==null||!selected.add(selection.loanId()))throw invalid("repayments","贷款选择不能为空或重复");
            var loan=linkedById.get(selection.loanId());
            if(loan==null||loan.getStatus()!=LoanStatus.ACTIVE||loan.getCurrentPrincipalAmount().signum()<=0)throw invalid("repayments","只能结算当前资产关联的未结清贷款");
            if(selection.mode()==AssetSaleDraft.Mode.PAYOFF&&(selection.additionalPrincipal()!=null||selection.strategy()!=null||selection.targetPeriods()!=null))
                throw invalid("repayments","一次结清不接受额外本金或重排策略");
            if(selection.mode()==AssetSaleDraft.Mode.PARTIAL&&selection.interestAmount()!=null)throw invalid("repayments","部分还款的利息由到期计划计算");
        }
        var retained=linked.stream().filter(l->l.getCurrentPrincipalAmount().signum()>0&&!selected.contains(l.getId()))
            .map(l->new AssetSalePreview.RetainedLoan(l.getId(),l.getName(),format(l.getCurrentPrincipalAmount()))).toList();
        var snapshots=new LinkedHashMap<Long,CashSnapshot>();
        if(draft.cashAccountId()!=null)snapshots.put(draft.cashAccountId(),account(h,draft.cashAccountId(),day,locked));
        if(!selected.isEmpty()&&!snapshots.containsKey(repaymentId))snapshots.put(repaymentId,account(h,repaymentId,day,locked));
        var funding=direct?LoanSettlementFunding.assetSale(assetId):LoanSettlementFunding.cash();
        BigDecimal initial=selected.isEmpty()?ZERO:snapshots.get(repaymentId).before();
        if(!direct&&Objects.equals(repaymentId,draft.cashAccountId()))initial=initial.add(saleNet);
        var children=children(context,draft,linkedById,repaymentId,locked,initial,funding);
        BigDecimal principal=children.stream().map(Child::principal).reduce(ZERO,BigDecimal::add);
        BigDecimal interest=children.stream().map(Child::interest).reduce(ZERO,BigDecimal::add);
        BigDecimal total=principal.add(interest),net=saleNet.subtract(total);
        // The direct parent posts its one net cash movement before children. Child receipts must show that balance unchanged.
        if(direct&&!children.isEmpty())children=children(context,draft,linkedById,repaymentId,locked,initial.add(net),funding);
        var changes=new LinkedHashMap<Long,BigDecimal>();snapshots.keySet().forEach(id->changes.put(id,ZERO));
        if(draft.cashAccountId()!=null)changes.merge(draft.cashAccountId(),direct?net:saleNet,BigDecimal::add);
        if(!direct&&!selected.isEmpty())changes.merge(repaymentId,total.negate(),BigDecimal::add);
        var blockers=new ArrayList<String>();String blockerCode="INSUFFICIENT_FUNDS";
        if(!retained.isEmpty()&&!draft.retainUnselectedLoans()){
            blockers.add("仍有未选择结算的贷款，请明确确认出售后保留这些债务");blockerCode="ASSET_SALE_RETAIN_REQUIRED";
        }
        var balances=new ArrayList<AssetSalePreview.Balance>();
        for(var entry:snapshots.entrySet()){
            var account=entry.getValue();var change=changes.get(entry.getKey());
            if(!account.permits(day,change))blockers.add("账户「"+account.accountName()+"」在出售日期及后续已记账日期余额不足");
            balances.add(new AssetSalePreview.Balance(account.accountId(),account.accountName(),account.currency(),format(account.before()),format(change),format(account.before().add(change))));
        }
        var q=new AssetSalePreview(assetId,asset.getName(),day,draft.route(),format(proceeds),format(cost),Money.formatCents(asset.getCurrentValueCents()),
            format(saleNet.subtract(DecimalMoney.fromCents(asset.getCurrentValueCents()))),format(principal),format(interest),format(total),format(net),
            List.copyOf(balances),children.stream().map(Child::response).toList(),retained,blockers.isEmpty(),List.copyOf(blockers),"");
        var relationState=linked.stream().map(l->Arrays.asList(l.getId(),l.getName(),l.getStatus(),l.getAssetRelation(),l.getCurrentPrincipalAmount(),
            l.getAssignedUser()==null?null:l.getAssignedUser().getId(),l.getPurchasedAssetId())).toList();
        String token=requests.digest("ASSET_SALE_PLAN_V1",context.userId(),Arrays.asList(draft,q,asset.getLastAccountingOn(),asset.getAccountingOn(),asset.getAccountingMode(),relationState,
            children.stream().map(Child::token).toList(),snapshots.values()));
        return new Prepared(asset,q.token(token),List.copyOf(children),blockerCode);
    }
    private List<Child> children(MembershipContext context,AssetSaleDraft draft,Map<Long,Loan> linked,Long account,
            boolean locked,BigDecimal balance,LoanSettlementFunding funding){
        var result=new ArrayList<Child>();
        for(var selection:draft.repayments()){
            var loan=linked.get(selection.loanId());Child child;
            if(selection.mode()==AssetSaleDraft.Mode.PAYOFF){
                child=new Child(loan,payoffs.prepareForSale(context,loan,draft.disposedOn(),account,selection.interestAmount(),locked,balance),null);
            }else{
                child=new Child(loan,null,repayments.prepareForSale(context,loan,selection.additionalPrincipal(),draft.disposedOn(),account,
                    selection.strategy(),selection.targetPeriods(),locked,balance,funding));
                if(child.principal().compareTo(loan.getCurrentPrincipalAmount())>=0)throw invalid("additionalPrincipal","部分还款必须保留剩余本金；全部偿还请选择一次结清");
            }
            result.add(child);if(funding.cashImpact())balance=balance.subtract(child.total());
        }
        return result;
    }
    private CashSnapshot account(long household,long id,LocalDate day,boolean locked){
        var account=(locked?accounts.findLockedByIdAndHouseholdId(id,household):accounts.findByIdAndHouseholdId(id,household))
            .orElseThrow(()->invalid("cashAccountId","账户必须属于当前家庭"));
        if(account.isArchived())throw new ResourceConflictException("ACCOUNT_ARCHIVED","账户已归档");
        if(!"CNY".equals(account.getCurrency()))throw invalid("cashAccountId","资产出售与贷款结算请使用人民币账户");
        if(!account.isOpeningConfirmed()||account.getOpeningOn()==null)throw new ResourceConflictException("ACCOUNTING_NOT_INITIALIZED","账户尚未确认期初余额和日期");
        if(day.isBefore(account.getOpeningOn()))throw new ResourceConflictException("ACCOUNT_ACTIVITY_BEFORE_OPENING","日期不能早于账户开账日期");
        if(locked)cash.requireConfirmed(account,day);
        var values=jdbc.queryForList("select balance_amount from ledger_accounts where household_id=? and account_code=?"+(locked?" for update":""),BigDecimal.class,household,"CASH:"+id);
        BigDecimal balance=values.isEmpty()?ZERO:values.get(0);
        var movements=jdbc.query("select e.id,j.effective_on,e.debit_amount-e.credit_amount from ledger_entries e join ledger_journals j on j.id=e.journal_id and j.household_id=e.household_id where e.household_id=? and e.account_code=? order by j.effective_on,e.id"+(locked?" for update":""),
            (r,n)->new Movement(r.getLong(1),r.getDate(2).toLocalDate(),r.getBigDecimal(3)),household,"CASH:"+id);
        if(movements.stream().map(Movement::change).reduce(ZERO,BigDecimal::add).compareTo(balance)!=0)
            throw new ResourceConflictException("ACCOUNTING_BALANCE_MISMATCH","资金账户余额与账务分录不一致");
        return new CashSnapshot(id,account.getName(),account.getCurrency(),account.getOpeningOn(),balance,List.copyOf(movements));
    }
    private static String format(BigDecimal value){return DecimalMoney.format(value);}
    private static RequestValidationException invalid(String field,String message){return new RequestValidationException(Map.of(field,message));}
}
