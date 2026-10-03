package com.familyfinance.loan;

import com.familyfinance.shared.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Insert-only application repository. No update/delete operation exists for confirmed batches. */
@Repository
public class LoanRepaymentBatchRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;
    public LoanRepaymentBatchRepository(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    public LoanRepaymentBatch append(long household,long actor,String key,Loan loan,LoanRepaymentPreview preview,
            List<LoanRepaymentBatch.Child> children,Instant now){
        var holder=new GeneratedKeyHolder();String snapshot=mapper.writeValueAsString(preview);
        jdbc.update(c->{
            var s=c.prepareStatement("""
                insert into loan_repayment_batches(household_id,loan_id,request_key,actor_id,paid_on,payment_account_id,
                due_principal_amount,due_interest_amount,additional_principal,total_cash_amount,balance_after,
                remaining_principal,loan_status,preview_json,recorded_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,new String[]{"id"});
            s.setLong(1,household);s.setLong(2,loan.getId());s.setString(3,key);s.setLong(4,actor);s.setObject(5,preview.paidOn());s.setLong(6,preview.paymentAccountId());
            s.setBigDecimal(7,new BigDecimal(preview.duePrincipalAmount()));s.setBigDecimal(8,new BigDecimal(preview.dueInterestAmount()));
            s.setBigDecimal(9,new BigDecimal(preview.additionalPrincipal()));s.setBigDecimal(10,new BigDecimal(preview.totalCashAmount()));
            s.setBigDecimal(11,new BigDecimal(preview.balanceAfter()));s.setBigDecimal(12,loan.getCurrentPrincipalAmount());
            s.setString(13,loan.getStatus().name());s.setString(14,snapshot);s.setTimestamp(15,Timestamp.from(now));return s;
        },holder);
        long id=holder.getKey().longValue();int no=0;
        for(var child:children)jdbc.update("insert into loan_repayment_batch_children(batch_id,household_id,child_no,source_type,source_id,transaction_id,principal_amount,interest_amount,cash_amount) values(?,?,?,?,?,?,?,?,?)",
                id,household,++no,child.sourceType(),child.sourceId(),child.transactionId(),new BigDecimal(child.principalAmount()),new BigDecimal(child.interestAmount()),new BigDecimal(child.cashAmount()));
        // Read the stored timestamp precision so first response and replay are byte-for-byte stable.
        return get(household,loan.getId(),id,true);
    }
    public LoanRepaymentBatch get(long household,long loan,long id,boolean current){
        var rows=jdbc.query("select id,loan_id,loan_status,remaining_principal,recorded_at,preview_json from loan_repayment_batches where household_id=? and loan_id=? and id=?"+(current?" for update":""),
                (r,n)->new LoanRepaymentBatch(r.getLong(1),r.getLong(2),LoanStatus.valueOf(r.getString(3)),DecimalMoney.format(r.getBigDecimal(4)),r.getTimestamp(5).toInstant(),mapper.readValue(r.getString(6),LoanRepaymentPreview.class),List.of()),household,loan,id);
        if(rows.isEmpty())throw new ResourceNotFoundException("合并还款记录不存在");var b=rows.get(0);
        var children=jdbc.query("select source_type,source_id,transaction_id,principal_amount,interest_amount,cash_amount from loan_repayment_batch_children where household_id=? and batch_id=? order by child_no"+(current?" for update":""),
                (r,n)->new LoanRepaymentBatch.Child(r.getString(1),r.getLong(2),r.getLong(3),DecimalMoney.format(r.getBigDecimal(4)),DecimalMoney.format(r.getBigDecimal(5)),DecimalMoney.format(r.getBigDecimal(6))),household,id);
        return new LoanRepaymentBatch(b.id(),b.loanId(),b.status(),b.remainingPrincipal(),b.recordedAt(),b.preview(),List.copyOf(children));
    }
    public List<LoanRepaymentBatch> history(long household,long loan){
        return jdbc.queryForList("select id from loan_repayment_batches where household_id=? and loan_id=? order by id",Long.class,household,loan).stream().map(id->get(household,loan,id,false)).toList();
    }
}
