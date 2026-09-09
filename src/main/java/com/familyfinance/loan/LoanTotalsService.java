package com.familyfinance.loan;

import com.familyfinance.shared.DecimalMoney;
import java.math.BigDecimal;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Whole history, not a schedule page or a possibly cached JPA collection. */
@Service
public class LoanTotalsService {
 private static final ZoneId BUSINESS_ZONE=ZoneId.of("Asia/Shanghai");
 private final JdbcTemplate jdbc; private final EntityManager em; private final Clock clock;
 public LoanTotalsService(JdbcTemplate jdbc,EntityManager em,Clock clock){this.jdbc=jdbc;this.em=em;this.clock=clock;}
 public record Totals(String scheduledRepaymentTotal,String remainingRepaymentTotal,String paidRepaymentTotal,PrepaymentStrategy latestStrategy,int remainingTerm,LocalDate maturityOn,LocalDate nextPaymentOn,String nextPaymentAmount,int overdueInstallments,String overdueAmount,int overdueDays){}
 public Totals read(Loan loan,boolean current){
  if(current)em.flush();
  String lock=current?" for update":"";long h=loan.getHousehold().getId(),id=loan.getId();BigDecimal paid=BigDecimal.ZERO,remaining=BigDecimal.ZERO;
  var rows=jdbc.query("select i.status,i.principal_amount,i.interest_amount,cast(t.amount_cents as decimal(21,2))/100,i.due_on from loan_installments i left join financial_transactions t on t.id=i.confirmed_transaction_id and t.household_id=i.household_id where i.household_id=? and i.loan_id=? order by i.installment_no"+lock,(rs,n)->new Row(rs.getString(1),rs.getBigDecimal(2),rs.getBigDecimal(3),rs.getBigDecimal(4),rs.getObject(5,LocalDate.class)),h,id);
  for(var row:rows){if("PENDING".equals(row.status()))remaining=remaining.add(row.principal().add(row.interest()));else if("PAID".equals(row.status()))paid=paid.add(row.cash()==null?row.principal().add(row.interest()):row.cash());}
  // One authoritative cash value per prepayment; never add both event and transaction.
  for(BigDecimal cash:jdbc.query("select coalesce(cast(t.amount_cents as decimal(21,2))/100,p.amount+p.interest_amount) from loan_prepayments p left join financial_transactions t on t.id=p.transaction_id and t.household_id=p.household_id where p.household_id=? and p.loan_id=? order by p.id"+lock,(rs,n)->rs.getBigDecimal(1),h,id))paid=paid.add(cash);
  var pending=rows.stream().filter(row->"PENDING".equals(row.status())).toList();
  // 逾期：未确认且到期日早于今日的待还期次（仅状态计算，不落表）。
  LocalDate today=LocalDate.now(clock.withZone(BUSINESS_ZONE));
  BigDecimal overdue=BigDecimal.ZERO;int overdueInstallments=0;LocalDate oldestDue=null;
  for(var row:rows){if("PENDING".equals(row.status())&&row.dueOn()!=null&&row.dueOn().isBefore(today)){overdueInstallments++;overdue=overdue.add(row.principal().add(row.interest()));if(oldestDue==null||row.dueOn().isBefore(oldestDue))oldestDue=row.dueOn();}}
  int overdueDays=oldestDue==null?0:(int)java.time.temporal.ChronoUnit.DAYS.between(oldestDue,today);
  var strategies=jdbc.queryForList("select strategy from loan_prepayments where household_id=? and loan_id=? and strategy is not null order by id desc limit 1"+lock,String.class,h,id);
  return new Totals(DecimalMoney.format(paid.add(remaining)),DecimalMoney.format(remaining),DecimalMoney.format(paid),strategies.isEmpty()?null:PrepaymentStrategy.valueOf(strategies.get(0)),pending.size(),pending.isEmpty()?null:pending.get(pending.size()-1).dueOn(),pending.isEmpty()?null:pending.get(0).dueOn(),pending.isEmpty()?null:DecimalMoney.format(pending.get(0).principal().add(pending.get(0).interest())),overdueInstallments,DecimalMoney.format(overdue),overdueDays);
 }
 private record Row(String status,BigDecimal principal,BigDecimal interest,BigDecimal cash,LocalDate dueOn){}
}
