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

    public CsvExportService(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    public byte[] export(long householdId, TransactionFilter filter) {
        List<FinancialTransaction> transactions = transactionService.findAllForCsvExport(householdId, filter);
        StringBuilder csv = new StringBuilder("日期,类型,金额,成员,分类,商家,地点,备注\n");
        for (FinancialTransaction transaction : transactions) {
            csv.append(transaction.getOccurredOn()).append(',')
                    .append(label(transaction.getKind())).append(',')
                    .append(Money.formatCents(transaction.getAmountCents())).append(',')
                    .append(escape(transaction.getMember().getName())).append(',')
                    .append(escape(transaction.getCategory().getName())).append(',')
                    .append(escape(transaction.getMerchant())).append(',')
                    .append(escape(transaction.getLocation())).append(',')
                    .append(escape(transaction.getNote())).append('\n');
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
