package com.familyfinance.accounting;

import static com.familyfinance.accounting.LedgerAccountKind.CASH;
import com.familyfinance.family.CurrentMembership;
import com.familyfinance.family.FamilyMutationAuthorization;
import com.familyfinance.ledger.FinancialAccount;
import com.familyfinance.ledger.FinancialAccountRepository;
import com.familyfinance.shared.Money;
import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.shared.ResourceNotFoundException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class CashTransferService {
    private final FamilyMutationAuthorization authorization;
    private final CurrentMembership membership;
    private final FinancialAccountRepository accounts;
    private final CashAccountingService cash;
    private final LedgerPostingService posting;
    private final AccountingRequests requests;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public CashTransferService(FamilyMutationAuthorization authorization,CurrentMembership membership,FinancialAccountRepository accounts,CashAccountingService cash,LedgerPostingService posting,AccountingRequests requests,JdbcTemplate jdbc,Clock clock) {
        this.authorization=authorization; this.membership=membership; this.accounts=accounts; this.cash=cash; this.posting=posting; this.requests=requests; this.jdbc=jdbc; this.clock=clock;
    }
    public List<CashTransferResponse> list(Authentication authentication,int page,int size) {
        return jdbc.query("select * from cash_transfers where household_id=? order by occurred_on desc,id desc limit ? offset ?",CashTransferService::row,
            membership.require(authentication).householdId(),Math.max(1,Math.min(50,size)),(long)Math.max(0,page)*Math.max(1,Math.min(50,size)));
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public TransferPage page(Authentication authentication,int page,int size) {
        long household=membership.require(authentication).householdId();
        int current=Math.max(0,page),limit=Math.max(1,Math.min(50,size));
        long total=jdbc.queryForObject("select count(*) from cash_transfers where household_id=?",Long.class,household);
        var items=jdbc.query("select * from cash_transfers where household_id=? order by occurred_on desc,id desc limit ? offset ?",CashTransferService::row,household,limit,(long)current*limit);
        long pages=(total+limit-1)/limit;
        return new TransferPage(items,current,limit,total,pages,(long)(current+1)*limit<total);
    }
    public record TransferPage(List<CashTransferResponse> items,int page,int size,long totalElements,long totalPages,boolean hasNext) { }
    @Transactional
    public CashTransferResponse create(Authentication authentication,CashTransferRequest request) {
        var access=authorization.requireAdmin(authentication);
        long h=access.context().householdId();
        String key=AccountingRequests.key(request.idempotencyKey());
        String digest=requests.digest("TRANSFER",access.context().userId(),request);
        Long previous=requests.replay(h,key,digest);
        if(previous!=null) return jdbc.queryForObject("select * from cash_transfers where household_id=? and id=? for update",CashTransferService::row,h,previous);
        FinancialAccount from=account(h,request.fromAccountId()),to=account(h,request.toAccountId());
        if(from.getId().equals(to.getId())) throw new RequestValidationException(Map.of("toAccountId","转出和转入账户必须不同"));
        if(!from.getCurrency().equals(to.getCurrency()))throw new RequestValidationException(Map.of("toAccountId","不同币种请使用记录换汇，不能直接互转"));
        long amount;
        try { amount=Money.parseCents(request.amount()); } catch(IllegalArgumentException e) { throw new RequestValidationException(Map.of("amount",e.getMessage())); }
        var day=cash.date(request.occurredOn(),"occurredOn");
        cash.requireConfirmed(from,day); cash.requireConfirmed(to,day);
        GeneratedKeyHolder holder=new GeneratedKeyHolder();
        jdbc.update(connection->{
            var s=connection.prepareStatement("insert into cash_transfers(household_id,from_account_id,to_account_id,amount_cents,occurred_on,actor_id,recorded_at) values(?,?,?,?,?,?,?)",new String[]{"id"});
            s.setLong(1,h);s.setLong(2,from.getId());s.setLong(3,to.getId());s.setLong(4,amount);s.setObject(5,day);s.setLong(6,access.context().userId());s.setTimestamp(7,java.sql.Timestamp.from(clock.instant()));return s;
        },holder);
        long id=holder.getKey().longValue();
        posting.post(new LedgerPostingCommand(h,"CASH_TRANSFER",id,key,day,access.context().userId(),List.of(
            new LedgerEntryInput("CASH:"+from.getId(),CASH,0,amount,null,null,from.getCurrency()),
            new LedgerEntryInput("CASH:"+to.getId(),CASH,amount,0,null,null,to.getCurrency()))));
        requests.record(h,key,digest,id);
        return new CashTransferResponse(id,from.getId(),to.getId(),Money.formatCents(amount),day,access.context().userId());
    }
    private FinancialAccount account(long h,Long id) {
        if(id==null) throw new RequestValidationException(Map.of("accountId","账户不能为空"));
        return accounts.findLockedByIdAndHouseholdId(id,h).filter(a->!a.isArchived()).orElseThrow(()->new ResourceNotFoundException("账户不存在"));
    }
    private static CashTransferResponse row(java.sql.ResultSet rs,int n) throws java.sql.SQLException {
        return new CashTransferResponse(rs.getLong("id"),rs.getLong("from_account_id"),rs.getLong("to_account_id"),Money.formatCents(rs.getLong("amount_cents")),rs.getDate("occurred_on").toLocalDate(),rs.getLong("actor_id"));
    }
}
