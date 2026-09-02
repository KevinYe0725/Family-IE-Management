package com.familyfinance.ledger;

import com.familyfinance.shared.ApiEnvelope;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    ApiEnvelope<AccountPage> list(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiEnvelope.data(accounts.list(authentication, page, size));
    }

    @GetMapping("/{id}")
    ApiEnvelope<AccountResponse> get(Authentication authentication, @PathVariable long id) {
        return ApiEnvelope.data(accounts.get(authentication, id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiEnvelope<AccountResponse> create(Authentication authentication, @RequestBody AccountCreateRequest request) {
        return ApiEnvelope.data(accounts.create(authentication, request));
    }

    @PatchMapping("/{id}")
    ApiEnvelope<AccountResponse> update(
            Authentication authentication,
            @PathVariable long id,
            @RequestBody AccountPatchRequest request) {
        return ApiEnvelope.data(accounts.update(authentication, id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(Authentication authentication, @PathVariable long id) {
        accounts.archive(authentication, id);
    }
}
