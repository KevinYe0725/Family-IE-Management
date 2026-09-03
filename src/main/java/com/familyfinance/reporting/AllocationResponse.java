package com.familyfinance.reporting;

import com.familyfinance.shared.Money;
import java.math.BigDecimal;

public record AllocationResponse(String type, String amount, String sharePercent) {
    static AllocationResponse from(AllocationSlice value) {
        return new AllocationResponse(value.type(), Money.formatCents(value.amountCents()),
                BigDecimal.valueOf(value.shareTenths(), 1).toPlainString());
    }
}
