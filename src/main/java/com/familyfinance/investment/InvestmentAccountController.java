package com.familyfinance.investment;

import com.familyfinance.shared.ApiEnvelope;
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
@RequestMapping("/api/investment-accounts")
public class InvestmentAccountController {

    private final InvestmentAccountService accounts;

    public InvestmentAccountController(InvestmentAccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    ResponseEntity<ApiEnvelope<InvestmentAccountPage>> list(
            Authentication authentication,
            @RequestParam(defaultValue = "ACTIVE") InvestmentAccountStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        InvestmentAccountPage result = accounts.list(authentication, status, page, size);
        return ResponseEntity.ok()
                .header("X-Page", Integer.toString(result.page()))
                .header("X-Page-Size", Integer.toString(result.size()))
                .header("X-Total-Elements", Long.toString(result.totalElements()))
                .header("X-Total-Pages", Integer.toString(result.totalPages()))
                .header("X-Has-Next", Boolean.toString(result.hasNext()))
                .body(ApiEnvelope.data(result));
    }

    @GetMapping("/{id}")
    ApiEnvelope<InvestmentAccountResponse> get(Authentication authentication, @PathVariable long id) {
        return ApiEnvelope.data(accounts.get(authentication, id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiEnvelope<InvestmentAccountResponse> create(
            Authentication authentication, @RequestBody InvestmentAccountCreateRequest request) {
        return ApiEnvelope.data(accounts.create(authentication, request));
    }

    @PatchMapping("/{id}")
    ApiEnvelope<InvestmentAccountResponse> update(
            Authentication authentication, @PathVariable long id, @RequestBody InvestmentAccountPatchRequest request) {
        return ApiEnvelope.data(accounts.update(authentication, id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(Authentication authentication, @PathVariable long id) {
        accounts.archive(authentication, id);
    }
}
