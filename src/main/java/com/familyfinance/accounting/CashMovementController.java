package com.familyfinance.accounting;

import com.familyfinance.shared.ApiEnvelope;
import com.familyfinance.shared.CurrentHousehold;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cash-movements")
public class CashMovementController {
    private final CurrentHousehold household;
    private final CashMovementService service;

    public CashMovementController(CurrentHousehold household, CashMovementService service) {
        this.household = household;
        this.service = service;
    }

    @GetMapping
    public ApiEnvelope<CashMovementResponse.Page> movements(Authentication authentication,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Long bankAccountId,
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiEnvelope.data(service.page(household.id(authentication), month, accountId, bankAccountId, kind, page, size));
    }
}
