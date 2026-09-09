package com.familyfinance.asset;

import com.familyfinance.accounting.*;
import com.familyfinance.ledger.FinancialAccountRepository;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.shared.ResourceNotFoundException;
import java.time.LocalDate;
import java.util.ArrayList;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.familyfinance.accounting.LedgerAccountKind.*;

/** Explicit acquisition, noncash carrying-value changes, and real disposal. */
@Service @Transactional
public class AssetAccountingService {
    private final LedgerPostingService posting;
    private final CashAccountingService cash;
    private final FinancialAccountRepository accounts;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public AssetAccountingService(LedgerPostingService posting,CashAccountingService cash,FinancialAccountRepository accounts,org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.posting=posting;this.cash=cash;this.accounts=accounts;this.jdbc=jdbc;
    }
    public LocalDate day(LocalDate day,String field){return cash.date(day==null?null:day.toString(),field);}
    /** Disposal result relative to the final carrying value, not cumulative investment return. */
    public Long disposalBookGain(Asset asset,boolean current) {
        if(asset.getDisposedOn()==null)return null;
        var values=jdbc.queryForList("""
            select e.credit_cents-e.debit_cents from ledger_sources s
            join ledger_entries e on e.journal_id=s.current_journal_id and e.household_id=s.household_id
            where s.household_id=? and s.source_type='ASSET_DISPOSAL' and s.source_id=?
              and e.account_code in ('INCOME:INVESTMENT_GAIN','EXPENSE:INVESTMENT_LOSS')
            """+(current?" for update":""),Long.class,asset.getHousehold().getId(),asset.getId());
        long result=0;
        for(long value:values)result=Math.addExact(result,value);
        return result;
    }
    public void originate(Asset asset,long actor,String key) {
        long initial=asset.getInitialValueCents();
        var entries=new ArrayList<LedgerEntryInput>();
        add(entries,"ASSET:"+asset.getId(),ASSET,initial);
        if(asset.getAccountingMode()==AssetAccountingMode.PURCHASE) {
            requireCash(asset.getHousehold().getId(),asset.getFundingAccountId(),asset.getAccountingOn());
            add(entries,"CASH:"+asset.getFundingAccountId(),CASH,-initial);
        } else add(entries,"EQUITY:OPENING",EQUITY,-initial);
        post(asset,"ASSET_ACQUISITION",asset.getId(),key,asset.getAccountingOn(),actor,entries);
    }
    public void revalue(Asset asset,long valuationId,LocalDate day,long oldValue,long value,long actor,String key) {
        long delta=Math.subtractExact(value,oldValue);
        var entries=new ArrayList<LedgerEntryInput>();
        add(entries,"ASSET:"+asset.getId(),ASSET,delta);
        add(entries,delta>=0?"INCOME:VALUATION_GAIN":"EXPENSE:VALUATION_LOSS",delta>=0?INCOME:EXPENSE,-delta);
        post(asset,"ASSET_VALUATION",valuationId,key,day,actor,entries);
    }
    public void dispose(Asset asset,LocalDate day,long proceeds,Long cashId,long actor,String key) {
        requireBalance(asset);
        requireChronology(asset,day);
        if(cashId!=null)requireCash(asset.getHousehold().getId(),cashId,day);
        if(proceeds>0&&cashId==null)throw new ResourceConflictException("DISPOSAL_CASH_REQUIRED","有处置收入时必须选择资金账户");
        var entries=new ArrayList<LedgerEntryInput>();
        add(entries,"ASSET:"+asset.getId(),ASSET,-asset.getCurrentValueCents());
        if(cashId!=null)add(entries,"CASH:"+cashId,CASH,proceeds);
        long profit=Math.subtractExact(proceeds,asset.getCurrentValueCents());
        add(entries,profit>=0?"INCOME:INVESTMENT_GAIN":"EXPENSE:INVESTMENT_LOSS",profit>=0?INCOME:EXPENSE,-profit);
        post(asset,"ASSET_DISPOSAL",asset.getId(),key,day,actor,entries);
    }
    public void requireBalance(Asset asset) {
        if(asset.getAccountingMode()==null)throw new ResourceConflictException("ACCOUNTING_NOT_INITIALIZED","旧资产尚未确认账务期初，不能估值或处置");
        var values=jdbc.queryForList("select balance_cents from ledger_accounts where household_id=? and account_code=? for update",Long.class,asset.getHousehold().getId(),"ASSET:"+asset.getId());
        long actual=values.isEmpty()?0:values.get(0);
        if(actual!=asset.getCurrentValueCents())throw new ResourceConflictException("ACCOUNTING_BALANCE_MISMATCH","资产当前价值与账务余额不一致，请先核对账务");
    }
    public void sell(Asset asset,LocalDate day,java.math.BigDecimal saleNet,java.math.BigDecimal repayment,
            Long cashId,boolean direct,long actor,String key){
        requireBalance(asset);requireChronology(asset,day);
        if(cashId!=null)requireCash(asset.getHousehold().getId(),cashId,day);
        var cashChange=direct?saleNet.subtract(repayment):saleNet;
        if(cashChange.signum()!=0&&cashId==null)throw new ResourceConflictException("DISPOSAL_CASH_REQUIRED","请选择收款或补款账户");
        var entries=new ArrayList<LedgerEntryInput>();
        add(entries,"ASSET:"+asset.getId(),ASSET,-asset.getCurrentValueCents());
        if(direct)add(entries,"ASSET_SALE_CLEARING:"+asset.getId(),ASSET,com.familyfinance.shared.DecimalMoney.toCents(repayment));
        if(cashId!=null)add(entries,"CASH:"+cashId,CASH,com.familyfinance.shared.DecimalMoney.toCents(cashChange));
        long profit=com.familyfinance.shared.DecimalMoney.toCents(saleNet.subtract(com.familyfinance.shared.DecimalMoney.fromCents(asset.getCurrentValueCents())));
        add(entries,profit>=0?"INCOME:INVESTMENT_GAIN":"EXPENSE:INVESTMENT_LOSS",profit>=0?INCOME:EXPENSE,-profit);
        post(asset,"ASSET_DISPOSAL",asset.getId(),key,day,actor,entries);
    }
    public void requireChronology(Asset asset,LocalDate day) {
        if(day.isBefore(asset.getLastAccountingOn()))throw new ResourceConflictException("ASSET_ACCOUNTING_CHRONOLOGY","日期不能早于资产最近的入账或估值日期；历史估值保留只读");
    }
    private void requireCash(long h,Long id,LocalDate day) {
        var account=accounts.findLockedByIdAndHouseholdId(id,h).orElseThrow(()->new ResourceNotFoundException("资金账户不存在"));
        cash.requireConfirmed(account,day);
        if(!account.getCurrency().equals("CNY"))throw new com.familyfinance.shared.RequestValidationException(java.util.Map.of("accountId","实体资产买卖请使用人民币账户"));
    }
    private void post(Asset asset,String source,long id,String key,LocalDate day,long actor,java.util.List<LedgerEntryInput> entries) {
        // Zero economic events retain their domain history and command receipt, without zero legs.
        if(!entries.isEmpty())posting.post(new LedgerPostingCommand(asset.getHousehold().getId(),source,id,key,day,actor,entries));
    }
    private static void add(java.util.List<LedgerEntryInput> entries,String code,LedgerAccountKind kind,long debit) {
        if(debit!=0)entries.add(new LedgerEntryInput(code,kind,debit>0?debit:0,debit<0?Math.negateExact(debit):0,null,null));
    }
}
