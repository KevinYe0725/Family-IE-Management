package com.familyfinance.investment;

import com.familyfinance.shared.ApiEnvelope;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/investment-trades")
public class InvestmentTradeController {

    private final InvestmentTradeService trades;

    public InvestmentTradeController(InvestmentTradeService trades) {
        this.trades = trades;
    }

    @GetMapping
    ResponseEntity<ApiEnvelope<InvestmentTradePage>> list(
            Authentication authentication,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Long securityId,
            @RequestParam(required = false) InvestmentTradeType type,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        InvestmentTradePage result = trades.list(
                authentication, accountId, securityId, type, from, to, page, size);
        return ResponseEntity.ok()
                .header("X-Page", Integer.toString(result.page()))
                .header("X-Page-Size", Integer.toString(result.size()))
                .header("X-Total-Elements", Long.toString(result.totalElements()))
                .header("X-Total-Pages", Integer.toString(result.totalPages()))
                .header("X-Has-Next", Boolean.toString(result.hasNext()))
                .body(ApiEnvelope.data(result));
    }

    @GetMapping("/{id}")
    ApiEnvelope<InvestmentTradeResponse> get(Authentication authentication, @PathVariable long id) {
        return ApiEnvelope.data(trades.get(authentication, id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiEnvelope<InvestmentTradeMutationResponse> create(
            Authentication authentication, @RequestBody InvestmentTradeRequest request) {
        return ApiEnvelope.data(trades.create(authentication, request));
    }

    @PatchMapping("/{id}")
    ApiEnvelope<InvestmentTradeMutationResponse> update(
            Authentication authentication, @PathVariable long id, @RequestBody InvestmentTradePatchRequest request) {
        return ApiEnvelope.data(trades.update(authentication, id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(Authentication authentication, @PathVariable long id) {
        trades.delete(authentication, id);
    }
}
