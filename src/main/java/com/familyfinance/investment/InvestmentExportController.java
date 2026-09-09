package com.familyfinance.investment;
import com.familyfinance.family.CurrentMembership;
import com.familyfinance.fx.FxJournalRates;
import com.familyfinance.shared.CsvCell;
import com.familyfinance.shared.ResourceConflictException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
public class InvestmentExportController {
 private final InvestmentTradeRepository trades;private final CurrentMembership membership;private final FxJournalRates fx;
 public InvestmentExportController(InvestmentTradeRepository trades,CurrentMembership membership,FxJournalRates fx){this.trades=trades;this.membership=membership;this.fx=fx;}
 @GetMapping(value="/api/investment-trades/export.csv",produces="text/csv;charset=UTF-8")
 @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
 public ResponseEntity<byte[]> export(Authentication auth){
  long h=membership.require(auth).householdId();var rows=trades.findByHouseholdId(h,PageRequest.of(0,10001,Sort.by("tradedOn","id")));
  if(rows.size()>10000)throw new ResourceConflictException("EXPORT_LIMIT","单次最多导出10000笔交易，请联系管理员分批导出");
  var csv=new StringBuilder("\uFEFF日期,市场,交易所,代码,证券,业务,数量,成交单价,费用,原币,原币业务金额,人民币参考金额,参考汇率,汇率日期\n");
  for(var t:rows){var security=t.getSecurity();String currency=t.getAccount().getCurrency();
   BigDecimal gross=t.getQuantity()==null?t.getUnitPrice():t.getQuantity().multiply(t.getUnitPrice()).setScale(2,RoundingMode.HALF_UP);
   BigDecimal amount=t.getType()==InvestmentTradeType.SELL?gross.subtract(BigDecimal.valueOf(t.getFeeCents(),2)):gross.add(BigDecimal.valueOf(t.getFeeCents(),2));
   var rate=fx.sourceReference(h,"INVESTMENT_TRADE",t.getId(),currency);
   String[] cells={t.getTradedOn().toString(),security.getMarket(),security.getExchange(),security.getSymbol(),security.getName(),t.getType().name(),t.getQuantity()==null?"":t.getQuantity().toPlainString(),UnitPrice.format(t.getUnitPrice()),BigDecimal.valueOf(t.getFeeCents(),2).toPlainString(),currency,amount.toPlainString(),rate==null?"":amount.multiply(rate.value()).setScale(2,RoundingMode.HALF_UP).toPlainString(),rate==null?"":rate.value().toPlainString(),rate==null||rate.effectiveOn()==null?"":rate.effectiveOn().toString()};
   for(int index=0;index<cells.length;index++){
    if(index>0)csv.append(',');
    boolean numeric=(index>=6&&index<=8)||(index>=10&&index<=12);
    csv.append(numeric?cells[index]:CsvCell.escape(cells[index]));
   }
   csv.append('\n');
  }
  return ResponseEntity.ok().header("Content-Disposition","attachment; filename=\"investment-trades.csv\"").body(csv.toString().getBytes(StandardCharsets.UTF_8));
 }
}
