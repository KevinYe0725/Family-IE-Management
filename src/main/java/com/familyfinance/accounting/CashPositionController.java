package com.familyfinance.accounting;

import com.familyfinance.shared.ApiEnvelope;
import com.familyfinance.shared.CurrentHousehold;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cash-position")
public class CashPositionController {
    private final CurrentHousehold household;
    private final CashPositionService service;

    public CashPositionController(CurrentHousehold household, CashPositionService service) {
        this.household = household;
        this.service = service;
    }

    @GetMapping
    public ApiEnvelope<CashPositionResponse> position(Authentication authentication) {
        return ApiEnvelope.data(service.calculate(household.id(authentication)));
    }
}
