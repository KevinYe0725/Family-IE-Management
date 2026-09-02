package com.familyfinance.asset;

import com.familyfinance.shared.Money;
import java.time.Instant;
import java.time.LocalDate;

public record AssetValuationResponse(
        long id,
        LocalDate valuedOn,
        String value,
        AssetValuationSource source,
        String note,
        long createdBy,
        Instant fetchedAt) {

    static AssetValuationResponse from(AssetValuation valuation) {
        return new AssetValuationResponse(
                valuation.getId(),
                valuation.getValuedOn(),
                Money.formatCents(valuation.getValueCents()),
                valuation.getSource(),
                valuation.getNote(),
                valuation.getCreatedBy().getId(),
                valuation.getFetchedAt());
    }
}
