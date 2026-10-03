package com.familyfinance.accounting;

import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.shared.ResourceNotFoundException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

/** Mutations use current reads under the household lock; display reads opt into nonlocking snapshots. */
@Component
class LedgerStore {
    final JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired private com.familyfinance.fx.FxJournalRates fx;
    LedgerStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    void lock(long householdId) {
        var states=jdbc.queryForList("select status from households where id=? for update",String.class,householdId);
        if (states.isEmpty()) throw new ResourceNotFoundException("家庭不存在");
        if (!"ACTIVE".equals(states.get(0))) throw conflict("HOUSEHOLD_ARCHIVED","家庭已归档");
    }

    static ResourceConflictException conflict(String code,String message) {
        return new ResourceConflictException(code,message);
    }

    record Journal(LedgerReceipt receipt,String digest) {}
    private static final RowMapper<Journal> JOURNAL=(rs,row)->new Journal(new LedgerReceipt(
            rs.getLong("id"),rs.getLong("household_id"),rs.getString("source_type"),rs.getLong("source_id"),
            rs.getLong("revision"),rs.getDate("effective_on").toLocalDate(),rs.getObject("reverses_journal_id",Long.class)),rs.getString("request_digest"));

    Optional<Journal> request(long h,String key) {
        return jdbc.query("select * from ledger_journals where household_id=? and request_key=? for update",JOURNAL,h,key).stream().findFirst();
    }

    Journal journal(long h,long id) {
        return journal(h,id,true);
    }
    Journal journal(long h,long id,boolean current) {
        return jdbc.query("select * from ledger_journals where household_id=? and id=?"+lockClause(current),JOURNAL,h,id).stream()
                .findFirst().orElseThrow(()->new ResourceNotFoundException("账务凭证不存在"));
    }

    record Source(long revision,Long currentJournalId) {}
    Optional<Source> source(long h,String type,long id) {
        return jdbc.query("select revision,current_journal_id from ledger_sources where household_id=? and source_type=? and source_id=? for update",
                (rs,row)->new Source(rs.getLong(1),rs.getObject(2,Long.class)),h,type,id).stream().findFirst();
    }

    List<LedgerEntryInput> entries(long h,long journalId) {
        return entries(h,journalId,true);
    }
    List<LedgerEntryInput> entries(long h,long journalId,boolean current) {
        return jdbc.query("select e.*,a.kind from ledger_entries e join ledger_accounts a on a.household_id=e.household_id and a.account_code=e.account_code where e.household_id=? and e.journal_id=? order by e.line_no"+lockClause(current),
                (rs,row)->new LedgerEntryInput(rs.getString("account_code"),LedgerAccountKind.valueOf(rs.getString("kind")),rs.getBigDecimal("debit_amount"),rs.getBigDecimal("credit_amount"),rs.getObject("category_id",Long.class),rs.getObject("member_id",Long.class),rs.getString("currency")),h,journalId);
    }

    record Account(String code,LedgerAccountKind kind,BigDecimal balance,String currency) {}
    List<Account> accounts(long h) {
        return accounts(h,true);
    }
    List<Account> accounts(long h,boolean current) {
        return jdbc.query("select account_code,kind,balance_amount,currency from ledger_accounts where household_id=? order by account_code"+lockClause(current),
                (rs,row)->new Account(rs.getString(1),LedgerAccountKind.valueOf(rs.getString(2)),rs.getBigDecimal(3),rs.getString(4)),h);
    }

    String cashAccountName(long householdId,String code,boolean current) {
        return jdbc.queryForList("select name from financial_accounts where household_id=? and id=?"+lockClause(current),
                String.class,householdId,Long.parseLong(code.substring("CASH:".length())))
                .stream().findFirst().orElse("现金账户");
    }

    record Movement(LocalDate day,BigDecimal debit,BigDecimal credit) {}
    List<Movement> movements(long h,String code) {
        return movements(h,code,true);
    }
    List<Movement> movements(long h,String code,boolean current) {
        // Do not replace with a snapshot SUM: RR transactions may already have an older read view.
        return jdbc.query("select j.effective_on,e.debit_amount,e.credit_amount from ledger_entries e join ledger_journals j on j.id=e.journal_id and j.household_id=e.household_id where e.household_id=? and e.account_code=? order by j.effective_on,e.id"+lockClause(current),
                (rs,row)->new Movement(rs.getDate(1).toLocalDate(),rs.getBigDecimal(2),rs.getBigDecimal(3)),h,code);
    }

    private static String lockClause(boolean current) { return current ? " for update" : ""; }

    LedgerReceipt append(LedgerPostingCommand c,long revision,String operation,String digest,Long reversed,Instant now) {
        var keys=new GeneratedKeyHolder();
        jdbc.update(connection->{
            var ps=connection.prepareStatement("insert into ledger_journals(household_id,source_type,source_id,revision,request_key,request_digest,operation,effective_on,recorded_at,actor_id,reverses_journal_id) values (?,?,?,?,?,?,?,?,?,?,?)",new String[]{"id"});
            ps.setLong(1,c.householdId()); ps.setString(2,c.sourceType()); ps.setLong(3,c.sourceId());
            ps.setLong(4,revision); ps.setString(5,c.idempotencyKey()); ps.setString(6,digest); ps.setString(7,operation);
            ps.setObject(8,c.effectiveOn()); ps.setTimestamp(9,Timestamp.from(now)); ps.setLong(10,c.actorId()); ps.setObject(11,reversed);
            return ps;
        },keys);
        long id=keys.getKey().longValue();
        int line=0;
        for (var e:c.entries()) jdbc.update("insert into ledger_entries(household_id,journal_id,line_no,account_code,debit_amount,credit_amount,category_id,member_id,currency) values (?,?,?,?,?,?,?,?,?)",
                c.householdId(),id,++line,e.accountCode(),e.debitAmount(),e.creditAmount(),e.categoryId(),e.memberId(),e.currency());
        fx.bind(id,c.effectiveOn(),c.entries().stream().map(LedgerEntryInput::currency).toList(),reversed);
        return new LedgerReceipt(id,c.householdId(),c.sourceType(),c.sourceId(),revision,c.effectiveOn(),reversed);
    }

    void pointSource(LedgerPostingCommand c,long revision,Long journalId,boolean exists) {
        if (exists) jdbc.update("update ledger_sources set revision=?,current_journal_id=? where household_id=? and source_type=? and source_id=?",
                revision,journalId,c.householdId(),c.sourceType(),c.sourceId());
        else jdbc.update("insert into ledger_sources(household_id,source_type,source_id,revision,current_journal_id) values (?,?,?,?,?)",
                c.householdId(),c.sourceType(),c.sourceId(),revision,journalId);
    }
}
