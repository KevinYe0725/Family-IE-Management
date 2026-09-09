package com.familyfinance.investment;

import static com.familyfinance.investment.InvestmentPlanDtos.*;

import com.familyfinance.accounting.AccountingRequests;
import com.familyfinance.accounting.CashAccountingService;
import com.familyfinance.family.*;
import com.familyfinance.ledger.FinancialAccountRepository;
import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.shared.ResourceNotFoundException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Plans and occurrence snapshots never post money. Only explicit confirmation delegates to trade accounting. */
@Service
@Transactional(readOnly=true)
public class InvestmentPlanService {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final String REF="INVESTMENT_PLAN_OCCURRENCE";
    private static final String PLAN_SELECT="select p.*,s.market as security_market,s.ts_code as security_code,s.name as catalog_name,s.security_type,s.active as security_active,s.currency as security_currency,coalesce(s.symbol,p.symbol) as catalog_symbol,coalesce(s.exchange_name,s.market) as security_exchange,coalesce(s.timezone,'Asia/Shanghai') as security_timezone from investment_plans p join securities s on s.id=p.security_id ";
    private final JdbcTemplate jdbc;
    private final CurrentMembership current;
    private final FamilyMutationAuthorization authorization;
    private final FamilyLockService locks;
    private final InvestmentAccountService accounts;
    private final FinancialAccountRepository funding;
    private final SecurityRepository securities;
    private final HouseholdMembershipRepository memberships;
    private final CashAccountingService cash;
    private final InvestmentTradeService trades;
    private final AccountingRequests requests;
    private final Clock clock;

    public InvestmentPlanService(JdbcTemplate jdbc, CurrentMembership current, FamilyMutationAuthorization authorization,
            FamilyLockService locks, InvestmentAccountService accounts, FinancialAccountRepository funding,
            SecurityRepository securities, HouseholdMembershipRepository memberships, CashAccountingService cash,
            InvestmentTradeService trades, AccountingRequests requests, Clock clock) {
        this.jdbc=jdbc;this.current=current;this.authorization=authorization;this.locks=locks;this.accounts=accounts;
        this.funding=funding;this.securities=securities;this.memberships=memberships;this.cash=cash;
        this.trades=trades;this.requests=requests;this.clock=clock;
    }

    public Page list(Authentication auth,int planPage,int occurrencePage,int size) {
        long h=current.require(auth).householdId();int limit=Math.min(100,Math.max(1,size));
        long po=(long)Math.max(0,planPage)*limit,oo=(long)Math.max(0,occurrencePage)*limit;
        var plans=jdbc.query(PLAN_SELECT+"where p.household_id=? order by case p.state when 'ACTIVE' then 0 when 'PAUSED' then 1 else 2 end,p.next_due_on,p.id limit ? offset ?",this::mapPlan,h,limit+1,po);
        var rows=jdbc.query("select * from investment_plan_occurrences where household_id=? order by case state when 'PENDING' then 0 else 1 end, due_on,id limit ? offset ?",this::mapOccurrence,h,limit+1,oo);
        boolean morePlans=plans.size()>limit,moreOccurrences=rows.size()>limit;
        return new Page(plans.stream().limit(limit).toList(),rows.stream().limit(limit).map(o->details(auth,o)).toList(),
                morePlans,moreOccurrences,jdbc.queryForObject("select count(*) from investment_plan_occurrences where household_id=? and state='PENDING'",Long.class,h));
    }

    @Transactional
    public Plan create(Authentication auth,Request request,String key) {
        var access=authorization.requireAdmin(auth);long h=access.context().householdId(),actor=access.context().userId();
        String digest=requests.digest("INVESTMENT_PLAN_CREATE",actor,request);Long original=requests.replay(h,key,digest);
        if(original!=null)return currentPlan(h,original);
        Validated v=validate(h,request);Timestamp now=Timestamp.from(clock.instant());
        long id=insert("insert into investment_plans(household_id,name,account_id,account_name,funding_account_id,security_id,security_name,symbol,currency,quantity,frequency,first_due_on,next_due_on,assigned_user_id,state,created_by,updated_by,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?,?,?)",
                h,v.name(),v.account().getId(),v.account().getName(),v.account().getFundingAccountId(),v.security().getId(),v.security().getName(),v.security().getSymbol(),v.account().getCurrency(),v.quantity(),request.frequency().name(),request.firstDueOn(),request.firstDueOn(),request.assignedUserId(),actor,actor,now,now);
        requests.record(h,key,digest,id);generateHousehold(h);return currentPlan(h,id);
    }

    @Transactional
    public Plan update(Authentication auth,long id,Request request) {
        var access=authorization.requireAdmin(auth);long h=access.context().householdId();Plan old=currentPlan(h,id);
        if(old.state()==State.ENDED)throw conflict("PLAN_ENDED","已结束的计划不能编辑");
        Validated v=validate(h,request);
        LocalDate next=onOrAfter(request.firstDueOn(),request.frequency(),today());
        jdbc.update("update investment_plans set name=?,account_id=?,account_name=?,funding_account_id=?,security_id=?,security_name=?,symbol=?,currency=?,quantity=?,amount=null,frequency=?,first_due_on=?,next_due_on=?,assigned_user_id=?,updated_by=?,updated_at=? where household_id=? and id=?",
                v.name(),v.account().getId(),v.account().getName(),v.account().getFundingAccountId(),v.security().getId(),v.security().getName(),v.security().getSymbol(),v.account().getCurrency(),v.quantity(),request.frequency().name(),request.firstDueOn(),next,request.assignedUserId(),access.context().userId(),Timestamp.from(clock.instant()),h,id);
        return currentPlan(h,id);
    }

    @Transactional
    public Plan state(Authentication auth,long id,StateRequest request) {
        var access=authorization.requireAdmin(auth);long h=access.context().householdId();Plan p=currentPlan(h,id);
        if(request==null||request.state()==null)throw invalid("state","请选择计划状态");
        if(p.state()==State.ENDED&&request.state()!=State.ENDED)throw conflict("PLAN_ENDED","已结束的计划不能重新启动");
        if(request.state()==State.ACTIVE&&p.quantity()==null)throw conflict("INVESTMENT_PLAN_QUANTITY_REQUIRED","旧金额计划需先编辑并填写每期股数，再恢复执行");
        LocalDate next=p.nextDueOn();
        if(p.state()==State.PAUSED&&request.state()==State.ACTIVE) next=onOrAfter(p.firstDueOn(),p.frequency(),today());
        jdbc.update("update investment_plans set state=?,next_due_on=?,updated_by=?,updated_at=? where household_id=? and id=?",
                request.state().name(),next,access.context().userId(),Timestamp.from(clock.instant()),h,id);
        return currentPlan(h,id);
    }

    @Transactional
    public Occurrence confirm(Authentication auth,long id,Confirmation request) {
        var access=authorization.requireAdmin(auth);long h=access.context().householdId();Occurrence o=currentOccurrence(h,id);
        // The durable occurrence link is the idempotency identity, independent of the caller's request key.
        if(o.state().equals("CONFIRMED"))return currentDetails(h,o);
        requirePending(o);
        if(request==null)throw invalid("request","请填写实际成交内容");
        var account=accounts.findCurrent(h,o.accountId());
        if(!Objects.equals(account.getFundingAccountId(),o.fundingAccountId()))throw conflict("INVESTMENT_PLAN_FUNDING_CHANGED","关联资金账户已变更，请核对本期记录；可在交易页面手工记录实际成交");
        var trade=trades.create(auth,new InvestmentTradeRequest(o.accountId(),o.securityId(),null,null,
                InvestmentTradeType.BUY,request.quantity(),request.price(),request.fee(),request.tradedOn(),null,null,null),
                "investment-plan-occurrence:"+id).trade();
        BigDecimal paid=new BigDecimal(trade.cashImpact()).negate();
        jdbc.update("update investment_plan_occurrences set state='CONFIRMED',trade_id=?,actual_quantity=?,actual_amount=?,acted_by=?,acted_at=?,notification_pending=false where household_id=? and id=?",
                trade.id(),trade.quantity(),paid,access.context().userId(),Timestamp.from(clock.instant()),h,id);
        resolve(h,id);return currentDetails(h,currentOccurrence(h,id));
    }

    @Transactional
    public Occurrence skip(Authentication auth,long id,SkipRequest request) {
        var access=authorization.requireAdmin(auth);long h=access.context().householdId();Occurrence o=currentOccurrence(h,id);
        if(o.state().equals("SKIPPED"))return o;
        requirePending(o);String reason=request==null||request.reason()==null?"":request.reason().trim();
        if(reason.length()>500)throw invalid("reason","原因不能超过500字");
        jdbc.update("update investment_plan_occurrences set state='SKIPPED',reason=?,acted_by=?,acted_at=?,notification_pending=false where household_id=? and id=?",reason,access.context().userId(),Timestamp.from(clock.instant()),h,id);
        resolve(h,id);return currentOccurrence(h,id);
    }

    @Transactional
    public Occurrence snooze(Authentication auth,long id,SnoozeRequest request) {
        var access=authorization.requireAdmin(auth);long h=access.context().householdId();Occurrence o=currentOccurrence(h,id);requirePending(o);
        if(request==null||request.option()==null)throw invalid("option","请选择稍后提醒时间");
        Instant at=request.option()==Snooze.TWO_HOURS?clock.instant().plus(2,ChronoUnit.HOURS):today().plusDays(1).atTime(9,0).atZone(ZONE).toInstant();
        jdbc.update("update investment_plan_occurrences set remind_at=?,notification_pending=true,snoozed_by=?,snoozed_at=? where household_id=? and id=?",Timestamp.from(at),access.context().userId(),Timestamp.from(clock.instant()),h,id);
        resolve(h,id);return currentOccurrence(h,id);
    }

    @Transactional
    public int generate(Authentication auth) {return generateHousehold(authorization.requireAdmin(auth).context().householdId());}

    /** Each scheduler call is its own transaction, serialized with confirmation and plan edits by the household lock. */
    @Transactional
    public int generateHousehold(long h) {
        locks.lockActiveHousehold(h);int made=0,processed=0;
        var due=jdbc.query(PLAN_SELECT+"where p.household_id=? and p.state='ACTIVE' and p.quantity is not null and p.next_due_on<=? order by p.next_due_on,p.id limit 100 for update",this::mapPlan,h,today());
        for(Plan p:due) {
            LocalDate day=p.nextDueOn();
            while(!day.isAfter(today())&&processed<100) {
                processed++;
                if(jdbc.queryForList("select id from investment_plan_occurrences where plan_id=? and due_on=? for update",Long.class,p.id(),day).isEmpty()) {
                    jdbc.update("insert into investment_plan_occurrences(household_id,plan_id,plan_name,account_id,account_name,funding_account_id,security_id,security_name,symbol,currency,quantity,due_on,assigned_user_id,state,remind_at,notification_pending) values(?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',?,true)",
                            h,p.id(),p.name(),p.accountId(),p.accountName(),p.fundingAccountId(),p.securityId(),p.securityName(),p.symbol(),p.currency(),new BigDecimal(p.quantity()),day,p.assignedUserId(),Timestamp.from(day.atStartOfDay(ZONE).toInstant()));made++;
                }
                day=onOrAfter(p.firstDueOn(),p.frequency(),day.plusDays(1));
            }
            jdbc.update("update investment_plans set next_due_on=? where household_id=? and id=?",day,h,p.id());
            if(processed==100)break;
        }
        // Apply recipient eligibility before the batch limit so suspended users cannot starve other reminders.
        var reminders=jdbc.query("select o.* from investment_plan_occurrences o join household_memberships m on m.household_id=o.household_id and m.user_id=o.assigned_user_id and m.status='ACTIVE' where o.household_id=? and o.state='PENDING' and o.notification_pending=true and o.remind_at<=? order by o.remind_at,o.id limit 100 for update",this::mapOccurrence,h,Timestamp.from(clock.instant()));
        for(Occurrence o:reminders) {
            var member=memberships.findByHouseholdIdAndUserIdAndStatus(h,o.assignedUserId(),MembershipStatus.ACTIVE);
            if(member.isEmpty())continue;
            var existing=jdbc.queryForList("select id from notifications where household_id=? and reference_type=? and reference_id=? and user_id=? for update",Long.class,h,REF,o.id(),o.assignedUserId());
            if(existing.isEmpty()) jdbc.update("insert into notifications(household_id,user_id,type,title,body,reference_type,reference_id,due_at) values(?,?,'INVESTMENT_PLAN_DUE',?,?,?,?,?)",h,o.assignedUserId(),"定投计划到期",o.planName()+"：请在实际买入后确认成交，不会自动扣款",REF,o.id(),Timestamp.from(o.remindAt()));
            else jdbc.update("update notifications set due_at=?,read_at=null,resolved_at=null where id=?",Timestamp.from(o.remindAt()),existing.get(0));
            jdbc.update("update investment_plan_occurrences set notification_pending=false where id=?",o.id());
        }
        return made;
    }

    private Validated validate(long h,Request r) {
        if(r==null)throw invalid("request","请填写计划");
        String name=r.name()==null?"":r.name().trim();if(name.isEmpty()||name.length()>100)throw invalid("name","名称需为1至100字");
        if(r.accountId()==null||r.securityId()==null)throw invalid("accountId","请选择账户和证券");
        if(r.frequency()==null)throw invalid("frequency","请选择频率");
        if(r.firstDueOn()==null||r.firstDueOn().getYear()<1000||r.firstDueOn().getYear()>9998)throw invalid("firstDueOn","请填写有效日期");
        BigDecimal quantity;
        try {if(r.quantity()==null||!r.quantity().trim().matches("\\d{1,15}(\\.\\d{1,4})?"))throw new IllegalArgumentException();quantity=new BigDecimal(r.quantity().trim()).setScale(4);if(quantity.signum()<=0)throw new IllegalArgumentException();}
        catch(IllegalArgumentException e){throw invalid("quantity","每期股数必须大于零，最多15位整数和4位小数");}
        var account=accounts.findCurrent(h,r.accountId());if(account.isArchived())throw conflict("INVESTMENT_ACCOUNT_ARCHIVED","投资账户已归档");
        if(account.getFundingAccountId()==null)throw conflict("INVESTMENT_FUNDING_REQUIRED","请先为投资账户关联资金账户");
        var cashAccount=funding.findLockedByIdAndHouseholdId(account.getFundingAccountId(),h).orElseThrow(()->new ResourceNotFoundException("资金账户不存在"));
        cash.requireConfirmed(cashAccount);
        var security=securities.findById(r.securityId()).orElseThrow(()->new ResourceNotFoundException("证券不存在"));
        if(!security.isActive()||!security.isCatalogVerified())throw conflict("SECURITY_NOT_LISTED","请从股票搜索结果选择证券");
        if(!account.getCurrency().equals(security.getCurrency())||!account.getCurrency().equals(cashAccount.getCurrency()))throw conflict("CURRENCY_MISMATCH","投资账户、资金账户和证券必须同币种");
        if(r.assignedUserId()==null||memberships.findByHouseholdIdAndUserIdAndStatus(h,r.assignedUserId(),MembershipStatus.ACTIVE).isEmpty())throw invalid("assignedUserId","负责人必须为当前家庭有效成员");
        return new Validated(name,quantity,account,security);
    }
    private record Validated(String name,BigDecimal quantity,InvestmentAccount account,Security security) {}

    static LocalDate onOrAfter(LocalDate anchor,Frequency frequency,LocalDate minimum) {
        if(!anchor.isBefore(minimum))return anchor;
        if(frequency==Frequency.MONTHLY) {
            long months=ChronoUnit.MONTHS.between(YearMonth.from(anchor),YearMonth.from(minimum));
            LocalDate candidate=anchor.plusMonths(months);return candidate.isBefore(minimum)?anchor.plusMonths(months+1):candidate;
        }
        long step=frequency==Frequency.WEEKLY?7:14,days=ChronoUnit.DAYS.between(anchor,minimum);
        return anchor.plusDays(((days+step-1)/step)*step);
    }
    private LocalDate today(){return LocalDate.now(clock.withZone(ZONE));}
    private void requirePending(Occurrence o){if(!o.state().equals("PENDING"))throw conflict("OCCURRENCE_COMPLETED","本期已处理");}
    private void resolve(long h,long id){jdbc.update("update notifications set resolved_at=? where household_id=? and reference_type=? and reference_id=? and resolved_at is null",Timestamp.from(clock.instant()),h,REF,id);}
    // A household lock serializes writers but does not reset a MySQL REPEATABLE READ snapshot.
    // Every mutation decision and replay must therefore hydrate current rows with locking reads.
    private Plan currentPlan(long h,long id){return jdbc.query(PLAN_SELECT+"where p.household_id=? and p.id=? for update",this::mapPlan,h,id).stream().findFirst().orElseThrow(()->new ResourceNotFoundException("定投计划不存在"));}
    private Occurrence currentOccurrence(long h,long id){return jdbc.query("select * from investment_plan_occurrences where household_id=? and id=? for update",this::mapOccurrence,h,id).stream().findFirst().orElseThrow(()->new ResourceNotFoundException("定投待办不存在"));}
    private Occurrence details(Authentication auth,Occurrence o){
        if(o.tradeId()==null)return o;
        long h=current.require(auth).householdId();
        InvestmentTradeResponse trade=jdbc.queryForObject("select count(*) from investment_trades where household_id=? and id=?",Long.class,h,o.tradeId())==0?null:trades.get(auth,o.tradeId());
        return withTrade(o,trade);
    }
    private Occurrence currentDetails(long h,Occurrence o){return o.tradeId()==null?o:withTrade(o,trades.currentIfPresent(h,o.tradeId()));}
    private Occurrence withTrade(Occurrence o,InvestmentTradeResponse trade){
        return new Occurrence(o.id(),o.planId(),o.planName(),o.accountId(),o.accountName(),o.fundingAccountId(),o.securityId(),o.securityName(),o.symbol(),o.currency(),o.quantity(),o.amount(),o.dueOn(),o.assignedUserId(),o.state(),o.remindAt(),o.tradeId(),o.actualQuantity(),o.actualAmount(),o.reason(),o.actedBy(),o.actedAt(),trade==null,trade);
    }
    private Plan mapPlan(ResultSet r,int n)throws SQLException{
        SecurityResponse security=new SecurityResponse(r.getLong("security_id"),r.getString("security_market"),r.getString("security_code"),r.getString("catalog_name"),r.getString("security_type"),r.getBoolean("security_active"),r.getString("security_currency"),r.getString("catalog_symbol"),r.getString("security_exchange"),r.getString("security_timezone"));
        return new Plan(r.getLong("id"),r.getString("name"),r.getLong("account_id"),r.getString("account_name"),r.getLong("funding_account_id"),r.getLong("security_id"),r.getString("security_name"),r.getString("symbol"),r.getString("currency"),decimal(r,"quantity"),decimal(r,"amount"),Frequency.valueOf(r.getString("frequency")),r.getObject("first_due_on",LocalDate.class),r.getObject("next_due_on",LocalDate.class),r.getLong("assigned_user_id"),State.valueOf(r.getString("state")),security);
    }
    private Occurrence mapOccurrence(ResultSet r,int n)throws SQLException{return new Occurrence(r.getLong("id"),r.getLong("plan_id"),r.getString("plan_name"),r.getLong("account_id"),r.getString("account_name"),r.getLong("funding_account_id"),r.getLong("security_id"),r.getString("security_name"),r.getString("symbol"),r.getString("currency"),decimal(r,"quantity"),decimal(r,"amount"),r.getObject("due_on",LocalDate.class),r.getLong("assigned_user_id"),r.getString("state"),r.getTimestamp("remind_at").toInstant(),r.getObject("trade_id",Long.class),decimal(r,"actual_quantity"),decimal(r,"actual_amount"),r.getString("reason"),r.getObject("acted_by",Long.class),r.getTimestamp("acted_at")==null?null:r.getTimestamp("acted_at").toInstant(),false,null);}
    private static String decimal(ResultSet r,String column)throws SQLException{BigDecimal value=r.getBigDecimal(column);return value==null?null:value.toPlainString();}
    private long insert(String sql,Object...args){var keys=new GeneratedKeyHolder();jdbc.update(connection->{var statement=connection.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);return statement;},keys);return Objects.requireNonNull(keys.getKey()).longValue();}
    private static RequestValidationException invalid(String field,String message){return new RequestValidationException(Map.of(field,message));}
    private static ResourceConflictException conflict(String code,String message){return new ResourceConflictException(code,message);}
}
