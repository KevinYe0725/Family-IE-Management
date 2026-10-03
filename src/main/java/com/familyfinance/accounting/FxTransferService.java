package com.familyfinance.accounting;

import static com.familyfinance.accounting.LedgerAccountKind.*;
import com.familyfinance.family.CurrentMembership;
import com.familyfinance.family.FamilyMutationAuthorization;
import com.familyfinance.ledger.FinancialAccount;
import com.familyfinance.ledger.FinancialAccountRepository;
import com.familyfinance.shared.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Manual record of an actual exchange, never a broker/bank execution. */
@Service @Transactional(readOnly=true)
public class FxTransferService {
    private final FamilyMutationAuthorization authorization;
    private final CurrentMembership membership;
    private final FinancialAccountRepository accounts;
    private final CashAccountingService cash;
    private final LedgerPostingService posting;
    private final AccountingRequests requests;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final MultiCurrencyPolicy policy;
    public FxTransferService(FamilyMutationAuthorization authorization,CurrentMembership membership,FinancialAccountRepository accounts,
            CashAccountingService cash,LedgerPostingService posting,AccountingRequests requests,JdbcTemplate jdbc,Clock clock,MultiCurrencyPolicy policy){
        this.authorization=authorization;this.membership=membership;this.accounts=accounts;this.cash=cash;this.posting=posting;
        this.requests=requests;this.jdbc=jdbc;this.clock=clock;this.policy=policy;
    }
    public record Page(List<FxTransferResponse> items,int page,int size,long totalElements,long totalPages,boolean hasNext){}
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Page list(Authentication auth,int page,int size){
        long h=membership.require(auth).householdId();int p=Math.max(0,page),limit=Math.max(1,Math.min(50,size));
        long total=jdbc.queryForObject("select count(*) from fx_transfers where household_id=?",Long.class,h);
        var items=jdbc.query("select * from fx_transfers where household_id=? order by occurred_on desc,id desc limit ? offset ?",FxTransferService::row,h,limit,(long)p*limit);
        return new Page(items,p,limit,total,(total+limit-1)/limit,(long)(p+1)*limit<total);
    }
    @Transactional
    public FxTransferResponse save(Authentication auth,Long id,FxTransferRequest request){
        policy.requireEnabled();var access=authorization.requireAdmin(auth);long h=access.context().householdId(),actor=access.context().userId();
        String key=AccountingRequests.key(request.idempotencyKey());
        String digest=requests.digest(id==null?"FX_CREATE":"FX_UPDATE:"+id,actor,request);
        Long replay=requests.replay(h,key,digest);if(replay!=null)return find(h,replay);
        FxTransferResponse old=id==null?null:find(h,id);
        if(old!=null){requireRevision(old,request.expectedRevision());cash.requireEditableSourceCash(h,"FX_TRANSFER",id);}
        var from=account(h,request.fromAccountId());var to=account(h,request.toAccountId());
        if(from.getId().equals(to.getId())||from.getCurrency().equals(to.getCurrency()))
            throw invalid("toAccountId","换汇必须选择两个不同币种账户；同币种请用账户互转");
        long debit=positive(request.fromAmount(),"fromAmount"),credit=positive(request.toAmount(),"toAmount");
        long fee=zero(request.fee())?0:positive(request.fee(),"fee");
        long total=Math.addExact(debit,fee);
        LocalDate day=cash.date(request.occurredOn(),"occurredOn");cash.requireConfirmed(from,day);cash.requireConfirmed(to,day);
        long revision=old==null?1:Math.addExact(old.revision(),1);
        if(id==null){
            var keys=new GeneratedKeyHolder();
            jdbc.update(connection->{
                var ps=connection.prepareStatement("insert into fx_transfers(household_id,from_account_id,to_account_id,from_currency,to_currency,from_amount,to_amount,fee,occurred_on,actor_id,recorded_at,revision,reversed) values(?,?,?,?,?,?,?,?,?,?,?,?,false)",new String[]{"id"});
                ps.setLong(1,h);ps.setLong(2,from.getId());ps.setLong(3,to.getId());ps.setString(4,from.getCurrency());ps.setString(5,to.getCurrency());
                ps.setBigDecimal(6,DecimalMoney.fromCents(debit));ps.setBigDecimal(7,DecimalMoney.fromCents(credit));ps.setBigDecimal(8,DecimalMoney.fromCents(fee));
                ps.setObject(9,day);ps.setLong(10,actor);ps.setTimestamp(11,java.sql.Timestamp.from(clock.instant()));ps.setLong(12,revision);return ps;
            },keys);id=keys.getKey().longValue();
        }else jdbc.update("update fx_transfers set from_account_id=?,to_account_id=?,from_currency=?,to_currency=?,from_amount=?,to_amount=?,fee=?,occurred_on=?,actor_id=?,recorded_at=?,revision=? where household_id=? and id=?",
                from.getId(),to.getId(),from.getCurrency(),to.getCurrency(),DecimalMoney.fromCents(debit),DecimalMoney.fromCents(credit),DecimalMoney.fromCents(fee),day,actor,java.sql.Timestamp.from(clock.instant()),revision,h,id);
        List<LedgerEntryInput> entries=new ArrayList<>();
        entries.add(new LedgerEntryInput("CASH:"+from.getId(),CASH,0,total,null,null,from.getCurrency()));
        entries.add(new LedgerEntryInput(LedgerCodes.inCurrency("EQUITY:FX_CLEARING",from.getCurrency()),EQUITY,debit,0,null,null,from.getCurrency()));
        if(fee>0)entries.add(new LedgerEntryInput(LedgerCodes.inCurrency("EXPENSE:FX_FEE",from.getCurrency()),EXPENSE,fee,0,null,null,from.getCurrency()));
        entries.add(new LedgerEntryInput("CASH:"+to.getId(),CASH,credit,0,null,null,to.getCurrency()));
        entries.add(new LedgerEntryInput(LedgerCodes.inCurrency("EQUITY:FX_CLEARING",to.getCurrency()),EQUITY,0,credit,null,null,to.getCurrency()));
        var command=new LedgerPostingCommand(h,"FX_TRANSFER",id,key,day,actor,entries);
        if(old==null)posting.post(command);else posting.replace(command);
        revision(id,h,old==null?"POST":"REPLACE");requests.record(h,key,digest,id);return find(h,id);
    }
    @Transactional
    public FxTransferResponse reverse(Authentication auth,long id,long expectedRevision,String suppliedKey){
        policy.requireEnabled();var access=authorization.requireAdmin(auth);long h=access.context().householdId(),actor=access.context().userId();
        String key=AccountingRequests.key(suppliedKey),digest=requests.digest("FX_REVERSE:"+id,actor,expectedRevision);
        Long replay=requests.replay(h,key,digest);if(replay!=null)return find(h,replay);
        var old=find(h,id);requireRevision(old,expectedRevision);cash.requireEditableSourceCash(h,"FX_TRANSFER",id);
        posting.reverse(h,"FX_TRANSFER",id,key,actor);
        jdbc.update("update fx_transfers set reversed=true,revision=?,actor_id=?,recorded_at=? where household_id=? and id=?",Math.addExact(old.revision(),1),actor,java.sql.Timestamp.from(clock.instant()),h,id);
        revision(id,h,"REVERSE");requests.record(h,key,digest,id);return find(h,id);
    }
    private void revision(long id,long h,String operation){
        jdbc.update("""
            insert into fx_transfer_revisions(transfer_id,revision,operation,from_account_id,to_account_id,from_currency,to_currency,from_amount,to_amount,fee,occurred_on,actor_id,recorded_at)
            select id,revision,?,from_account_id,to_account_id,from_currency,to_currency,from_amount,to_amount,fee,occurred_on,actor_id,recorded_at from fx_transfers where household_id=? and id=?
            """,operation,h,id);
    }
    private FxTransferResponse find(long h,long id){return jdbc.query("select * from fx_transfers where household_id=? and id=? for update",FxTransferService::row,h,id).stream().findFirst().orElseThrow(()->new ResourceNotFoundException("换汇记录不存在"));}
    private FinancialAccount account(long h,Long id){if(id==null)throw invalid("accountId","请选择账户");return accounts.findLockedByIdAndHouseholdId(id,h).filter(a->!a.isArchived()).orElseThrow(()->new ResourceNotFoundException("账户不存在"));}
    private static void requireRevision(FxTransferResponse row,Long revision){
        if(row.reversed())throw new ResourceConflictException("FX_TRANSFER_REVERSED","该换汇已冲销");
        if(revision==null||row.revision()!=revision)throw new ResourceConflictException("FX_REVISION_CHANGED","换汇记录已变化，请刷新后核对");
    }
    private static long positive(String value,String field){try{return Money.parseCents(value);}catch(IllegalArgumentException e){throw invalid(field,e.getMessage());}}
    private static boolean zero(String raw){return raw==null||raw.isBlank()||raw.trim().matches("0+(\\.0{1,2})?");}
    private static RequestValidationException invalid(String field,String message){return new RequestValidationException(Map.of(field,message));}
    private static FxTransferResponse row(java.sql.ResultSet rs,int ignored)throws java.sql.SQLException{
        BigDecimal from=rs.getBigDecimal("from_amount"),to=rs.getBigDecimal("to_amount");
        return new FxTransferResponse(rs.getLong("id"),rs.getLong("from_account_id"),rs.getLong("to_account_id"),rs.getString("from_currency"),rs.getString("to_currency"),
                from.toPlainString(),to.toPlainString(),rs.getBigDecimal("fee").toPlainString(),to.divide(from,12,RoundingMode.HALF_UP).toPlainString(),
                rs.getObject("occurred_on",LocalDate.class),rs.getLong("revision"),rs.getBoolean("reversed"));
    }
}
