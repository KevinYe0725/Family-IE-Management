package com.familyfinance.reporting;

import com.familyfinance.category.TransactionKind;
import com.familyfinance.shared.Money;
import com.familyfinance.transaction.FinancialTransaction;
import com.familyfinance.transaction.TransactionFilter;
import com.familyfinance.transaction.TransactionService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CsvExportService {

    private static final byte[] UTF8_BOM = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final TransactionService transactionService;
    @org.springframework.beans.factory.annotation.Autowired private com.familyfinance.fx.FxJournalRates fx;

    public CsvExportService(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public byte[] export(long householdId, TransactionFilter filter) {
        List<FinancialTransaction> transactions = transactionService.findAllForCsvExport(householdId, filter);
        StringBuilder csv = new StringBuilder("日期,类型,金额,成员,分类,商家,地点,备注,币种,人民币参考金额,参考汇率,汇率日期,现金影响\n");
        for (FinancialTransaction transaction : transactions) {
            String currency=transaction.getAccount().getCurrency();
            var rate=fx.sourceReference(householdId,"TRANSACTION",transaction.getId(),currency);
            var converted=rate==null?null:java.math.BigDecimal.valueOf(transaction.getAmountCents(),2).multiply(rate.value()).setScale(2,java.math.RoundingMode.HALF_UP);
            csv.append(transaction.getOccurredOn()).append(',')
                    .append(label(transaction.getKind())).append(',')
                    .append(Money.formatCents(transaction.getAmountCents())).append(',')
                    .append(escape(transaction.getMember() == null ? "全体（家庭共同）" : transaction.getMember().getName())).append(',')
                    .append(escape(transaction.getCategory().getName())).append(',')
                    .append(escape(transaction.getMerchant())).append(',')
                    .append(escape(transaction.getLocation())).append(',')
                    .append(escape(transaction.getNote())).append(',').append(escape(currency)).append(',')
                    .append(converted==null?"":converted.toPlainString()).append(',').append(rate==null?"":rate.value().toPlainString()).append(',')
                    .append(rate==null||rate.effectiveOn()==null?"":rate.effectiveOn()).append(',')
                    .append(transaction.hasCashImpact()?"是":"否（买方代偿）").append('\n');
        }

        byte[] body = csv.toString().getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, withBom, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, withBom, UTF8_BOM.length, body.length);
        return withBom;
    }

    private static String label(TransactionKind kind) {
        return kind == TransactionKind.INCOME ? "收入" : "支出";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        String safeValue = neutralizeFormula(value);
        boolean mustQuote = safeValue.indexOf(',') >= 0
                || safeValue.indexOf('"') >= 0
                || safeValue.indexOf('\n') >= 0
                || safeValue.indexOf('\r') >= 0;
        String escaped = safeValue.replace("\"", "\"\"");
        return mustQuote ? "\"" + escaped + "\"" : escaped;
    }

    private static String neutralizeFormula(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return switch (value.charAt(0)) {
            case '=', '+', '-', '@', '\t' -> "'" + value;
            default -> value;
        };
    }
}
