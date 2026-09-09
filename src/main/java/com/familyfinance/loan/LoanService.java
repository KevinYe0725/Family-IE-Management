package com.familyfinance.loan;

import com.familyfinance.asset.*;
import com.familyfinance.accounting.*;
import com.familyfinance.category.*;
import com.familyfinance.family.*;
import com.familyfinance.household.*;
import com.familyfinance.ledger.*;
import com.familyfinance.shared.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service @Transactional(readOnly = true,isolation=Isolation.REPEATABLE_READ)
public class LoanService {
    private static final int MAX_PAGE_SIZE=50;
    private final LoanRepository loans; private final AssetRepository assets; private final FamilyMemberRepository members; private final AppUserRepository users;
    private final FinancialAccountRepository accounts; private final CategoryRepository categories; private final CurrentMembership current; private final FamilyMutationAuthorization mutations; private final Clock clock;
    private final AmortizationCalculator calculator=new AmortizationCalculator();
    private final LoanAccountingService accounting; private final AccountingRequests requests; private final LoanPrepaymentRepository prepayments; private final LoanInstallmentRepository installments;
    private final LoanPurchasedAssetService purchasedAssets; private final LoanTotalsService totals;
    LoanService(LoanRepository loans, AssetRepository assets, FamilyMemberRepository members, AppUserRepository users, FinancialAccountRepository accounts, CategoryRepository categories, CurrentMembership current, FamilyMutationAuthorization mutations, Clock clock, LoanAccountingService accounting, AccountingRequests requests,LoanPrepaymentRepository prepayments,LoanInstallmentRepository installments,LoanPurchasedAssetService purchasedAssets,LoanTotalsService totals) {
        this.loans=loans;this.assets=assets;this.members=members;this.users=users;this.accounts=accounts;this.categories=categories;this.current=current;this.mutations=mutations;this.clock=clock;this.accounting=accounting;this.requests=requests;this.prepayments=prepayments;this.installments=installments;
        this.purchasedAssets=purchasedAssets;this.totals=totals;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public LoanPage list(Authentication a, LoanStatus status,int page,int size){long h=current.require(a).householdId(); int p=Math.max(0,page), s=Math.min(MAX_PAGE_SIZE,Math.max(1,size)); Page<Loan> r=loans.findByHouseholdIdAndStatus(h,status==null?LoanStatus.ACTIVE:status,PageRequest.of(p,s,Sort.by(Sort.Direction.DESC,"id"))); return new LoanPage(r.stream().map(l->response(l,false)).toList(),p,s,r.getTotalElements(),r.getTotalPages(),r.hasNext());}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public LoanResponse get(Authentication a,long id){return response(find(current.require(a).householdId(),id),false);}
    public LoanSchedulePage schedule(Authentication a,long id,int page,int size){return schedule(a,id,page,size,LoanScheduleView.ALL);}
    public LoanSchedulePage schedule(Authentication a,long id,int page,int size,LoanScheduleView view){
        Loan l=find(current.require(a).householdId(),id);int p=Math.max(0,page),s=Math.min(MAX_PAGE_SIZE,Math.max(1,size));
        var rows=l.getInstallments().stream().filter(i->view==null||view==LoanScheduleView.ALL||(view==LoanScheduleView.CURRENT)==(i.getStatus()==LoanInstallmentStatus.PENDING)).toList();
        long total=rows.size();int totalPages=(int)Math.ceil((double)total/s);
        List<LoanInstallmentResponse> items=rows.stream().skip((long)p*s).limit(s).map(LoanInstallmentResponse::from).toList();
        return new LoanSchedulePage(items,p,s,total,totalPages,p+1<totalPages);
    }
    @Transactional public LoanResponse create(Authentication a,LoanCreateRequest r){return create(a,r,AccountingRequests.key(null));}
    @Transactional public LoanResponse create(Authentication a,LoanCreateRequest r,String key){
        var access=mutations.requireAdmin(a);long h=access.context().householdId();String digest=requests.digest("LOAN_CREATE",access.context().userId(),r);
        Long replay=requests.replay(h,key,digest,LoanRequestHistory.create(requests,access.context().userId(),r));if(replay!=null)return response(locked(h,replay),true);
        Values v=validate(h,r);if(r.fundingMode()==null)throw new RequestValidationException(Map.of("fundingMode","请选择贷款入账方式"));
        boolean purchased=Boolean.TRUE.equals(r.createPurchasedAsset());
        if(purchased!=(r.fundingMode()==LoanFundingMode.FINANCED_PURCHASE))throw new RequestValidationException(Map.of("createPurchasedAsset","本次贷款购买物必须同时选择直接购买入账方式"));
        if(purchased&&r.linkedAssetId()!=null)throw new RequestValidationException(Map.of("linkedAssetId","本次贷款购买物不能同时关联已有资产"));
        FinancialAccount disbursement=disbursement(h,r.fundingMode(),r.disbursementAccountId());
        LocalDate day=accounting.accountingDate(r.accountingOn());
        Loan loan=new Loan(access.household(),v.name,v.type,v.asset,v.member,v.user,v.account,v.category,v.principal,v.rate,v.term,v.method,v.start,access.membership().getUser());
        loan.replaceSchedule(v.schedule);
        long purchasePriceCents=loan.getPrincipalCents();
        com.familyfinance.ledger.FinancialAccount downAccount=null;
        var spec=r.purchasedAsset();
        if(purchased&&spec!=null){
            var perrors=new LinkedHashMap<String,String>();
            if(spec.purchaseValue()!=null){
                try{purchasePriceCents=Money.parseCents(spec.purchaseValue());}
                catch(IllegalArgumentException e){perrors.put("purchaseValue",e.getMessage());}
            }
            if(purchasePriceCents<loan.getPrincipalCents())perrors.put("purchaseValue","资产购入价值不能低于贷款本金");
            if(purchasePriceCents>loan.getPrincipalCents()){
                if(r.downPaymentAccountId()==null)perrors.put("downPaymentAccountId","有首付差额时必须选择首付资金账户");
                else{
                    FinancialAccount candidate=accounts.findLockedByIdAndHouseholdId(r.downPaymentAccountId(),h).filter(x->!x.isArchived()).orElse(null);
                    if(candidate==null)perrors.put("downPaymentAccountId","首付资金账户必须属于当前家庭且未归档");
                    else if(!candidate.getCurrency().equals("CNY"))perrors.put("downPaymentAccountId","首付请使用人民币资金账户");
                    else downAccount=candidate;
                }
            }
            if(!perrors.isEmpty())throw new RequestValidationException(perrors);
        }
        if(purchased){
            // Persist the valid all-null accounting tuple to obtain the immutable origin ID.
            loans.saveAndFlush(loan);
            String assetName=spec==null||spec.name()==null?null:spec.name().trim();
            FamilyMember assetOwner=spec==null||spec.ownerMemberId()==null?null:resolveMember(h,spec.ownerMemberId(),new LinkedHashMap<>());
            loan.attachPurchasedAsset(purchasedAssets.create(loan,day,assetName,assetOwner,purchasePriceCents));
        }
        loan.accounting(r.fundingMode(),day,disbursement);loans.saveAndFlush(loan);
        if(purchased&&purchasePriceCents>loan.getPrincipalCents())
            accounting.originateFinanced(loan,purchasePriceCents,downAccount,access.context().userId(),key);
        else accounting.originate(loan,access.context().userId(),key,false);
        requests.record(h,key,digest,loan.getId());return response(loan,true);
    }
    @Transactional public LoanResponse update(Authentication a,long id,LoanPatchRequest r){return update(a,id,r,AccountingRequests.key(null));}
    @Transactional public LoanResponse update(Authentication a,long id,LoanPatchRequest r,String key){
        var access=mutations.requireAdmin(a);long h=access.context().householdId();String digest=requests.digest("LOAN_UPDATE:"+id,access.context().userId(),r);
        Long replay=requests.replay(h,key,digest);if(replay!=null)return response(locked(h,replay),true);
        Loan old=locked(h,id);if(old.isArchived())throw new ResourceConflictException("LOAN_CLOSED","贷款已归档或结清");
        if(r==null)throw new RequestValidationException(Map.of("request","请求不能为空"));
        if(old.getPurchasedAssetId()!=null && (r.principal()!=null||r.accountingOn()!=null||r.startOn()!=null||r.disbursementAccountId()!=null
                ||r.linkedAssetId()!=null&&!r.linkedAssetId().equals(old.getPurchasedAssetId())))
            throw new ResourceConflictException("LOAN_PURCHASE_IMMUTABLE","贷款购买物的本金、起始日期和来源关联不能单独更改；可以更正未付款的利率与计划");
        if(hasContractField(r)){
            accounting.requireBalance(old);
            var currentSchedule=installments.findAllLockedByLoanIdAndHouseholdIdOrderByInstallmentNo(id,h);
            if(old.getLastPaymentOn()!=null||currentSchedule.stream().anyMatch(i->i.getStatus()==LoanInstallmentStatus.PAID)||!prepayments.findAllLockedByLoanIdAndHouseholdId(id,h).isEmpty())
                throw new ResourceConflictException("LOAN_HAS_PAYMENTS","已有还款或提前还款记录，不能更正本金合同和账务起始信息");
            List<CustomInstallmentRequest> custom=r.customSchedule();
            if(custom==null&&old.getRepaymentMethod()==RepaymentMethod.CUSTOM)custom=currentSchedule.stream().map(i->new CustomInstallmentRequest(i.getDueOn(),Money.formatCents(i.getPrincipalCents()),Money.formatCents(i.getInterestCents()))).toList();
            LoanCreateRequest merged=new LoanCreateRequest(r.name()==null?old.getName():r.name(),old.getType(),r.linkedAssetId()==null?(old.getLinkedAsset()==null?null:old.getLinkedAsset().getId()):r.linkedAssetId(),r.memberId()==null?(old.getMember()==null?null:old.getMember().getId()):r.memberId(),r.assignedUserId()==null?(old.getAssignedUser()==null?null:old.getAssignedUser().getId()):r.assignedUserId(),r.paymentAccountId()==null?old.getPaymentAccount().getId():r.paymentAccountId(),r.paymentCategoryId()==null?old.getPaymentCategory().getId():r.paymentCategoryId(),r.principal()==null?Money.formatCents(old.getPrincipalCents()):r.principal(),r.annualRate()==null?old.getAnnualRate():r.annualRate(),r.termMonths()==null?old.getTermMonths():r.termMonths(),r.repaymentMethod()==null?old.getRepaymentMethod():r.repaymentMethod(),r.startOn()==null?old.getStartOn():r.startOn(),custom);
            Values v=validate(h,merged);
            LoanTermOptions.requireMinimum(v.schedule,old.getMinimumInstallmentAmount());
            FinancialAccount disbursement=r.disbursementAccountId()==null?old.getDisbursementAccount():disbursement(h,old.getFundingMode(),r.disbursementAccountId());
            old.accounting(old.getFundingMode(),r.accountingOn()==null?old.getAccountingOn():r.accountingOn(),disbursement);
            // Remove old unposted rows before inserting their replacement installment numbers.
            installments.deleteAll(currentSchedule);installments.flush();
            old.updateContract(v.name,v.member,v.user,v.asset,v.account,v.category,v.principal,v.rate,v.term,v.method,v.start);
            installments.saveAll(v.schedule.stream().map(d->new LoanInstallment(old,d)).toList());
            if(old.getPurchasedAssetId()==null)accounting.originate(old,access.context().userId(),key,true);
        }else{
            Map<String,String> errors=new LinkedHashMap<>();String name=r.name()==null?old.getName():required(r.name(),"name",errors);
            FamilyMember member=r.memberId()==null?old.getMember():resolveMember(h,r.memberId(),errors);
            Asset asset=r.linkedAssetId()==null?old.getLinkedAsset():resolveAsset(h,r.linkedAssetId(),old.getType(),errors);
            FinancialAccount account=r.paymentAccountId()==null?old.getPaymentAccount():accounts.findLockedByIdAndHouseholdId(r.paymentAccountId(),h).filter(x->!x.isArchived()).orElse(null);
            Category category=r.paymentCategoryId()==null?old.getPaymentCategory():categories.findByIdAndHouseholdId(r.paymentCategoryId(),h).filter(x->x.getKind()==TransactionKind.EXPENSE).orElse(null);
            if(account==null)errors.put("paymentAccountId","请选择当前家庭未归档的付款账户");else if(!account.getCurrency().equals("CNY"))errors.put("paymentAccountId","贷款请使用人民币付款账户");if(category==null)errors.put("paymentCategoryId","请选择当前家庭的支出分类");if(!errors.isEmpty())throw new RequestValidationException(errors);
            old.updateDefaults(name,member,resolveUser(h,r.assignedUserId(),old.getAssignedUser()),asset,account,category);
        }
        loans.flush();requests.record(h,key,digest,id);return response(old,true);
    }
    @Transactional public void archive(Authentication a,long id){archive(a,id,AccountingRequests.key(null));}
    @Transactional public void archive(Authentication a,long id,String key){
        var access=mutations.requireAdmin(a);long h=access.context().householdId();String digest=requests.digest("LOAN_ARCHIVE:"+id,access.context().userId(),id);
        if(requests.replay(h,key,digest)!=null)return;Loan loan=locked(h,id);
        if(loan.getFundingMode()!=null)accounting.requireBalance(loan);
        if(loan.getCurrentPrincipalCents()!=0)throw new ResourceConflictException("LOAN_BALANCE_NOT_ZERO","贷款仍有未偿本金，不能归档");
        // Fully paid loans retain CLOSED and all payment history.
        loan.archive(clock.instant());requests.record(h,key,digest,id);
    }
    private FinancialAccount disbursement(long h,LoanFundingMode mode,Long id){
        if(mode==LoanFundingMode.FINANCED_PURCHASE){if(id!=null)throw new RequestValidationException(Map.of("disbursementAccountId","直接购买不经过家庭现金账户"));return null;}
        if(mode==LoanFundingMode.OPENING){if(id!=null)throw new RequestValidationException(Map.of("disbursementAccountId","期初贷款不产生现金放款"));return null;}
        if(id==null)throw new RequestValidationException(Map.of("disbursementAccountId","实际放款必须选择收款账户"));
        return accounts.findLockedByIdAndHouseholdId(id,h).filter(account->account.getCurrency().equals("CNY")).orElseThrow(()->new RequestValidationException(Map.of("disbursementAccountId","放款账户必须属于当前家庭且为人民币账户")));
    }
    private Values validate(long h,LoanCreateRequest r){Map<String,String> f=new LinkedHashMap<>(); if(r==null){throw new RequestValidationException(Map.of("request","请求不能为空"));} String name=required(r.name(),"name",f);LoanType type=r.type();if(type==null)f.put("type","贷款类型不能为空");long principal=parseMoney(r.principal(),"principal",f);BigDecimal rate=r.annualRate();if(rate==null||rate.scale()>6||rate.signum()<0||rate.compareTo(BigDecimal.ONE)>0)f.put("annualRate","年利率必须在 0 到 1 之间且最多六位小数");int term=r.termMonths()==null?0:r.termMonths();if(term<1||term>360)f.put("termMonths","期数必须在 1 到 360 之间");if(r.startOn()==null)f.put("startOn","起息日不能为空"); RepaymentMethod method=r.repaymentMethod();if(method==null)f.put("repaymentMethod","还款方式不能为空"); Asset asset=resolveAsset(h,r.linkedAssetId(),type,f); FamilyMember member=resolveMember(h,r.memberId(),f);AppUser user=resolveUser(h,r.assignedUserId(),null);FinancialAccount account=r.paymentAccountId()==null?null:accounts.findByIdAndHouseholdIdAndArchivedAtIsNull(r.paymentAccountId(),h).orElse(null);if(account==null)f.put("paymentAccountId","付款账户必须属于当前家庭且未归档");else if(!account.getCurrency().equals("CNY"))f.put("paymentAccountId","贷款请使用人民币付款账户");Category category=r.paymentCategoryId()==null?null:categories.findByIdAndHouseholdId(r.paymentCategoryId(),h).filter(c->c.getKind()==TransactionKind.EXPENSE).orElse(null);if(category==null)f.put("paymentCategoryId","付款分类必须是当前家庭的支出分类");if(!f.isEmpty())throw new RequestValidationException(f);List<InstallmentDraft> schedule=method==RepaymentMethod.CUSTOM?custom(r.customSchedule(),principal,term):standard(principal,rate,term,r.startOn(),method);return new Values(name,type,asset,member,user,account,category,principal,rate.setScale(6),term,method,r.startOn(),schedule);}
    private List<InstallmentDraft> standard(long principal,BigDecimal rate,int term,LocalDate start,RepaymentMethod method){
        try{return calculator.calculate(principal,rate,term,start,method);}catch(IllegalArgumentException e){throw new RequestValidationException(Map.of("termMonths","所选期数无法形成正现金计划，请减少期数"));}
    }
    private List<InstallmentDraft> custom(List<CustomInstallmentRequest> rows,long principal,int term){if(rows==null||rows.size()!=term)throw new RequestValidationException(Map.of("customSchedule","自定义计划必须与期数相同"));List<InstallmentDraft> out=new ArrayList<>();long sum=0;LocalDate previous=null;for(int i=0;i<rows.size();i++){var r=rows.get(i);if(r==null||r.dueOn()==null||previous!=null&&!r.dueOn().isAfter(previous))throw new RequestValidationException(Map.of("customSchedule","自定义到期日必须递增"));long p;try{if(r.principal()==null)throw new IllegalArgumentException();BigDecimal value=new BigDecimal(r.principal());if(value.signum()<0||value.compareTo(DecimalMoney.fromCents(principal))>0)throw new IllegalArgumentException();p=DecimalMoney.toCents(value);}catch(IllegalArgumentException e){throw new RequestValidationException(Map.of("customSchedule","自定义本金格式不正确"));}long interest=r.interest()==null||r.interest().equals("0")||r.interest().equals("0.00")?0:Money.parseCents(r.interest());if(Math.addExact(p,interest)<=0)throw new RequestValidationException(Map.of("customSchedule","每期付款必须为正"));if(p>principal-sum)throw new RequestValidationException(Map.of("customSchedule","自定义本金总和不能超过贷款本金"));sum=Math.addExact(sum,p);out.add(new InstallmentDraft(i+1,r.dueOn(),p,interest,0));previous=r.dueOn();}if(sum!=principal)throw new RequestValidationException(Map.of("customSchedule","自定义本金总和必须等于贷款本金"));long remaining=principal;List<InstallmentDraft> fixed=new ArrayList<>();for(var d:out){remaining-=d.principalCents();if(remaining==0&&fixed.size()<out.size()-1)throw new RequestValidationException(Map.of("customSchedule","本金不能早于末期结清"));fixed.add(new InstallmentDraft(d.installmentNo(),d.dueOn(),d.principalCents(),d.interestCents(),remaining,d.principalAmount().setScale(12),d.interestAmount().setScale(12),BigDecimal.ZERO.setScale(12),"CUSTOM_CONTRACT_V1",DecimalMoney.fromCents(remaining+d.principalCents()).setScale(12),d.interestAmount().setScale(12)));}return fixed;}
    private Asset resolveAsset(long h,Long id,LoanType type,Map<String,String> f){if(id==null)return null;Asset a=assets.findCurrent(id,h).filter(x->!x.isArchived()).orElse(null);if(a==null){f.put("linkedAssetId","关联资产必须属于当前家庭且未归档");return null;}if((a.getType()==AssetType.PROPERTY&&type!=LoanType.MORTGAGE)||(a.getType()==AssetType.VEHICLE&&type!=LoanType.CAR)||(a.getType()==AssetType.OTHER&&type!=LoanType.OTHER))f.put("linkedAssetId","贷款类型与关联资产类型不兼容");return a;}
    private FamilyMember resolveMember(long h,Long id,Map<String,String> f){if(id==null)return null;return members.findByIdAndHouseholdId(id,h).orElseGet(()->{f.put("memberId","成员必须属于当前家庭");return null;});}
    private AppUser resolveUser(long h,Long id,AppUser fallback){if(id==null)return fallback;return users.findByIdAndHouseholdIdAndStatus(id,h,AppUserStatus.ACTIVE).orElseThrow(()->new RequestValidationException(Map.of("assignedUserId","指派用户必须属于当前家庭且有效")));}
    private static String required(String v,String field){return required(v,field,new LinkedHashMap<>());} private static String required(String v,String field,Map<String,String> f){String n=v==null?"":v.trim();if(n.isEmpty()||n.length()>100)f.put(field,"名称不能为空且不超过 100 个字符");return n;}
    private static long parseMoney(String raw,String field,Map<String,String> f){try{return Money.parseCents(raw);}catch(IllegalArgumentException e){f.put(field,e.getMessage());return 0;}}
    private static boolean hasContractField(LoanPatchRequest r){return r.principal()!=null||r.annualRate()!=null||r.termMonths()!=null||r.repaymentMethod()!=null||r.startOn()!=null||r.customSchedule()!=null||r.accountingOn()!=null||r.disbursementAccountId()!=null;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<LoanPrepaymentResponse> prepaymentHistory(Authentication a,long id){long h=current.require(a).householdId();Loan loan=find(h,id);var summary=totals.read(loan,false);return prepayments.findAllByLoanIdAndHouseholdIdOrderById(id,h).stream().map(p->LoanPrepaymentResponse.from(p,loan,summary)).toList();}
    private LoanResponse response(Loan loan,boolean current){return LoanResponse.from(loan,totals.read(loan,current));}
    private Loan locked(long h,long id){return loans.findLockedByIdAndHouseholdId(id,h).orElseThrow(()->new ResourceNotFoundException("贷款不存在"));}
    private Loan find(long h,long id){return loans.findByIdAndHouseholdId(id,h).orElseThrow(()->new ResourceNotFoundException("贷款不存在"));}
    private record Values(String name,LoanType type,Asset asset,FamilyMember member,AppUser user,FinancialAccount account,Category category,long principal,BigDecimal rate,int term,RepaymentMethod method,LocalDate start,List<InstallmentDraft> schedule){}
}
