package com.familyfinance.loan;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.LocalDate;
import java.math.BigDecimal;
import com.familyfinance.shared.DecimalMoney;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.familyfinance.shared.ResourceConflictException;

/** Canonical loan plan snapshot. The caller chooses an RR snapshot or household-locked current read. */
@Component
public class LoanPlanToken {
 private final JdbcTemplate jdbc;
 public LoanPlanToken(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public record Period(long id,int installmentNo,LocalDate dueOn,long principalCents,long interestCents,
        BigDecimal precisePrincipalAmount,BigDecimal preciseInterestAmount,BigDecimal interestCarryAmount,String roundingPolicy,BigDecimal customRatePrincipalAmount,BigDecimal customRateInterestAmount){
  public Period(long id,int no,LocalDate date,long principal,long interest){this(id,no,date,principal,interest,null,null,null,null,null,null);}
  public BigDecimal principalAmount(){return DecimalMoney.fromCents(principalCents);}
  public BigDecimal interestAmount(){return DecimalMoney.fromCents(interestCents);}
  public BigDecimal settledPrecisionInterest(){return preciseInterestAmount==null?interestAmount():preciseInterestAmount;}
  public InstallmentDraft draft(long remaining){return new InstallmentDraft(installmentNo,dueOn,principalCents,interestCents,remaining,precisePrincipalAmount,preciseInterestAmount,interestCarryAmount,roundingPolicy,customRatePrincipalAmount,customRateInterestAmount);}
 }
 public List<Period> pending(long household,long loan,boolean current){return jdbc.query("select id,installment_no,due_on,principal_cents,interest_cents,precise_principal_amount,precise_interest_amount,interest_carry_amount,rounding_policy,custom_rate_principal_amount,custom_rate_interest_amount from loan_installments where household_id=? and loan_id=? and status='PENDING' order by installment_no,id"+(current?" for update":""),(r,n)->new Period(r.getLong(1),r.getInt(2),r.getObject(3,LocalDate.class),r.getLong(4),r.getLong(5),r.getBigDecimal(6),r.getBigDecimal(7),r.getBigDecimal(8),r.getString(9),r.getBigDecimal(10),r.getBigDecimal(11)),household,loan);}
 public LoanRoundingContext roundingContext(long household,long loan,boolean current){
  String lock=current?" for update":"";
  var rows=jdbc.query("select i.precise_interest_amount,i.interest_amount,t.loan_interest_cents from loan_installments i left join financial_transactions t on t.id=i.confirmed_transaction_id and t.household_id=i.household_id where i.household_id=? and i.loan_id=? and i.status='PAID' order by i.installment_no"+(current?" for update of i":""),(r,n)->{
   BigDecimal actual=r.getObject(3)==null?r.getBigDecimal(2):DecimalMoney.fromCents(r.getLong(3));
   return new LoanRoundingContext(r.getBigDecimal(1)==null?actual:r.getBigDecimal(1),actual);
  },household,loan);
  LoanRoundingContext result=LoanRoundingContext.ZERO;
  for(var row:rows)result=result.plus(row.preciseInterestPaid(),row.actualInterestPaid());
  for(var interest:jdbc.queryForList("select interest_amount from loan_prepayments where household_id=? and loan_id=? order by id"+lock,BigDecimal.class,household,loan))result=result.plus(interest,interest);
  return result;
 }
 public String token(Loan loan,List<Period> periods,String operation,LocalDate paidOn,long account,long interest,String extra){
  StringBuilder value=new StringBuilder("loan-plan-v1|").append(loan.getHousehold().getId()).append('|').append(loan.getId()).append('|').append(loan.getCurrentPrincipalCents()).append('|').append(loan.getStatus()).append('|').append(loan.getAccountingOn()).append('|').append(loan.getLastPaymentOn()).append('|').append(loan.getAnnualRate().toPlainString()).append('|').append(loan.getRepaymentMethod()).append('|').append(loan.getPaymentCategory().getId()).append('|').append(loan.getMember()==null?null:loan.getMember().getId()).append('|').append(operation).append('|').append(paidOn).append('|').append(account).append('|').append(interest).append('|').append(extra);
  value.append("|precision-v1|").append(loan.getRepaymentPolicyRevision()).append('|').append(loan.getMinimumInstallmentAmount()).append('|').append(loan.getRepaymentPolicySource());
  value.append("|calculation-v2|");
  for(var p:periods)value.append('|').append(p.id()).append(':').append(p.installmentNo()).append(':').append(p.dueOn()).append(':').append(p.principalCents()).append(':').append(p.interestCents()).append(':').append(p.precisePrincipalAmount()).append(':').append(p.preciseInterestAmount()).append(':').append(p.interestCarryAmount()).append(':').append(p.roundingPolicy()).append(':').append(p.customRatePrincipalAmount()).append(':').append(p.customRateInterestAmount());
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
 }
 public void requireMatch(String expected,String supplied){if(!expected.equals(supplied))throw new ResourceConflictException("LOAN_PLAN_CHANGED","贷款计划或付款信息已变化，请重新预览并确认");}
 public long dueInterest(List<Period> periods,LocalDate day){long result=0;for(var p:periods)if(!p.dueOn().isAfter(day))result=Math.addExact(result,p.interestCents());return result;}
}
