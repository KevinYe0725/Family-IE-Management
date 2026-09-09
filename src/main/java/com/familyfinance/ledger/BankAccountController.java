package com.familyfinance.ledger;

import com.familyfinance.accounting.AccountingCommandExecutor;
import com.familyfinance.accounting.AccountingRequests;
import com.familyfinance.shared.ApiEnvelope;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static com.familyfinance.ledger.BankAccountDtos.*;

@RestController
@RequestMapping("/api/bank-accounts")
public class BankAccountController {

    private final BankAccountService bankAccounts;
    private final AccountingCommandExecutor executor;

    public BankAccountController(BankAccountService bankAccounts, AccountingCommandExecutor executor) {
        this.bankAccounts = bankAccounts;
        this.executor = executor;
    }

    @GetMapping
    ApiEnvelope<List<BankAccountDtos.BankAccount>> list(Authentication authentication) {
        return ApiEnvelope.data(bankAccounts.list(authentication));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiEnvelope<BankAccountDtos.BankAccount> create(
            Authentication authentication,
            @RequestBody CreateRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String suppliedKey) {
        String key = AccountingRequests.key(suppliedKey);
        return ApiEnvelope.data(executor.execute(() -> bankAccounts.create(authentication, request, key)));
    }

    @PatchMapping("/{id}")
    ApiEnvelope<BankAccountDtos.BankAccount> update(
            Authentication authentication,
            @PathVariable long id,
            @RequestBody PatchRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String suppliedKey) {
        String key = AccountingRequests.key(suppliedKey);
        return ApiEnvelope.data(executor.execute(() -> bankAccounts.update(authentication, id, request, key)));
    }

    @PostMapping("/{id}/balances")
    ApiEnvelope<BankAccountDtos.BankAccount> addBalance(
            Authentication authentication,
            @PathVariable long id,
            @RequestBody BalanceRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String suppliedKey) {
        String key = AccountingRequests.key(suppliedKey);
        return ApiEnvelope.data(executor.execute(() -> bankAccounts.addBalance(authentication, id, request, key)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(Authentication authentication, @PathVariable long id) {
        executor.execute(() -> {
            bankAccounts.archive(authentication, id);
            return null;
        });
    }
}
