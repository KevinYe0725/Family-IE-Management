package com.familyfinance.investment;

public record SecurityResponse(
        long id,
        String market,
        String tsCode,
        String name,
        String securityType,
        boolean active) {

    static SecurityResponse from(Security security) {
        return new SecurityResponse(
                security.getId(), security.getMarket(), security.getTsCode(), security.getName(),
                security.getSecurityType(), security.isActive());
    }
}
