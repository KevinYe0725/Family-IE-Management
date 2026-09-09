package com.familyfinance.accounting;

import static com.familyfinance.accounting.LedgerAccountKind.*;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.ledger.FinancialAccount;
import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.transaction.FinancialTransaction;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CashAccountingService {
    private final LedgerPostingService posting;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public CashAccountingService(LedgerPostingService posting,JdbcTemplate jdbc,Clock clock) {
        this.posting=posting; this.jdbc=jdbc; this.clock=clock;
    }
    public void requireConfirmed(FinancialAccount account) {
        confirmedState(account.getHousehold().getId(),account.getId());
    }
    /** New cash activity must occur on or after the start-of-day opening snapshot. */
    public void requireConfirmed(FinancialAccount account,LocalDate occurredOn) {
        requireConfirmed(account.getHousehold().getId(),account.getId(),occurredOn);
    }
    private void requireConfirmed(long household,long account,LocalDate occurredOn) {
        CashState state=confirmedState(household,account);
        LocalDate day=date(occurredOn==null?null:occurredOn.toString(),"occurredOn");
        if(day.isBefore(state.openingOn()))
            throw new ResourceConflictException("ACCOUNT_ACTIVITY_BEFORE_OPENING", "现金业务日期不能早于账户开账日期 "+state.openingOn());
    }
    private CashState confirmedState(long household,long account) {
        lockHousehold(household);
        // Current read also protects callers whose account association came from an earlier JPA snapshot.
        var states=jdbc.query("select opening_confirmed,archived_at,opening_on from financial_accounts where household_id=? and id=? for update",
            (rs,n)->new CashState(rs.getBoolean(1),rs.getTimestamp(2)!=null,rs.getObject(3,LocalDate.class)),household,account);
        if(states.isEmpty()||!states.get(0).confirmed()||states.get(0).openingOn()==null)
            throw new ResourceConflictException("ACCOUNTING_NOT_INITIALIZED", "账户尚未确认期初余额和日期");
        if(states.get(0).archived()) throw new ResourceConflictException("ACCOUNT_ARCHIVED", "账户已归档");
        return states.get(0);
    }
    private record CashState(boolean confirmed,boolean archived,LocalDate openingOn) {}

    /** Before reversing/replacing a source, validate every ORIGINAL cash leg against current account state. */
    public void requireEditableSourceCash(long household,String sourceType,long sourceId) {
        lockHousehold(household);
        var legs=jdbc.query("""
            select e.account_code,j.effective_on from ledger_sources s
            join ledger_journals j on j.household_id=s.household_id and j.id=s.current_journal_id
            join ledger_entries e on e.household_id=j.household_id and e.journal_id=j.id
            where s.household_id=? and s.source_type=? and s.source_id=? and e.account_code like 'CASH:%'
            order by e.account_code for update
            """,(rs,n)->new CashLeg(Long.parseLong(rs.getString(1).substring(5)),rs.getObject(2,LocalDate.class)),household,sourceType,sourceId);
        legs.forEach(leg->requireConfirmed(household,leg.account(),leg.day()));
    }
    private record CashLeg(long account,LocalDate day) {}
    public long currentBalance(long household,long account) {
        lockHousehold(household);
        var balances=jdbc.query("select balance_cents from ledger_accounts where household_id=? and account_code=? for update",(rs,n)->rs.getLong(1),household,"CASH:"+account);
        return balances.isEmpty()?0:balances.get(0);
    }
    private void lockHousehold(long household) {
        var states=jdbc.queryForList("select status from households where id=? for update",String.class,household);
        if(states.isEmpty()||!"ACTIVE".equals(states.get(0))) throw new ResourceConflictException("HOUSEHOLD_ARCHIVED","家庭不可进行资金操作");
    }
    public LocalDate date(String raw,String field) {
        try {
            LocalDate day=LocalDate.parse(raw);
            if(day.getYear()<1000 || day.isAfter(LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))))) throw new IllegalArgumentException();
            return day;
        } catch(RuntimeException e) { throw new RequestValidationException(Map.of(field,"日期必须是有效日期且不晚于上海今天")); }
    }
    public void opening(FinancialAccount account,long amount,LocalDate day,long actor,String key) {
        if(amount<0) throw new RequestValidationException(Map.of("openingBalance","期初余额不能为负"));
        date(day.toString(),"openingOn");
        long h=account.getHousehold().getId();
        lockHousehold(h);
        var earlier=jdbc.queryForList("""
            select j.id from ledger_sources s
            join ledger_journals j on j.household_id=s.household_id and j.id=s.current_journal_id
            join ledger_entries e on e.household_id=j.household_id and e.journal_id=j.id
            where s.household_id=? and e.account_code=? and j.source_type<>'CASH_OPENING'
              and j.effective_on<? for update
            """,Long.class,h,"CASH:"+account.getId(),day);
        if(!earlier.isEmpty()) throw new ResourceConflictException("OPENING_DATE_AFTER_ACTIVITY", "开账日期不能晚于已有有效现金业务日期");
        GeneratedKeyHolder holder=new GeneratedKeyHolder();
        jdbc.update(connection->{
            var s=connection.prepareStatement("insert into cash_opening_events(household_id,account_id,amount_cents,opening_on,actor_id,recorded_at) values(?,?,?,?,?,?)",java.sql.Statement.RETURN_GENERATED_KEYS);
            s.setLong(1,h); s.setLong(2,account.getId()); s.setLong(3,amount); s.setObject(4,day); s.setLong(5,actor); s.setTimestamp(6,java.sql.Timestamp.from(clock.instant())); return s;
        },holder);
        long event=holder.getKey().longValue();
        Long source=account.getOpeningSourceId();
        if(amount==0 && source!=null) { posting.reverse(h,"CASH_OPENING",source,key,actor); source=null; }
        else if(amount>0) {
            var command=new LedgerPostingCommand(h,"CASH_OPENING",source==null?event:source,key,day,actor,List.of(
                new LedgerEntryInput("CASH:"+account.getId(),CASH,amount,0,null,null,account.getCurrency()),
                new LedgerEntryInput(LedgerCodes.inCurrency("EQUITY:OPENING",account.getCurrency()),EQUITY,0,amount,null,null,account.getCurrency())));
            if(source==null) { posting.post(command); source=event; } else posting.replace(command);
        }
        account.confirmOpening(amount,day,source);
    }
    public LedgerReceipt postTransaction(FinancialTransaction tx,String key) {
        requireConfirmed(tx.getAccount(),tx.getOccurredOn());
        return posting.post(command(tx,key,tx.getCreatedByUser().getId()));
    }
    public LedgerReceipt replaceTransaction(FinancialTransaction tx,String key,long actorId) {
        requireEditableSourceCash(tx.getHousehold().getId(),"TRANSACTION",tx.getId());
        requireConfirmed(tx.getAccount(),tx.getOccurredOn());
        return posting.replace(command(tx,key,actorId));
    }
    private LedgerPostingCommand command(FinancialTransaction tx,String key,long actorId) {
        if(tx.getSourceType()!=com.familyfinance.transaction.TransactionSourceType.MANUAL && tx.getSourceType()!=com.familyfinance.transaction.TransactionSourceType.RECURRING)
            throw new IllegalArgumentException("Loan transactions require their principal/interest adapter");
        boolean income=tx.getKind()==TransactionKind.INCOME;
        String currency=tx.getAccount().getCurrency();
        if(tx.getSourceType()==com.familyfinance.transaction.TransactionSourceType.RECURRING&&!currency.equals("CNY"))
            throw new RequestValidationException(Map.of("accountId","周期账单仅支持人民币账户"));
        long amount=tx.getAmountCents(); long category=tx.getCategory().getId(); Long member=tx.getMember()==null?null:tx.getMember().getId();
        return new LedgerPostingCommand(tx.getHousehold().getId(),"TRANSACTION",tx.getId(),key,tx.getOccurredOn(),actorId,List.of(
            new LedgerEntryInput("CASH:"+tx.getAccount().getId(),CASH,income?amount:0,income?0:amount,null,member,currency),
            new LedgerEntryInput(LedgerCodes.inCurrency((income?"INCOME:":"EXPENSE:")+category,currency),income?INCOME:EXPENSE,income?0:amount,income?amount:0,category,member,currency)));
    }
}
