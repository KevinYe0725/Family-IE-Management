package com.familyfinance.accounting;

import com.familyfinance.shared.RequestValidationException;
import java.math.BigDecimal;
import com.familyfinance.shared.DecimalMoney;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import org.springframework.stereotype.Component;

@Component
class LedgerValidation {
    private final LedgerStore store;
    private final Clock clock;
    private final LedgerSecurityCatalog securityCatalog;
    private static final Set<String> SYSTEM_ACCOUNTS=Set.of("EQUITY:OPENING","INCOME:INVESTMENT_GAIN",
            "EXPENSE:INVESTMENT_LOSS","INCOME:VALUATION_GAIN","EXPENSE:VALUATION_LOSS","EXPENSE:INVESTMENT_FEE",
            "EQUITY:FX_CLEARING","EXPENSE:FX_FEE");
    LedgerValidation(LedgerStore store,Clock clock,LedgerSecurityCatalog securityCatalog) {
        this.store=store; this.clock=clock; this.securityCatalog=securityCatalog;
    }

    static void require(boolean condition,String field,String message) {
        if (!condition) throw new RequestValidationException(Map.of(field,message));
    }

    void identity(long h,String type,long id,String key,long actor) {
        require(h>0 && id>0 && actor>0,"sourceId","家庭、来源和操作人必须有效");
        require(type!=null && type.matches("[A-Z][A-Z0-9_]{0,39}"),"sourceType","来源类型格式不正确");
        require(key!=null && key.matches("[A-Za-z0-9_.:-]{1,100}"),"idempotencyKey","请提供有效请求键（最多100字符）");
    }

    void command(LedgerPostingCommand c) {
        require(c!=null,"request","请提供过账请求");
        identity(c.householdId(),c.sourceType(),c.sourceId(),c.idempotencyKey(),c.actorId());
        require(c.effectiveOn()!=null && !c.effectiveOn().isAfter(LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))))
                && c.effectiveOn().getYear()>=1000,"effectiveOn","业务日期必须在1000年至上海当天之间");
        require(c.entries()!=null && c.entries().size()>=2 && c.entries().size()<=1000,"entries","凭证需包含2至1000条分录");
        Map<String,BigDecimal> debits=new java.util.HashMap<>(),credits=new java.util.HashMap<>();
        for (var e:c.entries()) {
            require(e!=null && e.kind()!=null,"entries","分录科目不能为空");
            require(e.accountCode()!=null && e.accountCode().length()<=120,"accountCode","科目编码无效");
            require((e.debitAmount().signum()>0 && e.creditAmount().signum()==0)||(e.creditAmount().signum()>0 && e.debitAmount().signum()==0),"entries","分录金额必须为正且仅填写借方或贷方");
            debits.merge(e.currency(),e.debitAmount(),BigDecimal::add);
            credits.merge(e.currency(),e.creditAmount(),BigDecimal::add);
        }
        for(String currency:debits.keySet()) {
            require(debits.get(currency).compareTo(credits.getOrDefault(currency,BigDecimal.ZERO))==0,"entries",currency+" 凭证借贷不平衡");
            require(debits.get(currency).compareTo(DecimalMoney.MAX_AMOUNT)<=0,"entries","凭证合计金额超出范围");
        }
    }

    void register(LedgerPostingCommand c) {
        owned("app_users",c.actorId(),c.householdId());
        Set<Long> requestedSecurities=new LinkedHashSet<>();
        for(var entry:c.entries()) {
            if(entry.accountCode().startsWith("POSITION:")) {
                String[] parts=entry.accountCode().split(":",-1);
                match(entry,LedgerAccountKind.ASSET,parts,3);
                requestedSecurities.add(parseId(parts[2]));
            }
        }
        Set<Long> currentSecurities=securityCatalog.lockCurrent(requestedSecurities);
        for (var e:c.entries()) {
            String code=e.accountCode();
            require(code!=null && code.length()<=120,"accountCode","科目编码无效");
            // Existing CNY codes remain byte-for-byte stable; foreign shared
            // accounts have an explicit currency suffix and cannot pool units.
            String logicalCode=code;
            if(!e.currency().equals("CNY") && (code.startsWith("EQUITY:")||code.startsWith("INCOME:")||code.startsWith("EXPENSE:"))) {
                require(code.endsWith(":"+e.currency()),"accountCode","外币公共科目必须带对应币种");
                logicalCode=code.substring(0,code.length()-4);
            }
            String[] parts=logicalCode.split(":",-1);
            if (SYSTEM_ACCOUNTS.contains(logicalCode)) {
                require(e.kind().name().equals(parts[0]),"accountCode","科目编码与类型不一致");
            } else {
                require(parts.length>=2,"accountCode","科目编码无效");
                long id=parseId(parts[1]);
                switch(parts[0]) {
                    case "CASH" -> { match(e,LedgerAccountKind.CASH,parts,2); ownedCurrency("financial_accounts",id,c.householdId(),e.currency()); }
                    case "LOAN" -> { match(e,LedgerAccountKind.LOAN,parts,2); owned("loans",id,c.householdId()); require(e.currency().equals("CNY"),"currency","贷款仅支持人民币"); }
                    case "ASSET" -> { match(e,LedgerAccountKind.ASSET,parts,2); owned("assets",id,c.householdId()); require(e.currency().equals("CNY"),"currency","实体资产仅支持人民币"); }
                    case "ASSET_SALE_CLEARING" -> { match(e,LedgerAccountKind.ASSET,parts,2); owned("assets",id,c.householdId()); require(e.currency().equals("CNY"),"currency","资产结算仅支持人民币"); }
                    case "POSITION" -> {
                        match(e,LedgerAccountKind.ASSET,parts,3); ownedCurrency("investment_accounts",id,c.householdId(),e.currency());
                        require(currentSecurities.contains(parseId(parts[2])),"accountCode","证券不存在");
                        String currency=store.jdbc.queryForObject("select currency from securities where id=?",String.class,parseId(parts[2]));
                        require(e.currency().equals(currency),"currency","分录币种与证券币种不一致");
                    }
                    case "INCOME","EXPENSE" -> {
                        match(e,LedgerAccountKind.valueOf(parts[0]),parts,2); owned("categories",id,c.householdId());
                        require(store.jdbc.queryForObject("select kind from categories where id=? and household_id=? for update",String.class,id,c.householdId()).equals(parts[0]),"categoryId","分类收支类型不一致");
                        require(e.categoryId()==null || e.categoryId()==id,"categoryId","分类维度与科目不一致");
                    }
                    default -> throw new RequestValidationException(Map.of("accountCode","不支持的科目编码"));
                }
            }
            if(e.categoryId()!=null) owned("categories",e.categoryId(),c.householdId());
            if(e.memberId()!=null) owned("family_members",e.memberId(),c.householdId());
            var accounts=store.jdbc.query("select kind,currency from ledger_accounts where household_id=? and account_code=? for update",
                    (rs,n)->List.of(rs.getString(1),rs.getString(2)),c.householdId(),code);
            if(accounts.isEmpty()) store.jdbc.update("insert into ledger_accounts(household_id,account_code,kind,currency) values (?,?,?,?)",c.householdId(),code,e.kind().name(),e.currency());
            else require(accounts.get(0).get(0).equals(e.kind().name()) && accounts.get(0).get(1).equals(e.currency()),"accountCode","科目类型和币种不可更改");
        }
    }

    private void ownedCurrency(String table,long id,long h,String currency) {
        owned(table,id,h);
        require(currency.equals(store.jdbc.queryForObject("select currency from "+table+" where id=? and household_id=? for update",String.class,id,h)),
                "currency","分录币种与账户币种不一致");
    }

    private void owned(String table,long id,long h) {
        require(!store.jdbc.queryForList("select id from "+table+" where id=? and household_id=? for update",Long.class,id,h).isEmpty(),"accountCode","关联对象不存在或不属于当前家庭");
    }
    private static void match(LedgerEntryInput e,LedgerAccountKind kind,String[] parts,int size) {
        require(e.kind()==kind && parts.length==size,"accountCode","科目编码与类型不一致");
    }
    private static long parseId(String value) {
        require(value.matches("[1-9][0-9]{0,18}"),"accountCode","科目ID格式不正确");
        try { return Long.parseLong(value); }
        catch(NumberFormatException ex) { throw new RequestValidationException(Map.of("accountCode","科目ID超出范围")); }
    }
}
