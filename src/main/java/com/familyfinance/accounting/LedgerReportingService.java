package com.familyfinance.accounting;

import com.familyfinance.category.TransactionKind;
import com.familyfinance.shared.ResourceConflictException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.math.BigDecimal;
import java.util.Arrays;
import com.familyfinance.shared.DecimalMoney;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** Snapshot-safe reporting of the corrected effective ledger, never a funding authorization. */
@Service @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class LedgerReportingService {
    private final JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired private com.familyfinance.fx.FxJournalRates fx;
    public LedgerReportingService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public void requireComplete(long h) {
        long missing=missingCount(h);
        if(missing>0)throw new ResourceConflictException("ACCOUNTING_NOT_INITIALIZED","存在 "+missing+" 项未确认的期初或旧资金记录；请先核对期初与来源账务，当前报表不完整");
    }
    public boolean isComplete(long h){return missingCount(h)==0;}
    public Map<String,String> currencies(long h){
        Map<String,String> result=new LinkedHashMap<>();jdbc.query("select account_code,currency from ledger_accounts where household_id=?",rs->{result.put(rs.getString(1),rs.getString(2));},h);return Map.copyOf(result);
    }
    private long missingCount(long h) {
        long missing=0;
        missing+=count("select count(*) from financial_accounts where household_id=? and opening_confirmed=false",h);
        missing+=count("select count(*) from assets where household_id=? and accounting_mode is null",h);
        missing+=count("select count(*) from loans where household_id=? and funding_mode is null",h);
        missing+=count("select count(*) from investment_trades where household_id=? and accounting_confirmed=false",h);
        missing+=count("""
            select count(*) from financial_transactions t where t.household_id=? and not exists (
            select 1 from ledger_sources s where s.household_id=t.household_id and s.current_journal_id is not null and
            ((t.source_type in ('MANUAL','RECURRING') and s.source_type='TRANSACTION' and s.source_id=t.id)
            or (t.source_type in ('LOAN_PAYMENT','LOAN_PREPAYMENT') and s.source_type=t.source_type and s.source_id=t.source_id)))
            """,h);
        return missing;
    }
    private long count(String sql,long h){return jdbc.queryForObject(sql,Long.class,h);}

    public Map<String,Long> balancesAsOf(long h,LocalDate day) {
        Map<String,Long> result=new LinkedHashMap<>();
        balanceAmountsAsOf(h,day).forEach((code,amount)->result.put(code,DecimalMoney.toCents(amount)));
        return Map.copyOf(result);
    }

    public Map<String,BigDecimal> balanceAmountsAsOf(long h,LocalDate day) {
        Map<String,BigDecimal> result=new LinkedHashMap<>();
        jdbc.query("""
            select e.account_code,a.kind,e.debit_amount,e.credit_amount from ledger_entries e
            join ledger_journals j on j.id=e.journal_id and j.household_id=e.household_id
            join ledger_sources s on s.current_journal_id=j.id and s.household_id=j.household_id
            join ledger_accounts a on a.household_id=e.household_id and a.account_code=e.account_code
            where e.household_id=? and j.effective_on<=? order by j.effective_on,j.id,e.line_no
            """,rs->{
                String kind=rs.getString(2);
                BigDecimal delta=rs.getBigDecimal(3).subtract(rs.getBigDecimal(4));
                if(kind.equals("LOAN")||kind.equals("INCOME")||kind.equals("EQUITY"))delta=delta.negate();
                result.merge(rs.getString(1),delta,BigDecimal::add);
            },h,day);
        return Map.copyOf(result);
    }

    public List<LedgerActivity> activities(long h,LocalDate from,LocalDate toExclusive) {
        requireComplete(h);
        return jdbc.query("""
            select e.id,j.id journal_id,e.currency,j.effective_on,a.kind,e.debit_amount,e.credit_amount,e.account_code,
              c.id category_id,c.name category_name,p.id parent_id,p.name parent_name,
              m.id member_id,m.name member_name,j.source_type,j.source_id,t.note
            from ledger_entries e
            join ledger_journals j on j.id=e.journal_id and j.household_id=e.household_id
            join ledger_sources s on s.current_journal_id=j.id and s.household_id=j.household_id
            join ledger_accounts a on a.household_id=e.household_id and a.account_code=e.account_code
            left join categories c on c.id=e.category_id and c.household_id=e.household_id
            left join categories p on p.id=c.parent_id and p.household_id=c.household_id
            left join family_members m on m.id=e.member_id and m.household_id=e.household_id
            left join financial_transactions t on t.household_id=j.household_id and
              ((j.source_type='TRANSACTION' and t.id=j.source_id) or
               (j.source_type in ('LOAN_PAYMENT','LOAN_PREPAYMENT') and t.source_type=j.source_type and t.source_id=j.source_id))
            where e.household_id=? and j.effective_on>=? and j.effective_on<?
              and a.kind in ('INCOME','EXPENSE')
              and e.account_code not in ('INCOME:VALUATION_GAIN','EXPENSE:VALUATION_LOSS')
            order by j.effective_on,e.id
            """,(rs,n)->{
                var kind=TransactionKind.valueOf(rs.getString("kind"));
                BigDecimal amount=rs.getBigDecimal("debit_amount").subtract(rs.getBigDecimal("credit_amount"));
                if(kind==TransactionKind.INCOME)amount=amount.negate();
                amount=historicalAmount(rs.getLong("journal_id"),rs.getString("currency"),amount);
                Long category=rs.getObject("category_id",Long.class), parent=rs.getObject("parent_id",Long.class),member=rs.getObject("member_id",Long.class);
                String code=rs.getString("account_code");
                String label=code.startsWith("EXPENSE:FX_FEE")?"换汇手续费":code.startsWith("EXPENSE:INVESTMENT_FEE")?"投资费用":kind==TransactionKind.INCOME?"已实现收益":"已实现损失";
                boolean assetDisposal=rs.getString("source_type").equals("ASSET_DISPOSAL");
                if(assetDisposal)label=kind==TransactionKind.INCOME?"资产处置账面收益":"资产处置账面损失";
                var cat=new LedgerActivity.Dimension(category==null?(assetDisposal?-3:code.startsWith("EXPENSE:FX_FEE")?-4:code.startsWith("EXPENSE:INVESTMENT_FEE")?-2:-1):category,category==null?label:rs.getString("category_name"),
                    parent==null?null:new LedgerActivity.Dimension(parent,rs.getString("parent_name"),null));
                var mem=new LedgerActivity.Dimension(member==null?0:member,member==null?"家庭共同":rs.getString("member_name"),null);
                return new LedgerActivity(rs.getLong("id"),rs.getObject("effective_on",LocalDate.class),kind,amount,cat,mem,rs.getString("note"),rs.getString("source_type"),rs.getLong("source_id"));
            },h,from,toExclusive);
    }

    public String sumBudgetExpenseCents(long h,LocalDate from,LocalDate to,String scope,Long category,Long member,boolean rollup) {
        // Budget's legacy decimal-string-of-cents API intentionally supports totals beyond long.
        return sumBudgetExpenseAmount(h,from,to,scope,category,member,rollup).movePointRight(2).toBigIntegerExact().toString();
    }
    public BigDecimal sumBudgetExpenseAmount(long h,LocalDate from,LocalDate to,String scope,Long category,Long member,boolean rollup) {
        BigDecimal result=DecimalMoney.fromCents(0);
        for(var item:activities(h,from,to)) {
            if(!matchesBudgetExpense(item,scope,category,member,rollup))continue;
            result=result.add(item.amount());
        }
        return result;
    }
    /** Per-budget drill-down: the same effective entries the usage sum counts, newest first. */
    public java.util.List<LedgerActivity> budgetEntries(long h,LocalDate from,LocalDate to,String scope,Long category,Long member,boolean rollup) {
        var matching=new java.util.ArrayList<>(activities(h,from,to).stream()
                .filter(item->matchesBudgetExpense(item,scope,category,member,rollup))
                .toList());
        java.util.Collections.reverse(matching);
        return java.util.List.copyOf(matching);
    }
    private static boolean matchesBudgetExpense(LedgerActivity item,String scope,Long category,Long member,boolean rollup) {
        if(item.kind()!=TransactionKind.EXPENSE)return false;
        boolean categoryBudget=scope.equals("CATEGORY")||scope.equals("CATEGORY_MEMBER");
        boolean memberBudget=scope.equals("MEMBER")||scope.equals("CATEGORY_MEMBER");
        if(categoryBudget&&!(category!=null&&(item.category().id()==category||(rollup&&item.category().parent()!=null&&item.category().parent().id()==category))))return false;
        if(memberBudget&&!(member!=null&&item.member().id()==member))return false;
        return true;
    }

    public CashFlow cashFlow(long h,LocalDate from,LocalDate to) {
        var amounts=cashFlowAmounts(h,from,to);
        return new CashFlow(DecimalMoney.toCents(amounts.cashIn()),DecimalMoney.toCents(amounts.cashOut()),
            DecimalMoney.toCents(amounts.principalPaid()),DecimalMoney.toCents(amounts.borrowed()),DecimalMoney.toCents(amounts.noncashValuationChange()));
    }
    public CashFlowAmounts cashFlowAmounts(long h,LocalDate from,LocalDate to) {
        requireComplete(h);
        BigDecimal[] totals=new BigDecimal[5];
        Arrays.fill(totals,DecimalMoney.fromCents(0));
        jdbc.query("""
            select a.kind,e.debit_amount,e.credit_amount,e.account_code,j.id,e.currency,j.source_type from ledger_entries e
            join ledger_accounts a on a.household_id=e.household_id and a.account_code=e.account_code
            join ledger_journals j on j.id=e.journal_id and j.household_id=e.household_id
            join ledger_sources s on s.current_journal_id=j.id and s.household_id=j.household_id
            where e.household_id=? and j.effective_on>=? and j.effective_on<?
              and j.source_type not in ('CASH_OPENING','CASH_TRANSFER','LOAN_OPENING')
            """,rs->{
                String kind=rs.getString(1),code=rs.getString(4);
                boolean exchange=rs.getString(7).equals("FX_TRANSFER");
                if(exchange&&!code.startsWith("EXPENSE:FX_FEE"))return;
                BigDecimal debit=historicalAmount(rs.getLong(5),rs.getString(6),rs.getBigDecimal(2)),credit=historicalAmount(rs.getLong(5),rs.getString(6),rs.getBigDecimal(3));
                if(exchange){totals[1]=totals[1].add(debit.subtract(credit));return;}
                if(kind.equals("CASH")){totals[0]=totals[0].add(debit);totals[1]=totals[1].add(credit);}
                if(kind.equals("LOAN")){totals[2]=totals[2].add(debit);totals[3]=totals[3].add(credit);}
                if(code.equals("INCOME:VALUATION_GAIN"))totals[4]=totals[4].add(credit.subtract(debit));
                if(code.equals("EXPENSE:VALUATION_LOSS"))totals[4]=totals[4].subtract(debit.subtract(credit));
            },h,from,to);
        return new CashFlowAmounts(totals[0],totals[1],totals[2],totals[3],totals[4]);
    }
    public record CashFlow(long cashIn,long cashOut,long principalPaid,long borrowed,long noncashValuationChange){}
    private BigDecimal historicalAmount(long journal,String currency,BigDecimal amount){
        if(currency.equals("CNY"))return amount;
        BigDecimal value=fx.historical(journal,currency,amount);
        if(value==null)throw new ResourceConflictException("FX_RATE_MISSING","原币记录已保留，缺少发生日汇率，暂不能计算人民币收支。请在投资持仓的汇率页更新对应日期。");
        return value;
    }
    public record CashFlowAmounts(BigDecimal cashIn,BigDecimal cashOut,BigDecimal principalPaid,BigDecimal borrowed,BigDecimal noncashValuationChange){}
}
