package com.familyfinance.asset;

import java.time.LocalDate;

public record AssetValuationRequest(LocalDate valuedOn, String value, String note) {
}
