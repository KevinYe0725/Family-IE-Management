package com.familyfinance.loan;

import static com.familyfinance.accounting.LedgerAccountKind.*;
import com.familyfinance.accounting.*;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.transaction.FinancialTransaction;
import java.time.LocalDate;
import java.math.BigDecimal;
import com.familyfinance.shared.DecimalMoney;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One loan business command and all of its cash/principal/interest legs share a transaction. */
@Service @Transactional
public class LoanAccountingService {
 private final LedgerPostingService posting;
 private final LedgerReadService ledger;
 private final CashAccountingService cash;
 private final org.springframework.jdbc.core.JdbcTemplate jdbc;
 public LoanAccountingService(LedgerPostingService posting,LedgerReadService ledger,CashAccountingService cash,org.springframework.jdbc.core.JdbcTemplate jdbc){this.posting=posting;this.ledger=ledger;this.cash=cash;this.jdbc=jdbc;}
 public LocalDate accountingDate(LocalDate day){return cash.date(day==null?null:day.toString(),"accountingOn");}
 /** 组合购买期初凭证：Dr ASSET:purchasePrice，差额首付 D=P-L>0 时 Cr CASH:downPaymentAccount，Cr LOAN:L。 */
 public void originateFinanced(Loan loan,long purchasePriceCents,
         com.familyfinance.ledger.FinancialAccount downPaymentAccount,long actor,String key) {
  if(loan.getFundingMode()!=LoanFundingMode.FINANCED_PURCHASE||loan.getPurchasedAssetId()==null)
   throw new IllegalArgumentException("组合入账仅支持贷款购买（已关联资产）");
  long down=Math.subtractExact(purchasePriceCents,loan.getPrincipalCents());
  if(down<0)throw new IllegalArgumentException("贷款本金不能超过资产购入价值");
  if(down>0&&downPaymentAccount==null)throw new IllegalArgumentException("存在首付差额时必须选择首付资金账户");
  long h=loan.getHousehold().getId();LocalDate day=accountingDate(loan.getAccountingOn());
  var entries=new ArrayList<LedgerEntryInput>();
  entries.add(new LedgerEntryInput("ASSET:"+loan.getPurchasedAssetId(),ASSET,
          DecimalMoney.fromCents(purchasePriceCents),BigDecimal.ZERO,null,null));
  if(down>0){
   cash.requireConfirmed(downPaymentAccount,day);
   entries.add(new LedgerEntryInput("CASH:"+downPaymentAccount.getId(),CASH,
           BigDecimal.ZERO,DecimalMoney.fromCents(down),null,null));
  }
  entries.add(new LedgerEntryInput("LOAN:"+loan.getId(),LOAN,
          BigDecimal.ZERO,loan.getPrincipalAmount(),null,null));
  posting.post(new LedgerPostingCommand(h,source(loan),loan.getId(),key,day,actor,entries));
  requireBalance(loan);
 }
 public void originate(Loan loan,long actor,String key,boolean replace) {
  long h=loan.getHousehold().getId();
  String source=source(loan);
  if(replace) cash.requireEditableSourceCash(h,source,loan.getId());
  LocalDate day=accountingDate(loan.getAccountingOn());
  var debit=new LedgerEntryInput("EQUITY:OPENING",EQUITY,loan.getPrincipalAmount(),BigDecimal.ZERO,null,null);
  if(loan.getFundingMode()==LoanFundingMode.DISBURSEMENT){
   cash.requireConfirmed(loan.getDisbursementAccount(),day);
   debit=new LedgerEntryInput("CASH:"+loan.getDisbursementAccount().getId(),CASH,loan.getPrincipalAmount(),BigDecimal.ZERO,null,null);
  }
  if(loan.getFundingMode()==LoanFundingMode.FINANCED_PURCHASE)
   debit=new LedgerEntryInput("ASSET:"+loan.getPurchasedAssetId(),ASSET,loan.getPrincipalAmount(),BigDecimal.ZERO,null,null);
  var command=new LedgerPostingCommand(h,source,loan.getId(),key,day,actor,List.of(debit,new LedgerEntryInput("LOAN:"+loan.getId(),LOAN,BigDecimal.ZERO,loan.getPrincipalAmount(),null,null)));
  if(replace)posting.replace(command);else posting.post(command);
  requireBalance(loan);
 }
 public void requireInitialized(Loan loan) {
  if(loan.getFundingMode()==null||loan.getAccountingOn()==null||ledger.currentSource(loan.getHousehold().getId(),source(loan),loan.getId()).isEmpty())
   throw new ResourceConflictException("ACCOUNTING_NOT_INITIALIZED","贷款尚未确认账务期初，不能进行资金操作");
 }
 public void requirePaymentDate(Loan loan,LocalDate day) {
  requireBalance(loan);
  cash.date(day==null?null:day.toString(),"paidOn");
  if(day.isBefore(loan.getAccountingOn()))throw new ResourceConflictException("LOAN_PAYMENT_BEFORE_OPENING","还款日期不能早于贷款账务起始日期");
  if(loan.getLastPaymentOn()!=null&&day.isBefore(loan.getLastPaymentOn()))throw new ResourceConflictException("LOAN_PAYMENT_CHRONOLOGY","还款日期不能早于已入账的最近还款日期");
 }
 /** Current mutation invariant. Never repairs either the source loan or the ledger projection. */
 public void requireBalance(Loan loan) {
  requireInitialized(loan); // Acquires the household lock before the current projection read.
  var balances=jdbc.queryForList("select balance_amount from ledger_accounts where household_id=? and account_code=? for update",BigDecimal.class,loan.getHousehold().getId(),"LOAN:"+loan.getId());
  if(balances.size()!=1||balances.get(0).compareTo(loan.getCurrentPrincipalAmount())!=0)
   throw new ResourceConflictException("ACCOUNTING_BALANCE_MISMATCH","贷款剩余本金与账务余额不一致，请核对账务后再操作");
 }
 public void pay(Loan loan,FinancialTransaction tx,long principal,long interest,String key) {
  pay(loan,tx,DecimalMoney.fromCents(principal),DecimalMoney.fromCents(interest),key);
 }
 public void pay(Loan loan,FinancialTransaction tx,BigDecimal principal,BigDecimal interest,String key) {
  principal=DecimalMoney.settled(principal);interest=DecimalMoney.settled(interest);
  if(principal.signum()<0||interest.signum()<0||principal.add(interest).signum()<=0||DecimalMoney.toCents(principal.add(interest))!=tx.getAmountCents())throw new IllegalArgumentException("invalid loan payment");
  requirePaymentDate(loan,tx.getOccurredOn());
  cash.requireConfirmed(tx.getAccount(),tx.getOccurredOn());
  List<LedgerEntryInput> entries=new ArrayList<>();
  if(principal.signum()>0)entries.add(new LedgerEntryInput("LOAN:"+loan.getId(),LOAN,principal,BigDecimal.ZERO,null,tx.getMember().getId()));
  if(interest.signum()>0)entries.add(new LedgerEntryInput("EXPENSE:"+tx.getCategory().getId(),EXPENSE,interest,BigDecimal.ZERO,tx.getCategory().getId(),tx.getMember().getId()));
  entries.add(new LedgerEntryInput("CASH:"+tx.getAccount().getId(),CASH,BigDecimal.ZERO,principal.add(interest),null,tx.getMember().getId()));
  posting.post(new LedgerPostingCommand(loan.getHousehold().getId(),tx.getSourceType().name(),tx.getSourceId(),key,tx.getOccurredOn(),tx.getCreatedByUser().getId(),entries));
  loan.paidOn(tx.getOccurredOn());
 }
 public static String source(Loan loan){return loan.getFundingMode()==LoanFundingMode.FINANCED_PURCHASE?"LOAN_FINANCED_PURCHASE":loan.getFundingMode()==LoanFundingMode.DISBURSEMENT?"LOAN_DISBURSEMENT":"LOAN_OPENING";}
}
